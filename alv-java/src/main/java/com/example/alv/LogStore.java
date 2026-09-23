package com.example.alv;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * ログの読み込み・保持を担うストア。
 *
 * <p>解析済みエントリを全件メモリに保持する（SQLite 等は使わない）。
 * 読み込みはバックグラウンドのデーモンスレッドで実行し、UI からの問い合わせには
 * 読み込み状態（idle / loading / ready / error）と進捗（行数）を返す。
 *
 * <h2>状態の一貫性</h2>
 * <p>対象パス・ファイル名・解析済みエントリは {@link LogSnapshot} に束ねて
 * <b>参照 1 回の代入</b>で差し替える。個別のフィールドに分けて持つと、読み込みの
 * 差し替え中に「エントリは前のディレクトリ、パスは次のディレクトリ」という
 * 組み合わせを外部から観測できてしまうため。読み込み中に対象を切り替えると
 * ワーカーが 2 本走り得るが、世代番号が一致しない古い結果は破棄する。
 *
 * <h2>パフォーマンス設計</h2>
 * <ul>
 *   <li>複数ファイルを {@link ExecutorService} で<b>並列パース</b>し、ファイルごとの
 *       時系列リストを作る。大きなファイルは行の境目で分けて、その範囲も並列にパースする
 *       （{@link #parseParallel}）。</li>
 *   <li>その後 {@link PriorityQueue} による <b>k-way マージ</b>で全体を時刻順に統合する
 *       （各ファイル内の順序は保持）。</li>
 *   <li>I/O は {@link ByteLineReader}（1 MiB バッファ）で行い、各行の byte offset を記録。
 *       生ログ本文はメモリに持たず、詳細表示・grep 時にオフセットから読み出す。</li>
 * </ul>
 */
public final class LogStore {

    private static final long PROGRESS_INTERVAL = 50_000L;
    private static final int MAX_SKIPPED_SAMPLES = 5;
    /**
     * これより大きいファイルは、行の境目で分けて並列にパースする（{@link #parseParallel}）。
     *
     * <p>8 MiB は「これより小さいファイルは分けなくても十分短く読める」とみて置いた値で、
     * 他の値とは比べていない。
     */
    static final long SPLIT_BYTES = 8L << 20;
    /** 1 ファイルを分ける数の上限。 */
    static final int MAX_RANGES_PER_FILE = 64;
    private static final int PREVIEW_MAX_LEN = 120;

    private final Object loadLock = new Object();
    private volatile LogSnapshot snapshot = LogSnapshot.empty();
    private final AtomicLong loadProgress = new AtomicLong();
    /** 世代番号の採番。{@link #loadLock} の下でのみ更新する。 */
    private long generationSeq;

    // ---- 状態アクセス -----------------------------------------------------

    /**
     * 現在のスナップショット。
     *
     * <p>1 リクエストの処理中はこの戻り値だけを参照すること。呼ぶたびに別の世代が
     * 返り得るため、複数回呼んで組み合わせると一貫性が崩れる。
     *
     * <p>副作用は持たない。以前はここで読み込みを開始していたが、状態を読むだけの
     * つもりの呼び出しが裏でワーカーを起動するため、呼び出し側から挙動が読めず
     * テストも書けなくなる。読み込みの開始は {@link #ensureLoadStarted()} を
     * 明示的に呼ぶこと。
     */
    public LogSnapshot snapshot() {
        return snapshot;
    }

    /** 読み込み済み行数（読み込み中は途中経過）。 */
    public long getLoadProgress() {
        return loadProgress.get();
    }

    /**
     * 利用者が明示指定した書式。{@code null} なら読み込みのたびに自動判定する。
     * 自動判定が外れたときに UI から上書きできるようにするための逃げ道。
     */
    private volatile LogFormatSpec requestedFormat;
    /** 直近の読み込みで実際に使った書式。画面に出すために保持する。 */
    private volatile LogFormatSpec resolvedFormat = LogFormatSpec.DEFAULT;
    /**
     * 自動判定の候補に加える利用者定義の書式。
     *
     * <p>定義ファイルを持っているのは {@link LogServer} なので、読み出しをここへ渡す。
     * 判定のたびに引き直すのは、ファイルを直してから読み込み直せば、サーバを起動し
     * 直さずに新しい書式を試せるようにするため。
     */
    private volatile Supplier<List<CustomLogFormat>> customFormats =
            new Supplier<List<CustomLogFormat>>() {
                @Override
                public List<CustomLogFormat> get() {
                    return Collections.emptyList();
                }
            };

    /** 書式を固定する。{@code null} で自動判定に戻す。 */
    public void setRequestedFormat(LogFormatSpec format) {
        this.requestedFormat = format;
    }

    /** 自動判定の候補に加える利用者定義の書式の読み出し。 */
    public void setCustomFormats(Supplier<List<CustomLogFormat>> supplier) {
        if (supplier != null) {
            this.customFormats = supplier;
        }
    }

    /** 自動判定かどうか（明示指定されていなければ true）。 */
    public boolean isFormatAuto() {
        return requestedFormat == null;
    }

    /** 直近の読み込みで実際に使った書式。 */
    public LogFormatSpec getResolvedFormat() {
        return resolvedFormat;
    }

    /** 読み込み対象を設定し、状態を idle にリセットする（世代を進める）。 */
    public void setSource(Path root, List<Path> paths) {
        List<Path> copy = (paths != null) ? new ArrayList<>(paths) : new ArrayList<Path>();
        List<String> names = new ArrayList<>(copy.size());
        for (Path p : copy) {
            names.add(PathUtil.normalizePath(p));
        }
        synchronized (loadLock) {
            generationSeq++;
            snapshot = LogSnapshot.withSource(generationSeq, root, copy, names);
            loadProgress.set(0);
        }
    }

    /** 対象があり idle なら読み込みを開始する。 */
    public void ensureLoadStarted() {
        LogSnapshot current = snapshot;
        if (!current.logPaths().isEmpty() && LogSnapshot.IDLE.equals(current.status())) {
            startLoad();
        }
    }

    /** バックグラウンドで読み込みを開始する。 */
    public void startLoad() {
        final long generation;
        final List<Path> paths;
        synchronized (loadLock) {
            LogSnapshot current = snapshot;
            if (current.isLoading()) {
                return;
            }
            snapshot = current.loading();
            loadProgress.set(0);
            generation = current.generation();
            paths = current.logPaths();
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoad(generation, paths);
            }
        }, "alv-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /** 読み込み本体。世代が一致する場合だけ結果を反映する。 */
    private void runLoad(final long generation, List<Path> paths) {
        try {
            // 明示指定がなければ先頭ファイルの冒頭から判定する。判定は読み込み開始時の 1 回だけで、
            // 1 行あたりに試す正規表現は確定した 1 書式ぶんだけになる。
            LogFormatSpec requested = requestedFormat;
            final LogFormatSpec format = requested != null
                    ? requested : LogFormatSpec.detect(paths, customFormats.get());
            resolvedFormat = format;
            LoadResult result = loadEntries(paths, true, format, new LongConsumer() {
                @Override
                public void accept(long value) {
                    publishProgress(generation, value);
                }
            });
            publishResult(generation, result);
        } catch (Throwable t) {
            publishFailure(generation, (t.getMessage() != null) ? t.getMessage() : t.toString());
        }
    }

    /**
     * 読み込み結果を反映する。
     *
     * <p>対象が切り替わっていれば何もしない。追い越された古い読み込みが後から完了して
     * 新しい状態を上書きするのを防ぐ要になる判定であり、スレッドのタイミングに
     * 依存せず検証できるよう独立したメソッドにしている。
     *
     * @return 反映したら {@code true}、世代が一致せず破棄したら {@code false}
     */
    boolean publishResult(long generation, LoadResult result) {
        synchronized (loadLock) {
            if (snapshot.generation() != generation) {
                return false;
            }
            snapshot = snapshot.ready(result.entries, result.skippedLines, result.skippedSamples);
            loadProgress.set(result.entries.size());
            return true;
        }
    }

    /**
     * 読み込み失敗を反映する。
     *
     * @return 反映したら {@code true}、世代が一致せず破棄したら {@code false}
     */
    boolean publishFailure(long generation, String message) {
        synchronized (loadLock) {
            if (snapshot.generation() != generation) {
                return false;
            }
            snapshot = snapshot.failed(message);
            loadProgress.set(0);
            return true;
        }
    }

    /** 進捗を反映する（世代が一致する場合のみ。古いワーカーの値で上書きしない）。 */
    private void publishProgress(long generation, long value) {
        synchronized (loadLock) {
            if (snapshot.generation() == generation) {
                loadProgress.set(value);
            }
        }
    }

    // ---- 読み込み本体（並列パース + k-way マージ）--------------------------

    /** 読み込み結果（解析済みエントリとスキップ行の集計）。 */
    public static final class LoadResult {
        public final List<LogEntry> entries;
        public final int skippedLines;
        public final List<SkippedLine> skippedSamples;

        LoadResult(List<LogEntry> entries, int skippedLines, List<SkippedLine> skippedSamples) {
            this.entries = entries;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    /**
     * 複数ファイルを並列パースし、{@code sort=true} なら時刻順にマージして返す。
     *
     * @param progress 進捗（読み込み済み行数）コールバック。不要なら {@code null}
     */
    /** 既定書式での読み込み（テスト・利便用）。 */
    public static LoadResult loadEntries(List<Path> paths, boolean sort, LongConsumer progress)
            throws IOException {
        return loadEntries(paths, sort, LogFormatSpec.DEFAULT, progress);
    }

    public static LoadResult loadEntries(List<Path> paths, boolean sort, LogFormatSpec format,
            LongConsumer progress) throws IOException {
        return loadEntries(paths, sort, format, progress, SPLIT_BYTES);
    }

    /** 分ける大きさを指定した読み込み（試験で小さなファイルを細かく分けるため）。 */
    static LoadResult loadEntries(List<Path> paths, boolean sort, LogFormatSpec format,
            LongConsumer progress, long splitBytes) throws IOException {
        if (paths.isEmpty()) {
            if (progress != null) {
                progress.accept(0);
            }
            return new LoadResult(new ArrayList<LogEntry>(), 0, Collections.<SkippedLine>emptyList());
        }
        ParseAggregate aggregate = parseParallel(paths, format, progress, splitBytes);
        List<LogEntry> result;
        if (sort && aggregate.perFile.size() == 1) {
            // 1 ファイルならファイル内の順序がそのまま答えなので、マージを通さない
            result = aggregate.perFile.get(0);
        } else if (sort) {
            result = merge(aggregate.perFile);
        } else {
            result = new ArrayList<>();
            for (List<LogEntry> list : aggregate.perFile) {
                result.addAll(list);
            }
        }
        if (progress != null) {
            progress.accept(result.size());
        }
        return new LoadResult(result, aggregate.skippedLines, aggregate.skippedSamples);
    }

    private static final class ParseAggregate {
        final List<List<LogEntry>> perFile;
        final int skippedLines;
        final List<SkippedLine> skippedSamples;

        ParseAggregate(List<List<LogEntry>> perFile, int skippedLines, List<SkippedLine> skippedSamples) {
            this.perFile = perFile;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    /**
     * 読み込む単位（ファイル、または大きなファイルを行の境目で分けた一部）。
     *
     * <p>{@link #end} は含まない。ファイルの最後の範囲は {@link Long#MAX_VALUE} で、読み込み中に
     * 追記された行も含めて末尾まで読む（分けない場合と同じ）。
     */
    static final class Range {
        final int fileId;
        final Path path;
        final long start;
        final long end;

        Range(int fileId, Path path, long start, long end) {
            this.fileId = fileId;
            this.path = path;
            this.start = start;
            this.end = end;
        }

        boolean isLast() {
            return end == Long.MAX_VALUE;
        }
    }

    /** 1 範囲の解析結果。行番号は範囲の中で数えたもの（{@link #parseParallel} で直す）。 */
    private static final class RangeResult {
        final List<LogEntry> entries;
        /** 範囲の中で読んだ行数（空行・読み飛ばした行を含む）。 */
        final int lineCount;
        final int skippedLines;
        /** この範囲で読み飛ばした行のうち先頭から最大 {@link #MAX_SKIPPED_SAMPLES} 件。 */
        final List<SkippedLine> skippedSamples;

        RangeResult(List<LogEntry> entries, int lineCount, int skippedLines,
                List<SkippedLine> skippedSamples) {
            this.entries = entries;
            this.lineCount = lineCount;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    /** 範囲の中の行で、利用者定義の書式が失敗した。行番号はファイル全体に直してから伝える。 */
    private static final class RangeFormatFailure extends IOException {
        final int localLineNo;
        final Path path;

        RangeFormatFailure(CustomLogFormat.FormatFailure cause, Path path, int localLineNo) {
            super(cause.getMessage(), cause);
            this.path = path;
            this.localLineNo = localLineNo;
        }
    }

    /**
     * 複数ファイルを並列にパースする。{@code splitBytes} を超えるファイルは行の境目で
     * 分け、分けた範囲も並列にパースする。
     *
     * <p>ファイル単位の並列化だけでは、大きなファイル 1 つを読むときに 1 コアしか使えない。
     * 1 行ごとの解析は分けない場合とまったく同じにする。行番号は範囲の中で数えておき、
     * 全範囲を読み終えてから前の範囲の行数を足して直す（行番号のためにファイルを 2 回読まない。
     * ネットワークドライブ上のログでは読み込みの I/O がそのまま倍になるため）。
     * 読み飛ばした行のサンプルは範囲ごとに集め、ファイル順・範囲順に先頭から取る
     * （1 ファイルなら分けない場合と同じ行が選ばれる）。
     *
     * <p>実測（100 万行・148 MB のアクセスログ、12 論理コア、Windows 11 / JDK 11、
     * 変更前後を交互に 5 回の中央値を 3 ラウンド取った中央値）:
     * 1 ファイル 1,527ms → 361ms、3 ファイル（各 49 MB）646ms → 389ms。
     * 分けない大きさのファイルだけの構成（30 ファイル・300 ファイル）では差はない。
     */
    private static ParseAggregate parseParallel(List<Path> paths, LogFormatSpec format,
            LongConsumer progress, long splitBytes) throws IOException {
        List<Range> ranges = splitIntoRanges(paths, splitBytes);
        int threads = Math.max(1, Math.min(ranges.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final AtomicLong counter = new AtomicLong();
            List<Future<RangeResult>> futures = new ArrayList<>(ranges.size());
            for (final Range range : ranges) {
                futures.add(pool.submit(() -> parseRange(range, format, counter, progress)));
            }
            // 投入順に受け取るので、ある範囲が失敗した時点でそれより前の範囲の行数は揃っている
            List<RangeResult> results = new ArrayList<>(ranges.size());
            int[] lineNoBase = new int[ranges.size()];
            for (int i = 0; i < ranges.size(); i++) {
                Range range = ranges.get(i);
                lineNoBase[i] = range.start == 0 ? 0 : lineNoBase[i - 1] + results.get(i - 1).lineCount;
                try {
                    results.add(await(futures.get(i)));
                } catch (RangeFormatFailure e) {
                    throw new IOException(e.getMessage() + "（" + e.path + " の "
                            + (lineNoBase[i] + e.localLineNo) + " 行目）", e.getCause());
                }
            }
            shiftLineNumbers(results, lineNoBase, pool);

            List<List<LogEntry>> perFile = new ArrayList<>(paths.size());
            int skippedLines = 0;
            List<SkippedLine> samples = new ArrayList<>();
            int r = 0;
            for (int fileId = 0; fileId < paths.size(); fileId++) {
                int from = r;
                int size = 0;
                while (r < ranges.size() && ranges.get(r).fileId == fileId) {
                    size += results.get(r).entries.size();
                    r++;
                }
                List<LogEntry> entries;
                if (r - from == 1) {
                    entries = results.get(from).entries;
                } else {
                    entries = new ArrayList<>(size);
                    for (int i = from; i < r; i++) {
                        entries.addAll(results.get(i).entries);
                    }
                }
                perFile.add(entries);
                for (int i = from; i < r; i++) {
                    RangeResult result = results.get(i);
                    skippedLines += result.skippedLines;
                    for (SkippedLine s : result.skippedSamples) {
                        if (samples.size() < MAX_SKIPPED_SAMPLES) {
                            samples.add(new SkippedLine(s.fileId, s.lineNo + lineNoBase[i], s.preview));
                        }
                    }
                }
            }
            return new ParseAggregate(perFile, skippedLines, samples);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 範囲の中で数えた行番号を、ファイル全体の行番号へ直す（ファイルの先頭の範囲はそのまま）。 */
    private static void shiftLineNumbers(List<RangeResult> results, int[] lineNoBase,
            ExecutorService pool) throws IOException {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            final int delta = lineNoBase[i];
            if (delta == 0) {
                continue;
            }
            final List<LogEntry> entries = results.get(i).entries;
            futures.add(pool.submit(() -> {
                for (int k = 0; k < entries.size(); k++) {
                    entries.set(k, entries.get(k).withLineNoOffset(delta));
                }
            }));
        }
        for (Future<?> f : futures) {
            await(f);
        }
    }

    /**
     * ファイルを読み込む範囲に分ける。{@code splitBytes} 以下のファイルは分けない。
     * 分け目は、おおよその位置のあとに来る最初の改行の直後にする。
     */
    static List<Range> splitIntoRanges(List<Path> paths, long splitBytes) throws IOException {
        List<Range> ranges = new ArrayList<>();
        for (int fileId = 0; fileId < paths.size(); fileId++) {
            Path path = paths.get(fileId);
            long size = Files.size(path);
            // 天井除算。切り捨てだと splitBytes 超〜2 倍未満のファイルが分かれない
            long wanted = size <= splitBytes ? 1 : (size + splitBytes - 1) / splitBytes;
            int pieces = (int) Math.min(MAX_RANGES_PER_FILE, wanted);
            long start = 0;
            if (pieces > 1) {
                try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                    for (int k = 1; k < pieces; k++) {
                        long boundary = nextLineStart(raf, Math.max(start, size / pieces * k));
                        if (boundary <= start || boundary >= size) {
                            continue;
                        }
                        ranges.add(new Range(fileId, path, start, boundary));
                        start = boundary;
                    }
                }
            }
            ranges.add(new Range(fileId, path, start, Long.MAX_VALUE));
        }
        return ranges;
    }

    /** {@code pos} 以降で最初に始まる行の先頭（{@code pos - 1} が改行なら {@code pos}）。見つからなければ -1。 */
    private static long nextLineStart(RandomAccessFile raf, long pos) throws IOException {
        byte[] buf = new byte[8192];
        long at = Math.max(0, pos - 1);
        raf.seek(at);
        while (true) {
            int n = raf.read(buf);
            if (n <= 0) {
                return -1;
            }
            for (int i = 0; i < n; i++) {
                if (buf[i] == '\n') {
                    return at + i + 1;
                }
            }
            at += n;
        }
    }

    /**
     * 範囲の先頭から読むストリーム。最後の範囲以外は範囲の終わりで止まる。
     *
     * <p>読み始めの位置は {@link FileChannel#position(long)} で直接決める。
     * {@link InputStream#skip} は要求より少なく進むことがあり、足りないまま読むと
     * 別の行から読み始めてしまうため使わない。
     */
    private static InputStream openRange(Range range) throws IOException {
        FileChannel channel = FileChannel.open(range.path, StandardOpenOption.READ);
        try {
            channel.position(range.start);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        InputStream in = Channels.newInputStream(channel);
        return range.isLast() ? in : new BoundedInputStream(in, range.end - range.start);
    }

    /** 残りのバイト数で読み出しを止めるストリーム。 */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }

    /** タスクの完了を待つ。失敗は元の {@link IOException} に戻して投げる。 */
    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("読み込みが中断されました", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException) {
                throw ((UncheckedIOException) cause).getCause();
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(cause != null ? cause.getMessage() : e.getMessage(), cause);
        }
    }

    private static RangeResult parseRange(Range range, LogFormatSpec format, AtomicLong counter,
            LongConsumer progress) throws IOException {
        // 書式は読み込み開始時に確定しているので、分岐の材料はループの外で 1 回だけ取り出す。
        // 組み込み書式のときは custom == null で、従来と同じ経路をそのまま通る。
        final LogFormat builtin = format.builtin();
        final CustomLogFormat custom = format.custom();
        final int fileId = range.fileId;
        List<LogEntry> out = new ArrayList<>();
        int skipped = 0;
        List<SkippedLine> samples = new ArrayList<>();
        int lineNo = 0;
        try (InputStream in = openRange(range);
             ByteLineReader reader = new ByteLineReader(in)) {
            while (reader.next()) {
                lineNo++;
                if (reader.isBlankLine()) {
                    continue;
                }
                long lineStart = range.start + reader.lineStart;
                String line = new String(reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8);
                LogEntry entry;
                if (custom == null) {
                    entry = LogParser.parseLine(builtin, line, fileId, lineNo, lineStart);
                } else {
                    try {
                        entry = custom.parse(line, fileId, lineNo, lineStart);
                    } catch (CustomLogFormat.FormatFailure e) {
                        // 暴走した正規表現や壊れた定義。黙って固まる・原因不明で落ちるより、
                        // どの書式のどこで止めたかが分かる形で失敗させる。
                        throw new RangeFormatFailure(e, range.path, lineNo);
                    }
                }
                if (entry != null) {
                    out.add(entry);
                    long c = counter.incrementAndGet();
                    if (progress != null && c % PROGRESS_INTERVAL == 0) {
                        progress.accept(c);
                    }
                } else {
                    skipped++;
                    if (samples.size() < MAX_SKIPPED_SAMPLES) {
                        samples.add(new SkippedLine(fileId, lineNo, previewLine(line)));
                    }
                }
            }
        }
        return new RangeResult(out, lineNo, skipped, samples);
    }

    private static String previewLine(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        String trimmed = line.substring(0, end);
        if (trimmed.length() <= PREVIEW_MAX_LEN) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_MAX_LEN - 3) + "...";
    }

    /** ファイルごとの時系列リストを時刻順（タイブレーク: fileId, lineNo）にマージする。 */
    private static List<LogEntry> merge(List<List<LogEntry>> perFile) {
        int total = 0;
        for (List<LogEntry> list : perFile) {
            total += list.size();
        }
        List<LogEntry> result = new ArrayList<>(total);
        PriorityQueue<Cursor> pq = new PriorityQueue<>();
        for (List<LogEntry> list : perFile) {
            if (!list.isEmpty()) {
                pq.add(new Cursor(list));
            }
        }
        while (!pq.isEmpty()) {
            Cursor c = pq.poll();
            result.add(c.current());
            if (c.advance()) {
                pq.add(c);
            }
        }
        return result;
    }

    private static final class Cursor implements Comparable<Cursor> {
        private final List<LogEntry> list;
        private int idx;

        Cursor(List<LogEntry> list) {
            this.list = list;
        }

        LogEntry current() {
            return list.get(idx);
        }

        boolean advance() {
            idx++;
            return idx < list.size();
        }

        @Override
        public int compareTo(Cursor o) {
            LogEntry a = current();
            LogEntry b = o.current();
            int cmp = Long.compare(a.tsMillis, b.tsMillis);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(a.fileId, b.fileId);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a.lineNo, b.lineNo);
        }
    }
}

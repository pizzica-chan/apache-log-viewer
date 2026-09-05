package com.example.alv;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *       時系列リストを作る。</li>
 *   <li>その後 {@link PriorityQueue} による <b>k-way マージ</b>で全体を時刻順に統合する
 *       （各ファイル内の順序は保持）。</li>
 *   <li>I/O は {@link ByteLineReader}（1 MiB バッファ）で行い、各行の byte offset を記録。
 *       生ログ本文はメモリに持たず、詳細表示・grep 時にオフセットから読み出す。</li>
 * </ul>
 */
public final class LogStore {

    private static final long PROGRESS_INTERVAL = 50_000L;
    private static final int MAX_SKIPPED_SAMPLES = 5;
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
            LoadResult result = loadEntries(paths, true, new LongConsumer() {
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
    public static LoadResult loadEntries(List<Path> paths, boolean sort, LongConsumer progress)
            throws IOException {
        if (paths.isEmpty()) {
            if (progress != null) {
                progress.accept(0);
            }
            return new LoadResult(new ArrayList<LogEntry>(), 0, Collections.<SkippedLine>emptyList());
        }
        ParseAggregate aggregate = parseParallel(paths, progress);
        List<LogEntry> result;
        if (sort) {
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

    private static ParseAggregate parseParallel(List<Path> paths, LongConsumer progress)
            throws IOException {
        int n = paths.size();
        final List<List<LogEntry>> perFile = new ArrayList<>(Collections.<List<LogEntry>>nCopies(n, null));
        final AtomicLong counter = new AtomicLong();
        final AtomicLong skippedCounter = new AtomicLong();
        final List<SkippedLine> skippedSamples = Collections.synchronizedList(new ArrayList<SkippedLine>());
        int threads = Math.max(1, Math.min(n, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int fileId = i;
                final Path path = paths.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        perFile.set(fileId, parseFile(fileId, path, counter, skippedCounter,
                                skippedSamples, progress));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
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
        } finally {
            pool.shutdownNow();
        }
        return new ParseAggregate(perFile, (int) skippedCounter.get(),
                new ArrayList<>(skippedSamples));
    }

    private static List<LogEntry> parseFile(int fileId, Path path, AtomicLong counter,
            AtomicLong skippedCounter, List<SkippedLine> skippedSamples, LongConsumer progress)
            throws IOException {
        List<LogEntry> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path);
             ByteLineReader reader = new ByteLineReader(in)) {
            int lineNo = 0;
            while (reader.next()) {
                lineNo++;
                if (reader.isBlankLine()) {
                    continue;
                }
                String line = new String(reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8);
                LogEntry entry = LogParser.parseLine(line, fileId, lineNo, reader.lineStart);
                if (entry != null) {
                    out.add(entry);
                    long c = counter.incrementAndGet();
                    if (progress != null && c % PROGRESS_INTERVAL == 0) {
                        progress.accept(c);
                    }
                } else {
                    skippedCounter.incrementAndGet();
                    if (skippedSamples.size() < MAX_SKIPPED_SAMPLES) {
                        skippedSamples.add(new SkippedLine(fileId, lineNo, previewLine(line)));
                    }
                }
            }
        }
        return out;
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

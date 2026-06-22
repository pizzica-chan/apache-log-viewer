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

    private volatile Path logRoot;
    private volatile List<Path> logPaths = Collections.emptyList();
    private volatile List<String> sourceNames = Collections.emptyList();
    private volatile List<LogEntry> entries;

    private volatile String loadStatus = "idle"; // idle / loading / ready / error
    private volatile String loadError;
    private final AtomicLong loadProgress = new AtomicLong();

    private final Object loadLock = new Object();

    // ---- 状態アクセス -----------------------------------------------------

    public Path getLogRoot() {
        return logRoot;
    }

    public List<Path> getLogPaths() {
        return logPaths;
    }

    public List<String> getSourceNames() {
        return sourceNames;
    }

    public String getLoadStatus() {
        return loadStatus;
    }

    public String getLoadError() {
        return loadError;
    }

    public long getLoadProgress() {
        return loadProgress.get();
    }

    public boolean isLoading() {
        return "loading".equals(loadStatus);
    }

    public boolean isError() {
        return "error".equals(loadStatus);
    }

    public String sourceName(LogEntry entry) {
        return sourceNames.get(entry.fileId);
    }

    /** 読み込み対象を設定し、状態を idle にリセットする。 */
    public synchronized void setSource(Path root, List<Path> paths) {
        this.logRoot = root;
        this.logPaths = paths != null ? paths : Collections.<Path>emptyList();
        List<String> names = new ArrayList<>(this.logPaths.size());
        for (Path p : this.logPaths) {
            names.add(PathUtil.normalizePath(p));
        }
        this.sourceNames = names;
        this.entries = null;
        this.loadStatus = "idle";
        this.loadError = null;
        this.loadProgress.set(0);
    }

    /** 読み込み済みエントリ（未読み込み時は空リスト）。 */
    public List<LogEntry> getEntries() {
        ensureLoadStarted();
        List<LogEntry> e = entries;
        return e != null ? e : Collections.<LogEntry>emptyList();
    }

    /** 対象があり idle なら読み込みを開始する。 */
    public void ensureLoadStarted() {
        if (!logPaths.isEmpty() && "idle".equals(loadStatus)) {
            startLoad();
        }
    }

    /** バックグラウンドで読み込みを開始する。 */
    public void startLoad() {
        synchronized (loadLock) {
            if ("loading".equals(loadStatus)) {
                return;
            }
            loadStatus = "loading";
            loadError = null;
            loadProgress.set(0);
            entries = null;
        }
        final List<Path> paths = new ArrayList<>(logPaths);

        Thread worker = new Thread(() -> {
            try {
                List<LogEntry> result = loadEntries(paths, true, loadProgress::set);
                synchronized (loadLock) {
                    entries = result;
                    loadProgress.set(result.size());
                    loadStatus = "ready";
                }
            } catch (Throwable t) {
                synchronized (loadLock) {
                    loadStatus = "error";
                    loadError = t.getMessage() != null ? t.getMessage() : t.toString();
                    entries = Collections.emptyList();
                }
            }
        }, "alv-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /** source / line_no（と任意の timestamp）に一致するエントリを探す。 */
    public LogEntry findEntry(String source, int lineNo, String timestamp) {
        for (LogEntry e : getEntries()) {
            if (!sourceName(e).equals(source) || e.lineNo != lineNo) {
                continue;
            }
            if (timestamp != null && !e.timestampIso().equals(timestamp)) {
                continue;
            }
            return e;
        }
        return null;
    }

    // ---- 読み込み本体（並列パース + k-way マージ）--------------------------

    /**
     * 複数ファイルを並列パースし、{@code sort=true} なら時刻順にマージして返す。
     *
     * @param progress 進捗（読み込み済み行数）コールバック。不要なら {@code null}
     */
    public static List<LogEntry> loadEntries(List<Path> paths, boolean sort, LongConsumer progress)
            throws IOException {
        if (paths.isEmpty()) {
            if (progress != null) {
                progress.accept(0);
            }
            return new ArrayList<>();
        }
        List<List<LogEntry>> perFile = parseParallel(paths, progress);
        List<LogEntry> result;
        if (sort) {
            result = merge(perFile);
        } else {
            result = new ArrayList<>();
            for (List<LogEntry> list : perFile) {
                result.addAll(list);
            }
        }
        if (progress != null) {
            progress.accept(result.size());
        }
        return result;
    }

    private static List<List<LogEntry>> parseParallel(List<Path> paths, LongConsumer progress)
            throws IOException {
        int n = paths.size();
        final List<List<LogEntry>> perFile = new ArrayList<>(Collections.<List<LogEntry>>nCopies(n, null));
        final AtomicLong counter = new AtomicLong();
        int threads = Math.max(1, Math.min(n, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int fileId = i;
                final Path path = paths.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        perFile.set(fileId, parseFile(fileId, path, counter, progress));
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
        return perFile;
    }

    private static List<LogEntry> parseFile(int fileId, Path path, AtomicLong counter, LongConsumer progress)
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
                }
            }
        }
        return out;
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

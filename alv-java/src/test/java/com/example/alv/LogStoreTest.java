package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * {@link LogStore} の単体テスト。
 *
 * <p>サンプルログの読み込み・時刻順マージ・バイトオフセットからの生行復元、
 * および非同期ロード状態の遷移を検証する。
 */
class LogStoreTest {

    private List<Path> sampleLogPaths() throws IOException {
        Path samples = TestPaths.samplesDir();
        assumeTrue(Files.isDirectory(samples), "samples ディレクトリが見つかりません");
        List<Path> paths = Discovery.findLogFiles(samples);
        assumeTrue(!paths.isEmpty(), "サンプルログが見つかりません");
        return paths;
    }

    /**
     * 試験: サンプルログ全ファイルを同期読み込みする。
     * 担保: 有効行 15 件がパースされ、空行・不正行は除外され skippedLines に集計される。
     */
    @Test
    void loadsAllSampleEntries() throws IOException {
        LogStore.LoadResult result = LogStore.loadEntries(sampleLogPaths(), true, null);
        assertEquals(15, result.entries.size());
        assertEquals(5, result.skippedLines);
    }

    /**
     * 試験: 複数ファイルをマージした結果の時刻順。
     * 担保: {@code tsMillis} が非降順であり、UI の時系列表示が正しい順序になる。
     */
    @Test
    void mergedEntriesAreSortedByTimestamp() throws IOException {
        List<LogEntry> entries = LogStore.loadEntries(sampleLogPaths(), true, null).entries;
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).tsMillis <= entries.get(i).tsMillis,
                    "時刻順にソートされていること");
        }
    }

    /**
     * 試験: エントリに記録された byteOffset から元ログ行を読み出す。
     * 担保: メモリに raw を保持せず、オフセット経由で原文が完全復元できる。
     */
    @Test
    void byteOffsetReadsOriginalLine() throws IOException {
        List<Path> paths = sampleLogPaths();
        int xffId = -1;
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i).getFileName().toString().equals("access_log.xff")) {
                xffId = i;
                break;
            }
        }
        assumeTrue(xffId >= 0, "access_log.xff が見つかりません");

        List<LogEntry> entries = LogStore.loadEntries(paths, false, null).entries;
        LogEntry first = null;
        for (LogEntry e : entries) {
            if (e.fileId == xffId && e.lineNo == 1) {
                first = e;
                break;
            }
        }
        assertNotNull(first);
        try (LineReader reader = new LineReader(paths)) {
            String raw = reader.read(first.fileId, first.byteOffset);
            assertEquals(
                    "203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] "
                    + "\"GET /api/users HTTP/1.1\" 200 1024 \"https://app.example.com/\" \"Mozilla/5.0\"",
                    raw);
        }
    }

    /**
     * 試験: 読み込み対象が空の場合。
     * 担保: 空リストが返り、進捗コールバックにも 0 が通知される。
     */
    @Test
    void loadEntriesEmptyPathsReturnsEmpty() throws IOException {
        long[] progress = { -1 };
        List<LogEntry> entries = LogStore.loadEntries(
                java.util.Collections.<Path>emptyList(), true, n -> progress[0] = n).entries;
        assertTrue(entries.isEmpty());
        assertEquals(0, progress[0]);
    }

    /**
     * 試験: sort=false でファイル順のまま結合する。
     * 担保: 先頭エントリは先頭ファイルの先頭有効行になる（時刻順マージは行わない）。
     */
    @Test
    void unsortedLoadPreservesFileOrder() throws IOException {
        List<Path> paths = sampleLogPaths();
        List<LogEntry> entries = LogStore.loadEntries(paths, false, null).entries;
        assertTrue(entries.size() >= 1);
        assertEquals(0, entries.get(0).fileId);
        assertEquals(1, entries.get(0).lineNo);
    }

    /**
     * 試験: 解析できない行をカウントし、サンプルを返す。
     * 担保: 不正行は一覧から除外され、skippedLines に件数が入る。
     */
    @Test
    void countsSkippedUnparseableLines() throws IOException {
        Path temp = Files.createTempFile("alv-skipped-", ".log");
        try {
            Files.write(temp,
                    ("127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /ok HTTP/1.1\" 200 0\n"
                    + "this is not a log line\n"
                    + "another bad line\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LogStore.LoadResult result = LogStore.loadEntries(
                    java.util.Collections.singletonList(temp), false, null);
            assertEquals(1, result.entries.size());
            assertEquals(2, result.skippedLines);
            assertEquals(2, result.skippedSamples.size());
            assertEquals(2, result.skippedSamples.get(0).lineNo);
            assertEquals("this is not a log line", result.skippedSamples.get(0).preview);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 試験: バックグラウンド読み込み完了後に findEntry でエントリを検索する。
     * 担保: source 名 + 行番号（+ 任意の timestamp）で一意にエントリを取得できる。
     */
    @Test
    void findEntryAfterAsyncLoad() throws Exception {
        List<Path> paths = sampleLogPaths();
        LogStore store = new LogStore();
        store.setSource(TestPaths.samplesDir(), paths);
        store.startLoad();
        waitUntilReady(store, 10, TimeUnit.SECONDS);

        assertEquals("ready", store.getLoadStatus());
        assertEquals(15, store.getEntries().size());
        assertEquals(5, store.getSkippedLineCount());

        LogEntry any = store.getEntries().get(0);
        String source = store.sourceName(any);
        LogEntry found = store.findEntry(source, any.lineNo, any.timestampIso());
        assertNotNull(found);
        assertEquals(any.byteOffset, found.byteOffset);

        assertNull(store.findEntry(source, any.lineNo, "wrong-timestamp"));
        assertNull(store.findEntry("nonexistent.log", 1, null));
    }

    /**
     * 試験: setSource 後の初期状態。
     * 担保: loadStatus が idle、エントリ未読み込み（空リスト）になる。
     */
    @Test
    void setSourceResetsToIdle() {
        LogStore store = new LogStore();
        store.setSource(TestPaths.samplesDir(), java.util.Collections.<Path>emptyList());
        assertEquals("idle", store.getLoadStatus());
        assertTrue(store.getEntries().isEmpty());
        assertEquals(0, store.getLoadProgress());
    }

    private static void waitUntilReady(LogStore store, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            String status = store.getLoadStatus();
            if ("ready".equals(status) || "error".equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
    }
}

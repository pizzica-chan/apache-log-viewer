package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * 試験: 大きなファイルを行の境目で細かく分けて読んだ結果を、分けずに読んだ結果と比べる。
     * 担保: 分けても、エントリの全項目・並び・行番号・バイト位置、読み飛ばした行の件数と
     * サンプルが変わらない（空行・読めない行・CRLF・多バイト文字・改行で終わらない最終行・
     * ファイル内で時刻が前後する行を含む）。
     */
    @Test
    void splitParsingMatchesUnsplit(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        StringBuilder big = new StringBuilder();
        java.util.Random random = new java.util.Random(1);
        for (int i = 1; i <= 3000; i++) {
            int r = random.nextInt(100);
            // 読めない行は後半にだけ置き、サンプルが先頭以外の範囲から選ばれるようにする
            if (r < 3 && i > 1000) {
                big.append("not a log line ").append(i).append('\n');
            } else if (r < 5) {
                big.append('\n');
            }
            int sec = (i * 7 + random.nextInt(5)) % 60;
            big.append(String.format("10.0.0.%d - - [20/Jun/2025:08:%02d:%02d +0900] \"GET /パス/%d HTTP/1.1\" 200 %d",
                    i % 250, (i / 60) % 60, sec, i, i))
                    .append(r < 30 ? "\r\n" : "\n");
        }
        big.append("10.0.0.1 - - [20/Jun/2025:09:00:00 +0900] \"GET /last HTTP/1.1\" 200 1");
        Path a = tmp.resolve("access_log.big");
        Files.write(a, big.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path b = tmp.resolve("access_log.small");
        Files.write(b, ("bad line\n10.0.0.9 - - [20/Jun/2025:08:30:00 +0900] \"GET /small HTTP/1.1\" 404 0\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<Path> paths = java.util.Arrays.asList(a, b);

        assertTrue(LogStore.splitIntoRanges(paths, 512).size() > 10, "細かく分かれていること");
        LogStore.LoadResult whole = LogStore.loadEntries(paths, true, LogFormatSpec.DEFAULT, null,
                Long.MAX_VALUE);
        LogStore.LoadResult split = LogStore.loadEntries(paths, true, LogFormatSpec.DEFAULT, null, 512);

        assertEquals(describe(whole.entries), describe(split.entries));
        assertEquals(whole.skippedLines, split.skippedLines);
        assertTrue(whole.skippedLines > 20, "読めない行が複数の範囲に散らばっていること");
        assertTrue(whole.skippedSamples.get(0).lineNo > 1000, "サンプルが先頭の範囲の外にあること");
        assertEquals(samples(whole.skippedSamples), samples(split.skippedSamples));
        assertEquals("/last", split.entries.get(split.entries.size() - 1).path);
    }

    /**
     * 試験: 分けた範囲の途中で利用者定義の書式が失敗したときの行番号。
     * 担保: エラーに出る行番号は、範囲の中で数えた番号ではなくファイル全体の行番号になる。
     */
    @Test
    void formatFailureReportsFileLineNumberWhenSplit(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws IOException {
        // (?x) のコメント内の (?<status> を必須グループと数えてしまう書式。一致した行で失敗する
        CustomLogFormat broken = new CustomLogFormat("cmt", "コメント入り",
                "(?x) ^(?<client>\\S+) \\  \\[(?<ts>[^\\]]+)\\] # (?<status>zzz)\n",
                "dd/MMM/yyyy:HH:mm:ss Z");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i < 1500; i++) {
            content.append("filler line ").append(i).append('\n');
        }
        content.append("203.0.113.5 [15/Jun/2026:08:01:12 +0900]\n");
        for (int i = 1501; i <= 2000; i++) {
            content.append("filler line ").append(i).append('\n');
        }
        Path log = tmp.resolve("access_log");
        Files.write(log, content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<Path> paths = java.util.Collections.singletonList(log);
        assertTrue(LogStore.splitIntoRanges(paths, 256).size() > 10, "失敗する行が先頭の範囲にないこと");

        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> LogStore.loadEntries(paths, true, LogFormatSpec.of(broken), null, 256));
        assertTrue(e.getMessage().contains(" 1500 行目"), e.getMessage());
    }

    private static List<String> describe(List<LogEntry> entries) {
        List<String> out = new java.util.ArrayList<>();
        for (LogEntry e : entries) {
            out.add(e.fileId + "|" + e.lineNo + "|" + e.byteOffset + "|" + e.tsMillis + "|"
                    + e.tzOffsetMin + "|" + e.host + "|" + e.clientHost + "|" + e.forwardedFor + "|"
                    + e.method + "|" + e.path + "|" + e.status);
        }
        return out;
    }

    private static List<String> samples(List<SkippedLine> lines) {
        List<String> out = new java.util.ArrayList<>();
        for (SkippedLine s : lines) {
            out.add(s.fileId + ":" + s.lineNo + ":" + s.preview);
        }
        return out;
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

        LogSnapshot snap = store.snapshot();
        assertEquals(LogSnapshot.READY, snap.status());
        assertEquals(15, snap.entries().size());
        assertEquals(5, snap.skippedLines());

        LogEntry any = snap.entries().get(0);
        String source = snap.sourceName(any);
        LogEntry found = snap.findEntry(source, any.lineNo, any.timestampIso());
        assertNotNull(found);
        assertEquals(any.byteOffset, found.byteOffset);

        assertNull(snap.findEntry(source, any.lineNo, "wrong-timestamp"));
        assertNull(snap.findEntry("nonexistent.log", 1, null));
    }

    /**
     * 試験: 世代が一致しない読み込み結果は反映されないこと。
     * 担保: 追い越された古い読み込みが後から完了しても、切り替え後の状態を
     *       上書きしない。スレッドの実行タイミングに依存せず、判定そのものを検証する。
     */
    @Test
    void publishResultIgnoresOutdatedGeneration() throws Exception {
        LogStore store = new LogStore();
        store.setSource(TestPaths.samplesDir(), sampleLogPaths());
        long staleGeneration = store.snapshot().generation();
        LogStore.LoadResult staleResult = LogStore.loadEntries(sampleLogPaths(), true, null);
        assertEquals(15, staleResult.entries.size());

        // 対象を 1 ファイルへ切り替える（世代が進む）。
        List<Path> one = sampleLogPaths().subList(0, 1);
        store.setSource(TestPaths.samplesDir(), one);
        long currentGeneration = store.snapshot().generation();
        assertTrue(currentGeneration > staleGeneration);

        assertFalse(store.publishResult(staleGeneration, staleResult), "古い世代の結果は破棄される");
        assertTrue(store.snapshot().entries().isEmpty(), "古いエントリが混入していない");
        assertEquals(1, store.snapshot().logPaths().size(), "対象は切り替え後のまま");

        LogStore.LoadResult fresh = LogStore.loadEntries(one, true, null);
        assertTrue(store.publishResult(currentGeneration, fresh), "現行世代の結果は反映される");
        assertEquals(LogSnapshot.READY, store.snapshot().status());
        assertEquals(fresh.entries.size(), store.snapshot().entries().size());

        // 失敗の反映も同じ判定で守られる。
        assertFalse(store.publishFailure(staleGeneration, "古いエラー"));
        assertEquals(LogSnapshot.READY, store.snapshot().status());
        assertTrue(store.publishFailure(store.snapshot().generation(), "読み込み失敗"));
        assertEquals(LogSnapshot.ERROR, store.snapshot().status());
        assertEquals("読み込み失敗", store.snapshot().error());
    }

    /**
     * 試験: 読み込み中に別ディレクトリへ切り替えたときの結果反映。
     * 担保: 先に始まった重い読み込みが後から完了しても、新しい読み込み結果を
     *       上書きしない（世代が一致しない結果は破棄される）。
     *       この保護がないと、件数・ファイル名・エントリの組み合わせが壊れる。
     */
    @Test
    void staleLoadResultIsDiscarded() throws Exception {
        LogStore store = new LogStore();
        // 1 本目: サンプル全件（4 ファイル・15 行）
        store.setSource(TestPaths.samplesDir(), sampleLogPaths());
        store.startLoad();
        // 完了を待たずに 2 本目へ切り替える（1 ファイルだけ）
        List<Path> one = sampleLogPaths().subList(0, 1);
        store.setSource(TestPaths.samplesDir(), one);
        store.startLoad();
        waitUntilReady(store, 10, TimeUnit.SECONDS);
        // 1 本目が遅れて完了しても上書きされないこと。
        Thread.sleep(300);

        LogSnapshot snap = store.snapshot();
        assertEquals(LogSnapshot.READY, snap.status());
        assertEquals(1, snap.logPaths().size(), "対象は切り替え後の 1 ファイル");
        assertEquals(1, snap.sourceNames().size());
        // エントリの fileId が sourceNames の範囲に収まっている（添字ずれがない）。
        for (LogEntry e : snap.entries()) {
            assertTrue(e.fileId < snap.sourceNames().size(),
                    "fileId=" + e.fileId + " が範囲外");
        }
        assertEquals(snap.entries().size(), store.getLoadProgress());
    }

    /**
     * 試験: setSource 後の初期状態。
     * 担保: loadStatus が idle、エントリ未読み込み（空リスト）になる。
     */
    @Test
    void setSourceResetsToIdle() {
        LogStore store = new LogStore();
        store.setSource(TestPaths.samplesDir(), java.util.Collections.<Path>emptyList());
        LogSnapshot snap = store.snapshot();
        assertEquals(LogSnapshot.IDLE, snap.status());
        assertTrue(snap.entries().isEmpty());
        assertEquals(0, store.getLoadProgress());
    }

    /**
     * 試験: 読み込み対象を切り替えるたびに世代番号が進むこと。
     * 担保: 古い読み込みワーカーが自分の結果を破棄すべきかを判断できる。
     */
    @Test
    void generationAdvancesOnEachSetSource() {
        LogStore store = new LogStore();
        long first = store.snapshot().generation();
        store.setSource(TestPaths.samplesDir(), java.util.Collections.<Path>emptyList());
        long second = store.snapshot().generation();
        store.setSource(TestPaths.samplesDir(), java.util.Collections.<Path>emptyList());
        long third = store.snapshot().generation();
        assertTrue(first < second && second < third,
                "generation: " + first + " -> " + second + " -> " + third);
    }

    private static void waitUntilReady(LogStore store, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            String status = store.snapshot().status();
            if (LogSnapshot.READY.equals(status) || LogSnapshot.ERROR.equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
    }
}

package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link QueryFilter} の単体テスト。
 *
 * <p>ログ一覧 API に渡される検索条件（ステータス・メソッド・正規表現・日時範囲・grep）
 * の解析と、エントリとのマッチ判定を検証する。
 */
class FiltersTest {

    private static LogEntry sample() {
        return LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /api/users HTTP/1.1\" 200 4523",
                0, 1, 0);
    }

    /**
     * 試験: ステータスフィルタ文字列（単一値 / 範囲 / 複数指定）の解析。
     * 担保: 空文字は「フィルタ無し（null）」、{@code 4xx} は 400–499、カンマ区切りは和集合になる。
     */
    @Test
    void parseStatusFilter() {
        assertNull(QueryFilter.parseStatusFilter(""));
        assertNull(QueryFilter.parseStatusFilter(null));
        assertTrue(QueryFilter.parseStatusFilter("500").contains(500));
        Set<Integer> range = QueryFilter.parseStatusFilter("4xx");
        assertTrue(range.contains(400));
        assertTrue(range.contains(499));
        assertFalse(range.contains(500));
        Set<Integer> multi = QueryFilter.parseStatusFilter("500,502");
        assertTrue(multi.contains(500) && multi.contains(502));
    }

    /**
     * 試験: ステータス範囲指定の大文字表記（{@code 4XX}）。
     * 担保: 他のフィルタと同様に大文字小文字を区別せず、小文字表記と同じ集合になる。
     */
    @Test
    void parseStatusFilterIsCaseInsensitive() {
        Set<Integer> upper = QueryFilter.parseStatusFilter("4XX");
        assertTrue(upper.contains(400));
        assertTrue(upper.contains(499));
        assertFalse(upper.contains(500));
        assertEquals(QueryFilter.parseStatusFilter("4xx"), upper);
        assertEquals(QueryFilter.parseStatusFilter("5xx"), QueryFilter.parseStatusFilter("5Xx"));
    }

    /**
     * 試験: ステータス・メソッド・パス・ホストの複合条件でマッチ判定する。
     * 担保: すべての条件を満たすエントリのみ {@code true}、1 つでも不一致なら {@code false}。
     */
    @Test
    void matchesByStatusMethodPathHost() throws Exception {
        LogEntry e = sample();
        QueryFilter f = new QueryFilter();
        f.status = QueryFilter.parseStatusFilter("2xx");
        f.method = "get";
        f.pathRe = QueryFilter.compileRegex("/api/");
        f.hostRe = QueryFilter.compileRegex("127\\.0\\.0");
        assertTrue(f.matches(e, "access_log", null));

        QueryFilter no = new QueryFilter();
        no.status = QueryFilter.parseStatusFilter("500");
        assertFalse(no.matches(e, "access_log", null));
    }

    /**
     * 試験: grep 条件指定時の raw 行読み出し依存。
     * 担保: RawLine が無い場合は常に {@code false}、渡されれば生ログ行に対して正規表現マッチする。
     */
    @Test
    void grepRequiresRaw() throws Exception {
        LogEntry e = sample();
        QueryFilter f = new QueryFilter();
        f.grepRe = QueryFilter.compileRegex("users");
        assertFalse(f.matches(e, "access_log", null));
        assertTrue(f.matches(e, "access_log", entry -> "GET /api/users"));
        assertTrue(f.needsRaw());
    }

    /**
     * 試験: ログファイル正規表現フィルタ。
     * 担保: 絶対パス文字列に対して部分一致（find）で判定される。
     */
    @Test
    void sourceFilter() throws Exception {
        LogEntry e = sample();
        QueryFilter f = new QueryFilter();
        f.sourceRe = QueryFilter.compileRegex("access_log");
        assertTrue(f.matches(e, "C:/logs/access_log.1", null));
        f.sourceRe = QueryFilter.compileRegex("error_log");
        assertFalse(f.matches(e, "C:/logs/access_log.1", null));
    }

    /**
     * 試験: since / until による時刻範囲フィルタ。
     * 担保: 範囲外のエントリは除外され、境界値（since 以上・until 以下）は含まれる。
     */
    @Test
    void matchesByTimeRange() throws Exception {
        LogEntry e = sample();
        QueryFilter f = new QueryFilter();
        f.sinceWallMillis = e.wallMillis() - 1;
        f.untilWallMillis = e.wallMillis() + 1;
        assertTrue(f.matches(e, "access_log", null));

        f.sinceWallMillis = e.wallMillis() + 1;
        assertFalse(f.matches(e, "access_log", null));

        f.sinceWallMillis = null;
        f.untilWallMillis = e.wallMillis() - 1;
        assertFalse(f.matches(e, "access_log", null));
    }

    /**
     * 試験: JST 以外のタイムゾーンで記録されたログ行の期間フィルタ。
     * 担保: UI に表示されている現地時刻をそのまま入力すればヒットする。
     *       サーバ側で特定タイムゾーンを仮定していた頃は 16 時間ずれて 0 件になっていた。
     */
    @Test
    void matchesByTimeRangeForNonJstLog() throws Exception {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] \"GET /x HTTP/1.0\" 200 1",
                0, 1, 0);
        assertEquals("2000-10-10T13:55:36-07:00", e.timestampIso());

        QueryFilter f = new QueryFilter();
        // 表示されている 13:55:36 の前後 1 分。
        f.sinceWallMillis = TimeUtil.parseUiWallClockMillis("2000-10-10 13:54:36");
        f.untilWallMillis = TimeUtil.parseUiWallClockMillis("2000-10-10 13:56:36");
        assertTrue(f.matches(e, "access_log", null));

        // 範囲を外せば当たらない。
        f.sinceWallMillis = TimeUtil.parseUiWallClockMillis("2000-10-10 14:00:00");
        f.untilWallMillis = TimeUtil.parseUiWallClockMillis("2000-10-10 15:00:00");
        assertFalse(f.matches(e, "access_log", null));
    }

    /**
     * 試験: ステータス {@code "-"}（{@link LogEntry#NO_STATUS}）のエントリ。
     * 担保: 数値ステータスフィルタが指定されている場合、NO_STATUS 行は常に除外される。
     */
    @Test
    void noStatusExcludedFromNumericStatusFilter() throws Exception {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET / HTTP/1.1\" - -");
        assertTrue(e != null);
        QueryFilter f = new QueryFilter();
        f.status = QueryFilter.parseStatusFilter("2xx");
        assertFalse(f.matches(e, "access_log", null));
    }

    /**
     * 試験: ホストフィルタが forwardedFor にもマッチする。
     * 担保: clientHost / host / forwardedFor のいずれかに一致すればホスト条件を満たす。
     */
    @Test
    void hostFilterMatchesForwardedFor() throws Exception {
        LogEntry e = LogParser.parseLine(
                "203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] "
                + "\"GET /api/users HTTP/1.1\" 200 1024");
        assertTrue(e != null);
        QueryFilter f = new QueryFilter();
        f.hostRe = QueryFilter.compileRegex("203\\.0\\.113");
        assertTrue(f.matches(e, "access_log", null));
    }

    /**
     * 試験: メソッドフィルタの大文字小文字無視。
     * 担保: フィルタ側の大文字小文字に関わらず、エントリの method と一致判定される。
     */
    @Test
    void methodFilterIsCaseInsensitive() throws Exception {
        LogEntry e = sample();
        QueryFilter f = new QueryFilter();
        f.method = "GET";
        assertTrue(f.matches(e, "access_log", null));
        f.method = "post";
        assertFalse(f.matches(e, "access_log", null));
    }

    /**
     * 試験: 正規表現コンパイルヘルパの空入力。
     * 担保: null / 空文字は「条件無し（null Pattern）」として扱われる。
     */
    @Test
    void compileRegexEmptyReturnsNull() {
        assertNull(QueryFilter.compileRegex(""));
        assertNull(QueryFilter.compileRegex(null));
    }
}

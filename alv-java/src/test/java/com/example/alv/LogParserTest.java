package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * {@link LogParser} の単体テスト。
 *
 * <p>Apache Common / Combined 形式の 1 行を構造化データ（{@link LogEntry}）へ
 * 変換するパーサーの振る舞いを検証する。
 */
class LogParserTest {

    private static final String COMMON_LINE =
            "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] "
            + "\"GET /index.html HTTP/1.1\" 200 4523 \"-\" \"Mozilla/5.0 (very long user agent)\"";

    private static final String XFF_LINE =
            "203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] "
            + "\"GET /api/users HTTP/1.1\" 200 1024 \"https://app.example.com/\" \"Mozilla/5.0\"";

    /**
     * 試験: 標準的な Common Log 1 行を解析する。
     * 担保: host / method / path / status および fileId・lineNo・byteOffset が正しく設定される。
     */
    @Test
    void commonLog() {
        LogEntry e = LogParser.parseLine(COMMON_LINE, 0, 1, 10);
        assertNotNull(e);
        assertEquals("127.0.0.1", e.host);
        assertEquals("127.0.0.1", e.clientHost);
        assertEquals("GET", e.method);
        assertEquals("/index.html", e.path);
        assertEquals(200, e.status);
        assertEquals(0, e.fileId);
        assertEquals(1, e.lineNo);
        assertEquals(10, e.byteOffset);
    }

    /**
     * 試験: Combined Log の referer / user-agent 付き行を解析する。
     * 担保: 末尾フィールドがあっても status 等の主要フィールドは正しく取り出せる。
     */
    @Test
    void combinedLogIgnoresTrailingFields() {
        LogEntry e = LogParser.parseLine(COMMON_LINE);
        assertNotNull(e);
        assertEquals(200, e.status);
        assertEquals("/index.html", e.path);
    }

    /**
     * 試験: X-Forwarded-For + remote host 形式の leading 部分を解析する。
     * 担保: forwardedFor / host / clientHost（= XFF 先頭 IP）が正しく分離される。
     */
    @Test
    void xForwardedFor() {
        LogEntry e = LogParser.parseLine(XFF_LINE);
        assertNotNull(e);
        assertEquals("203.0.113.50", e.forwardedFor);
        assertEquals("10.0.0.5", e.host);
        assertEquals("203.0.113.50", e.clientHost);
    }

    /**
     * 試験: カンマ区切りの多段 XFF から clientHost を決定する。
     * 担保: 最初のホップ（左端）が clientHost として採用される。
     */
    @Test
    void multiHopForwardedForUsesFirst() {
        LogEntry e = LogParser.parseLine(
                "203.0.113.52, 198.51.100.11, 203.0.113.99 10.0.0.5 - - "
                + "[20/Jun/2025:07:58:00 +0900] \"GET /api/users HTTP/1.1\" 500 0 \"-\" \"x\"");
        assertNotNull(e);
        assertEquals("203.0.113.52", e.clientHost);
        assertEquals("10.0.0.5", e.host);
    }

    /**
     * 試験: ステータスが {@code "-"}（未記録）の行を解析する。
     * 担保: {@link LogEntry#NO_STATUS} に正規化され、行全体は破棄されない。
     */
    @Test
    void dashStatusBecomesNoStatus() {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET / HTTP/1.1\" - -");
        assertNotNull(e);
        assertEquals(LogEntry.NO_STATUS, e.status);
    }

    /**
     * 試験: ログ形式に合致しない行を渡す。
     * 担保: 例外を投げず {@code null} を返し、呼び出し元が行をスキップできる。
     */
    @Test
    void invalidLineReturnsNull() {
        assertNull(LogParser.parseLine("not a log line"));
    }

    /**
     * 試験: {@link LogParser#splitLeadingHosts(String)} の分岐を検証する。
     * 担保: XFF 無し・XFF あり・XFF が {@code "-"} の各ケースで [forwardedFor, host] が期待通り。
     */
    @Test
    void splitLeadingHosts() {
        assertArrayEquals(new String[] {"", "127.0.0.1"}, LogParser.splitLeadingHosts("127.0.0.1"));
        assertArrayEquals(new String[] {"203.0.113.50", "10.0.0.5"},
                LogParser.splitLeadingHosts("203.0.113.50 10.0.0.5"));
        assertArrayEquals(new String[] {"", "127.0.0.1"}, LogParser.splitLeadingHosts("- 127.0.0.1"));
        assertArrayEquals(new String[] {"", ""}, LogParser.splitLeadingHosts(""));
    }

    /**
     * 試験: VirtualHost プレフィックス（{@code example.com:80}）付き行を解析する。
     * 担保: 先頭の vhost 部分は読み飛ばされ、以降のフィールドが通常行と同様に解析される。
     */
    @Test
    void virtualHostPrefixIsSkipped() {
        LogEntry e = LogParser.parseLine(
                "example.com:80 127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] "
                + "\"GET / HTTP/1.1\" 200 0");
        assertNotNull(e);
        assertEquals("127.0.0.1", e.host);
        assertEquals("GET", e.method);
        assertEquals("/", e.path);
    }

    /**
     * 試験: 行末に CRLF が付いた行を解析する。
     * 担保: 改行は除去されてから正規表現マッチするため、パース結果に影響しない。
     */
    @Test
    void stripsTrailingCrLf() {
        LogEntry e = LogParser.parseLine(COMMON_LINE + "\r\n");
        assertNotNull(e);
        assertEquals("/index.html", e.path);
    }

    /**
     * 試験: タイムスタンプ部分が不正な行を解析する。
     * 担保: 行全体を {@code null} として破棄する（部分パースはしない）。
     */
    @Test
    void invalidTimestampReturnsNull() {
        assertNull(LogParser.parseLine(
                "127.0.0.1 - - [99/XXX/2025:08:01:12 +0900] \"GET / HTTP/1.1\" 200 0"));
    }

    /**
     * 試験: リクエスト行が {@code METHOD PATH PROTO} 形式でない場合。
     * 担保: method にリクエスト全文、path に {@code "-"} が入り、行は有効エントリとして保持される。
     */
    @Test
    void malformedRequestLineUsesFallback() {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"INVALID REQUEST\" 200 0");
        assertNotNull(e);
        assertEquals("INVALID REQUEST", e.method);
        assertEquals("-", e.path);
    }

    /**
     * 試験: POST / DELETE など GET 以外の HTTP メソッド。
     * 担保: メソッド名がそのまま抽出される。
     */
    @Test
    void parsesVariousHttpMethods() {
        LogEntry post = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"POST /api/login HTTP/1.1\" 401 128");
        assertNotNull(post);
        assertEquals("POST", post.method);
        assertEquals("/api/login", post.path);

        LogEntry del = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"DELETE /api/users/42 HTTP/1.1\" 403 89");
        assertNotNull(del);
        assertEquals("DELETE", del.method);
    }

    /**
     * 試験: clientHost と host が同一 IP の場合の参照共有。
     * 担保: メモリ節約のため同一文字列参照（{@code ==}）が使われる。
     */
    @Test
    void clientHostSharesReferenceWhenSameAsHost() {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET / HTTP/1.1\" 200 0");
        assertNotNull(e);
        assertSame(e.host, e.clientHost);
    }

    /**
     * 試験: 同一メソッド名を複数回パースする。
     * 担保: method 文字列が intern され、同一参照が再利用される。
     */
    @Test
    void methodStringIsInterned() {
        LogEntry a = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 0");
        LogEntry b = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /b HTTP/1.1\" 200 0");
        assertNotNull(a);
        assertNotNull(b);
        assertSame(a.method, b.method);
    }
}

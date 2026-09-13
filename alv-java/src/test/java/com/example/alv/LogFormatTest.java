package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LogFormat} と、書式を指定した {@link LogParser} の試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>Apache / nginx の combined・common（既定）と、その変種の解析</li>
 *   <li>nginx の {@code main} 形式で、行末の X-Forwarded-For から実クライアントを取ること</li>
 *   <li>行末の User-Agent を X-Forwarded-For と取り違えないこと</li>
 *   <li>ident / authuser を出力しない構成の解析</li>
 *   <li>ISO8601 のタイムスタンプ（nginx の {@code $time_iso8601} など）</li>
 *   <li>先頭ファイルのサンプリングによる自動判定</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>既定書式の解析結果が従来と変わらない</li>
 *   <li>プロキシ経由の nginx ログで、Client にプロキシではなく実クライアントが出る</li>
 *   <li>自動判定が外れても既定書式に落ちるだけで、例外にはならない</li>
 * </ul>
 */
class LogFormatTest {

    private static final String COMBINED =
            "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 123 "
                    + "\"-\" \"Mozilla/5.0\"";
    private static final String NGINX_MAIN =
            "10.0.0.5 - - [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 123 "
                    + "\"-\" \"Mozilla/5.0\" \"203.0.113.50\"";
    private static final String MINIMAL =
            "127.0.0.1 [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 123";
    private static final String ISO =
            "127.0.0.1 - - [2025-06-20T08:01:12+09:00] \"GET /a HTTP/1.1\" 200 123";

    @Test
    void parsesCombined() {
        LogEntry e = LogParser.parseLine(LogFormat.COMBINED, COMBINED);
        assertNotNull(e);
        assertEquals(200, e.status);
        assertEquals("/a", e.path);
        assertEquals("127.0.0.1", e.host);
        assertEquals("127.0.0.1", e.clientHost);
    }

    @Test
    void parsesVhostPrefixAndLeadingForwardedFor() {
        LogEntry vhost = LogParser.parseLine(LogFormat.COMBINED,
                "example.com:80 127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 1");
        assertNotNull(vhost, "vhost 前置き（%v:%p）を読み飛ばせること");
        LogEntry xff = LogParser.parseLine(LogFormat.COMBINED,
                "203.0.113.5 10.0.0.5 - - [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 1");
        assertNotNull(xff);
        assertEquals("203.0.113.5", xff.clientHost, "先頭の XFF を実クライアントとして扱う");
        assertEquals("10.0.0.5", xff.host);
    }

    @Test
    void nginxMainUsesTrailingForwardedFor() {
        LogEntry e = LogParser.parseLine(LogFormat.NGINX_MAIN, NGINX_MAIN);
        assertNotNull(e);
        assertEquals("203.0.113.50", e.clientHost, "末尾の XFF を実クライアントとして扱う");
        assertEquals("10.0.0.5", e.host, "remote は接続元（プロキシ）のまま");
    }

    /** 既定書式では末尾を読まないため、プロキシの IP が Client に出る（nginx 書式が要る理由）。 */
    @Test
    void combinedIgnoresTrailingForwardedFor() {
        LogEntry e = LogParser.parseLine(LogFormat.COMBINED, NGINX_MAIN);
        assertNotNull(e);
        assertEquals("10.0.0.5", e.clientHost);
    }

    @Test
    void nginxMainDoesNotMistakeUserAgentForForwardedFor() {
        // 末尾の引用フィールドが User-Agent の combined 形式。IP に見えないので採用しない。
        LogEntry e = LogParser.parseLine(LogFormat.NGINX_MAIN, COMBINED);
        assertNotNull(e);
        assertEquals("127.0.0.1", e.clientHost);
        assertNull(LogParser.trailingForwardedFor(COMBINED));
    }

    @Test
    void nginxMainFallsBackWhenForwardedForIsDash() {
        LogEntry e = LogParser.parseLine(LogFormat.NGINX_MAIN,
                "10.0.0.5 - - [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 123 "
                        + "\"-\" \"Mozilla/5.0\" \"-\"");
        assertNotNull(e);
        assertEquals("10.0.0.5", e.clientHost, "XFF が - のときは接続元をそのまま使う");
    }

    @Test
    void parsesMinimalWithoutIdentAndAuthuser() {
        assertNull(LogParser.parseLine(LogFormat.COMBINED, MINIMAL),
                "既定書式は ident/authuser を必須にしているため解析できない");
        LogEntry e = LogParser.parseLine(LogFormat.MINIMAL, MINIMAL);
        assertNotNull(e);
        assertEquals(200, e.status);
        assertEquals("127.0.0.1", e.host);
    }

    @Test
    void parsesIso8601Timestamp() {
        LogEntry e = LogParser.parseLine(LogFormat.COMBINED, ISO);
        assertNotNull(e, "nginx の $time_iso8601 など ISO8601 の時刻も受ける");
        assertEquals(200, e.status);
        LogEntry apache = LogParser.parseLine(LogFormat.COMBINED,
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 123");
        assertNotNull(apache);
        assertEquals(apache.tsMillis, e.tsMillis, "同じ瞬間を指す表記は同じ値になる");
    }

    @Test
    void parsesIso8601WithZuluAndFraction() {
        assertNotNull(LogParser.parseLine(LogFormat.COMBINED,
                "127.0.0.1 - - [2025-06-20T08:01:12Z] \"GET /a HTTP/1.1\" 200 1"));
        assertNotNull(LogParser.parseLine(LogFormat.COMBINED,
                "127.0.0.1 - - [2025-06-20T08:01:12.345+09:00] \"GET /a HTTP/1.1\" 200 1"));
    }

    @Test
    void detectsFormats(@TempDir Path dir) throws IOException {
        assertEquals(LogFormat.COMBINED, detectOf(dir, "a.log", COMBINED));
        assertEquals(LogFormat.NGINX_MAIN, detectOf(dir, "b.log", NGINX_MAIN));
        assertEquals(LogFormat.MINIMAL, detectOf(dir, "c.log", MINIMAL));
    }

    /**
     * nginx は combined の部分集合なので、末尾 XFF を持つ行が過半数のときだけ選ぶ。
     * ちょうど半数のときは combined のままになる（境界の挙動を固定する）。
     */
    @Test
    void detectionRequiresMajorityForNginx(@TempDir Path dir) throws IOException {
        Path half = dir.resolve("half.log");
        Files.write(half, Arrays.asList(NGINX_MAIN, COMBINED, NGINX_MAIN, COMBINED),
                StandardCharsets.UTF_8);
        assertEquals(LogFormat.COMBINED, LogFormat.detect(Collections.singletonList(half)),
                "ちょうど半数では nginx を選ばない");

        Path majority = dir.resolve("majority.log");
        Files.write(majority, Arrays.asList(NGINX_MAIN, NGINX_MAIN, COMBINED),
                StandardCharsets.UTF_8);
        assertEquals(LogFormat.NGINX_MAIN, LogFormat.detect(Collections.singletonList(majority)),
                "過半数なら nginx を選ぶ");
    }

    @Test
    void detectionFallsBackToCombined(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("none.log");
        Files.write(log, Arrays.asList("hello", "world"), StandardCharsets.UTF_8);
        assertEquals(LogFormat.COMBINED, LogFormat.detect(Collections.singletonList(log)));
        assertEquals(LogFormat.COMBINED, LogFormat.detect(Collections.<Path>emptyList()));
        assertEquals(LogFormat.COMBINED, LogFormat.detect(null));
    }

    @Test
    void byIdRejectsUnknownValues() {
        assertNull(LogFormat.byId("nope"));
        assertNull(LogFormat.byId(null));
        for (LogFormat f : LogFormat.values()) {
            assertEquals(f, LogFormat.byId(f.id()));
        }
    }

    private static LogFormat detectOf(Path dir, String name, String line) throws IOException {
        Path log = dir.resolve(name);
        List<String> lines = Arrays.asList(line, line, line);
        Files.write(log, lines, StandardCharsets.UTF_8);
        return LogFormat.detect(Collections.singletonList(log));
    }
}

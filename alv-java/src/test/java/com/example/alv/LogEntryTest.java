package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link LogEntry} の単体テスト。
 *
 * <p>ログエントリモデルの表示用メソッドと定数の意味を検証する。
 */
class LogEntryTest {

    /**
     * 試験: {@link LogEntry#timestampIso()} による ISO 8601 表示。
     * 担保: 保持している UTC millis とタイムゾーンオフセットから、API 応答用の文字列が生成される。
     */
    @Test
    void timestampIsoUsesStoredOffset() {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET / HTTP/1.1\" 200 0");
        assertEquals("2025-06-20T08:01:12+09:00", e.timestampIso());
    }

    /**
     * 試験: {@link LogEntry#NO_STATUS} の意味。
     * 担保: Apache の {@code "-"} ステータスが -1 に正規化され、フィルタ・表示で区別できる。
     */
    @Test
    void noStatusConstant() {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET / HTTP/1.1\" - -");
        assertEquals(LogEntry.NO_STATUS, e.status);
        assertEquals(-1, LogEntry.NO_STATUS);
    }
}

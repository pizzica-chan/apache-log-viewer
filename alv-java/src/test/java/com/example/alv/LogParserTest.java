package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LogParserTest {

    private static final String COMMON_LINE =
            "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] "
            + "\"GET /index.html HTTP/1.1\" 200 4523 \"-\" \"Mozilla/5.0 (very long user agent)\"";

    private static final String XFF_LINE =
            "203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] "
            + "\"GET /api/users HTTP/1.1\" 200 1024 \"https://app.example.com/\" \"Mozilla/5.0\"";

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

    @Test
    void combinedLogIgnoresTrailingFields() {
        LogEntry e = LogParser.parseLine(COMMON_LINE);
        assertNotNull(e);
        assertEquals(200, e.status);
    }

    @Test
    void xForwardedFor() {
        LogEntry e = LogParser.parseLine(XFF_LINE);
        assertNotNull(e);
        assertEquals("203.0.113.50", e.forwardedFor);
        assertEquals("10.0.0.5", e.host);
        assertEquals("203.0.113.50", e.clientHost);
    }

    @Test
    void multiHopForwardedForUsesFirst() {
        LogEntry e = LogParser.parseLine(
                "203.0.113.52, 198.51.100.11, 203.0.113.99 10.0.0.5 - - "
                + "[20/Jun/2025:07:58:00 +0900] \"GET /api/users HTTP/1.1\" 500 0 \"-\" \"x\"");
        assertNotNull(e);
        assertEquals("203.0.113.52", e.clientHost);
        assertEquals("10.0.0.5", e.host);
    }

    @Test
    void dashStatusBecomesNoStatus() {
        LogEntry e = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET / HTTP/1.1\" - -");
        assertNotNull(e);
        assertEquals(LogEntry.NO_STATUS, e.status);
    }

    @Test
    void invalidLineReturnsNull() {
        assertNull(LogParser.parseLine("not a log line"));
    }

    @Test
    void splitLeadingHosts() {
        assertArrayEquals(new String[] {"", "127.0.0.1"}, LogParser.splitLeadingHosts("127.0.0.1"));
        assertArrayEquals(new String[] {"203.0.113.50", "10.0.0.5"},
                LogParser.splitLeadingHosts("203.0.113.50 10.0.0.5"));
        assertArrayEquals(new String[] {"", "127.0.0.1"}, LogParser.splitLeadingHosts("- 127.0.0.1"));
    }
}

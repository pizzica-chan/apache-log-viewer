package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class FiltersTest {

    private static LogEntry sample() {
        return LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET /api/users HTTP/1.1\" 200 4523",
                0, 1, 0);
    }

    @Test
    void parseStatusFilter() {
        assertNull(QueryFilter.parseStatusFilter(""));
        assertTrue(QueryFilter.parseStatusFilter("500").contains(500));
        Set<Integer> range = QueryFilter.parseStatusFilter("4xx");
        assertTrue(range.contains(400));
        assertTrue(range.contains(499));
        assertFalse(range.contains(500));
        Set<Integer> multi = QueryFilter.parseStatusFilter("500,502");
        assertTrue(multi.contains(500) && multi.contains(502));
    }

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

    @Test
    void grepRequiresRaw() throws Exception {
        LogEntry e = sample();
        QueryFilter f = new QueryFilter();
        f.grepRe = QueryFilter.compileRegex("users");
        // raw リーダが無い場合は false。
        assertFalse(f.matches(e, "access_log", null));
        // raw を渡せば評価できる。
        assertTrue(f.matches(e, "access_log", entry -> "GET /api/users"));
    }

    @Test
    void sourceFilter() throws Exception {
        LogEntry e = sample();
        QueryFilter f = new QueryFilter();
        f.sourceRe = QueryFilter.compileRegex("access_log");
        assertTrue(f.matches(e, "C:/logs/access_log.1", null));
        f.sourceRe = QueryFilter.compileRegex("error_log");
        assertFalse(f.matches(e, "C:/logs/access_log.1", null));
    }
}

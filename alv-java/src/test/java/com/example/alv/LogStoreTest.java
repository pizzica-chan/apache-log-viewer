package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class LogStoreTest {

    private List<Path> sampleLogPaths() throws IOException {
        Path samples = TestPaths.samplesDir();
        assumeTrue(Files.isDirectory(samples), "samples ディレクトリが見つかりません");
        List<Path> paths = Discovery.findLogFiles(samples);
        assumeTrue(!paths.isEmpty(), "サンプルログが見つかりません");
        return paths;
    }

    @Test
    void loadsAllSampleEntries() throws IOException {
        List<LogEntry> entries = LogStore.loadEntries(sampleLogPaths(), true, null);
        assertEquals(13, entries.size());
    }

    @Test
    void mergedEntriesAreSortedByTimestamp() throws IOException {
        List<LogEntry> entries = LogStore.loadEntries(sampleLogPaths(), true, null);
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).tsMillis <= entries.get(i).tsMillis,
                    "時刻順にソートされていること");
        }
    }

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

        List<LogEntry> entries = LogStore.loadEntries(paths, false, null);
        LogEntry first = null;
        for (LogEntry e : entries) {
            if (e.fileId == xffId && e.lineNo == 1) {
                first = e;
                break;
            }
        }
        assertTrue(first != null);
        try (LineReader reader = new LineReader(paths)) {
            String raw = reader.read(first.fileId, first.byteOffset);
            assertEquals(
                    "203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] "
                    + "\"GET /api/users HTTP/1.1\" 200 1024 \"https://app.example.com/\" \"Mozilla/5.0\"",
                    raw);
        }
    }
}

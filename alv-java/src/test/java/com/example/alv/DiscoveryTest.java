package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class DiscoveryTest {

    @Test
    void isLogFileMatchesApachePatterns() {
        assertTrue(Discovery.isLogFile("access_log"));
        assertTrue(Discovery.isLogFile("access_log.1"));
        assertTrue(Discovery.isLogFile("error_log"));
        assertTrue(Discovery.isLogFile("server.log"));
        assertFalse(Discovery.isLogFile("access_log.1.gz"));
        assertFalse(Discovery.isLogFile("readme.txt"));
    }

    @Test
    void findLogFilesInSamples() throws IOException {
        Path samples = TestPaths.samplesDir();
        assumeTrue(Files.isDirectory(samples), "samples ディレクトリが見つかりません");
        List<Path> found = Discovery.findLogFiles(samples);
        assertEquals(3, found.size());
    }

    @Test
    void findLogFilesMissingDirReturnsEmpty() throws IOException {
        assertTrue(Discovery.findLogFiles(java.nio.file.Paths.get("does-not-exist-xyz")).isEmpty());
    }
}

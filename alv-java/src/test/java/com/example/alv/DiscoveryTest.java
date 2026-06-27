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

/**
 * {@link Discovery} の単体テスト。
 *
 * <p>ログファイル名の判定と、ディレクトリ再帰探索の結果を検証する。
 */
class DiscoveryTest {

    /**
     * 試験: Apache 系ログファイル名パターンへの一致判定。
     * 担保: access_log / error_log / *.log は対象、圧縮ファイル（.gz 等）は除外される。
     */
    @Test
    void isLogFileMatchesApachePatterns() {
        assertTrue(Discovery.isLogFile("access_log"));
        assertTrue(Discovery.isLogFile("access_log.1"));
        assertTrue(Discovery.isLogFile("error_log"));
        assertTrue(Discovery.isLogFile("server.log"));
        assertTrue(Discovery.isLogFile("access.log"));
        assertTrue(Discovery.isLogFile("access.log.1"));
        assertFalse(Discovery.isLogFile("access_log.1.gz"));
        assertFalse(Discovery.isLogFile("access_log.1.bz2"));
        assertFalse(Discovery.isLogFile("access_log.1.xz"));
        assertFalse(Discovery.isLogFile("readme.txt"));
    }

    /**
     * 試験: リポジトリ同梱の {@code samples/} ディレクトリを探索する。
     * 担保: サンプルログ 4 件がすべて発見される（回帰テスト用の固定件数）。
     */
    @Test
    void findLogFilesInSamples() throws IOException {
        Path samples = TestPaths.samplesDir();
        assumeTrue(Files.isDirectory(samples), "samples ディレクトリが見つかりません");
        List<Path> found = Discovery.findLogFiles(samples);
        assertEquals(4, found.size());
    }

    /**
     * 試験: 存在しないディレクトリを指定した探索。
     * 担保: 例外を投げず空リストを返す。
     */
    @Test
    void findLogFilesMissingDirReturnsEmpty() throws IOException {
        assertTrue(Discovery.findLogFiles(java.nio.file.Paths.get("does-not-exist-xyz")).isEmpty());
    }

    /**
     * 試験: {@link Discovery#globMatch(String, String)} のワイルドカード動作。
     * 担保: {@code *} は任意長、{@code ?} は 1 文字にマッチし、大文字小文字は区別しない（呼び出し前に lower 化）。
     */
    @Test
    void globMatchPatterns() {
        assertTrue(Discovery.globMatch("access_log*", "access_log.1"));
        assertTrue(Discovery.globMatch("access_log*", "access_log"));
        assertTrue(Discovery.globMatch("*.log", "server.log"));
        assertTrue(Discovery.globMatch("error_log?", "error_log1"));
        assertFalse(Discovery.globMatch("access_log*", "error_log"));
        assertFalse(Discovery.globMatch("*.log", "access_log"));
    }
}

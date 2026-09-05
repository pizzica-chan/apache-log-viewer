package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
     * 試験: シンボリックリンク経由で同一ファイルに二重到達する構成の探索。
     * 担保: 実体パスで重複排除され、同じログを 2 回読み込まない。
     *       （シンボリックリンクを作成できない環境ではスキップする）
     */
    @Test
    void findLogFilesDeduplicatesSymlinkedFiles(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectory(tmp.resolve("real"));
        Path target = real.resolve("access_log");
        Files.write(target, "dummy".getBytes(StandardCharsets.UTF_8));
        // ログ名パターンに一致するファイルへのリンクを張り、実体へ二重に到達させる。
        // （ディレクトリへのリンクでは walkFileTree が追従しないため再現しない）
        try {
            Files.createSymbolicLink(tmp.resolve("access_log.link"), target);
        } catch (IOException | UnsupportedOperationException e) {
            abort("シンボリックリンクを作成できない環境です: " + e);
        }
        List<Path> found = Discovery.findLogFiles(tmp);
        assertEquals(1, found.size(), "実体パスが同じファイルは 1 件に集約される: " + found);
        assertEquals(target.toRealPath(), found.get(0));
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

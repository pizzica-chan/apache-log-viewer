package com.example.alv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * テスト用にサンプルログのディレクトリを解決するヘルパー。
 *
 * <p>Maven の作業ディレクトリ（{@code alv-java/}）から見た相対パスで
 * リポジトリ直下の {@code samples/} を探す。
 */
final class TestPaths {

    private TestPaths() {
    }

    /**
     * リポジトリ直下の {@code samples} ディレクトリを返す。
     *
     * <p>候補: {@code ../samples}（Maven モジュール直下から）、{@code samples}。
     * 見つからない場合は先頭候補を返し、呼び出し側で {@code assumeTrue} 等でスキップする。
     */
    static Path samplesDir() {
        Path[] candidates = {
                Paths.get("..", "samples"),
                Paths.get("samples"),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return candidates[0];
    }
}

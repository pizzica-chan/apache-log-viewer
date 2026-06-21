package com.example.alv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** テスト用にサンプルログのディレクトリを解決するヘルパー。 */
final class TestPaths {

    private TestPaths() {
    }

    /**
     * リポジトリ直下の {@code samples} ディレクトリを返す。
     * Maven 実行時の作業ディレクトリ（モジュール直下）からの相対 {@code ../samples} を優先する。
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

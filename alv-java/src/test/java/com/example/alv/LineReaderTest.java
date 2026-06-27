package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LineReader} の単体テスト。
 *
 * <p>バイトオフセット指定による生ログ行のオンデマンド読み出しを検証する。
 */
class LineReaderTest {

    /**
     * 試験: 既知のバイトオフセットから 2 行目を読み出す。
     * 担保: grep / 詳細表示で使う RandomAccess 読み出しが正確な文字列を返す。
     */
    @Test
    void readLineAtOffset(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.log");
        String content = "first line\nsecond line\nthird line\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        long secondOffset = "first line\n".getBytes(StandardCharsets.UTF_8).length;

        try (LineReader reader = new LineReader(Collections.singletonList(file))) {
            assertEquals("second line", reader.read(0, secondOffset));
            assertEquals("first line", reader.read(0, 0));
        }
    }

    /**
     * 試験: CRLF 行末の除去。
     * 担保: 返却文字列から {@code \r} / {@code \n} が取り除かれる。
     */
    @Test
    void stripsCrLfFromResult(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("crlf.log");
        Files.write(file, "hello\r\n".getBytes(StandardCharsets.UTF_8));

        try (LineReader reader = new LineReader(Collections.singletonList(file))) {
            assertEquals("hello", reader.read(0, 0));
        }
    }
}

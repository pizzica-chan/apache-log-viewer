package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
     * 試験: 窓をまたぐ大きさのファイルを、前向き・後ろ向き・飛び飛びの順で読む。
     * 担保: まとめ読みの窓を使い回しても読み直しても、行ごとに読んだ場合と同じ文字列を返す
     * （窓の境界にかかる行・多バイト文字・CRLF・改行で終わらない最終行を含む）。
     */
    @Test
    void readsSameLinesInAnyOrder(@TempDir Path tempDir) throws Exception {
        List<String> lines = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            StringBuilder line = new StringBuilder("line ").append(i).append(' ');
            for (int k = 0; k < i % 97; k++) {
                line.append(k % 3 == 0 ? "あ" : "x");
            }
            lines.add(line.toString());
            content.append(line).append(i % 5 == 0 ? "\r\n" : "\n");
        }
        lines.add("last line without newline");
        content.append("last line without newline");
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length > 2 * LineReader.MAX_WINDOW_BYTES, "窓を何度かまたぐ大きさにする");
        Path file = tempDir.resolve("many.log");
        Files.write(file, bytes);
        long[] offsets = offsetsOf(bytes);
        assertEquals(lines.size(), offsets.length);

        try (LineReader reader = new LineReader(Collections.singletonList(file))) {
            for (int i = 0; i < lines.size(); i++) {
                assertEquals(lines.get(i), reader.read(0, offsets[i]), "前向き " + i);
            }
            for (int i = lines.size() - 1; i >= 0; i--) {
                assertEquals(lines.get(i), reader.read(0, offsets[i]), "後ろ向き " + i);
            }
            for (int i = 0; i < lines.size(); i += 7) {
                int j = (i * 31) % lines.size();
                assertEquals(lines.get(j), reader.read(0, offsets[j]), "飛び飛び " + j);
            }
            assertEquals("", reader.read(0, bytes.length), "ファイル末尾");
        }
    }

    /**
     * 試験: 窓より長い行と、複数ファイルを交互に読む。
     * 担保: 窓に収まらない行も末尾まで返し、ファイルごとの窓が互いを壊さない。
     */
    @Test
    void readsLongLinesAndInterleavedFiles(@TempDir Path tempDir) throws Exception {
        StringBuilder longLine = new StringBuilder();
        while (longLine.length() < LineReader.MAX_WINDOW_BYTES * 3) {
            longLine.append("0123456789");
        }
        Path a = tempDir.resolve("a.log");
        Files.write(a, ("a1\n" + longLine + "\na3\n").getBytes(StandardCharsets.UTF_8));
        Path b = tempDir.resolve("b.log");
        Files.write(b, "b1\nb2\n".getBytes(StandardCharsets.UTF_8));
        long a2 = 3;
        long a3 = a2 + longLine.length() + 1;

        try (LineReader reader = new LineReader(Arrays.asList(a, b))) {
            assertEquals("a1", reader.read(0, 0));
            assertEquals("b1", reader.read(1, 0));
            assertEquals(longLine.toString(), reader.read(0, a2));
            assertEquals("b2", reader.read(1, 3));
            assertEquals("a3", reader.read(0, a3));
            assertEquals("a1", reader.read(0, 0));
        }
    }

    /**
     * 試験: ファイル数に応じた窓の大きさ。
     * 担保: ファイルが多くても窓の合計が膨らみすぎず、下限は変更前の読み出し単位を下回らない。
     */
    @Test
    void windowSizeDependsOnFileCount() {
        assertEquals(LineReader.MAX_WINDOW_BYTES, LineReader.windowBytes(1));
        assertEquals(LineReader.MAX_WINDOW_BYTES, LineReader.windowBytes(30));
        assertEquals(LineReader.TOTAL_WINDOW_BYTES / 256, LineReader.windowBytes(256));
        assertEquals(LineReader.MIN_WINDOW_BYTES, LineReader.windowBytes(10000));
    }

    /** 各行の先頭のバイト位置。 */
    private static long[] offsetsOf(byte[] bytes) {
        List<Long> out = new ArrayList<>();
        out.add(0L);
        for (int i = 0; i < bytes.length - 1; i++) {
            if (bytes[i] == '\n') {
                out.add((long) i + 1);
            }
        }
        long[] result = new long[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
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

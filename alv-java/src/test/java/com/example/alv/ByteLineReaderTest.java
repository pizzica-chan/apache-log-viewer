package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * {@link ByteLineReader} の単体テスト。
 *
 * <p>大容量ログ読み込み用リーダーの行分割・バイトオフセット記録・空白行判定を検証する。
 */
class ByteLineReaderTest {

    /**
     * 試験: LF 区切りの複数行を順に読み出す。
     * 担保: 各行の {@code lineStart} がファイル先頭からの正しいバイト位置を指す。
     */
    @Test
    void readsLinesWithCorrectOffsets() throws Exception {
        byte[] data = "first\nsecond\n".getBytes(StandardCharsets.UTF_8);
        try (ByteLineReader reader = new ByteLineReader(new ByteArrayInputStream(data))) {
            assertTrue(reader.next());
            assertEquals(0, reader.lineStart);
            assertEquals("first\n", new String(reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
            assertFalse(reader.isBlankLine());

            assertTrue(reader.next());
            assertEquals(6, reader.lineStart);
            assertEquals("second\n", new String(reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
        }
    }

    /**
     * 試験: 空白のみの行。
     * 担保: {@link ByteLineReader#isBlankLine()} が true を返し、LogStore がスキップできる。
     */
    @Test
    void detectsBlankLine() throws Exception {
        byte[] data = "   \ncontent\n".getBytes(StandardCharsets.UTF_8);
        try (ByteLineReader reader = new ByteLineReader(new ByteArrayInputStream(data))) {
            assertTrue(reader.next());
            assertTrue(reader.isBlankLine());
            assertTrue(reader.next());
            assertFalse(reader.isBlankLine());
        }
    }

    /**
     * 試験: 最終行に改行が無いファイル（EOF で終端）。
     * 担保: 最終行も 1 行として読み出される。
     */
    @Test
    void readsLastLineWithoutTrailingNewline() throws Exception {
        byte[] data = "only".getBytes(StandardCharsets.UTF_8);
        try (ByteLineReader reader = new ByteLineReader(new ByteArrayInputStream(data))) {
            assertTrue(reader.next());
            assertEquals("only", new String(reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
            assertFalse(reader.next());
        }
    }

    /**
     * 試験: CRLF 改行を含む行。
     * 担保: {@code \r\n} 全体が行バッファに含まれる（LogParser 側で EOL 除去）。
     */
    @Test
    void includesCrInLineBuffer() throws Exception {
        byte[] data = "line\r\n".getBytes(StandardCharsets.UTF_8);
        try (ByteLineReader reader = new ByteLineReader(new ByteArrayInputStream(data))) {
            assertTrue(reader.next());
            assertEquals("line\r\n", new String(reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
        }
    }
}

package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link PathUtil} の単体テスト。
 *
 * <p>Windows 拡張パス（{@code \\?\}）の正規化と、UI 表示用パス文字列の生成を検証する。
 */
class PathUtilTest {

    /**
     * 試験: {@code \\?\} プレフィックス付き絶対パスの正規化。
     * 担保: UI に表示する際、通常のドライブレター形式へ戻される。
     */
    @Test
    void normalizeExtendedLengthPath() {
        assertEquals("C:\\logs\\access_log.1", PathUtil.normalizePathStr("\\\\?\\C:\\logs\\access_log.1"));
    }

    /**
     * 試験: {@code \\?\UNC\} 形式の UNC パス正規化。
     * 担保: {@code \\server\share\...} 形式へ変換される。
     */
    @Test
    void normalizeUncExtendedPath() {
        assertEquals("\\\\server\\share\\access_log",
                PathUtil.normalizePathStr("\\\\?\\UNC\\server\\share\\access_log"));
    }

    /**
     * 試験: 通常パス文字列はそのまま返る。
     * 担保: 不要な変換を行わない。
     */
    @Test
    void normalizePlainPathUnchanged() {
        assertEquals("/var/log/access_log", PathUtil.normalizePathStr("/var/log/access_log"));
    }

    /**
     * 試験: null 入力。
     * 担保: null をそのまま返す（NPE を投げない）。
     */
    @Test
    void normalizeNullReturnsNull() {
        assertNull(PathUtil.normalizePathStr(null));
    }
}

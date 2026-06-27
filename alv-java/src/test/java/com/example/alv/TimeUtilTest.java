package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TimeUtil} の単体テスト。
 *
 * <p>Apache ログのタイムスタンプと UI 日時文字列の相互変換、
 * および ISO 8601 表示の正確性を検証する。
 */
class TimeUtilTest {

    /**
     * 試験: タイムゾーン付き Apache タイムスタンプの解析と ISO 整形。
     * 担保: オフセット（+09:00 = 540 分）が保持され、表示文字列が期待どおり復元される。
     */
    @Test
    void parseAndFormatWithTimezone() {
        long[] ts = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12 +0900");
        assertNotNull(ts);
        assertEquals(540, ts[1]);
        assertEquals("2025-06-20T08:01:12+09:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
    }

    /**
     * 試験: タイムゾーン省略時の Apache タイムスタンプ。
     * 担保: UTC（+0000）として解釈される。
     */
    @Test
    void parseWithoutTimezoneDefaultsToUtc() {
        long[] ts = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12");
        assertNotNull(ts);
        assertEquals(0, ts[1]);
        assertEquals("2025-06-20T08:01:12+00:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
    }

    /**
     * 試験: 負のタイムゾーンオフセット（-0700）。
     * 担保: オフセット符号と ISO 表示が正しく処理される。
     */
    @Test
    void negativeOffset() {
        long[] ts = TimeUtil.parseApacheTimestamp("10/Oct/2000:13:55:36 -0700");
        assertNotNull(ts);
        assertEquals(-420, ts[1]);
        assertEquals("2000-10-10T13:55:36-07:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
    }

    /**
     * 試験: 異なるタイムゾーン表記で同一瞬間を表す 2 つのタイムスタンプ。
     * 担保: UTC epoch millis（{@code ts[0]}）が一致し、時刻順ソートに使える。
     */
    @Test
    void utcMillisIsTimezoneIndependent() {
        long[] jst = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12 +0900");
        long[] utc = TimeUtil.parseApacheTimestamp("19/Jun/2025:23:01:12 +0000");
        assertEquals(utc[0], jst[0]);
    }

    /**
     * 試験: UI 日時文字列（スペース区切り）を JST として解釈する。
     * 担保: 同じローカル時刻の Apache タイムスタンプと UTC millis が一致する。
     */
    @Test
    void parseUiDatetimeUsesJst() {
        long ui = TimeUtil.parseUiDatetime("2025-06-20 08:01:12");
        long[] apache = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12 +0900");
        assertNotNull(apache);
        assertEquals(apache[0], ui);
    }

    /**
     * 試験: UI 日時の複数入力形式（T 区切り / 日付のみ）。
     * 担保: 同等の時刻表現は同一 millis に正規化され、日付のみは 00:00:00 として解釈される。
     */
    @Test
    void parseUiDatetimeVariants() {
        long a = TimeUtil.parseUiDatetime("2025-06-20 08:00:00");
        long b = TimeUtil.parseUiDatetime("2025-06-20T08:00:00");
        assertEquals(a, b);
        assertTrue(TimeUtil.parseUiDatetime("2025-06-20") < a);
    }

    /**
     * 試験: 解釈不能な UI 日時文字列。
     * 担保: {@link IllegalArgumentException} を投げ、API 層で 400 応答に変換できる。
     */
    @Test
    void parseUiDatetimeInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiDatetime("not-a-date"));
    }

    /**
     * 試験: 不正な Apache タイムスタンプ文字列。
     * 担保: 例外ではなく {@code null} を返し、パーサーが行単位でスキップできる。
     */
    @Test
    void parseApacheTimestampInvalidReturnsNull() {
        assertNull(TimeUtil.parseApacheTimestamp("not-a-timestamp"));
        assertNull(TimeUtil.parseApacheTimestamp("99/XXX/2025:08:01:12 +0900"));
    }

    /**
     * 試験: parse → format のラウンドトリップ。
     * 担保: 一度 UTC millis に変換しても、元のローカル時刻・オフセットが ISO 文字列で復元できる。
     */
    @Test
    void formatIsoOffsetRoundTrip() {
        String original = "15/Mar/2024:23:59:59 +0900";
        long[] ts = TimeUtil.parseApacheTimestamp(original);
        assertNotNull(ts);
        assertEquals("2024-03-15T23:59:59+09:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
    }

    /**
     * 試験: UI 日時の分まで指定（秒省略）。
     * 担保: 秒は 0 として補完され、パースが成功する。
     */
    @Test
    void parseUiDatetimeMinutePrecision() {
        long full = TimeUtil.parseUiDatetime("2025-06-20 08:30:00");
        long minute = TimeUtil.parseUiDatetime("2025-06-20 08:30");
        assertEquals(full, minute);
    }
}

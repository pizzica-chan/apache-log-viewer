package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimeUtilTest {

    @Test
    void parseAndFormatWithTimezone() {
        long[] ts = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12 +0900");
        assertNotNull(ts);
        assertEquals(540, ts[1]); // +09:00 = 540 分
        assertEquals("2025-06-20T08:01:12+09:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
    }

    @Test
    void parseWithoutTimezoneDefaultsToUtc() {
        long[] ts = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12");
        assertNotNull(ts);
        assertEquals(0, ts[1]);
        assertEquals("2025-06-20T08:01:12+00:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
    }

    @Test
    void negativeOffset() {
        long[] ts = TimeUtil.parseApacheTimestamp("10/Oct/2000:13:55:36 -0700");
        assertNotNull(ts);
        assertEquals(-420, ts[1]);
        assertEquals("2000-10-10T13:55:36-07:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
    }

    @Test
    void utcMillisIsTimezoneIndependent() {
        // 同一瞬間（08:01:12 +0900 == 23:01:12 前日 UTC）の UTC millis は一致する。
        long[] jst = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12 +0900");
        long[] utc = TimeUtil.parseApacheTimestamp("19/Jun/2025:23:01:12 +0000");
        assertEquals(utc[0], jst[0]);
    }

    @Test
    void parseUiDatetimeUsesJst() {
        long ui = TimeUtil.parseUiDatetime("2025-06-20 08:01:12");
        long[] apache = TimeUtil.parseApacheTimestamp("20/Jun/2025:08:01:12 +0900");
        assertNotNull(apache);
        assertEquals(apache[0], ui);
    }

    @Test
    void parseUiDatetimeVariants() {
        long a = TimeUtil.parseUiDatetime("2025-06-20 08:00:00");
        long b = TimeUtil.parseUiDatetime("2025-06-20T08:00:00");
        assertEquals(a, b);
        assertTrue(TimeUtil.parseUiDatetime("2025-06-20") < a);
    }

    @Test
    void parseUiDatetimeInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiDatetime("not-a-date"));
    }
}

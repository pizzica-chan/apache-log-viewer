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
     * 試験: UI 日時文字列を「壁時計」として解釈する（タイムゾーン非依存）。
     * 担保: 同じ壁時計表記のログ行なら、記録されたタイムゾーンが何であっても
     *       {@link LogEntry#wallMillis()} と一致する。画面に表示された時刻を
     *       そのまま入力して絞り込めることを保証する。
     */
    @Test
    void parseUiWallClockMillisIsTimezoneIndependent() {
        long ui = TimeUtil.parseUiWallClockMillis("2025-06-20 08:01:12");
        for (String tz : new String[] {"+0900", "+0000", "-0700", "+0530"}) {
            LogEntry e = LogParser.parseLine(
                    "127.0.0.1 - - [20/Jun/2025:08:01:12 " + tz + "] \"GET / HTTP/1.1\" 200 1");
            assertNotNull(e, tz);
            assertEquals(ui, e.wallMillis(), tz);
        }
    }

    /**
     * 試験: 壁時計 millis は UTC の瞬間ではないこと。
     * 担保: 同一瞬間でも記録タイムゾーンが違えば壁時計は異なり、
     *       逆に壁時計が同じなら瞬間は異なる（座標系の取り違えを防ぐ）。
     */
    @Test
    void wallMillisDiffersFromInstant() {
        LogEntry jst = LogParser.parseLine(
                "127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] \"GET / HTTP/1.1\" 200 1");
        LogEntry utc = LogParser.parseLine(
                "127.0.0.1 - - [19/Jun/2025:23:01:12 +0000] \"GET / HTTP/1.1\" 200 1");
        assertEquals(jst.tsMillis, utc.tsMillis, "同一瞬間である");
        assertTrue(jst.wallMillis() != utc.wallMillis(), "壁時計は異なる");
        assertEquals(9 * 3600_000L, jst.wallMillis() - utc.wallMillis());
    }

    /**
     * 試験: タイムゾーン指定付きの期間文字列。
     * 担保: 壁時計として扱う以上意味を持たないため、黙って読み飛ばさず例外にする。
     */
    @Test
    void parseUiWallClockMillisRejectsTimezoneSuffix() {
        for (String bad : new String[] {
                "2025-06-20 08:01:12+09:00", "2025-06-20T08:01:12Z", "2025-06-20 08:01:12.123"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TimeUtil.parseUiWallClockMillis(bad), bad);
        }
    }

    /**
     * 試験: UI 日時の複数入力形式（T 区切り / 日付のみ）。
     * 担保: 同等の時刻表現は同一 millis に正規化され、日付のみは 00:00:00 として解釈される。
     */
    @Test
    void parseUiDatetimeVariants() {
        long a = TimeUtil.parseUiWallClockMillis("2025-06-20 08:00:00");
        long b = TimeUtil.parseUiWallClockMillis("2025-06-20T08:00:00");
        assertEquals(a, b);
        assertTrue(TimeUtil.parseUiWallClockMillis("2025-06-20") < a);
    }

    /**
     * 試験: 解釈不能な UI 日時文字列。
     * 担保: {@link IllegalArgumentException} を投げ、API 層で 400 応答に変換できる。
     */
    @Test
    void parseUiDatetimeInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiWallClockMillis("not-a-date"));
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
        long full = TimeUtil.parseUiWallClockMillis("2025-06-20 08:30:00");
        long minute = TimeUtil.parseUiWallClockMillis("2025-06-20 08:30");
        assertEquals(full, minute);
    }

    /**
     * 試験: 実在しない日時を含む UI 日時文字列。
     * 担保: 桁数が揃っていても月・日・時分秒が範囲外なら例外になり、
     *       翌月へ繰り上がった別の日時として黙って検索されない。
     */
    @Test
    void parseUiDatetimeRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiWallClockMillis("2025-13-45"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiWallClockMillis("2025-00-10"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiWallClockMillis("2025-02-29"));
        assertThrows(IllegalArgumentException.class,
                () -> TimeUtil.parseUiWallClockMillis("2025-06-20 24:00:00"));
        assertThrows(IllegalArgumentException.class,
                () -> TimeUtil.parseUiWallClockMillis("2025-06-20 08:60:00"));
        // うるう年の 2/29 は有効。
        assertTrue(TimeUtil.parseUiWallClockMillis("2024-02-29") > 0);
    }

    /**
     * 試験: 実在しない日時を含む Apache タイムスタンプ。
     * 担保: 繰り上がった別の時刻として取り込まれず、解析失敗（null）として
     *       スキップ行の警告に集計される。
     */
    @Test
    void parseApacheTimestampRejectsOutOfRangeValues() {
        assertNull(TimeUtil.parseApacheTimestamp("32/Jan/2025:08:01:12 +0900"));
        assertNull(TimeUtil.parseApacheTimestamp("29/Feb/2025:08:01:12 +0900"));
        assertNull(TimeUtil.parseApacheTimestamp("20/Jun/2025:24:01:12 +0900"));
        assertNull(TimeUtil.parseApacheTimestamp("20/Jun/2025:08:61:12 +0900"));
        assertNotNull(TimeUtil.parseApacheTimestamp("29/Feb/2024:08:01:12 +0900"));
    }

    /**
     * 試験: うるう秒（{@code :60}）を含む Apache タイムスタンプ。
     * 担保: 実在するログ行のため解析でき、翌分の {@code 00} 秒として扱われる。
     *       妥当性検証の厳格化で調査対象の行が一覧から消えないことを保証する。
     */
    @Test
    void parseApacheTimestampAcceptsLeapSecond() {
        long[] ts = TimeUtil.parseApacheTimestamp("30/Jun/2015:23:59:60 +0000");
        assertNotNull(ts);
        assertEquals("2015-07-01T00:00:00+00:00", TimeUtil.formatIsoOffset(ts[0], (int) ts[1]));
        // 分・時の範囲外は引き続き弾く。
        assertNull(TimeUtil.parseApacheTimestamp("30/Jun/2015:23:60:59 +0000"));
    }

    /**
     * 試験: 数値化に失敗する UI 日時文字列のエラーメッセージ。
     * 担保: {@code NumberFormatException}（IllegalArgumentException のサブクラス）の
     *       内部メッセージがそのまま API 応答へ漏れず、利用者向けの文言に統一される。
     */
    @Test
    void parseUiDatetimeReportsFriendlyMessage() {
        for (String bad : new String[] {"abcd-ef-gh", "2025-06-2X", "2025-06-20 XX:00", "short"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TimeUtil.parseUiWallClockMillis(bad));
            assertEquals("日時形式を解釈できません（yyyy-MM-dd[ HH:mm[:ss]]、"
                    + "タイムゾーン指定不可）: " + bad, e.getMessage());
        }
    }

    /**
     * 試験: 日付だけを指定した終了日時（UI の時刻未入力時にフロントが送る形式）。
     * 担保: {@code 23:59:59} まで解釈でき、その日の最後の 1 分が範囲から漏れない。
     */
    @Test
    void parseUiDatetimeEndOfDay() {
        long endOfDay = TimeUtil.parseUiWallClockMillis("2025-06-20 23:59:59");
        long startOfNextDay = TimeUtil.parseUiWallClockMillis("2025-06-21");
        assertEquals(1000L, startOfNextDay - endOfDay);
    }
}

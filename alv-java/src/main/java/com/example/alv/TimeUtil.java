package com.example.alv;

/**
 * タイムスタンプの解析・整形ユーティリティ。
 *
 * <p>大容量ログでは 1 行ごとに呼ばれるため、{@code ZonedDateTime} 等の重いオブジェクトを
 * 生成せず、civil calendar アルゴリズムで epoch millis（UTC 基準の単調な比較値）へ直接変換する。
 * 整列・範囲フィルタともこの millis を用いるため内部で一貫する。表示用の文字列は、保持した
 * タイムゾーンオフセットを使って ISO 8601 表記へ戻す。
 */
public final class TimeUtil {

    private TimeUtil() {
    }

    private static final long MILLIS_PER_DAY = 86_400_000L;
    /** UI の開始/終了日時（タイムゾーン無し）のオフセット: JST (+09:00)。 */
    private static final int UI_DATETIME_OFFSET_MINUTES = 540;

    /**
     * Apache 形式のタイムスタンプ {@code 10/Oct/2000:13:55:36 -0700} を解析する。
     *
     * <p>タイムゾーンが無い場合は {@code +0000}（UTC）とみなす。
     * 実在しない日時（{@code 32/Jan}、{@code 29/Feb} の平年など）は解析失敗として扱う。
     *
     * @return {@code [utcMillis, offsetMinutes]}。解析できない場合は {@code null}
     */
    public static long[] parseApacheTimestamp(String value) {
        try {
            String v = value.trim();
            // タイムゾーン（[+-]HHMM）が無ければ UTC を補う。
            if (v.length() < 5 || (v.charAt(v.length() - 5) != '+' && v.charAt(v.length() - 5) != '-')) {
                v = v + " +0000";
            }
            // day/Mon/year:HH:MM:SS +HHMM
            int slash1 = v.indexOf('/');
            int slash2 = v.indexOf('/', slash1 + 1);
            int day = Integer.parseInt(v.substring(0, slash1));
            int month = monthNumber(v.substring(slash1 + 1, slash2));
            if (month < 0) {
                return null;
            }
            String rest = v.substring(slash2 + 1); // year:HH:MM:SS +HHMM
            int sp = rest.lastIndexOf(' ');
            String tz = rest.substring(sp + 1);
            String yearTime = rest.substring(0, sp);
            int colon1 = yearTime.indexOf(':');
            int year = Integer.parseInt(yearTime.substring(0, colon1));
            String timePart = yearTime.substring(colon1 + 1); // HH:MM:SS
            int hour = Integer.parseInt(timePart.substring(0, 2));
            int min = Integer.parseInt(timePart.substring(3, 5));
            int sec = Integer.parseInt(timePart.substring(6, 8));

            if (!isValidDateTime(year, month, day, hour, min, sec)) {
                return null;
            }

            int sign = tz.charAt(0) == '-' ? -1 : 1;
            int offHour = Integer.parseInt(tz.substring(1, 3));
            int offMin = Integer.parseInt(tz.substring(3, 5));
            if (offHour > 23 || offMin > 59) {
                return null;
            }
            int offsetMinutes = sign * (offHour * 60 + offMin);

            long localMillis = toMillis(year, month, day, hour, min, sec, 0);
            long utcMillis = localMillis - offsetMinutes * 60_000L;
            return new long[] {utcMillis, offsetMinutes};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * UI から渡される日時文字列を epoch millis（UTC）へ解析する。
     *
     * <p>対応形式: {@code yyyy-MM-ddTHH:mm:ss} / {@code yyyy-MM-dd HH:mm:ss} /
     * {@code yyyy-MM-dd HH:mm} / {@code yyyy-MM-dd}。タイムゾーン指定が無ければ
     * JST (+09:00) として解釈する。
     *
     * @throws IllegalArgumentException 解釈できない場合
     */
    public static long parseUiDatetime(String value) {
        String v = value.trim().replace('T', ' ');
        try {
            int year = Integer.parseInt(v.substring(0, 4));
            int month = Integer.parseInt(v.substring(5, 7));
            int day = Integer.parseInt(v.substring(8, 10));
            int hour = 0;
            int min = 0;
            int sec = 0;
            if (v.length() >= 16) {
                hour = Integer.parseInt(v.substring(11, 13));
                min = Integer.parseInt(v.substring(14, 16));
            }
            if (v.length() >= 19) {
                sec = Integer.parseInt(v.substring(17, 19));
            }
            if (!isValidDateTime(year, month, day, hour, min, sec)) {
                throw new IllegalArgumentException("日時形式を解釈できません: " + value);
            }
            long localMillis = toMillis(year, month, day, hour, min, sec, 0);
            return localMillis - UI_DATETIME_OFFSET_MINUTES * 60_000L;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("日時形式を解釈できません: " + value);
        }
    }

    /**
     * epoch millis（UTC）とオフセットから ISO 8601 文字列へ整形する。
     *
     * <p>例: {@code 2025-06-20T08:01:12+09:00}（小数秒は付与しない）。
     */
    public static String formatIsoOffset(long utcMillis, int offsetMinutes) {
        long localMillis = utcMillis + offsetMinutes * 60_000L;
        long days = Math.floorDiv(localMillis, MILLIS_PER_DAY);
        int msOfDay = (int) Math.floorMod(localMillis, MILLIS_PER_DAY);
        int[] ymd = civilFromDays(days);
        int hour = msOfDay / 3_600_000;
        int rem = msOfDay % 3_600_000;
        int min = rem / 60_000;
        rem %= 60_000;
        int sec = rem / 1000;

        StringBuilder sb = new StringBuilder(25);
        pad(sb, ymd[0], 4);
        sb.append('-');
        pad(sb, ymd[1], 2);
        sb.append('-');
        pad(sb, ymd[2], 2);
        sb.append('T');
        pad(sb, hour, 2);
        sb.append(':');
        pad(sb, min, 2);
        sb.append(':');
        pad(sb, sec, 2);
        appendOffset(sb, offsetMinutes);
        return sb.toString();
    }

    private static void appendOffset(StringBuilder sb, int offsetMinutes) {
        sb.append(offsetMinutes < 0 ? '-' : '+');
        int abs = Math.abs(offsetMinutes);
        pad(sb, abs / 60, 2);
        sb.append(':');
        pad(sb, abs % 60, 2);
    }

    /** 年月日・時分秒が実在する値かどうか（うるう年を考慮した月末日まで判定）。 */
    static boolean isValidDateTime(int year, int month, int day, int hour, int min, int sec) {
        if (month < 1 || month > 12) {
            return false;
        }
        if (day < 1 || day > daysInMonth(year, month)) {
            return false;
        }
        return hour >= 0 && hour <= 23
                && min >= 0 && min <= 59
                && sec >= 0 && sec <= 59;
    }

    /** 指定年月の日数。 */
    static int daysInMonth(int year, int month) {
        switch (month) {
            case 2:
                return isLeapYear(year) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    private static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    static long toMillis(int year, int month, int day, int hour, int min, int sec, int milli) {
        long days = daysFromCivil(year, month, day);
        return days * MILLIS_PER_DAY
                + (hour * 3600L + min * 60L + sec) * 1000L
                + milli;
    }

    /**
     * Howard Hinnant の civil ⇄ days アルゴリズム。
     * 1970-01-01 を 0 とする経過日数を返す。
     */
    static long daysFromCivil(int y, int m, int d) {
        int yy = m <= 2 ? y - 1 : y;
        int era = (yy >= 0 ? yy : yy - 399) / 400;
        int yoe = yy - era * 400;
        int doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146_097 + doe - 719_468;
    }

    static int[] civilFromDays(long z) {
        z += 719_468;
        long era = (z >= 0 ? z : z - 146_096) / 146_097;
        long doe = z - era * 146_097;
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        return new int[] {(int) (m <= 2 ? y + 1 : y), (int) m, (int) d};
    }

    private static int monthNumber(String name) {
        switch (name) {
            case "Jan": return 1;
            case "Feb": return 2;
            case "Mar": return 3;
            case "Apr": return 4;
            case "May": return 5;
            case "Jun": return 6;
            case "Jul": return 7;
            case "Aug": return 8;
            case "Sep": return 9;
            case "Oct": return 10;
            case "Nov": return 11;
            case "Dec": return 12;
            default: return -1;
        }
    }

    private static void pad(StringBuilder sb, int value, int width) {
        String s = Integer.toString(value);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
    }
}

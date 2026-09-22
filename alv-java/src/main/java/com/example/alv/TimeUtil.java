package com.example.alv;

/**
 * タイムスタンプの解析・整形ユーティリティ。
 *
 * <p>大容量ログでは 1 行ごとに呼ばれるため、{@code ZonedDateTime} 等の重いオブジェクトを
 * 生成せず、civil calendar アルゴリズムで epoch millis（UTC 基準の単調な比較値）へ直接変換する。
 * 整列にはこの millis を用い、表示用の文字列は保持したタイムゾーンオフセットを使って
 * ISO 8601 表記へ戻す。
 *
 * <p>一方、UI から渡される期間指定はタイムゾーンを持たない「壁時計」として扱う
 * （{@link #parseUiWallClockMillis}）。画面の時刻列がログ行ごとの現地時刻で表示される以上、
 * 同じ座標系で絞り込めないと「表示されている時刻を入力しても一致しない」ことになるため。
 */
public final class TimeUtil {

    private TimeUtil() {
    }

    private static final long MILLIS_PER_DAY = 86_400_000L;

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
            // nginx の $time_iso8601 など ISO8601 で出力する設定もあるため、そちらも受ける。
            // Apache 形式は 2 文字目までに / が来るので取り違えない。
            if (v.length() >= 19 && v.charAt(4) == '-' && v.charAt(7) == '-'
                    && (v.charAt(10) == 'T' || v.charAt(10) == ' ')) {
                return parseIso8601Timestamp(v);
            }
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
            if (offHour < 0 || offHour > 23 || offMin < 0 || offMin > 59) {
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
     * ISO8601 形式（{@code 2025-06-20T08:01:12+09:00} / {@code ...Z} / オフセットなし）を解析する。
     *
     * <p>nginx の {@code $time_iso8601} や、Apache の {@code %{%Y-%m-%dT%H:%M:%S%z}t} のような
     * 独自指定で出力されるログ向け。オフセットが無い場合は UTC とみなす（Apache 形式と同じ扱い）。
     *
     * @return {@code [utcMillis, offsetMinutes]}。解析できない場合は {@code null}
     */
    private static long[] parseIso8601Timestamp(String v) {
        int year = Integer.parseInt(v.substring(0, 4));
        int month = Integer.parseInt(v.substring(5, 7));
        int day = Integer.parseInt(v.substring(8, 10));
        int hour = Integer.parseInt(v.substring(11, 13));
        int min = Integer.parseInt(v.substring(14, 16));
        int sec = Integer.parseInt(v.substring(17, 19));
        if (!isValidDateTime(year, month, day, hour, min, sec)) {
            return null;
        }
        int offsetMinutes = 0;
        String tail = v.substring(19).trim();
        // 秒未満（.123）が付く場合は読み飛ばす。表示は秒単位のため保持しない。
        if (tail.startsWith(".")) {
            int i = 1;
            while (i < tail.length() && tail.charAt(i) >= '0' && tail.charAt(i) <= '9') {
                i++;
            }
            tail = tail.substring(i);
        }
        if (!tail.isEmpty() && tail.charAt(0) != 'Z' && tail.charAt(0) != 'z') {
            char sign = tail.charAt(0);
            if (sign != '+' && sign != '-') {
                return null;
            }
            String digits = tail.substring(1).replace(":", "");
            if (digits.length() < 4) {
                return null;
            }
            int offHour = Integer.parseInt(digits.substring(0, 2));
            int offMin = Integer.parseInt(digits.substring(2, 4));
            if (offHour < 0 || offHour > 23 || offMin < 0 || offMin > 59) {
                return null;
            }
            offsetMinutes = (sign == '-' ? -1 : 1) * (offHour * 60 + offMin);
        }
        long localMillis = toMillis(year, month, day, hour, min, sec, 0);
        return new long[] {localMillis - offsetMinutes * 60_000L, offsetMinutes};
    }

    /**
     * UI から渡される期間指定を「壁時計 millis」へ解析する。
     *
     * <p>対応形式: {@code yyyy-MM-dd} / {@code yyyy-MM-dd HH:mm} /
     * {@code yyyy-MM-dd HH:mm:ss}（{@code T} 区切りも可）。
     *
     * <p>戻り値はタイムゾーンを持たない壁時計を millis で表した<b>比較用の値</b>であり、
     * UTC の瞬間ではない。{@link LogEntry#wallMillis()} と突き合わせることで、
     * 画面に表示されている時刻をそのまま入力して絞り込める。
     *
     * <p>タイムゾーン指定（{@code +09:00} 等）は受け付けない。壁時計として扱う以上
     * 意味を持たず、黙って読み飛ばすと「指定したのに効かない」状態になるため。
     *
     * @throws IllegalArgumentException 解釈できない場合
     */
    public static long parseUiWallClockMillis(String value) {
        String v = value.trim().replace('T', ' ');
        // 末尾のタイムゾーン指定や余分な文字を読み飛ばさないよう、長さで形式を限定する。
        if (v.length() != 10 && v.length() != 16 && v.length() != 19) {
            throw invalidDatetime(value);
        }
        int year;
        int month;
        int day;
        int hour = 0;
        int min = 0;
        int sec = 0;
        // 桁位置の切り出しと数値化のみを try で囲む。妥当性判定を中に入れると
        // NumberFormatException（IllegalArgumentException のサブクラス）と
        // 区別できなくなり、内部メッセージがそのまま API 応答に出てしまう。
        try {
            year = Integer.parseInt(v.substring(0, 4));
            month = Integer.parseInt(v.substring(5, 7));
            day = Integer.parseInt(v.substring(8, 10));
            if (v.length() >= 16) {
                hour = Integer.parseInt(v.substring(11, 13));
                min = Integer.parseInt(v.substring(14, 16));
            }
            if (v.length() == 19) {
                sec = Integer.parseInt(v.substring(17, 19));
            }
        } catch (RuntimeException e) {
            throw invalidDatetime(value);
        }
        if (!isValidDateTime(year, month, day, hour, min, sec)) {
            throw invalidDatetime(value);
        }
        return toMillis(year, month, day, hour, min, sec, 0);
    }

    private static IllegalArgumentException invalidDatetime(String value) {
        return new IllegalArgumentException(
                "日時形式を解釈できません（yyyy-MM-dd[ HH:mm[:ss]]、タイムゾーン指定不可）: " + value);
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

    /**
     * 年月日・時分秒が実在する値かどうか（うるう年を考慮した月末日まで判定）。
     *
     * <p>秒だけは {@code 60}（うるう秒）も許容する。{@code right/} 系タイムゾーンの
     * システムが出力し得る実在のログ行であり、これを弾くと調査対象の行が
     * 一覧から消えてしまうため。{@code toMillis} が翌分へ繰り上げて扱う。
     */
    static boolean isValidDateTime(int year, int month, int day, int hour, int min, int sec) {
        if (month < 1 || month > 12) {
            return false;
        }
        if (day < 1 || day > daysInMonth(year, month)) {
            return false;
        }
        return hour >= 0 && hour <= 23
                && min >= 0 && min <= 59
                && sec >= 0 && sec <= 60;
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

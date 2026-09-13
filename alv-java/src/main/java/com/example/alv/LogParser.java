package com.example.alv;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apache Common / Combined ログのパーサー。
 *
 * <p>Apache Common / Combined 形式向けの正規表現で 1 行を分解する。
 *
 * <ul>
 *   <li>先頭の任意 VirtualHost（{@code example.com:80 }）を読み飛ばす</li>
 *   <li>{@code leading} は X-Forwarded-For + remote host、または remote host のみ</li>
 *   <li>Combined の referer / user-agent は解析対象外（マッチ後に無視）</li>
 *   <li>解析できない行は {@code null} を返す（エラーにしない）</li>
 * </ul>
 */
public final class LogParser {

    private LogParser() {
    }

    private static final Pattern LOG_RE = Pattern.compile(
            "^(?:\\S+:\\d+\\s+)?"
            + "(?<leading>.+?)\\s+"
            + "(?<ident>\\S+)\\s+"
            + "(?<authuser>\\S+)\\s+"
            + "\\[(?<timestamp>[^\\]]+)\\]\\s+"
            + "\"(?<request>[^\"]*)\"\\s+"
            + "(?<status>\\d+|-)\\s+"
            + "(?<bytes>\\S+)");

    /**
     * ident と authuser を出力しない構成（{@code %h %t "%r" %>s %b}）。
     * {@link #LOG_RE} はこの 2 つを必須にしているため別の式が要る。
     */
    private static final Pattern MINIMAL_RE = Pattern.compile(
            "^(?:\\S+:\\d+\\s+)?"
            + "(?<leading>.+?)\\s+"
            + "\\[(?<timestamp>[^\\]]+)\\]\\s+"
            + "\"(?<request>[^\"]*)\"\\s+"
            + "(?<status>\\d+|-)\\s+"
            + "(?<bytes>\\S+)");

    private static final Pattern REQUEST_RE = Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(\\S+)$");

    /** 末尾の引用フィールドが XFF かどうかの判定に使う。IP・カンマ・空白だけを許す。 */
    private static final Pattern XFF_LIKE = Pattern.compile("[0-9A-Fa-f.:, ]+");

    /** メソッド文字列は種類が限られるため intern してメモリを節約する。 */
    private static final ConcurrentHashMap<String, String> METHOD_POOL = new ConcurrentHashMap<>();

    /**
     * 1 行を解析する。解析できない場合は {@code null}。既定書式（{@link LogFormat#COMBINED}）。
     */
    public static LogEntry parseLine(String raw, int fileId, int lineNo, long byteOffset) {
        return parseLine(LogFormat.COMBINED, raw, fileId, lineNo, byteOffset);
    }

    /**
     * 書式を指定して 1 行を解析する。解析できない場合は {@code null}。
     *
     * <p>書式は取り込み開始時に 1 つへ確定させる前提のため、1 行あたりに試す正規表現は 1 本だけ。
     */
    public static LogEntry parseLine(LogFormat fmt, String raw, int fileId, int lineNo,
            long byteOffset) {
        String line = stripEol(raw);
        Matcher m = (fmt == LogFormat.MINIMAL ? MINIMAL_RE : LOG_RE).matcher(line);
        if (!m.lookingAt()) {
            return null;
        }
        long[] ts = TimeUtil.parseApacheTimestamp(m.group("timestamp"));
        if (ts == null) {
            return null;
        }
        String[] fh = splitLeadingHosts(m.group("leading"));
        String forwardedFor = fh[0];
        String host = fh[1];
        if (fmt == LogFormat.NGINX_MAIN) {
            // nginx の main 形式は XFF が行末の引用フィールドに来る。
            // 取れなかった行（プロキシ経由でない等）は先頭側の判定をそのまま使う。
            String trailing = trailingForwardedFor(line);
            if (trailing != null) {
                forwardedFor = trailing;
            }
        }
        String clientHost = clientHost(forwardedFor, host);
        // client と remote が同値なら参照を共有してメモリを節約する。
        if (clientHost.equals(host)) {
            clientHost = host;
        }

        String request = m.group("request");
        String method;
        String path;
        Matcher rm = REQUEST_RE.matcher(request);
        if (rm.matches()) {
            method = rm.group(1);
            path = rm.group(2);
        } else {
            method = request;
            path = "-";
        }
        method = internMethod(method);

        String statusStr = m.group("status");
        int status;
        if ("-".equals(statusStr)) {
            status = LogEntry.NO_STATUS;
        } else {
            try {
                status = Integer.parseInt(statusStr);
            } catch (NumberFormatException e) {
                status = LogEntry.NO_STATUS;
            }
        }

        return new LogEntry(fileId, lineNo, byteOffset, ts[0], (int) ts[1],
                host, clientHost, forwardedFor, method, path, status);
    }

    /** テスト用の簡易オーバーロード。 */
    public static LogEntry parseLine(String raw) {
        return parseLine(raw, 0, 0, 0);
    }

    /** テスト用の簡易オーバーロード（書式指定）。 */
    public static LogEntry parseLine(LogFormat fmt, String raw) {
        return parseLine(fmt, raw, 0, 0, 0);
    }

    /**
     * 行末の引用フィールドから X-Forwarded-For を取り出す。nginx の {@code main} 形式で
     * {@code "$http_user_agent" "$http_x_forwarded_for"} と並ぶ場合を想定する。
     *
     * <p>User-Agent を誤って拾わないよう、IP アドレスの並びに見えるものだけを返す。
     * 値が {@code -}（プロキシ経由でない）や引用が見つからない場合は {@code null}。
     */
    static String trailingForwardedFor(String raw) {
        String line = stripEol(raw);
        int end = line.length() - 1;
        while (end >= 0 && line.charAt(end) != '"') {
            end--;
        }
        if (end < 1) {
            return null;
        }
        int start = end - 1;
        while (start >= 0 && line.charAt(start) != '"') {
            start--;
        }
        if (start < 0) {
            return null;
        }
        String value = line.substring(start + 1, end).trim();
        if (value.isEmpty() || "-".equals(value)) {
            return null;
        }
        return XFF_LIKE.matcher(value).matches() ? value : null;
    }

    /**
     * leading 部分から X-Forwarded-For と remote host (%h) を分離する。
     *
     * <p>最後の空白で分割し、前半を XFF、
     * 後半を remote host とする。XFF が {@code "-"} の場合は空文字にする。
     *
     * @return {@code [forwardedFor, host]}
     */
    static String[] splitLeadingHosts(String leading) {
        String trimmed = leading.trim();
        if (trimmed.isEmpty()) {
            return new String[] {"", ""};
        }
        int j = trimmed.length() - 1;
        while (j >= 0 && !isWhitespace(trimmed.charAt(j))) {
            j--;
        }
        if (j < 0) {
            return new String[] {"", trimmed};
        }
        String host = trimmed.substring(j + 1);
        String forwardedFor = rstrip(trimmed.substring(0, j));
        if ("-".equals(forwardedFor)) {
            forwardedFor = "";
        }
        return new String[] {forwardedFor, host};
    }

    static String clientHost(String forwardedFor, String host) {
        if (!forwardedFor.isEmpty()) {
            int comma = forwardedFor.indexOf(',');
            String first = (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
            if (!first.isEmpty() && !"-".equals(first)) {
                return first;
            }
        }
        return host;
    }

    private static String internMethod(String method) {
        String existing = METHOD_POOL.putIfAbsent(method, method);
        return existing != null ? existing : method;
    }

    private static String stripEol(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }
}

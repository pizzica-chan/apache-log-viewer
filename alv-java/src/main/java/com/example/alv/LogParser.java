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

    /**
     * 引用フィールドの中身。Apache は {@code "} を {@code \"}、{@code \} を {@code \\} と
     * エスケープして書くので、{@code [^"]*} では {@code \"} で引用を閉じたと取り違え、
     * 行ごと解析できなくなる。バックスラッシュは次の 1 文字と組で読む。
     *
     * <p>{@code (?:[^"\\]|\\.)*} と書くと Java は 1 文字ごとに再帰し、数千文字の URL で
     * {@link StackOverflowError} になる。ループを展開して独占的量指定子にしたこの形なら
     * 再帰しない。
     */
    private static final String ESCAPED_BODY = "[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+";

    /**
     * エスケープを読まない引用フィールドの中身。{@link #ESCAPED_BODY} で一致しない行の
     * 読み直しに使う（Apache 以外が書いた、閉じ引用の直前に {@code \} が単独で来る行など）。
     */
    private static final String PLAIN_BODY = "[^\"]*";

    private static final Pattern LOG_RE = logPattern(ESCAPED_BODY);
    private static final Pattern LOG_RE_PLAIN = logPattern(PLAIN_BODY);
    private static final Pattern MINIMAL_RE = minimalPattern(ESCAPED_BODY);
    private static final Pattern MINIMAL_RE_PLAIN = minimalPattern(PLAIN_BODY);

    private static Pattern logPattern(String requestBody) {
        return Pattern.compile(
                "^(?:\\S+:\\d+\\s+)?"
                + "(?<leading>.+?)\\s+"
                + "(?<ident>\\S+)\\s+"
                + "(?<authuser>\\S+)\\s+"
                + "\\[(?<timestamp>[^\\]]+)\\]\\s+"
                + "\"(?<request>" + requestBody + ")\"\\s+"
                + "(?<status>\\d+|-)\\s+"
                + "(?<bytes>\\S+)");
    }

    /**
     * ident と authuser を出力しない構成（{@code %h %t "%r" %>s %b}）。
     * {@link #logPattern} はこの 2 つを必須にしているため別の式が要る。
     */
    private static Pattern minimalPattern(String requestBody) {
        return Pattern.compile(
                "^(?:\\S+:\\d+\\s+)?"
                + "(?<leading>.+?)\\s+"
                + "\\[(?<timestamp>[^\\]]+)\\]\\s+"
                + "\"(?<request>" + requestBody + ")\"\\s+"
                + "(?<status>\\d+|-)\\s+"
                + "(?<bytes>\\S+)");
    }

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
     * <p>指定された書式に対応する、エスケープを読む正規表現を試す。
     * 一致せず、行にバックスラッシュがある場合はエスケープを読まない式で読み直すため、
     * ログ行全体の解析に試す正規表現は 1 行あたり最大 2 本。
     */
    public static LogEntry parseLine(LogFormat fmt, String raw, int fileId, int lineNo,
            long byteOffset) {
        String line = stripEol(raw);
        boolean minimal = fmt == LogFormat.MINIMAL;
        Matcher m = (minimal ? MINIMAL_RE : LOG_RE).matcher(line);
        if (!m.lookingAt()) {
            if (line.indexOf('\\') < 0) {
                return null; // バックスラッシュが無ければ読み直しても結果は同じ
            }
            m = (minimal ? MINIMAL_RE_PLAIN : LOG_RE_PLAIN).matcher(line);
            if (!m.lookingAt()) {
                return null;
            }
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

    /** メソッド文字列は種類が限られるので、組み込みと利用者定義で同じプールを共有する。 */
    static String internMethod(String method) {
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

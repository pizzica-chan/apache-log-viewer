package com.example.alv;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ログ一覧 API のフィルタ条件と判定ロジック。
 *
 * <p>安価な条件（日時・ステータス・メソッド）を先に評価し、I/O を伴う grep は最後に評価する。
 */
public final class QueryFilter {

    /** 対象ステータス集合。{@code null} は全件許可。 */
    public Set<Integer> status;
    /** メソッド完全一致（大文字小文字無視）。{@code null} は任意。 */
    public String method;
    public Pattern pathRe;
    public Pattern hostRe;
    public Pattern sourceRe;
    public Pattern grepRe;
    /** 期間の下限（壁時計 millis）。{@code null} は下限なし。 */
    public Long sinceWallMillis;
    /** 期間の上限（壁時計 millis、境界を含む）。{@code null} は上限なし。 */
    public Long untilWallMillis;

    /** {@link #bindSources} でファイルごとに照合した source の結果（添字は {@link LogEntry#fileId}）。 */
    private boolean[] sourceMatchByFile;
    /** {@link #sourceMatchByFile} を作ったときの {@link #sourceRe}。差し替えられたら使わない。 */
    private Pattern boundSourceRe;

    /** grep が指定され、生ログ行の読み出しが必要かどうか。 */
    public boolean needsRaw() {
        return grepRe != null;
    }

    /** 生ログ行の遅延読み出しインタフェース（grep 用）。 */
    public interface RawLine {
        String read(LogEntry entry) throws IOException;
    }

    /**
     * source の正規表現を、ファイルごとに 1 回だけ照合しておく。
     *
     * <p>source はファイルのパスなので、同じファイルのエントリでは結果が変わらない。
     * エントリごとに照合すると、パスの長さぶんの正規表現の走査が全エントリに乗る。
     * 呼ばなくても {@link #matches} は正しく動く（その場合はエントリごとに照合する）。
     *
     * <p>実測（100 万行・148 MB のアクセスログを 30 ファイルに分けたもの、パスは約 130 文字、
     * Windows 11 / JDK 11、変更前後を交互に 5 回の中央値を 3 ラウンド取った中央値）:
     * 5 ファイルに一致 884ms → 18ms、全ファイルに一致 786ms → 27ms。
     * 省けるのはパスへの正規表現の走査なので、パスが短ければ差は小さくなると考えられる（未計測）。
     *
     * @param sourceNames {@link LogEntry#fileId} を添字とするファイル名
     *                    （{@link LogSnapshot#sourceNames()}）
     */
    public void bindSources(List<String> sourceNames) {
        if (sourceRe == null) {
            sourceMatchByFile = null;
            boundSourceRe = null;
            return;
        }
        boolean[] byFile = new boolean[sourceNames.size()];
        for (int i = 0; i < byFile.length; i++) {
            byFile[i] = sourceRe.matcher(sourceNames.get(i)).find();
        }
        sourceMatchByFile = byFile;
        boundSourceRe = sourceRe;
    }

    /**
     * 1 エントリがフィルタ条件に合致するか判定する。
     *
     * @param raw grep 用の生ログ読み出し（grep 指定がなければ {@code null} 可）
     */
    public boolean matches(LogEntry e, String sourceName, RawLine raw) throws IOException {
        if (sinceWallMillis != null || untilWallMillis != null) {
            // 画面表示と同じ「ログ行の現地時刻」で比較する。
            long wall = e.wallMillis();
            if (sinceWallMillis != null && wall < sinceWallMillis) {
                return false;
            }
            if (untilWallMillis != null && wall > untilWallMillis) {
                return false;
            }
        }
        if (status != null) {
            if (e.status == LogEntry.NO_STATUS || !status.contains(e.status)) {
                return false;
            }
        }
        if (method != null && !e.method.equalsIgnoreCase(method)) {
            return false;
        }
        if (sourceRe != null && !matchSource(e, sourceName)) {
            return false;
        }
        if (pathRe != null && !pathRe.matcher(e.path).find()) {
            return false;
        }
        if (hostRe != null && !matchHost(e)) {
            return false;
        }
        if (grepRe != null) {
            if (raw == null) {
                return false;
            }
            if (!grepRe.matcher(raw.read(e)).find()) {
                return false;
            }
        }
        return true;
    }

    private boolean matchSource(LogEntry e, String sourceName) {
        if (sourceMatchByFile != null && boundSourceRe == sourceRe) {
            return sourceMatchByFile[e.fileId];
        }
        return sourceRe.matcher(sourceName).find();
    }

    private boolean matchHost(LogEntry e) {
        return matchOne(e.clientHost) || matchOne(e.host) || matchOne(e.forwardedFor);
    }

    private boolean matchOne(String value) {
        return value != null && !value.isEmpty() && hostRe.matcher(value).find();
    }

    // ---- パラメータ解析 ---------------------------------------------------

    /**
     * {@code 500} / {@code 4xx} / {@code 500,502} 形式のステータス指定を解釈する。
     *
     * <p>{@code 4xx} の {@code xx} は大文字小文字を問わない（他のフィルタと同様）。
     */
    public static Set<Integer> parseStatusFilter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Set<Integer> result = new HashSet<>();
        for (String rawPart : value.split(",")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.length() == 3 && lower.endsWith("xx")
                    && lower.charAt(0) >= '0' && lower.charAt(0) <= '9') {
                int base = (lower.charAt(0) - '0') * 100;
                for (int i = base; i < base + 100; i++) {
                    result.add(i);
                }
            } else {
                result.add(Integer.parseInt(part));
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 正規表現を大文字小文字無視でコンパイルする（空なら {@code null}）。 */
    public static Pattern compileRegex(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}

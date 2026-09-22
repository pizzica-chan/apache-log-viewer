package com.example.alv;

import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 利用者が {@code alv-log-formats.txt} に定義したログ書式（{@link LogFormatStore}）。
 * 1 行を 1 本の正規表現で解析する。
 *
 * <p>組み込み書式（{@link LogFormat}）は Apache / nginx の並びを決め打ちで読むが、この書式は
 * 利用者の {@code LogFormat} ディレクティブに合わせて自由に組み立てられる。
 * その代わり、名前付きグループの取り出しと日時の解釈を 1 行ごとに行うぶん重い。
 * combined 100 万行の読み込みで 1,857ms → 2,725ms（+47%。Windows 11 / JDK 8 / -Xmx4g、
 * 交互に 7 回ずつの中央値。<strong>同じファイル</strong>を、組み込みの combined と、
 * combined を正規表現で書き直した利用者定義書式で読み比べた。件数は 1,000,000 件、
 * 読み飛ばし 0 件で一致）。<strong>この重さを払うのは、この書式を選んだ読み込みだけ</strong>で、
 * 組み込み書式の経路は変わらない（同じ測定で 1,854ms → 1,857ms。
 * 1,773〜2,032ms のばらつきの中なので差は無い）。
 *
 * <h2>取り出す項目</h2>
 * <p>画面の絞り込みと集計は {@link LogEntry} の項目の上に建っているので、取り出す項目も
 * そこへ揃える。<strong>必須は {@code ts} / {@code client} / {@code status} の 3 つ</strong>。
 * この 3 つが欠けると、一覧・期間・ステータスの絞り込みがどれも成り立たない。
 * {@code method} / {@code path} / {@code host} / {@code xff} は任意で、
 * 無ければ {@code method} と {@code path} は {@code -}、{@code host} は {@code client} と
 * 同じ値、{@code xff} は空になる。
 *
 * <h2>時刻とタイムゾーン</h2>
 * <p>日時書式にオフセット（{@code Z} や {@code XXX}）があればそれを使い、無ければ
 * <strong>UTC とみなす</strong>。組み込み書式がオフセットの無いログを
 * {@code +0000} として扱うのと同じで、画面にはログに書かれたままの時刻が出る。
 *
 * <h2>暴走する正規表現への備え</h2>
 * <p>利用者が書いた正規表現は、入れ子の量指定子などで後戻りが爆発しうる。Java の
 * {@link Matcher} は外から止められないため、<strong>行の長さに比例した回数だけ文字を
 * 読ませる</strong>入力を渡し、超えたら {@link BudgetExceededException} で取り込みごと
 * 止める。黙って固まるより、直すべき書式が分かる形で失敗させる。
 */
public final class CustomLogFormat {

    /** 1 行あたりに正規表現へ読ませる文字数の上限（行長に比例）。 */
    static final int BUDGET_PER_CHAR = 64;

    /** 短い行でも最低限これだけは許す。 */
    static final int BUDGET_MIN = 4096;

    /** 必須のグループ名。 */
    static final String GROUP_TS = "ts";
    static final String GROUP_CLIENT = "client";
    static final String GROUP_STATUS = "status";

    /** 値が無いことを表す印。組み込み書式がリクエスト行を割れなかったときと同じ。 */
    static final String NO_VALUE = "-";

    /** 名前付きグループの開始。名前は英字始まりの英数字（Java の正規表現の規則）。 */
    private static final String GROUP_START = "(?<";

    private final String id;
    private final String name;
    private final String patternText;
    private final String timestampPattern;
    private final Pattern pattern;
    private final DateTimeFormatter timestampFormatter;
    private final boolean hasMethod;
    private final boolean hasPath;
    private final boolean hasHost;
    private final boolean hasXff;

    /**
     * @throws IllegalArgumentException 正規表現・日時書式が壊れている、
     *                                  または必須グループが無い場合
     */
    CustomLogFormat(String id, String name, String patternText, String timestampPattern) {
        this.id = id;
        this.name = name;
        this.patternText = patternText;
        this.timestampPattern = timestampPattern;
        try {
            this.pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("pattern の正規表現が不正です: " + e.getMessage(), e);
        }
        try {
            // 月名（MMM）は英語で書かれる前提。アクセスログの月名は英語で出る。
            this.timestampFormatter =
                    DateTimeFormatter.ofPattern(timestampPattern, Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("timestamp の日時書式が不正です: " + e.getMessage(), e);
        }
        Set<String> groups = groupNames(patternText);
        requireGroup(groups, GROUP_TS, "時刻が無いと並べ替えも期間の絞り込みもできません");
        requireGroup(groups, GROUP_CLIENT, "アクセス元が無いと誰のリクエストか分かりません");
        requireGroup(groups, GROUP_STATUS, "ステータスが無いと絞り込みが常に空振りします");
        this.hasMethod = groups.contains("method");
        this.hasPath = groups.contains("path");
        this.hasHost = groups.contains("host");
        this.hasXff = groups.contains("xff");
    }

    private static void requireGroup(Set<String> groups, String name, String why) {
        if (!groups.contains(name)) {
            throw new IllegalArgumentException(
                    "pattern に名前付きグループ (?<" + name + ">…) が必要です（" + why + "）");
        }
    }

    /**
     * パターン文字列から、名前付きグループの名前を集める。
     *
     * <p>Java 8 の {@link Matcher} には名前の一覧を得る公開 API が無く、マッチしていない
     * 状態で {@code group(name)} を呼ぶと、名前の有無にかかわらず
     * {@link IllegalStateException} になる（存在確認より先に投げられる）。そのため
     * パターン文字列を自分で走査する。
     *
     * <p>数えてよいのは<strong>文字クラスの外にある、打ち消されても引用されてもいない
     * {@code (?<name>}</strong> だけ。次はいずれもグループではない。
     * <ul>
     *   <li>{@code [(?<status>0-9]} … 文字クラスの中</li>
     *   <li>{@code \(?<status>} … 括弧が打ち消されている</li>
     *   <li>{@code \Q(?<status>\E} … リテラル引用の中</li>
     * </ul>
     * 取り違えると、存在しない名前を取りにいって解析のたびに落ちる。逆に
     * {@code \Q[\E} の {@code [} を文字クラスの開始と数えてしまうと、それ以降の
     * グループを<strong>すべて見失う</strong>（行は一致するのに項目が黙って空になる）。
     * 文字クラスは {@code [a-z&&[^bc]]} のように入れ子になるので深さで数える。
     * 先読み・後読みの {@code (?<=} {@code (?<!} は、名前が英字始まりでないので外れる。
     *
     * <p><strong>取りこぼす書き方が 1 つ残っている。</strong>{@code (?x)} を付けると
     * {@code #} から行末までが正規表現のコメントになり、Java はその中の
     * {@code (?<name>} をグループとして扱わないが、ここでは数えてしまう。
     * ここまで合わせるには正規表現の構文解析をもう 1 つ持つことになるので、
     * 代わりに {@link #parse} がどの書式で失敗したかを示して投げる。
     */
    private static Set<String> groupNames(String pattern) {
        Set<String> names = new LinkedHashSet<String>();
        int classDepth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == 'Q') {
                    // \Q…\E の中はすべてただの文字。ここを読み飛ばさないと、引用した
                    // [ を文字クラスの開始と数えてしまい、以降のグループを全部見失う
                    // （行は一致するのに status や path が黙って空になる）。
                    int end = pattern.indexOf("\\E", i + 2);
                    i = end < 0 ? pattern.length() : end + 1;
                    continue;
                }
                i++; // 次の 1 文字は打ち消されている
                continue;
            }
            if (c == '[') {
                classDepth++;
                continue;
            }
            if (c == ']') {
                classDepth = Math.max(0, classDepth - 1);
                continue;
            }
            if (classDepth > 0 || !pattern.startsWith(GROUP_START, i)) {
                continue;
            }
            int end = groupNameEnd(pattern, i + GROUP_START.length());
            if (end < 0) {
                continue; // (?<= や (?<! など、名前ではない
            }
            names.add(pattern.substring(i + GROUP_START.length(), end));
            i = end;
        }
        return names;
    }

    /**
     * {@code (?<} の直後から名前の終わり（{@code >} の位置）を返す。
     * 名前として成立しなければ {@code -1}。
     */
    private static int groupNameEnd(String pattern, int start) {
        if (start >= pattern.length() || !isLetter(pattern.charAt(start))) {
            return -1;
        }
        for (int i = start + 1; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '>') {
                return i;
            }
            if (!isLetter(c) && !(c >= '0' && c <= '9')) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public String id() {
        return id;
    }

    /** 画面表示用の名前。 */
    public String displayName() {
        return name;
    }

    String patternText() {
        return patternText;
    }

    String timestampPattern() {
        return timestampPattern;
    }

    /**
     * 1 行を解析する。解析できない場合は {@code null}。
     *
     * @throws BudgetExceededException 正規表現が行長に見合わない量の後戻りをした場合
     */
    public LogEntry parse(final String raw, final int fileId, final int lineNo,
            final long byteOffset) {
        return guarded(new Supplier<LogEntry>() {
            @Override
            public LogEntry get() {
                return match(raw, fileId, lineNo, byteOffset);
            }
        });
    }

    /**
     * この書式を当てる処理を包み、定義に由来する失敗を {@link FormatFailure} に変える。
     *
     * <p><strong>照合する経路はすべてここを通すこと。</strong>取り込みだけを包んで
     * 試し打ちを素通しにすると、同じ壊れた書式が、取り込みでは「どの書式が原因か」を
     * 示す 400 になり、試し打ちでは原因の分からない 500 になる。<strong>先に触るのは
     * 試し打ちのほう</strong>なので、いちばん親切であるべき経路がいちばん不親切になる。
     */
    private <T> T guarded(Supplier<T> body) {
        try {
            return body.get();
        } catch (FormatFailure e) {
            throw e;
        } catch (RuntimeException e) {
            // グループ名の取り違えなど、この書式の定義に由来する失敗。どの書式かを
            // 添えて投げ直す。素の例外のままだと、取り込みや自動判定が
            // 「原因の分からない失敗」になり、直すべきファイルに辿り着けない。
            throw new FormatFailure("書式 " + id + " で解析に失敗しました: " + e, e);
        } catch (StackOverflowError e) {
            // 入れ子の深い正規表現は照合が再帰でスタックを食い潰す。ここで受け止めないと
            // 取り込みスレッドごと死ぬ。
            throw new FormatFailure("書式 " + id + " の正規表現が深すぎます（入れ子を浅くしてください）", e);
        }
    }

    /** 読ませる文字数に上限を掛けた照合器。 */
    private Matcher matcher(String line) {
        return pattern.matcher(new BoundedCharSequence(id, line, budgetFor(line.length())));
    }

    private LogEntry match(String raw, int fileId, int lineNo, long byteOffset) {
        String line = stripEol(raw);
        Matcher m = matcher(line);
        if (!m.matches()) {
            return null;
        }
        String ts = m.group(GROUP_TS);
        if (ts == null) {
            return null;
        }
        long[] parsed = parseTimestamp(ts);
        if (parsed == null) {
            return null;
        }
        String client = group(m, true, GROUP_CLIENT, NO_VALUE);
        String host = hasHost ? group(m, true, "host", client) : client;
        // client と host が同値なら参照を共有してメモリを節約する（組み込み書式と同じ）
        if (client.equals(host)) {
            client = host;
        }
        return new LogEntry(fileId, lineNo, byteOffset, parsed[0], (int) parsed[1],
                host, client, group(m, hasXff, "xff", ""),
                group(m, hasMethod, "method", NO_VALUE),
                group(m, hasPath, "path", NO_VALUE),
                parseStatus(m.group(GROUP_STATUS)));
    }

    private static String stripEol(String raw) {
        int end = raw.length();
        while (end > 0 && (raw.charAt(end - 1) == '\n' || raw.charAt(end - 1) == '\r')) {
            end--;
        }
        return end == raw.length() ? raw : raw.substring(0, end);
    }

    /** {@code -} や数字でない値は「記録なし」。組み込み書式と同じ扱い。 */
    static int parseStatus(String value) {
        if (value == null || value.isEmpty() || NO_VALUE.equals(value)) {
            return LogEntry.NO_STATUS;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return LogEntry.NO_STATUS;
        }
    }

    /**
     * 正規表現だけを当てて、{@code ts} に取れた文字列を返す。一致しなければ {@code null}。
     *
     * <p>試し打ちで「一致しなかった」のか「一致したが日時を読めなかった」のかを
     * 区別するために使う。直す場所（正規表現か日時書式か）が違うため。
     */
    String matchedTimestamp(final String line) {
        return guarded(new Supplier<String>() {
            @Override
            public String get() {
                Matcher m = matcher(stripEol(line));
                return m.matches() ? m.group(GROUP_TS) : null;
            }
        });
    }

    /**
     * 正規表現だけを当てて、取り出せた項目を返す。一致しなければ空。
     *
     * <p>日時書式をまだ書いていない段階の試し打ちで使う。日時は文字列のままなので、
     * ここでは解釈しない。
     */
    Map<String, String> matchedGroups(final String line) {
        return guarded(new Supplier<Map<String, String>>() {
            @Override
            public Map<String, String> get() {
                Matcher m = matcher(stripEol(line));
                if (!m.matches()) {
                    return Collections.emptyMap();
                }
                String client = group(m, true, GROUP_CLIENT, NO_VALUE);
                Map<String, String> values = new LinkedHashMap<String, String>();
                values.put(GROUP_CLIENT, client);
                values.put("method", group(m, hasMethod, "method", NO_VALUE));
                values.put("path", group(m, hasPath, "path", NO_VALUE));
                values.put(GROUP_STATUS, group(m, true, GROUP_STATUS, NO_VALUE));
                values.put("host", hasHost ? group(m, true, "host", client) : client);
                values.put("xff", group(m, hasXff, "xff", ""));
                return values;
            }
        });
    }

    static long budgetFor(int length) {
        return Math.max(BUDGET_MIN, (long) BUDGET_PER_CHAR * length);
    }

    private static String group(Matcher m, boolean present, String name, String fallback) {
        if (!present) {
            return fallback;
        }
        String v = m.group(name);
        return v != null && !v.isEmpty() ? v : fallback;
    }

    /**
     * 取り出した文字列を {@code [UTC millis, オフセット分]} に変換する。
     *
     * <p>日時書式にオフセットが無ければ UTC とみなす（組み込み書式が
     * {@code +0000} を補うのと同じ）。
     *
     * @return 解析できない場合は {@code null}
     */
    long[] parseTimestamp(String text) {
        try {
            TemporalAccessor ta = timestampFormatter.parse(text);
            int year = field(ta, ChronoField.YEAR, Integer.MIN_VALUE);
            int month = field(ta, ChronoField.MONTH_OF_YEAR, Integer.MIN_VALUE);
            int day = field(ta, ChronoField.DAY_OF_MONTH, Integer.MIN_VALUE);
            if (year == Integer.MIN_VALUE || month == Integer.MIN_VALUE
                    || day == Integer.MIN_VALUE) {
                return null;
            }
            if (wasAdjusted(ta, text)) {
                return null;
            }
            int offsetMinutes = ta.isSupported(ChronoField.OFFSET_SECONDS)
                    ? ta.get(ChronoField.OFFSET_SECONDS) / 60 : 0;
            long localMillis = TimeUtil.toMillis(year, month, day,
                    field(ta, ChronoField.HOUR_OF_DAY, 0),
                    field(ta, ChronoField.MINUTE_OF_HOUR, 0),
                    field(ta, ChronoField.SECOND_OF_MINUTE, 0),
                    field(ta, ChronoField.MILLI_OF_SECOND, 0));
            return new long[] {localMillis - offsetMinutes * 60_000L, offsetMinutes};
        } catch (DateTimeException e) {
            return null;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * 解釈の途中で値が寄せられていないか。寄せられていれば<strong>書かれていない日時</strong>に
     * なるので、その行は取り込まない。
     *
     * <p>{@link DateTimeFormatter} の既定（SMART）は、{@code 2026/02/31} を 2 月末へ、
     * {@code 24:00:00} を翌日 0 時へ黙って寄せる。取り込んでしまうと、実際には存在しない
     * 時刻で並び、期間検索の結果がずれる。
     *
     * <p>{@link java.time.format.ResolverStyle#STRICT} に切り替える手もあるが、それだと
     * {@code yyyy}（年号内の年）が使えなくなり（{@code Unsupported field: Year}）、
     * 利用者が自然に書く書式がすべて通らなくなる。
     *
     * <p>そこで<strong>解釈する前の値と、解釈した後の値を比べる</strong>。書き戻した文字列と
     * 比べる方法もあるが、それでは桁の埋め方の違いまで拾ってしまう。{@code yyyy/M/d} は
     * {@code 06} も {@code 6} も読めるのに書き戻しは {@code 6} になるため、ゼロ埋めの
     * ログが 1 行残らず捨てられる。見たいのは暦の値が動いたかどうかだけ。
     */
    private boolean wasAdjusted(TemporalAccessor resolved, String text) {
        TemporalAccessor raw;
        try {
            raw = timestampFormatter.parseUnresolved(text, new ParsePosition(0));
        } catch (DateTimeException e) {
            return false;
        }
        if (raw == null) {
            // 解釈前の値を取れないときは判断しない（解釈そのものは成功している）
            return false;
        }
        for (ChronoField f : ADJUSTABLE_FIELDS) {
            if (raw.isSupported(f) && resolved.isSupported(f)
                    && raw.getLong(f) != resolved.getLong(f)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解釈で寄せられうる項目。年は寄せられないが、{@code yyyy} は解釈前が年号内の年
     * （{@code YEAR_OF_ERA}）なので、そもそも突き合わせの対象にならない。
     *
     * <p>時・分・秒は、いまの Java だと単独では寄せられない（{@code 24:00} は日付も
     * 翌日へ動くので、日の比較で捕まる）。将来の版で挙動が変わっても取りこぼさないよう、
     * 念のため並べてある。
     */
    private static final ChronoField[] ADJUSTABLE_FIELDS = {
        ChronoField.MONTH_OF_YEAR, ChronoField.DAY_OF_MONTH, ChronoField.HOUR_OF_DAY,
        ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.MILLI_OF_SECOND,
    };

    /**
     * 日時として読めない理由を返す。読めるなら {@code null}。
     * 画面の「この行で試す」で、直す場所を示すために使う。
     */
    String timestampError(String text) {
        TemporalAccessor ta;
        try {
            ta = timestampFormatter.parse(text);
        } catch (DateTimeException e) {
            return "日時書式「" + timestampPattern + "」と形が合っていません";
        }
        if (!ta.isSupported(ChronoField.YEAR) || !ta.isSupported(ChronoField.MONTH_OF_YEAR)
                || !ta.isSupported(ChronoField.DAY_OF_MONTH)) {
            return "日時書式「" + timestampPattern + "」に年月日が揃っていません"
                    + "（日付が無いと日をまたいで並べられません）";
        }
        if (wasAdjusted(ta, text)) {
            return "実在しない日時です（" + resolvedText(ta) + "に寄せられます）";
        }
        return null;
    }

    /**
     * 寄せられた先を、その書式が持っている項目だけで言い表す。
     *
     * <p>{@code dd/MMM/yyyy} のように時刻を含まない書式では、解釈した結果も時刻を持たない。
     * 決め打ちで時まで読むと、実在しない日を試したときにその場で落ちる。
     *
     * <p>分の確認は、いまの Java では外れない（{@code yyyy/MM/dd HH} のように時だけ
     * 書いても、解釈側が時刻を組み立てるので分・秒まで付いてくる）。時と同じ壊れ方を
     * 繰り返さないよう、念のため残してある。
     */
    private static String resolvedText(TemporalAccessor ta) {
        StringBuilder sb = new StringBuilder();
        sb.append(ta.get(ChronoField.YEAR)).append(" 年 ")
                .append(ta.get(ChronoField.MONTH_OF_YEAR)).append(" 月 ")
                .append(ta.get(ChronoField.DAY_OF_MONTH)).append(" 日");
        if (ta.isSupported(ChronoField.HOUR_OF_DAY)) {
            sb.append(' ').append(ta.get(ChronoField.HOUR_OF_DAY)).append(" 時");
            if (ta.isSupported(ChronoField.MINUTE_OF_HOUR)) {
                sb.append(' ').append(ta.get(ChronoField.MINUTE_OF_HOUR)).append(" 分");
            }
        }
        return sb.toString();
    }

    private static int field(TemporalAccessor ta, ChronoField f, int fallback) {
        return ta.isSupported(f) ? ta.get(f) : fallback;
    }

    /**
     * この書式の定義に由来する失敗。ログの行が一致しないことは失敗ではない（{@code null} を返す）。
     *
     * <p>取り込みと自動判定は<strong>これを捕まえて、どの書式が原因かを示す</strong>。
     * 素の実行時例外が外へ漏れると、利用者は直すべきファイルに辿り着けない。
     */
    public static class FormatFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FormatFailure(String message, Throwable cause) {
            // 発生箇所より「どの書式か」が重要なので、スタックトレースは採らない
            super(message, cause, false, false);
        }
    }

    /** 正規表現に読ませる文字数が上限を超えた。 */
    public static final class BudgetExceededException extends FormatFailure {
        private static final long serialVersionUID = 1L;

        BudgetExceededException(String message) {
            super(message, null);
        }
    }

    /**
     * 正規表現に読ませる文字数を数える入力。上限を超えたら投げる。
     *
     * <p>{@link Matcher} は {@link #charAt} で 1 文字ずつ読むため、後戻りが爆発すると
     * 呼び出し回数が行長に対して不釣り合いに増える。ここで打ち切れば、取り込みが
     * 止まったまま返らない事態を避けられる。{@link #subSequence} は結果の取り出しに
     * 使われるだけなので数えない。
     */
    static final class BoundedCharSequence implements CharSequence {
        private final String formatId;
        private final CharSequence delegate;
        private long remaining;

        BoundedCharSequence(String formatId, CharSequence delegate, long budget) {
            this.formatId = formatId;
            this.delegate = delegate;
            this.remaining = budget;
        }

        @Override
        public char charAt(int index) {
            if (--remaining < 0) {
                // どの書式が原因かを必ず入れる。利用者はこれを手がかりに定義を直す。
                throw new BudgetExceededException("書式 " + formatId + " の正規表現が、長さ "
                        + delegate.length() + " の行に対して打ち切り基準を超えました。"
                        + "後戻りが爆発する書き方になっていないか見直してください");
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}

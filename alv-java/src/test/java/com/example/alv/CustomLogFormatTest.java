package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 利用者定義書式（正規表現）の解析。
 *
 * <ul>
 *   <li>名前付きグループの取り出しと、任意グループがないときの既定値</li>
 *   <li>時刻とタイムゾーンの解釈が組み込み書式と一致すること</li>
 *   <li>暴走する正規表現を打ち切ること（読み込みが返らなくなるのを防ぐ）</li>
 *   <li>定義そのものが壊れているときに、作る時点で弾くこと</li>
 * </ul>
 */
class CustomLogFormatTest {

    /** combined を利用者定義で書き直したもの。末尾の referer / UA はまとめて受ける。 */
    private static final String PATTERN =
            "^(?<client>\\S+) \\S+ \\S+ \\[(?<ts>[^\\]]+)\\] "
                    + "\"(?<method>\\S+) (?<path>\\S+)[^\"]*\" (?<status>\\d+|-) \\S+.*$";
    private static final String TS = "dd/MMM/yyyy:HH:mm:ss Z";

    private static final String LINE =
            "203.0.113.5 - - [15/Jun/2026:08:01:12 +0900] \"GET /a/b?x=1 HTTP/1.1\" 200 123 "
                    + "\"-\" \"curl/8.0\"";

    private static CustomLogFormat format() {
        return new CustomLogFormat("my-app", "自社の書式", PATTERN, TS);
    }

    private static LogEntry parse(CustomLogFormat f, String line) {
        return f.parse(line, 0, 0, 0L);
    }

    /** 名前付きグループをそれぞれ取り出せること。 */
    @Test
    void extractsNamedGroups() {
        LogEntry e = parse(format(), LINE + "\n");
        assertNotNull(e);
        assertEquals("203.0.113.5", e.clientHost);
        assertEquals("GET", e.method);
        assertEquals("/a/b?x=1", e.path);
        assertEquals(200, e.status);
        LogEntry again = parse(format(), LINE);
        assertSame(e.method, again.method, "メソッド文字列は行ごとに複製しない");
    }

    /**
     * 時刻とタイムゾーンの解釈が組み込み書式と一致すること。
     *
     * <p>ずれると、同じログを書式違いで読んだときに期間検索の結果も表示も変わる。
     * alv は真の瞬間（{@code tsMillis}）と表示用のオフセットを別に持つので、両方見る。
     */
    @Test
    void timestampMatchesBuiltinFormat() {
        LogEntry custom = parse(format(), LINE);
        LogEntry builtin = LogParser.parseLine(LogFormat.COMBINED, LINE);
        assertNotNull(custom);
        assertNotNull(builtin);
        assertEquals(builtin.tsMillis, custom.tsMillis);
        assertEquals(builtin.tzOffsetMin, custom.tzOffsetMin);
        assertEquals(builtin.timestampIso(), custom.timestampIso());
        assertEquals(540, custom.tzOffsetMin, "+0900 は 540 分");
    }

    /**
     * 日時書式にオフセットがなければ UTC とみなすこと。
     *
     * <p>組み込み書式がオフセットのないログへ {@code +0000} を補うのと同じ。
     * そろえないと、同じログを書式違いで読んだときに表示時刻が動く。
     */
    @Test
    void missingOffsetIsTreatedAsUtc() {
        CustomLogFormat f = new CustomLogFormat("no-tz", "オフセットなし",
                "^(?<client>\\S+) \\[(?<ts>[^\\]]+)\\] (?<status>\\d+)$",
                "dd/MMM/yyyy:HH:mm:ss");
        LogEntry e = parse(f, "203.0.113.5 [15/Jun/2026:08:01:12] 200");
        assertNotNull(e);
        assertEquals(0, e.tzOffsetMin);
        assertEquals("2026-06-15T08:01:12+00:00", e.timestampIso(),
                "ログに書かれた時刻がそのまま出る");
    }

    /**
     * 秒未満は読めても保持しないこと（組み込み書式と同じ）。
     *
     * <p>画面の時刻は秒で切って出る。ミリ秒を残すと、<strong>画面に出ている最後の時刻を
     * そのまま期間の上限にしたとき、その行自身が結果から消える</strong>
     * （上限は秒の {@code .000}、行は {@code .345} で、比較は {@code wall > until}）。
     * 開始と終了を同じ秒にすると、その秒の行が 0 件になる。
     */
    @Test
    void dropsSubSecondLikeBuiltinFormats() {
        CustomLogFormat f = new CustomLogFormat("ms", "ミリ秒あり",
                "^(?<client>\\S+) \\[(?<ts>[^\\]]+)\\] (?<status>\\d+)$",
                "dd/MMM/yyyy:HH:mm:ss.SSS Z");
        LogEntry e = parse(f, "203.0.113.5 [15/Jun/2026:08:01:12.345 +0900] 200");
        assertNotNull(e, ".SSS の桁がある行は読める");
        assertEquals(0, e.tsMillis % 1000, "秒未満は残さない");
        assertEquals("2026-06-15T08:01:12+09:00", e.timestampIso());

        // 画面に出ている秒をそのまま期間の上限にしても、その行は残る
        long until = TimeUtil.parseUiWallClockMillis("2026-06-15 08:01:12");
        assertTrue(e.wallMillis() <= until,
                "表示と同じ秒を上限にしたとき、その行が範囲から外れない");

        // 桁が書かれていない行は、この書式では読めない（.SSS は形の検査として効く）
        assertNull(parse(f, "203.0.113.5 [15/Jun/2026:08:01:12 +0900] 200"));
    }

    /** 任意グループがない書式でも使えて、欠けた項目は既定値になること。 */
    @Test
    void optionalGroupsFallBack() {
        CustomLogFormat f = new CustomLogFormat("minimal", "必須だけ",
                "^(?<client>\\S+) \\[(?<ts>[^\\]]+)\\] (?<status>\\d+)$", TS);
        LogEntry e = parse(f, "203.0.113.5 [15/Jun/2026:08:01:12 +0900] 404");
        assertNotNull(e);
        assertEquals("203.0.113.5", e.clientHost);
        assertEquals("203.0.113.5", e.host, "host を取らなければ client と同じ値にする");
        assertEquals("", e.forwardedFor);
        assertEquals("-", e.method);
        assertEquals("-", e.path);
        assertEquals(404, e.status);
    }

    /**
     * ガイドの日時の部品が、正規表現と日時書式の組で成り立っていること。
     *
     * <p>部品は正規表現と日時書式を同時に入れる。片方だけ直すと、押しただけでは
     * 動かないボタンになる。とくに ISO のオフセットは、コロンの有無で日時書式が
     * {@code XXX} と {@code XX} に分かれる。
     */
    @Test
    void timestampPartsMatchTheirRegex() {
        String[][] cases = {
            {"\\[(?<ts>[^\\]]+)\\]", "dd/MMM/yyyy:HH:mm:ss Z",
                "[15/Jun/2026:08:01:12 +0900]"},
            {"\\[(?<ts>[^\\]]+)\\]", "dd/MMM/yyyy:HH:mm:ss.SSS Z",
                "[15/Jun/2026:08:01:12.345 +0900]"},
            {"(?<ts>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2})",
                "yyyy-MM-dd'T'HH:mm:ssXXX", "2026-06-15T08:01:12+09:00"},
            {"(?<ts>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{4})",
                "yyyy-MM-dd'T'HH:mm:ssXX", "2026-06-15T08:01:12+0900"},
            {"(?<ts>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})",
                "yyyy-MM-dd HH:mm:ss", "2026-06-15 08:01:12"},
        };
        for (String[] c : cases) {
            CustomLogFormat f = new CustomLogFormat("part", "part",
                    "^(?<client>\\S+) " + c[0] + " (?<status>\\d+)$", c[1]);
            assertNotNull(parse(f, "203.0.113.5 " + c[2] + " 200"),
                    c[0] + " と " + c[1] + " の組で「" + c[2] + "」を読めること");
        }
    }

    /**
     * ガイドの「IP 例」表が言うとおりに Client 列へ入ること。
     *
     * <p>カンマで連なる X-Forwarded-For の取り方は、ここを間違えると
     * <strong>Client 列にプロキシの IP が出続ける</strong>という、気づきにくい外し方になる。
     * とくに {@code \S+} は空白で止まるので、{@code 203.0.113.5, 10.0.0.1} のように
     * カンマの後ろに空白がある値は最後まで取れない（表にもそう書いてある）。
     */
    @Test
    void commaSeparatedForwardedForBehavesAsTheGuideSays() {
        String line = "10.0.0.5 - - [15/Jun/2026:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 1 "
                + "\"-\" \"curl/8.0\" \"203.0.113.5, 10.0.0.1\"";
        String head = "^\\S+ \\S+ \\S+ \\[(?<ts>[^\\]]+)\\] \"[^\"]*\" (?<status>\\d+) \\S+ "
                + "\"[^\"]*\" \"[^\"]*\" \"";

        // 丸ごと取ると、カンマごとそのまま Client 列に出る（切り捨てはしない）
        LogEntry whole = parse(new CustomLogFormat("w", "w",
                head + "(?<client>[^\"]*)\"$", TS), line);
        assertNotNull(whole);
        assertEquals("203.0.113.5, 10.0.0.1", whole.clientHost);

        // 先頭の IP だけ取るなら、残りは受け流す
        LogEntry first = parse(new CustomLogFormat("f", "f",
                head + "(?<client>[^,\\s]+).*\"$", TS), line);
        assertNotNull(first);
        assertEquals("203.0.113.5", first.clientHost);

        // \S+ は空白で止まるので、この値では行ごと一致しない
        assertNull(parse(new CustomLogFormat("s", "s",
                head + "(?<client>\\S+)\"$", TS), line),
                "\\S+ では引用の終わりまで届かない");
    }

    /** host と xff を取る書式では、それぞれ別に入ること。 */
    @Test
    void takesHostAndForwardedFor() {
        CustomLogFormat f = new CustomLogFormat("xff", "XFF 付き",
                "^(?<xff>\\S+) (?<host>\\S+) \\[(?<ts>[^\\]]+)\\] (?<client>\\S+) (?<status>\\d+)$",
                TS);
        LogEntry e = parse(f,
                "198.51.100.7 10.0.0.1 [15/Jun/2026:08:01:12 +0900] 198.51.100.7 200");
        assertNotNull(e);
        assertEquals("198.51.100.7", e.forwardedFor);
        assertEquals("10.0.0.1", e.host);
        assertEquals("198.51.100.7", e.clientHost);
    }

    /** ステータスが {@code -}（記録なし）の行も読めること。 */
    @Test
    void acceptsDashStatus() {
        LogEntry e = parse(format(),
                "203.0.113.5 - - [15/Jun/2026:08:01:12 +0900] \"GET /a HTTP/1.1\" - 0 \"-\" \"-\"");
        assertNotNull(e);
        assertEquals(LogEntry.NO_STATUS, e.status);
    }

    /** 一致しない行は読み飛ばせるよう null を返すこと。 */
    @Test
    void returnsNullForNonMatchingLine() {
        assertNull(parse(format(), "これはアクセスログではない"));
        assertNull(parse(format(), ""));
    }

    /** 形は合っていても日時として成立しない行は取り込まないこと。 */
    @Test
    void rejectsImpossibleTimestamp() {
        assertNull(parse(format(),
                "203.0.113.5 - - [45/Zzz/2026:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 1"));
    }

    /**
     * 実在しない日を<strong>月末に寄せて取り込まない</strong>こと。
     *
     * <p>{@code DateTimeFormatter} の既定（SMART）は {@code 31/Feb} を 2 月末へ、
     * {@code 24:00:00} を翌日 0 時へ黙って寄せる。取り込むと、実際には存在しない時刻で
     * 並んで期間検索の結果がずれる。
     */
    @Test
    void rejectsDatesThatWereRoundedToTheEndOfMonth() {
        assertNull(parse(format(),
                "203.0.113.5 - - [31/Feb/2026:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 1"),
                "2 月 31 日は 2 月末に寄せずに捨てる");
        assertNotNull(parse(format(),
                "203.0.113.5 - - [29/Feb/2024:08:01:12 +0900] \"GET /a HTTP/1.1\" 200 1"),
                "閏年の 2 月 29 日は実在するので取り込む");
        assertNull(parse(format(),
                "203.0.113.5 - - [15/Jun/2026:24:00:00 +0900] \"GET /a HTTP/1.1\" 200 1"),
                "24 時は翌日へ寄せずに捨てる");
    }

    /**
     * ガイドが言う「空白 1 個以上」で、桁を揃えたログを読めること。
     * 空白の数の食い違いは、いちばん多いつまずき方。
     */
    @Test
    void paddedFieldsNeedOneOrMoreSpaces() {
        String padded = "203.0.113.5   [15/Jun/2026:08:01:12 +0900] 200";
        String one = "^(?<client>\\S+) \\[(?<ts>[^\\]]+)\\] (?<status>\\d+)$";
        String many = "^(?<client>\\S+) +\\[(?<ts>[^\\]]+)\\] (?<status>\\d+)$";
        assertNull(parse(new CustomLogFormat("p1", "p1", one, TS), padded),
                "空白 1 個では一致しない");
        assertNotNull(parse(new CustomLogFormat("p2", "p2", many, TS), padded),
                "空白 1 個以上なら読める");
    }

    /**
     * 日時書式を書く前でも、正規表現だけで取り出せた項目を見られること。
     *
     * <p>利用者はふつう、ログの行を貼って正規表現を組み立て、一致することを確かめてから
     * 日時書式を書く。試し打ちで日時書式を必須にすると、その最初の一歩が止まる。
     */
    @Test
    void matchedGroupsWorkBeforeTimestampIsWritten() {
        CustomLogFormat f = format();
        assertEquals("15/Jun/2026:08:01:12 +0900", f.matchedTimestamp(LINE));
        Map<String, String> groups = f.matchedGroups(LINE);
        assertEquals("203.0.113.5", groups.get("client"));
        assertEquals("GET", groups.get("method"));
        assertEquals("/a/b?x=1", groups.get("path"));
        assertEquals("200", groups.get("status"));
        assertTrue(f.matchedGroups("これは一致しない").isEmpty());
    }

    /**
     * 画面のガイドがボタンで配る日時書式が、そこに添えてある例をそのまま読めること。
     *
     * <p>ボタンは「押せば動く」前提で置いてある。書式と例がずれていると、利用者は
     * 自分の書き方を疑って延々と直すことになる。
     */
    @Test
    void timestampPartsFromGuideParseTheirSamples() {
        String[][] cases = {
            {"dd/MMM/yyyy:HH:mm:ss Z", "15/Jun/2026:08:01:12 +0900"},
            {"dd/MMM/yyyy:HH:mm:ss", "15/Jun/2026:08:01:12"},
            {"yyyy-MM-dd'T'HH:mm:ssXXX", "2026-06-15T08:01:12+09:00"},
            // Apache の %z はコロンが入らない。XXX では読めないので別のボタンにしている
            {"yyyy-MM-dd'T'HH:mm:ssXX", "2026-06-15T08:01:12+0900"},
            {"yyyy-MM-dd'T'HH:mm:ss", "2026-06-15T08:01:12"},
            {"yyyy-MM-dd HH:mm:ss", "2026-06-15 08:01:12"},
            {"dd/MMM/yyyy:HH:mm:ss.SSS Z", "15/Jun/2026:08:01:12.345 +0900"},
        };
        for (String[] c : cases) {
            CustomLogFormat f = new CustomLogFormat("btn", "btn",
                    "^(?<client>\\S+)\\|(?<ts>[^|]+)\\|(?<status>\\d+)$", c[0]);
            assertNotNull(parse(f, "203.0.113.5|" + c[1] + "|200"),
                    c[0] + " で「" + c[1] + "」を読めること");
        }
    }

    /** 試し打ちが、読めない理由を直す場所ごとに言い分けること。 */
    @Test
    void timestampErrorTellsWhatToFix() {
        CustomLogFormat f = format();
        assertNull(f.timestampError("15/Jun/2026:08:01:12 +0900"), "読めるときは理由なし");
        assertTrue(f.timestampError("2026-06-15T08:01:12+09:00").contains("形が合っていません"));
        assertTrue(f.timestampError("31/Feb/2026:08:01:12 +0900").contains("実在しない"));

        CustomLogFormat noDate = new CustomLogFormat("time-only", "時刻だけ",
                "^(?<client>\\S+) (?<ts>\\d{2}:\\d{2}:\\d{2}) (?<status>\\d+)$", "HH:mm:ss");
        assertTrue(noDate.timestampError("08:01:12").contains("年月日"));
    }

    /** 時刻を含まない書式でも、寄せ先の説明を出せること（決め打ちで時まで読まない）。 */
    @Test
    void timestampErrorWorksForDateOnlyPattern() {
        CustomLogFormat dateOnly = new CustomLogFormat("date-only", "日付だけ",
                "^(?<client>\\S+) (?<ts>\\S+) (?<status>\\d+)$", "dd/MMM/yyyy");
        String why = dateOnly.timestampError("31/Feb/2026");
        assertNotNull(why);
        assertTrue(why.contains("実在しない"), why);
        assertTrue(why.contains("2 月 28 日"), why);
        assertNull(dateOnly.timestampError("28/Feb/2026"));
        assertNull(parse(dateOnly, "203.0.113.5 31/Feb/2026 200"));
        assertNotNull(parse(dateOnly, "203.0.113.5 28/Feb/2026 200"));
    }

    /**
     * 後戻りが爆発する正規表現を打ち切ること。
     * 打ち切らないと、読み込みが返らないままアプリが無反応になる。
     */
    @Test
    void abortsCatastrophicBacktracking() {
        CustomLogFormat f = new CustomLogFormat("bad", "暴走する書式",
                "^(?<client>(a+)+b)(?<ts>)(?<status>)$", TS);
        // 20 文字でも後戻りは 2^20 通りに広がり、打ち切り基準を十分に超える。
        // これ以上長くすると、打ち切りを外す変異を入れたときに試験が
        // 「赤くなる」ではなく「返ってこない」になり、壊れたことに気づけない。
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            line.append('a');
        }
        line.append('!');
        assertThrows(CustomLogFormat.BudgetExceededException.class,
                () -> parse(f, line.toString()));
    }

    /** 後戻りの打ち切りは、日時書式が空の試し打ちでも効くこと。 */
    @Test
    void matchOnlyPathsAreAlsoBudgeted() {
        CustomLogFormat f = new CustomLogFormat("bad2", "暴走する書式",
                "^(?<client>(a+)+b)(?<ts>)(?<status>)$", TS);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            line.append('a');
        }
        line.append('!');
        assertThrows(CustomLogFormat.BudgetExceededException.class,
                () -> f.matchedTimestamp(line.toString()));
        assertThrows(CustomLogFormat.BudgetExceededException.class,
                () -> f.matchedGroups(line.toString()));
    }

    /** ふつうの書式は打ち切り基準に引っかからないこと。 */
    @Test
    void doesNotAbortNormalPattern() {
        StringBuilder path = new StringBuilder("/a?");
        for (int i = 0; i < 2000; i++) {
            path.append('x');
        }
        assertNotNull(parse(format(),
                "203.0.113.5 - - [15/Jun/2026:08:01:12 +0900] \"GET " + path
                        + " HTTP/1.1\" 200 1 \"-\" \"-\""));
    }

    /**
     * 書き方ガイドのボタンが配る中身が、そのままコンパイルできること。
     *
     * <p>ボタンは「押せば動く」前提で置いてある。{@code data-insert} は正規表現として、
     * {@code data-timestamp} は日時書式として、それぞれそのまま使われる。
     * <strong>ガイドの文章は試験で守られていたのに、ボタンやガイドの表に書いた
     * 正規表現は誰も実行していなかった</strong>ため、実際には一致しない例が載ったまま
     * になっていたことがある。書き換えるたびに機械で確かめる。
     */
    @Test
    void guidePartsCompile() throws Exception {
        java.nio.file.Path html = java.nio.file.Paths.get(
                "src", "main", "resources", "static", "index.html");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(html), "リポジトリ内で実行したときだけ確かめる");
        String page = new String(
                java.nio.file.Files.readAllBytes(html), StandardCharsets.UTF_8);

        int inserts = 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("data-insert=\"([^\"]*)\"").matcher(page);
        while (m.find()) {
            inserts++;
            String re = unescapeHtml(m.group(1));
            try {
                java.util.regex.Pattern.compile(re);
            } catch (RuntimeException e) {
                throw new AssertionError("部品の正規表現が壊れています: " + re, e);
            }
        }

        int stamps = 0;
        m = java.util.regex.Pattern.compile("data-timestamp=\"([^\"]*)\"").matcher(page);
        while (m.find()) {
            stamps++;
            String ts = unescapeHtml(m.group(1));
            try {
                java.time.format.DateTimeFormatter.ofPattern(ts, java.util.Locale.ENGLISH);
            } catch (RuntimeException e) {
                throw new AssertionError("部品の日時書式が壊れています: " + ts, e);
            }
        }

        // 抽出そのものが壊れて 0 件になっても気づけるようにする
        assertTrue(inserts >= 10, "正規表現の部品が見つからない: " + inserts);
        assertTrue(stamps >= 5, "日時書式の部品が見つからない: " + stamps);
    }

    /** ガイドは HTML なので、属性値は実体参照のまま入っている。 */
    private static String unescapeHtml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    /** 打ち切り基準は行の長さに比例すること（長い行を短い行と同じ上限で切らない）。 */
    @Test
    void budgetGrowsWithLineLength() {
        assertEquals(CustomLogFormat.BUDGET_MIN, CustomLogFormat.budgetFor(1));
        assertTrue(CustomLogFormat.budgetFor(10_000) > CustomLogFormat.budgetFor(1_000));
    }

    /**
     * 文字クラスの中にある {@code (?<name>} をグループと取り違えないこと。
     *
     * <p>取り違えると、存在しない名前を取りにいって解析のたびに落ちる。読み込みが
     * 全滅するうえ、自動判定でも落ちるので画面すら開けなくなる。
     */
    @Test
    void ignoresGroupLikeTextInsideCharacterClass() {
        CustomLogFormat f = new CustomLogFormat("cls", "文字クラス入り",
                "^(?<client>\\S+) [(?<method>a-z]+ \\[(?<ts>[^\\]]+)\\] (?<status>\\d+)$", TS);
        LogEntry e = parse(f, "203.0.113.5 get [15/Jun/2026:08:01:12 +0900] 200");
        assertNotNull(e);
        assertEquals("-", e.method, "文字クラスの中の (?<method> はグループではない");
    }

    /**
     * {@code \Q…\E}（リテラル引用）の中を、正規表現の記号として数えないこと。
     *
     * <p>引用した {@code [} を文字クラスの開始と数えると、それ以降のグループを
     * <strong>すべて見失う</strong>。行は一致して取り込まれるのに、ステータスも
     * パスも黙って既定値になるため、例外も出ず気づけない。
     */
    @Test
    void ignoresLiteralQuotedText() {
        CustomLogFormat f = new CustomLogFormat("quoted", "引用入り",
                "^(?<client>\\S+) \\Q[\\E(?<ts>[^\\]]+)\\] (?<status>\\d+)$", TS);
        LogEntry e = parse(f, "203.0.113.5 [15/Jun/2026:08:01:12 +0900] 200");
        assertNotNull(e);
        assertEquals(200, e.status, "引用の後ろのグループを見失わない");
    }

    /** 打ち消された括弧はグループの開始ではないこと。 */
    @Test
    void ignoresEscapedGroupStart() {
        CustomLogFormat f = new CustomLogFormat("escaped", "打ち消し",
                "^(?<client>\\S+) \\(?<method>x \\[(?<ts>[^\\]]+)\\] (?<status>\\d+)$", TS);
        LogEntry e = parse(f, "203.0.113.5 (<method>x [15/Jun/2026:08:01:12 +0900] 200");
        assertNotNull(e);
        assertEquals("-", e.method);
    }

    /** 後読みを名前付きグループと取り違えないこと（{@code (?<=} は名前ではない）。 */
    @Test
    void ignoresLookbehind() {
        CustomLogFormat f = new CustomLogFormat("lookbehind", "後読み",
                "^(?<client>\\S+) \\[(?<ts>[^\\]]+)\\] (?<=\\] )(?<status>\\d+)$", TS);
        assertNotNull(parse(f, "203.0.113.5 [15/Jun/2026:08:01:12 +0900] 200"));
    }

    /**
     * 走査で取りこぼす書き方でも、原因の書式が分かる形で失敗すること。
     *
     * <p>{@code (?x)} を付けると {@code #} から行末までが正規表現のコメントになり、
     * Java はその中の {@code (?<status>} をグループとして扱わない。こちらの走査は
     * コメントを知らないので「ある」と数えてしまう。<strong>取りこぼしても
     * どの書式が原因か分かる形で失敗させる</strong>ほうを選んでいる。
     */
    @Test
    void reportsFormatIdWhenGroupLookupFails() {
        CustomLogFormat f = new CustomLogFormat("cmt", "コメント入り",
                "(?x) ^(?<client>\\S+) \\  \\[(?<ts>[^\\]]+)\\] # (?<status>zzz)\n", TS);
        CustomLogFormat.FormatFailure e = assertThrows(CustomLogFormat.FormatFailure.class,
                () -> parse(f, "203.0.113.5 [15/Jun/2026:08:01:12 +0900]"));
        assertTrue(e.getMessage().contains("cmt"), e.getMessage());
    }

    /** 日時書式が空のままの試し打ちでも、書式 id つきで失敗すること。 */
    @Test
    void matchOnlyPathsReportFormatIdToo() {
        CustomLogFormat f = new CustomLogFormat("cmt2", "コメント入り",
                "(?x) ^(?<client>\\S+) \\  \\[(?<ts>[^\\]]+)\\] # (?<status>zzz)\n", TS);
        CustomLogFormat.FormatFailure e = assertThrows(CustomLogFormat.FormatFailure.class,
                () -> f.matchedGroups("203.0.113.5 [15/Jun/2026:08:01:12 +0900]"));
        assertTrue(e.getMessage().contains("cmt2"), e.getMessage());
    }

    /** 必須グループがない書式は作れないこと。 */
    @Test
    void requiresMandatoryGroups() {
        IllegalArgumentException noTs = assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("no-ts", "時刻なし",
                        "^(?<client>\\S+) (?<status>\\d+)$", TS));
        assertTrue(noTs.getMessage().contains("ts"), noTs.getMessage());

        IllegalArgumentException noClient = assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("no-client", "アクセス元なし",
                        "^(?<ts>\\S+) (?<status>\\d+)$", TS));
        assertTrue(noClient.getMessage().contains("client"), noClient.getMessage());

        IllegalArgumentException noStatus = assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("no-status", "ステータスなし",
                        "^(?<client>\\S+) (?<ts>\\S+)$", TS));
        assertTrue(noStatus.getMessage().contains("status"), noStatus.getMessage());
    }

    /** 壊れた正規表現・日時書式は作る時点で弾くこと。 */
    @Test
    void rejectsBrokenDefinition() {
        assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("bad-re", "壊れた正規表現", "^(?<ts>\\S+", TS));
        assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("bad-ts", "壊れた日時書式",
                        "^(?<client>\\S+) (?<ts>\\S+) (?<status>\\d+)$", "dd/QQQQQQQ"));
    }
}

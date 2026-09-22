package com.example.alv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LogServer} の HTTP 結合テスト。
 *
 * <p>実際に空きポートで待ち受けさせ、HTTP 越しに各エンドポイントを検証する。
 * ルーティング・パラメータ検証・ページング・エラー応答は単体テストでは通らない
 * 経路であり、ここが唯一の担保になる。
 */
class LogServerTest {

    private LogServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        Path samples = TestPaths.samplesDir();
        assumeTrue(Files.isDirectory(samples), "samples ディレクトリが見つかりません");
        start(PathUtil.resolve(samples), Discovery.findLogFiles(samples));
        waitUntilReady();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void start(Path root, List<Path> paths) throws IOException {
        server = new LogServer(root, paths);
        server.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.getPort();
    }

    // ---- ルーティング -----------------------------------------------------

    /**
     * 試験: 静的ファイルの配信とルーティング。
     * 担保: {@code /} は HTML、{@code /static/*} は種別ごとの Content-Type を返し、
     *       未知のパスと {@code ..} を含むパスは 404 になる（ディレクトリ横断の防止）。
     */
    @Test
    void servesStaticFilesAndRejectsUnknownPaths() throws Exception {
        Response index = get("/");
        assertEquals(200, index.status);
        assertEquals("text/html; charset=utf-8", index.contentType);
        assertTrue(index.body.contains("<table"), "index.html が返っている");

        assertEquals("text/javascript; charset=utf-8", get("/static/app.js").contentType);
        assertEquals("text/css; charset=utf-8", get("/static/style.css").contentType);

        assertEquals(404, get("/static/nope.js").status);
        assertEquals(404, get("/static/../pom.xml").status);
        assertEquals(404, get("/no-such-endpoint").status);
    }

    /**
     * 試験: POST 専用エンドポイントへの GET。
     * 担保: メソッド違いはルーティングに一致せず 404 になる。
     */
    @Test
    void loadEndpointRequiresPost() throws Exception {
        assertEquals(404, get("/api/load").status);
    }

    // ---- /api/meta --------------------------------------------------------

    /**
     * 試験: 読み込み完了後の meta。
     * 担保: 件数・ファイル一覧・期間・スキップ行数が実際の読み込み結果と一致する。
     */
    @Test
    void metaReportsLoadedState() throws Exception {
        JsonObject meta = getJson("/api/meta");
        assertFalse(meta.get("loading").getAsBoolean());
        assertEquals("ready", meta.get("load_status").getAsString());
        assertEquals(15, meta.get("total").getAsInt());
        assertEquals(4, meta.getAsJsonArray("files").size());
        assertEquals("2025-06-20T07:54:00+09:00", meta.get("first").getAsString());
        assertEquals("2025-06-20T08:11:05+09:00", meta.get("last").getAsString());
        assertEquals(5, meta.get("skipped_lines").getAsInt());
        assertEquals(5, meta.getAsJsonArray("skipped_samples").size());
        assertFalse(meta.has("load_error"));
    }

    // ---- /api/logs --------------------------------------------------------

    /**
     * 試験: フィルタなしの一覧と行の内容。
     * 担保: 全件が返り、各行に生ログ・ファイル名・行番号が揃っている。
     */
    @Test
    void logsReturnsAllEntries() throws Exception {
        JsonObject res = getJson("/api/logs");
        assertEquals(15, res.get("total").getAsInt());
        JsonArray items = res.getAsJsonArray("items");
        assertEquals(15, items.size());
        for (JsonElement el : items) {
            JsonObject row = el.getAsJsonObject();
            assertFalse(row.get("raw").getAsString().isEmpty(), "生ログが空でない");
            assertTrue(row.get("line_no").getAsInt() > 0);
            assertTrue(row.get("source").getAsString().contains("access_log"));
        }
    }

    /**
     * 試験: limit / offset によるページング。
     * 担保: 件数は total のまま、items だけがページ分割される。
     *       最終ページで端数になり、範囲を超える offset では空になる。
     */
    @Test
    void logsPagination() throws Exception {
        JsonObject all = getJson("/api/logs");
        JsonArray allItems = all.getAsJsonArray("items");

        JsonObject page = getJson("/api/logs?limit=10&offset=0");
        assertEquals(15, page.get("total").getAsInt());
        assertEquals(10, page.get("limit").getAsInt());
        assertEquals(0, page.get("offset").getAsInt());
        assertEquals(10, page.getAsJsonArray("items").size());

        JsonObject last = getJson("/api/logs?limit=10&offset=10");
        assertEquals(5, last.getAsJsonArray("items").size(), "最終ページは端数");
        assertEquals(allItems.get(10), last.getAsJsonArray("items").get(0), "先頭行が一致");

        assertEquals(0, getJson("/api/logs?limit=10&offset=100").getAsJsonArray("items").size());
    }

    /**
     * 試験: limit / offset の上限・下限。
     * 担保: limit は 5000 に丸められ、負の offset は 0 に丸められる。
     */
    @Test
    void logsClampsLimitAndOffset() throws Exception {
        assertEquals(5000, getJson("/api/logs?limit=999999").get("limit").getAsInt());
        assertEquals(0, getJson("/api/logs?offset=-5").get("offset").getAsInt());
    }

    /**
     * 試験: 各フィルタが HTTP 経由で機能すること。
     * 担保: ステータス（大文字表記を含む）・メソッド・パス・IP・ログファイル・
     *       全文検索が想定件数に絞り込む。
     */
    @Test
    void logsFilters() throws Exception {
        assertEquals(3, getJson("/api/logs?status=4XX").get("total").getAsInt());
        assertEquals(3, getJson("/api/logs?status=4xx").get("total").getAsInt());
        assertEquals(2, getJson("/api/logs?grep=login").get("total").getAsInt());
        assertTrue(getJson("/api/logs?method=POST").get("total").getAsInt() > 0);
        assertTrue(getJson("/api/logs?path=" + enc("/api/")).get("total").getAsInt() > 0);
        assertEquals(0, getJson("/api/logs?source=" + enc("no-such-file")).get("total").getAsInt());
    }

    /**
     * 試験: 期間フィルタ（ログの現地時刻で解釈）。
     * 担保: 表示されている時刻をそのまま指定して絞り込め、
     *       日付だけの指定ではその日の末尾（23:59:59）まで含まれる。
     */
    @Test
    void logsTimeRange() throws Exception {
        assertEquals(15, getJson("/api/logs?since=" + enc("2025-06-20 00:00:00")
                + "&until=" + enc("2025-06-20 23:59:59")).get("total").getAsInt());
        assertEquals(0, getJson("/api/logs?since=" + enc("2025-06-21")).get("total").getAsInt());
        int morning = getJson("/api/logs?since=" + enc("2025-06-20 08:00:00")).get("total").getAsInt();
        int before = getJson("/api/logs?until=" + enc("2025-06-20 08:00:00")).get("total").getAsInt();
        assertEquals(15, morning + before - overlapAt("2025-06-20T08:00:00+09:00"));
    }

    /**
     * 試験: 不正なパラメータに対する応答。
     * 担保: 400 と利用者向けメッセージを返し、内部例外の文言を露出しない。
     */
    @Test
    void logsRejectsInvalidParameters() throws Exception {
        assertError(get("/api/logs?path=" + enc("[")), 400, "正規表現が不正です");
        assertError(get("/api/logs?status=abc"), 400, "ステータス指定が不正です");
        assertError(get("/api/logs?limit=abc"), 400, "limit/offset は整数で指定してください");
        assertError(get("/api/logs?until=" + enc("abcd-ef-gh")), 400, "日時形式を解釈できません");
        assertError(get("/api/logs?until=" + enc("2025-13-45")), 400, "日時形式を解釈できません");
        assertError(get("/api/logs?until=" + enc("2025-06-20 08:00:00+09:00")),
                400, "タイムゾーン指定不可");
    }

    // ---- /api/logs/detail -------------------------------------------------

    /**
     * 試験: 行の詳細取得。
     * 担保: 一覧が返した source / line_no / timestamp で同じ行を再取得できる。
     */
    @Test
    void detailReturnsSameRow() throws Exception {
        JsonObject row = getJson("/api/logs?limit=1&offset=3").getAsJsonArray("items")
                .get(0).getAsJsonObject();
        JsonObject detail = getJson("/api/logs/detail?source=" + enc(row.get("source").getAsString())
                + "&line_no=" + row.get("line_no").getAsInt()
                + "&timestamp=" + enc(row.get("timestamp").getAsString()));
        assertEquals(row.get("source"), detail.get("source"));
        assertEquals(row.get("line_no"), detail.get("line_no"));
        assertEquals(row.get("raw"), detail.get("raw"));
    }

    /**
     * 試験: 詳細取得の異常系。
     * 担保: 指定不足は 400、存在しない行は 404 を返す。
     */
    @Test
    void detailErrors() throws Exception {
        assertError(get("/api/logs/detail"), 400, "ログファイルを指定してください");
        assertError(get("/api/logs/detail?source=x&line_no=abc"), 400, "line_no は整数で指定してください");
        assertError(get("/api/logs/detail?source=no-such&line_no=1"), 404, "該当行が見つかりません");
    }

    // ---- /api/browse ------------------------------------------------------

    /**
     * 試験: ディレクトリ一覧。
     * 担保: 現在位置と親を返し、存在しないパスは 400 になる。
     */
    @Test
    void browseListsDirectories() throws Exception {
        JsonObject res = getJson("/api/browse");
        assertNotNull(res.get("current").getAsString());
        assertFalse(res.get("parent").isJsonNull(), "samples には親がある");
        assertTrue(res.has("directories"));

        assertError(get("/api/browse?path=" + enc("no-such-dir-xyz")), 400,
                "ディレクトリが見つかりません");
    }

    // ---- /api/load --------------------------------------------------------

    /**
     * 試験: 読み込み指示の異常系。
     * 担保: 壊れた JSON・directory 未指定・存在しないディレクトリをそれぞれ 400 で返す。
     */
    @Test
    void loadRejectsInvalidRequests() throws Exception {
        assertError(post("/api/load", "not json"), 400, "JSON を解釈できません");
        assertError(post("/api/load", "{}"), 400, "directory を指定してください");
        assertError(post("/api/load", "{\"directory\":\"no-such-dir-xyz\"}"), 400,
                "ディレクトリが見つかりません");
    }

    /**
     * 試験: 読み込み中に別ディレクトリへ切り替えたときの整合性（退行防止）。
     *
     * <p>担保: 先に始まった重いディレクトリの読み込みが後から完了しても、
     * 切り替え後の状態を上書きしない。この保護がないと meta の件数が前の
     * ディレクトリのものに化け、各行の source と byte offset の組み合わせが崩れて
     * 生ログが空になる（ファイル数が異なる場合は 500 になる）。
     */
    @Test
    void switchingSourceWhileLoadingKeepsStateConsistent(@TempDir Path tmp) throws Exception {
        Path bigDir = Files.createDirectory(tmp.resolve("big"));
        writeBigLog(bigDir.resolve("access_log.big"), 150_000);

        assertEquals(200, post("/api/load",
                "{\"directory\":" + jsonString(bigDir.toString()) + "}").status);
        // 完了を待たずにサンプル（4 ファイル・15 行）へ切り替える。
        assertEquals(200, post("/api/load",
                "{\"directory\":" + jsonString(TestPaths.samplesDir().toAbsolutePath().toString())
                        + "}").status);
        waitUntilReady();
        // 先行スレッドが完了する余地を与える。
        Thread.sleep(1500);

        JsonObject meta = getJson("/api/meta");
        assertEquals("ready", meta.get("load_status").getAsString());
        assertEquals(4, meta.getAsJsonArray("files").size(), "対象は切り替え後のサンプル");
        assertEquals(15, meta.get("total").getAsInt(),
                "先行した大きい読み込みの件数で上書きされていない");

        JsonObject logs = getJson("/api/logs");
        assertEquals(15, logs.get("total").getAsInt());
        for (JsonElement el : logs.getAsJsonArray("items")) {
            JsonObject row = el.getAsJsonObject();
            assertFalse(row.get("raw").getAsString().isEmpty(),
                    "byte offset がファイル範囲外を指していない: " + row);
        }
    }

    // ---- ヘルパー ---------------------------------------------------------

    /** 指定 ISO 時刻ちょうどの行数（since/until が境界を含むため二重に数えられる分）。 */
    private int overlapAt(String iso) throws Exception {
        int n = 0;
        for (JsonElement el : getJson("/api/logs").getAsJsonArray("items")) {
            if (iso.equals(el.getAsJsonObject().get("timestamp").getAsString())) {
                n++;
            }
        }
        return n;
    }

    private static void writeBigLog(Path path, int lines) throws IOException {
        StringBuilder sb = new StringBuilder(lines * 100);
        for (int i = 0; i < lines; i++) {
            int sec = i % 60;
            int min = (i / 60) % 60;
            int hour = (i / 3600) % 24;
            sb.append("10.0.0.1 - - [20/Jun/2025:")
                    .append(two(hour)).append(':').append(two(min)).append(':').append(two(sec))
                    .append(" +0900] \"GET /big/").append(i)
                    .append(" HTTP/1.1\" 200 1234 \"-\" \"Mozilla/5.0\"\n");
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String two(int v) {
        return (v < 10) ? "0" + v : Integer.toString(v);
    }

    private void waitUntilReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            JsonObject meta = getJson("/api/meta");
            String status = meta.get("load_status").getAsString();
            if ("ready".equals(status) || "error".equals(status)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("読み込みが完了しませんでした");
    }

    private void assertError(Response res, int status, String messageFragment) {
        assertEquals(status, res.status, res.body);
        JsonObject o = JsonParser.parseString(res.body).getAsJsonObject();
        String message = o.get("error").getAsString();
        assertTrue(message.contains(messageFragment),
                "期待する語句 '" + messageFragment + "' を含まない: " + message);
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String jsonString(String s) {
        return new JsonPrimitive(s).toString();
    }

    private JsonObject getJson(String path) throws Exception {
        Response res = get(path);
        assertEquals(200, res.status, res.body);
        return JsonParser.parseString(res.body).getAsJsonObject();
    }

    private Response get(String path) throws IOException {
        return request("GET", path, null);
    }

    private Response post(String path, String body) throws IOException {
        return request("POST", path, body);
    }

    private Response request(String method, String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream in = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
        String text = (in != null) ? readAll(in) : "";
        return new Response(status, conn.getContentType(), text);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** HTTP 応答（ステータス・Content-Type・本文）。 */
    private static final class Response {
        final int status;
        final String contentType;
        final String body;

        Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

}

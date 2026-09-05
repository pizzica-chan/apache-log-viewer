package com.example.alv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.PatternSyntaxException;

/**
 * 自前 HTTP サーバ（JDK 内蔵 {@link com.sun.net.httpserver.HttpServer} を使用）。
 *
 * <p>アプリケーションサーバ（Tomcat 等）に依存せず単体で Web UI を提供する。
 * REST API と静的ファイル（Web UI）を提供する。JSON の入出力には Gson を用いる。
 *
 * <ul>
 *   <li>{@code GET /}               — index.html</li>
 *   <li>{@code GET /static/*}       — 静的ファイル（クラスパス同梱）</li>
 *   <li>{@code GET /api/meta}       — 読み込み状態・件数・期間</li>
 *   <li>{@code GET /api/browse}     — ディレクトリ一覧</li>
 *   <li>{@code POST /api/load}      — ディレクトリ指定・読み込み開始</li>
 *   <li>{@code GET /api/logs}       — フィルタ付き一覧</li>
 *   <li>{@code GET /api/logs/detail} — 生ログ行</li>
 * </ul>
 */
public final class LogServer {

    private static final int MAX_LIMIT = 5000;
    private static final int DEFAULT_LIMIT = 500;

    private final LogStore store = new LogStore();
    private final Map<String, byte[]> staticCache = new HashMap<>();

    private volatile HttpServer server;
    private volatile ExecutorService executor;

    public LogServer(Path logRoot, List<Path> logPaths) {
        store.setSource(logRoot, logPaths);
    }

    /**
     * サーバを起動して待ち受ける。
     *
     * <p>{@link HttpServer} は自前のスレッドで動くため、このメソッドは即座に戻る。
     * プロセスは待ち受けスレッドによって生存し続ける。
     *
     * @param port {@code 0} を指定すると空きポートが自動で割り当てられる（{@link #getPort()} で取得）
     */
    public void start(String host, int port) throws IOException {
        // 起動時に --dir が指定されていれば、ここで読み込みを開始する。
        store.ensureLoadStarted();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(executor);
        server.createContext("/", new RootHandler());
        this.server = server;
        this.executor = executor;

        System.out.println("Apache Log Viewer (Java): http://" + host + ":" + server.getAddress().getPort());
        LogSnapshot snap = store.snapshot();
        Path root = snap.logRoot();
        if (root != null) {
            System.out.println("ログディレクトリ: " + PathUtil.normalizePath(root));
        }
        List<Path> paths = snap.logPaths();
        System.out.println("読み込みファイル (" + paths.size() + "):");
        for (Path p : paths) {
            System.out.println("  - " + PathUtil.normalizePath(p));
        }
        if (paths.isEmpty()) {
            System.out.println("  (未読み込み — ブラウザからディレクトリを選択してください)");
        }
        server.start();
    }

    /** 実際に待ち受けているポート。未起動なら {@code -1}。 */
    public int getPort() {
        HttpServer s = server;
        return (s != null) ? s.getAddress().getPort() : -1;
    }

    /** サーバを停止し、リクエスト処理スレッドを解放する。 */
    public void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
            server = null;
        }
        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdownNow();
            executor = null;
        }
    }

    // ---- ルーティング -----------------------------------------------------

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                if ("/".equals(path)) {
                    serveStatic(ex, "index.html");
                } else if (path.startsWith("/static/")) {
                    serveStatic(ex, path.substring("/static/".length()));
                } else if ("/api/meta".equals(path)) {
                    sendJson(ex, 200, metaPayload());
                } else if ("/api/browse".equals(path)) {
                    handleBrowse(ex);
                } else if ("/api/load".equals(path) && "POST".equalsIgnoreCase(method)) {
                    handleLoad(ex);
                } else if ("/api/logs".equals(path)) {
                    handleLogs(ex);
                } else if ("/api/logs/detail".equals(path)) {
                    handleDetail(ex);
                } else {
                    sendError(ex, 404, "not found");
                }
            } catch (Exception e) {
                try {
                    sendError(ex, 500, e.getMessage() != null ? e.getMessage() : e.toString());
                } catch (IOException ignored) {
                    // レスポンス送信失敗は無視
                }
            } finally {
                ex.close();
            }
        }
    }

    // ---- API: meta --------------------------------------------------------

    private JsonObject metaPayload() {
        // 1 レスポンス内で状態が食い違わないよう、スナップショットは 1 回だけ取得する。
        LogSnapshot snap = store.snapshot();
        List<LogEntry> entries = snap.entries();
        boolean loading = snap.isLoading();
        long progress = store.getLoadProgress();
        long total = loading ? progress : entries.size();

        JsonObject payload = new JsonObject();
        Path root = snap.logRoot();
        payload.addProperty("directory", root != null ? PathUtil.normalizePath(root) : null);
        JsonArray files = new JsonArray();
        for (String name : snap.sourceNames()) {
            files.add(name);
        }
        payload.add("files", files);
        payload.addProperty("loading", loading);
        payload.addProperty("load_status", snap.status());
        payload.addProperty("load_progress", progress);
        payload.addProperty("total", total);
        payload.addProperty("first", entries.isEmpty() ? null : entries.get(0).timestampIso());
        payload.addProperty("last", entries.isEmpty() ? null : entries.get(entries.size() - 1).timestampIso());
        if (!loading && snap.skippedLines() > 0) {
            payload.addProperty("skipped_lines", snap.skippedLines());
            JsonArray samples = new JsonArray();
            for (SkippedLine s : snap.skippedSamples()) {
                JsonObject o = new JsonObject();
                o.addProperty("source", snap.sourceName(s.fileId));
                o.addProperty("line_no", s.lineNo);
                o.addProperty("preview", s.preview);
                samples.add(o);
            }
            payload.add("skipped_samples", samples);
        }
        if (snap.error() != null) {
            payload.addProperty("load_error", snap.error());
        }
        return payload;
    }

    // ---- API: browse ------------------------------------------------------

    private void handleBrowse(HttpExchange ex) throws IOException {
        Map<String, String> params = queryParams(ex);
        String rawPath = params.getOrDefault("path", "");
        LogSnapshot snap = store.snapshot();
        Path current;
        if (!rawPath.isEmpty()) {
            current = PathUtil.resolve(rawPath);
        } else if (snap.logRoot() != null) {
            current = snap.logRoot();
        } else {
            current = Paths.get("").toAbsolutePath();
        }

        if (!Files.isDirectory(current)) {
            sendErrorJson(ex, 400, "ディレクトリが見つかりません: " + PathUtil.normalizePath(current));
            return;
        }

        Path parent = current.getParent();
        List<String> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path entry : stream) {
                Path name = entry.getFileName();
                if (name != null && Files.isDirectory(entry) && !name.toString().startsWith(".")) {
                    dirs.add(PathUtil.normalizePath(entry));
                }
            }
        } catch (IOException e) {
            sendErrorJson(ex, 400, "ディレクトリを読み取れません: " + e.getMessage());
            return;
        }
        dirs.sort((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)));

        JsonObject payload = new JsonObject();
        payload.addProperty("current", PathUtil.normalizePath(current));
        payload.addProperty("parent",
                (parent != null && !parent.equals(current)) ? PathUtil.normalizePath(parent) : null);
        JsonArray arr = new JsonArray();
        for (String d : dirs) {
            arr.add(d);
        }
        payload.add("directories", arr);
        sendJson(ex, 200, payload);
    }

    // ---- API: load --------------------------------------------------------

    private void handleLoad(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String directory = "";
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("directory") && !obj.get("directory").isJsonNull()) {
                directory = obj.get("directory").getAsString();
            }
        } catch (RuntimeException e) {
            sendErrorJson(ex, 400, "JSON を解釈できません");
            return;
        }
        if (directory.isEmpty()) {
            sendErrorJson(ex, 400, "directory を指定してください");
            return;
        }
        Path root = PathUtil.resolve(directory);
        if (!Files.isDirectory(root)) {
            sendErrorJson(ex, 400, "ディレクトリが見つかりません: " + PathUtil.normalizePath(root));
            return;
        }

        List<Path> paths;
        try {
            paths = Discovery.findLogFiles(root);
        } catch (IOException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }
        store.setSource(root, paths);
        store.startLoad();
        sendJson(ex, 200, metaPayload());
    }

    // ---- API: logs --------------------------------------------------------

    private void handleLogs(HttpExchange ex) throws IOException {
        // 対象パスとエントリの組み合わせがずれないよう、以降は同じスナップショットだけを使う。
        LogSnapshot snap = store.snapshot();
        if (snap.isLoading()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("loading", true);
            payload.addProperty("load_progress", store.getLoadProgress());
            payload.addProperty("total", 0);
            payload.addProperty("offset", 0);
            payload.addProperty("limit", 0);
            payload.add("items", new JsonArray());
            sendJson(ex, 200, payload);
            return;
        }
        if (snap.isError()) {
            sendErrorJson(ex, 500, snap.error() != null ? snap.error() : "読み込みに失敗しました");
            return;
        }

        Map<String, String> p = queryParams(ex);
        QueryFilter filter = new QueryFilter();
        try {
            filter.status = QueryFilter.parseStatusFilter(p.get("status"));
            filter.pathRe = QueryFilter.compileRegex(p.get("path"));
            filter.hostRe = QueryFilter.compileRegex(p.get("host"));
            filter.sourceRe = QueryFilter.compileRegex(p.get("source"));
            filter.grepRe = QueryFilter.compileRegex(p.get("grep"));
            String method = p.get("method");
            filter.method = (method != null && !method.isEmpty()) ? method : null;
        } catch (PatternSyntaxException e) {
            sendErrorJson(ex, 400, "正規表現が不正です: " + e.getMessage());
            return;
        } catch (NumberFormatException e) {
            sendErrorJson(ex, 400, "ステータス指定が不正です");
            return;
        }
        try {
            String since = p.get("since");
            String until = p.get("until");
            filter.sinceWallMillis =
                    (since != null && !since.isEmpty()) ? TimeUtil.parseUiWallClockMillis(since) : null;
            filter.untilWallMillis =
                    (until != null && !until.isEmpty()) ? TimeUtil.parseUiWallClockMillis(until) : null;
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }

        long limit;
        long offset;
        try {
            limit = Math.min(parseLong(p.get("limit"), DEFAULT_LIMIT), MAX_LIMIT);
            offset = Math.max(parseLong(p.get("offset"), 0), 0);
        } catch (NumberFormatException e) {
            sendErrorJson(ex, 400, "limit/offset は整数で指定してください");
            return;
        }

        List<LogEntry> entries = snap.entries();
        List<LogEntry> page = new ArrayList<>();
        List<String> pageRaw = new ArrayList<>();
        JsonArray items = new JsonArray();
        long total;
        try (LineReader reader = new LineReader(snap.logPaths())) {
            LastRawLine raw = filter.needsRaw() ? new LastRawLine(reader) : null;
            total = collect(snap, entries, filter, page, pageRaw, offset, limit, raw);
            for (int i = 0; i < page.size(); i++) {
                LogEntry e = page.get(i);
                String line = pageRaw.get(i);
                items.add(rowJson(snap, e, line != null ? line : reader.read(e.fileId, e.byteOffset)));
            }
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("total", total);
        payload.addProperty("offset", offset);
        payload.addProperty("limit", limit);
        payload.add("items", items);
        sendJson(ex, 200, payload);
    }

    private long collect(LogSnapshot snap, List<LogEntry> entries, QueryFilter filter,
                         List<LogEntry> page, List<String> pageRaw, long offset, long limit,
                         LastRawLine raw) throws IOException {
        long total = 0;
        long end = offset + limit;
        for (LogEntry e : entries) {
            if (!filter.matches(e, snap.sourceName(e), raw)) {
                continue;
            }
            if (total >= offset && total < end) {
                page.add(e);
                // grep 指定時は判定で読んだ生ログをそのまま使い、同じ行の再読を避ける。
                pageRaw.add(raw != null ? raw.lastFor(e) : null);
            }
            total++;
        }
        return total;
    }

    /**
     * grep 判定用の生ログリーダー。直近に読んだ行を保持し、そのままレスポンスに再利用する。
     *
     * <p>{@link QueryFilter#matches} は grep を最後に評価するため、判定が {@code true} の
     * エントリについては {@link #read} が呼ばれている。ただし評価順に依存して誤った行を
     * 返さないよう、{@link #lastFor} は対象エントリが一致する場合のみ値を返す
     * （一致しなければ {@code null} を返し、呼び出し側が読み直す）。
     */
    private static final class LastRawLine implements QueryFilter.RawLine {
        private final LineReader reader;
        private LogEntry lastEntry;
        private String last;

        LastRawLine(LineReader reader) {
            this.reader = reader;
        }

        @Override
        public String read(LogEntry entry) throws IOException {
            last = reader.read(entry.fileId, entry.byteOffset);
            lastEntry = entry;
            return last;
        }

        /** 直近に読んだ行。{@code entry} が直近の対象と異なる場合は {@code null}。 */
        String lastFor(LogEntry entry) {
            return entry == lastEntry ? last : null;
        }
    }

    private JsonObject rowJson(LogSnapshot snap, LogEntry e, String raw) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", e.timestampIso());
        o.addProperty("status", e.status == LogEntry.NO_STATUS ? null : Integer.valueOf(e.status));
        o.addProperty("method", e.method);
        o.addProperty("path", e.path);
        o.addProperty("client_host", e.clientHost);
        o.addProperty("host", e.host);
        o.addProperty("forwarded_for", e.forwardedFor);
        o.addProperty("source", snap.sourceName(e));
        o.addProperty("line_no", e.lineNo);
        o.addProperty("raw", raw);
        return o;
    }

    // ---- API: logs/detail -------------------------------------------------

    private void handleDetail(HttpExchange ex) throws IOException {
        Map<String, String> p = queryParams(ex);
        String source = p.getOrDefault("source", "");
        String timestamp = p.get("timestamp");
        if (timestamp != null && timestamp.isEmpty()) {
            timestamp = null;
        }
        int lineNo;
        try {
            lineNo = Integer.parseInt(p.getOrDefault("line_no", "0"));
        } catch (NumberFormatException e) {
            sendErrorJson(ex, 400, "line_no は整数で指定してください");
            return;
        }
        if (source.isEmpty()) {
            sendErrorJson(ex, 400, "ログファイルを指定してください");
            return;
        }

        LogSnapshot snap = store.snapshot();
        LogEntry entry = snap.findEntry(source, lineNo, timestamp);
        if (entry == null) {
            sendErrorJson(ex, 404, "該当行が見つかりません");
            return;
        }

        String raw;
        try (LineReader reader = new LineReader(snap.logPaths())) {
            raw = reader.read(entry.fileId, entry.byteOffset);
        }

        JsonObject o = new JsonObject();
        o.addProperty("source", snap.sourceName(entry));
        o.addProperty("line_no", entry.lineNo);
        o.addProperty("client_host", entry.clientHost);
        o.addProperty("host", entry.host);
        o.addProperty("forwarded_for", entry.forwardedFor);
        o.addProperty("raw", raw);
        sendJson(ex, 200, o);
    }

    // ---- 静的ファイル -----------------------------------------------------

    private void serveStatic(HttpExchange ex, String name) throws IOException {
        byte[] content = loadStatic(name);
        if (content == null) {
            sendError(ex, 404, "not found");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", contentType(name));
        ex.sendResponseHeaders(200, content.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(content);
        }
    }

    /** 静的ファイルを読み出す（存在しない場合の {@code null} もキャッシュして再探索を防ぐ）。 */
    private byte[] loadStatic(String name) {
        if (name.contains("..")) {
            return null;
        }
        synchronized (staticCache) {
            if (staticCache.containsKey(name)) {
                return staticCache.get(name);
            }
        }
        byte[] data = null;
        try (InputStream in = LogServer.class.getResourceAsStream("/static/" + name)) {
            if (in != null) {
                data = readAll(in);
            }
        } catch (IOException ignored) {
            data = null;
        }
        synchronized (staticCache) {
            staticCache.put(name, data);
        }
        return data;
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    // ---- HTTP ユーティリティ ----------------------------------------------

    private Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> map = new TreeMap<>();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = decode(pair.substring(0, eq));
                value = decode(pair.substring(eq + 1));
            } else {
                key = decode(pair);
                value = "";
            }
            if (!map.containsKey(key)) {
                map.put(key, value);
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static long parseLong(String s, long defaultValue) {
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(s.trim());
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private void sendJson(HttpExchange ex, int status, JsonElement payload) throws IOException {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendErrorJson(HttpExchange ex, int status, String message) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message != null ? message : "error");
        sendJson(ex, status, obj);
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        byte[] body = (message != null ? message : "error").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}

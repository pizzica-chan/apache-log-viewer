package com.example.alv;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.regex.PatternSyntaxException;

/**
 * 自前 HTTP サーバ（JDK 内蔵 {@link com.sun.net.httpserver.HttpServer} を使用）。
 *
 * <p>アプリケーションサーバ（Tomcat 等）に依存せず単体で Web UI を提供する。
 * Python 版（{@code alv}）と互換の API を実装し、同じフロントエンドをそのまま利用する。
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
    private final Map<String, byte[]> staticCache = new LinkedHashMap<>();

    public LogServer(Path logRoot, List<Path> logPaths) {
        store.setSource(logRoot, logPaths);
    }

    /** サーバを起動して待ち受ける（戻らない）。 */
    public void start(String host, int port) throws IOException {
        store.ensureLoadStarted();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.createContext("/", new RootHandler());

        System.out.println("Apache Log Viewer (Java): http://" + host + ":" + port);
        Path root = store.getLogRoot();
        if (root != null) {
            System.out.println("ログディレクトリ: " + PathUtil.normalizePath(root));
        }
        List<Path> paths = store.getLogPaths();
        System.out.println("読み込みファイル (" + paths.size() + "):");
        for (Path p : paths) {
            System.out.println("  - " + PathUtil.normalizePath(p));
        }
        if (paths.isEmpty()) {
            System.out.println("  (未読み込み — ブラウザからディレクトリを選択してください)");
        }
        server.start();
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

    private Map<String, Object> metaPayload() {
        List<LogEntry> entries = store.getEntries();
        boolean loading = store.isLoading();
        long total = loading ? store.getLoadProgress() : entries.size();

        Map<String, Object> payload = new LinkedHashMap<>();
        Path root = store.getLogRoot();
        payload.put("directory", root != null ? PathUtil.normalizePath(root) : null);
        payload.put("files", new ArrayList<>(store.getSourceNames()));
        payload.put("loading", loading);
        payload.put("load_status", store.getLoadStatus());
        payload.put("load_progress", store.getLoadProgress());
        payload.put("total", total);
        payload.put("first", entries.isEmpty() ? null : entries.get(0).timestampIso());
        payload.put("last", entries.isEmpty() ? null : entries.get(entries.size() - 1).timestampIso());
        if (store.getLoadError() != null) {
            payload.put("load_error", store.getLoadError());
        }
        return payload;
    }

    // ---- API: browse ------------------------------------------------------

    private void handleBrowse(HttpExchange ex) throws IOException {
        Map<String, String> params = queryParams(ex);
        String rawPath = params.getOrDefault("path", "");
        Path current;
        if (!rawPath.isEmpty()) {
            current = PathUtil.resolve(rawPath);
        } else if (store.getLogRoot() != null) {
            current = store.getLogRoot();
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("current", PathUtil.normalizePath(current));
        payload.put("parent",
                (parent != null && !parent.equals(current)) ? PathUtil.normalizePath(parent) : null);
        payload.put("directories", dirs);
        sendJson(ex, 200, payload);
    }

    // ---- API: load --------------------------------------------------------

    private void handleLoad(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String directory = "";
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map) {
                Object dir = ((Map<?, ?>) parsed).get("directory");
                if (dir != null) {
                    directory = dir.toString();
                }
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
        if (store.isLoading()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("loading", true);
            payload.put("load_progress", store.getLoadProgress());
            payload.put("total", 0L);
            payload.put("offset", 0L);
            payload.put("limit", 0L);
            payload.put("items", new ArrayList<>());
            sendJson(ex, 200, payload);
            return;
        }
        if (store.isError()) {
            sendErrorJson(ex, 500, store.getLoadError() != null ? store.getLoadError() : "読み込みに失敗しました");
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
            filter.sinceMillis = (since != null && !since.isEmpty()) ? TimeUtil.parseUiDatetime(since) : null;
            filter.untilMillis = (until != null && !until.isEmpty()) ? TimeUtil.parseUiDatetime(until) : null;
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

        List<LogEntry> entries = store.getEntries();
        List<LogEntry> page = new ArrayList<>();
        long total;
        if (filter.needsRaw()) {
            try (LineReader reader = new LineReader(store.getLogPaths())) {
                QueryFilter.RawLine raw = e -> reader.read(e.fileId, e.byteOffset);
                total = collect(entries, filter, page, offset, limit, raw);
            }
        } else {
            total = collect(entries, filter, page, offset, limit, null);
        }

        List<Object> items = new ArrayList<>(page.size());
        for (LogEntry e : page) {
            items.add(rowJson(e));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("offset", offset);
        payload.put("limit", limit);
        payload.put("items", items);
        sendJson(ex, 200, payload);
    }

    private long collect(List<LogEntry> entries, QueryFilter filter, List<LogEntry> page,
                         long offset, long limit, QueryFilter.RawLine raw) throws IOException {
        long total = 0;
        long end = offset + limit;
        for (LogEntry e : entries) {
            if (!filter.matches(e, store.sourceName(e), raw)) {
                continue;
            }
            if (total >= offset && total < end) {
                page.add(e);
            }
            total++;
        }
        return total;
    }

    private Map<String, Object> rowJson(LogEntry e) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("timestamp", e.timestampIso());
        o.put("status", e.status == LogEntry.NO_STATUS ? null : Integer.valueOf(e.status));
        o.put("method", e.method);
        o.put("path", e.path);
        o.put("client_host", e.clientHost);
        o.put("host", e.host);
        o.put("forwarded_for", e.forwardedFor);
        o.put("source", store.sourceName(e));
        o.put("line_no", (long) e.lineNo);
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
            sendErrorJson(ex, 400, "source を指定してください");
            return;
        }

        LogEntry entry = store.findEntry(source, lineNo, timestamp);
        if (entry == null) {
            sendErrorJson(ex, 404, "該当行が見つかりません");
            return;
        }

        String raw;
        try (LineReader reader = new LineReader(store.getLogPaths())) {
            raw = reader.read(entry.fileId, entry.byteOffset);
        }

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("source", store.sourceName(entry));
        o.put("line_no", (long) entry.lineNo);
        o.put("client_host", entry.clientHost);
        o.put("host", entry.host);
        o.put("forwarded_for", entry.forwardedFor);
        o.put("raw", raw);
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

    private void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendErrorJson(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("error", message != null ? message : "error");
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

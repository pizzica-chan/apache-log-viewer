package com.example.alv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 取り込むアクセスログの書式。
 *
 * <p>同時に取り込むファイルはすべて同じ書式である前提とし、取り込み開始時に 1 つへ確定させる。
 * こうすることで、書式を増やしても <strong>1 行あたりに試す正規表現は 1 本だけ</strong>で済む。
 *
 * <p>Apache と nginx の既定（common / combined）は並びが同じなので {@link #COMBINED} が
 * 両方を受ける。書式を分ける必要があるのは、フィールドの<em>意味</em>が変わる場合だけ。
 */
public enum LogFormat {

    /**
     * Apache / nginx の common・combined。vhost 前置き（{@code %v:%p}）と、
     * remote host の前に置かれた X-Forwarded-For にも対応する。
     * {@code 127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] "GET /a HTTP/1.1" 200 123}
     */
    COMBINED("combined", "Apache / nginx combined・common"),

    /**
     * nginx の {@code main} 形式。combined の後ろに
     * {@code "$http_x_forwarded_for"} が付く並びで、実クライアントは<strong>末尾</strong>に来る。
     * {@link #COMBINED} でも行自体は解析できるが末尾を読まないため、プロキシ経由だと
     * Client にプロキシの IP が出てしまう。
     * {@code 127.0.0.1 - - [...] "GET /a HTTP/1.1" 200 123 "-" "UA" "203.0.113.5"}
     */
    NGINX_MAIN("nginx", "nginx main（末尾の X-Forwarded-For を使う）"),

    /**
     * ident と authuser を出力しない最小構成（{@code %h %t "%r" %>s %b}）。
     * {@code 127.0.0.1 [20/Jun/2025:08:01:12 +0900] "GET /a HTTP/1.1" 200 123}
     */
    MINIMAL("minimal", "ident/authuser なし");

    /** 自動判定でサンプリングする行数。 */
    static final int DETECT_SAMPLE_LINES = 500;

    private final String id;
    private final String displayName;

    LogFormat(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** API・索引フィンガープリントで使う識別子。 */
    public String id() {
        return id;
    }

    /** 画面表示用の名前。 */
    public String displayName() {
        return displayName;
    }

    /** {@link #id()} から引く。未知の値や {@code null} は {@code null}。 */
    public static LogFormat byId(String id) {
        if (id == null) {
            return null;
        }
        for (LogFormat f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }

    /**
     * 先頭ファイルの冒頭を読み、最もよく一致する書式を返す。
     *
     * <p>{@link #COMBINED} と {@link #NGINX_MAIN} は同じ行を両方とも解析できてしまうため、
     * 解析可否だけでは選べない。nginx の main 形式は末尾に XFF らしき引用フィールドを持つので、
     * それが実際に取れた行だけを {@link #NGINX_MAIN} の得点にする。
     *
     * <p>どの書式でも 1 行も解析できなかった場合は {@link #COMBINED} を返す。
     *
     * @param paths 取り込む対象。先頭の 1 つだけを見る（同時取り込みは同一書式の前提）
     */
    public static LogFormat detect(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return COMBINED;
        }
        int combined = 0;
        int nginx = 0;
        int minimal = 0;
        try (InputStream raw = Files.newInputStream(paths.get(0));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(raw, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            int seen = 0;
            while (seen < DETECT_SAMPLE_LINES && (line = reader.readLine()) != null) {
                seen++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (LogParser.parseLine(COMBINED, line, 0, 0, 0L) != null) {
                    combined++;
                    // combined として読める行のうち、末尾に XFF があるものだけ nginx の得点にする。
                    if (LogParser.trailingForwardedFor(line) != null) {
                        nginx++;
                    }
                } else if (LogParser.parseLine(MINIMAL, line, 0, 0, 0L) != null) {
                    minimal++;
                }
            }
        } catch (IOException e) {
            return COMBINED;
        }
        // nginx は combined の部分集合なので、過半数が末尾 XFF を持つときだけ選ぶ。
        if (combined > 0 && nginx * 2 > combined) {
            return NGINX_MAIN;
        }
        if (minimal > combined) {
            return MINIMAL;
        }
        return COMBINED;
    }
}

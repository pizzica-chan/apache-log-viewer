package com.example.alv;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 読み込み対象と読み込み結果を 1 つに束ねた不変スナップショット。
 *
 * <p>1 リクエストの処理中はこのオブジェクトだけを参照する。対象パス・ファイル名・
 * 解析済みエントリを別々のフィールドから読むと、読み込みの差し替え中に
 * 「エントリは前のディレクトリ、パスは次のディレクトリ」という組み合わせが生じ、
 * 別ファイルのバイトオフセットを読む・ファイル添字が範囲外になる、といった
 * 不整合が起きるため。
 *
 * <p>{@link #generation()} は読み込み対象を切り替えるたびに増える。読み込みワーカーは
 * 自分が開始した世代と一致する場合だけ結果を反映し、追い越された古い読み込みの結果は
 * 捨てる。これがないと、先に始まった重いディレクトリの読み込みが後から完了して
 * 新しい読み込み結果を上書きしてしまう。
 */
public final class LogSnapshot {

    /** 読み込み対象が未設定。 */
    public static final String IDLE = "idle";
    /** 読み込み中。 */
    public static final String LOADING = "loading";
    /** 読み込み完了。 */
    public static final String READY = "ready";
    /** 読み込み失敗。 */
    public static final String ERROR = "error";

    private final long generation;
    private final Path logRoot;
    private final List<Path> logPaths;
    private final List<String> sourceNames;
    private final String status;
    private final String error;
    private final List<LogEntry> entries;
    private final int skippedLines;
    private final List<SkippedLine> skippedSamples;

    private LogSnapshot(long generation, Path logRoot, List<Path> logPaths, List<String> sourceNames,
                        String status, String error, List<LogEntry> entries,
                        int skippedLines, List<SkippedLine> skippedSamples) {
        this.generation = generation;
        this.logRoot = logRoot;
        this.logPaths = logPaths;
        this.sourceNames = sourceNames;
        this.status = status;
        this.error = error;
        this.entries = entries;
        this.skippedLines = skippedLines;
        this.skippedSamples = skippedSamples;
    }

    /** 読み込み対象が未設定の初期状態。 */
    static LogSnapshot empty() {
        return new LogSnapshot(0L, null,
                Collections.<Path>emptyList(), Collections.<String>emptyList(),
                IDLE, null, Collections.<LogEntry>emptyList(),
                0, Collections.<SkippedLine>emptyList());
    }

    /** 読み込み対象を差し替えた idle 状態。世代は呼び出し側が進める。 */
    static LogSnapshot withSource(long generation, Path logRoot,
                                  List<Path> logPaths, List<String> sourceNames) {
        return new LogSnapshot(generation, logRoot,
                Collections.unmodifiableList(logPaths), Collections.unmodifiableList(sourceNames),
                IDLE, null, Collections.<LogEntry>emptyList(),
                0, Collections.<SkippedLine>emptyList());
    }

    /** 対象はそのままに loading へ遷移した状態。 */
    LogSnapshot loading() {
        return new LogSnapshot(generation, logRoot, logPaths, sourceNames,
                LOADING, null, Collections.<LogEntry>emptyList(),
                0, Collections.<SkippedLine>emptyList());
    }

    /** 読み込み結果を反映した ready 状態。 */
    LogSnapshot ready(List<LogEntry> loaded, int skipped, List<SkippedLine> samples) {
        return new LogSnapshot(generation, logRoot, logPaths, sourceNames,
                READY, null, Collections.unmodifiableList(loaded),
                skipped, Collections.unmodifiableList(samples));
    }

    /** 読み込み失敗を反映した error 状態。 */
    LogSnapshot failed(String message) {
        return new LogSnapshot(generation, logRoot, logPaths, sourceNames,
                ERROR, message, Collections.<LogEntry>emptyList(),
                0, Collections.<SkippedLine>emptyList());
    }

    // ---- 参照 -------------------------------------------------------------

    /** 読み込み対象を切り替えるたびに増える世代番号。 */
    public long generation() {
        return generation;
    }

    /** 読み込み対象のルートディレクトリ（未設定なら {@code null}）。 */
    public Path logRoot() {
        return logRoot;
    }

    /** 読み込み対象のログファイル（{@link LogEntry#fileId} の添字）。 */
    public List<Path> logPaths() {
        return logPaths;
    }

    /** 表示用に正規化した {@link #logPaths()} の文字列表現。 */
    public List<String> sourceNames() {
        return sourceNames;
    }

    /** 読み込み状態（{@link #IDLE} / {@link #LOADING} / {@link #READY} / {@link #ERROR}）。 */
    public String status() {
        return status;
    }

    /** 読み込み失敗時のメッセージ（それ以外は {@code null}）。 */
    public String error() {
        return error;
    }

    /** 時刻順に整列済みの解析結果（読み込み完了前は空）。 */
    public List<LogEntry> entries() {
        return entries;
    }

    /** 解析できずスキップした行数。 */
    public int skippedLines() {
        return skippedLines;
    }

    /** スキップした行のサンプル。 */
    public List<SkippedLine> skippedSamples() {
        return skippedSamples;
    }

    public boolean isLoading() {
        return LOADING.equals(status);
    }

    public boolean isError() {
        return ERROR.equals(status);
    }

    public boolean isReady() {
        return READY.equals(status);
    }

    /** エントリの属するログファイル名。 */
    public String sourceName(LogEntry entry) {
        return sourceNames.get(entry.fileId);
    }

    /** ファイル添字に対応するログファイル名。 */
    public String sourceName(int fileId) {
        return sourceNames.get(fileId);
    }

    /** source / line_no（と任意の timestamp）に一致するエントリを探す。 */
    public LogEntry findEntry(String source, int lineNo, String timestamp) {
        for (LogEntry e : entries) {
            if (e.lineNo != lineNo || !sourceName(e).equals(source)) {
                continue;
            }
            if (timestamp != null && !e.timestampIso().equals(timestamp)) {
                continue;
            }
            return e;
        }
        return null;
    }
}

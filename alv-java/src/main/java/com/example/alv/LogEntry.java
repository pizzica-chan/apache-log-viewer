package com.example.alv;

/**
 * 1 行の Apache アクセスログを表す不変モデル。
 *
 * <p>メモリ効率のため、生ログ行（raw）は保持せず {@link #byteOffset} のみを持つ。
 * 詳細表示・全文検索（grep）時に元ファイルの該当オフセットから読み出す。
 * タイムスタンプは整列・範囲比較に使う UTC epoch millis（{@link #tsMillis}）と、
 * 表示復元用のタイムゾーンオフセット（{@link #tzOffsetMin}）で保持する。
 */
public final class LogEntry {

    /** ステータスが {@code "-"}（記録なし）の場合の番兵値。 */
    public static final int NO_STATUS = -1;

    public final int fileId;
    public final int lineNo;
    public final long byteOffset;
    public final long tsMillis;
    public final int tzOffsetMin;
    public final String host;
    public final String clientHost;
    public final String forwardedFor;
    public final String method;
    public final String path;
    public final int status;

    public LogEntry(int fileId, int lineNo, long byteOffset, long tsMillis, int tzOffsetMin,
                    String host, String clientHost, String forwardedFor,
                    String method, String path, int status) {
        this.fileId = fileId;
        this.lineNo = lineNo;
        this.byteOffset = byteOffset;
        this.tsMillis = tsMillis;
        this.tzOffsetMin = tzOffsetMin;
        this.host = host;
        this.clientHost = clientHost;
        this.forwardedFor = forwardedFor;
        this.method = method;
        this.path = path;
        this.status = status;
    }

    /**
     * 行番号だけをずらした写しを返す。大きなファイルを分けて読んだとき、範囲の中で数えた
     * 行番号をファイル全体の行番号へ直すのに使う（{@link LogStore}）。
     */
    LogEntry withLineNoOffset(int delta) {
        return new LogEntry(fileId, lineNo + delta, byteOffset, tsMillis, tzOffsetMin,
                host, clientHost, forwardedFor, method, path, status);
    }

    /** ISO 8601（タイムゾーンオフセット付き）の文字列表現。 */
    public String timestampIso() {
        return TimeUtil.formatIsoOffset(tsMillis, tzOffsetMin);
    }

    /**
     * ログに記録された現地時刻（壁時計）を millis で表した<b>比較用の値</b>。
     *
     * <p>UTC の瞬間ではない。{@link #timestampIso()} が表示する時刻と同じ座標系であり、
     * 期間フィルタはこの値と UI 入力（{@link TimeUtil#parseUiWallClockMillis}）を比較する。
     * 整列には引き続き {@link #tsMillis}（真の瞬間）を用いる。
     */
    public long wallMillis() {
        return tsMillis + tzOffsetMin * 60_000L;
    }
}

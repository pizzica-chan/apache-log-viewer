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

    /** ISO 8601（タイムゾーンオフセット付き）の文字列表現。 */
    public String timestampIso() {
        return TimeUtil.formatIsoOffset(tsMillis, tzOffsetMin);
    }
}

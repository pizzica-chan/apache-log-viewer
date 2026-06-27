package com.example.alv;

/**
 * 解析に失敗したログ行の参照情報（生本文は保持しない）。
 */
public final class SkippedLine {

    public final int fileId;
    public final int lineNo;
    public final String preview;

    public SkippedLine(int fileId, int lineNo, String preview) {
        this.fileId = fileId;
        this.lineNo = lineNo;
        this.preview = preview;
    }
}

package com.example.alv;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * バイトオフセットから生ログ行をオンデマンドで読み出すリーダー（grep / 詳細表示用）。
 *
 * <p>ファイルごとに {@link RandomAccessFile} ハンドルと読み出しの窓を持ち、再利用する。
 * 一覧の grep は時刻順にエントリを辿るので、1 ファイルの中ではおおむね先頭から末尾へ進む。
 * 行ごとに seek + read するとシステムコールが 1 行 2 回かかり、1 行（百数十バイト）の
 * ために毎回まとめ読みバッファ全体を読むことになるため、窓にまとめて読んでおき、
 * そこから切り出す。戻る要求や窓の外の行が来たら、その位置から窓を読み直すので、
 * 返る内容は行ごとに読んだ場合と同じになる。
 *
 * <p>窓はファイルごとに持つ。複数ファイルの行が時刻順に交互に来る場合（複数台・
 * 複数バーチャルホストのログ）に、1 つの窓を共有すると読み直しが続くため。
 * 窓の大きさは合計 {@link #TOTAL_WINDOW_BYTES} をファイル数で割り、
 * {@link #MIN_WINDOW_BYTES}〜{@link #MAX_WINDOW_BYTES} に収める。
 *
 * <p>実測（100 万行・148 MB のアクセスログ、一覧 API と同じ走査、Windows 11 / JDK 11、
 * 変更前後を交互に 5 回の中央値を 3 ラウンド取った中央値）:
 * <table summary="まとめ読みの実測">
 *   <tr><th>grep</th><th>30 ファイル</th><th>3 ファイルが時刻順に交互</th><th>300 ファイル</th></tr>
 *   <tr><td>リテラル</td><td>3,681 → 835ms</td><td>3,831 → 811ms</td><td>3,851 → 944ms</td></tr>
 *   <tr><td>1 件だけ一致</td><td>3,806 → 848ms</td><td>3,838 → 834ms</td><td>3,800 → 960ms</td></tr>
 *   <tr><td>正規表現</td><td>3,776 → 1,128ms</td><td>4,054 → 1,014ms</td><td>4,028 → 1,021ms</td></tr>
 * </table>
 * 代わりに、1 行だけ読む詳細表示は 1 回の読み出しが大きくなるぶん遅くなる
 * （52 → 74µs / 176 → 206µs / 162 → 176µs。応答全体から見れば無視できる差）。
 */
public final class LineReader implements Closeable {

    /** 窓の合計の目安。ファイルが多いときは 1 ファイルあたりの窓を小さくする。 */
    static final int TOTAL_WINDOW_BYTES = 4 << 20;
    /** 1 ファイルあたりの窓の上限。 */
    static final int MAX_WINDOW_BYTES = 1 << 16;
    /** 1 ファイルあたりの窓の下限（変更前の読み出し単位と同じ）。 */
    static final int MIN_WINDOW_BYTES = 8192;

    private final List<Path> paths;
    private final RandomAccessFile[] handles;
    private final int windowBytes;
    /** ファイルごとの窓（初めて読むときに確保する）。 */
    private final byte[][] windows;
    /** 窓の先頭のファイル上の位置。{@code -1} は窓が無効。 */
    private final long[] windowStarts;
    /** 窓に読めたバイト数（ファイル末尾なら窓より短い）。 */
    private final int[] windowLens;

    public LineReader(List<Path> paths) {
        this.paths = paths;
        int n = paths.size();
        this.handles = new RandomAccessFile[n];
        this.windowBytes = windowBytes(n);
        this.windows = new byte[n][];
        this.windowStarts = new long[n];
        this.windowLens = new int[n];
        Arrays.fill(windowStarts, -1L);
    }

    /** ファイル数に応じた 1 ファイルあたりの窓の大きさ。 */
    static int windowBytes(int fileCount) {
        int each = TOTAL_WINDOW_BYTES / Math.max(1, fileCount);
        return Math.max(MIN_WINDOW_BYTES, Math.min(MAX_WINDOW_BYTES, each));
    }

    /** 指定ファイルの指定オフセットから 1 行を読み出す（末尾の改行は除去）。 */
    public String read(int fileId, long byteOffset) throws IOException {
        int nl = lineEndInWindow(fileId, byteOffset);
        if (nl < 0) {
            fill(fileId, byteOffset);
            nl = lineEndInWindow(fileId, byteOffset);
        }
        byte[] window = windows[fileId];
        int from = (int) (byteOffset - windowStarts[fileId]);
        if (nl >= 0) {
            return decode(window, from, nl - from);
        }
        if (windowLens[fileId] < window.length) {
            // 窓がファイル末尾まで届いていて改行がない = 改行で終わらない最終行
            return decode(window, from, windowLens[fileId] - from);
        }
        // 窓より長い行。まれなので窓を使わずに読む
        return readLongLine(fileId, byteOffset);
    }

    /**
     * {@code byteOffset} から始まる行の改行の位置（窓の中の添字）。
     * 窓の外の位置か、窓の中に改行がなければ {@code -1}。
     */
    private int lineEndInWindow(int fileId, long byteOffset) {
        long start = windowStarts[fileId];
        int len = windowLens[fileId];
        if (start < 0 || byteOffset < start || byteOffset >= start + len) {
            return -1;
        }
        byte[] window = windows[fileId];
        for (int i = (int) (byteOffset - start); i < len; i++) {
            if (window[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** {@code byteOffset} から窓を読み直す。 */
    private void fill(int fileId, long byteOffset) throws IOException {
        byte[] window = windows[fileId];
        if (window == null) {
            window = new byte[windowBytes];
            windows[fileId] = window;
        }
        RandomAccessFile raf = handle(fileId);
        raf.seek(byteOffset);
        int filled = 0;
        while (filled < window.length) {
            int n = raf.read(window, filled, window.length - filled);
            if (n < 0) {
                break;
            }
            filled += n;
        }
        windowStarts[fileId] = byteOffset;
        windowLens[fileId] = filled;
    }

    /** 窓に収まらない行を、改行かファイル末尾まで読む。 */
    private String readLongLine(int fileId, long byteOffset) throws IOException {
        RandomAccessFile raf = handle(fileId);
        raf.seek(byteOffset);
        byte[] chunk = new byte[MIN_WINDOW_BYTES];
        byte[] out = new byte[windowBytes * 2];
        int len = 0;
        while (true) {
            int n = raf.read(chunk);
            if (n <= 0) {
                break;
            }
            for (int i = 0; i < n; i++) {
                byte b = chunk[i];
                if (b == '\n') {
                    return decode(out, 0, len);
                }
                if (len == out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                out[len++] = b;
            }
        }
        return decode(out, 0, len);
    }

    private static String decode(byte[] buf, int from, int len) {
        while (len > 0 && (buf[from + len - 1] == '\r' || buf[from + len - 1] == '\n')) {
            len--;
        }
        return new String(buf, from, len, StandardCharsets.UTF_8);
    }

    private RandomAccessFile handle(int fileId) throws IOException {
        RandomAccessFile raf = handles[fileId];
        if (raf == null) {
            raf = new RandomAccessFile(paths.get(fileId).toFile(), "r");
            handles[fileId] = raf;
        }
        return raf;
    }

    @Override
    public void close() {
        for (RandomAccessFile raf : handles) {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // クローズ失敗は無視
                }
            }
        }
        Arrays.fill(handles, null);
        Arrays.fill(windows, null);
        Arrays.fill(windowStarts, -1L);
    }
}

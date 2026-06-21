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
 * <p>ファイルごとに {@link RandomAccessFile} ハンドルをキャッシュして再利用する。
 * 1 行は短いため、まとめ読みバッファ 1 回でほぼ取得できる。
 */
public final class LineReader implements Closeable {

    private static final int CHUNK = 8192;

    private final List<Path> paths;
    private final RandomAccessFile[] handles;
    private final byte[] chunk = new byte[CHUNK];

    public LineReader(List<Path> paths) {
        this.paths = paths;
        this.handles = new RandomAccessFile[paths.size()];
    }

    /** 指定ファイルの指定オフセットから 1 行を読み出す（末尾の改行は除去）。 */
    public String read(int fileId, long byteOffset) throws IOException {
        RandomAccessFile raf = handle(fileId);
        raf.seek(byteOffset);
        byte[] out = new byte[256];
        int len = 0;
        while (true) {
            int n = raf.read(chunk);
            if (n <= 0) {
                break;
            }
            for (int i = 0; i < n; i++) {
                byte b = chunk[i];
                if (b == '\n') {
                    return decode(out, len);
                }
                if (len == out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                out[len++] = b;
            }
        }
        return decode(out, len);
    }

    private static String decode(byte[] out, int len) {
        while (len > 0 && (out[len - 1] == '\r' || out[len - 1] == '\n')) {
            len--;
        }
        return new String(out, 0, len, StandardCharsets.UTF_8);
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
    }
}

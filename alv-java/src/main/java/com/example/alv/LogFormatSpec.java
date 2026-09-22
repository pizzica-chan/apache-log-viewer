package com.example.alv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1 回の取り込みで使う書式。組み込み書式（{@link LogFormat}）か、利用者が定義した
 * 書式（{@link CustomLogFormat}）のどちらか一方を指す。
 *
 * <p>取り込み開始時に 1 つへ確定させる設計は変えていない。1 行あたりに走る判定は
 * 確定した 1 書式ぶんだけで、書式を増やしても 1 行あたりの処理は増えない。
 * 利用者定義の書式を足しても、<strong>組み込み書式の解析経路は変わらない</strong>。
 */
public final class LogFormatSpec {

    /** 既定書式。書式を指定しないときの出発点。 */
    public static final LogFormatSpec DEFAULT = new LogFormatSpec(LogFormat.COMBINED, null);

    private final LogFormat builtin;
    private final CustomLogFormat custom;

    private LogFormatSpec(LogFormat builtin, CustomLogFormat custom) {
        this.builtin = builtin;
        this.custom = custom;
    }

    public static LogFormatSpec of(LogFormat builtin) {
        if (builtin == null) {
            throw new IllegalArgumentException("builtin");
        }
        return new LogFormatSpec(builtin, null);
    }

    public static LogFormatSpec of(CustomLogFormat custom) {
        if (custom == null) {
            throw new IllegalArgumentException("custom");
        }
        return new LogFormatSpec(null, custom);
    }

    /** 組み込みなら {@link LogFormat}、利用者定義なら {@code null}。 */
    LogFormat builtin() {
        return builtin;
    }

    /** 利用者定義なら {@link CustomLogFormat}、組み込みなら {@code null}。 */
    CustomLogFormat custom() {
        return custom;
    }

    public boolean isCustom() {
        return custom != null;
    }

    public String id() {
        return builtin != null ? builtin.id() : custom.id();
    }

    public String displayName() {
        return builtin != null ? builtin.displayName() : custom.displayName();
    }

    /**
     * id から引く。組み込みを先に探し、なければ利用者定義から探す。
     * 未知の id は {@code null}。
     */
    public static LogFormatSpec byId(String id, List<CustomLogFormat> customs) {
        if (id == null) {
            return null;
        }
        LogFormat builtin = LogFormat.byId(id);
        if (builtin != null) {
            return of(builtin);
        }
        for (CustomLogFormat c : customs != null ? customs : Collections.<CustomLogFormat>emptyList()) {
            if (c.id().equals(id)) {
                return of(c);
            }
        }
        return null;
    }

    /**
     * 先頭ファイルの冒頭を読み、最もよく一致する書式を返す。
     *
     * <p><strong>組み込みの選び方には手を入れない。</strong>{@link LogFormat#detect} は
     * combined と nginx main が同じ行を両方とも解析できることを踏まえた専用の規則を
     * 持っている（末尾の XFF が取れた行の割合で決める）。ここで候補を横並びにすると
     * その規則が壊れるので、<strong>まず組み込みで 1 つ選び、その得点と利用者定義の
     * 得点を比べる</strong>という二段にしている。
     *
     * <p>同数のときは組み込みを選ぶ（利用者定義が既定書式のログを横取りしないように）。
     *
     * <p>見るのは {@link LogFormat#DETECT_SAMPLE_LINES} 行までで、書式の数に比例して
     * 増えるのはこの判定だけ。取り込み本体は確定した 1 書式ぶんしか走らない。
     *
     * @param paths   取り込む対象。先頭の 1 つだけを見る（同時取り込みは同一書式の前提）
     * @param customs 利用者定義の書式。{@code null} なら組み込みだけで判定する
     */
    public static LogFormatSpec detect(List<Path> paths, List<CustomLogFormat> customs) {
        if (paths == null || paths.isEmpty()) {
            return DEFAULT;
        }
        LogFormat builtin = LogFormat.detect(paths);
        if (customs == null || customs.isEmpty()) {
            return of(builtin);
        }
        List<CustomLogFormat> alive = new ArrayList<CustomLogFormat>(customs);
        int builtinHits = 0;
        int[] hits = new int[alive.size()];
        try (InputStream raw = Files.newInputStream(paths.get(0));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(raw, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            int seen = 0;
            while (seen < LogFormat.DETECT_SAMPLE_LINES && (line = reader.readLine()) != null) {
                seen++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (LogParser.parseLine(builtin, line, 0, 0, 0L) != null) {
                    builtinHits++;
                }
                for (int i = 0; i < alive.size(); i++) {
                    CustomLogFormat c = alive.get(i);
                    if (c == null) {
                        continue;
                    }
                    try {
                        if (c.parse(line, 0, 0, 0L) != null) {
                            hits[i]++;
                        }
                    } catch (CustomLogFormat.FormatFailure e) {
                        // 壊れた書式や暴走する正規表現で自動判定まで巻き添えにしない。
                        // ここで落ちると、書式ファイルを直すための画面すら開けなくなる。
                        // 明示的に選ばれたときは取り込みがはっきり失敗する。
                        alive.set(i, null);
                        System.err.println("書式 " + c.id() + " は自動判定から外しました: "
                                + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            return of(builtin);
        }
        LogFormatSpec best = of(builtin);
        int bestHits = builtinHits;
        for (int i = 0; i < alive.size(); i++) {
            if (alive.get(i) != null && hits[i] > bestHits) {
                best = of(alive.get(i));
                bestHits = hits[i];
            }
        }
        return best;
    }
}

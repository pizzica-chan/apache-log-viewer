# Apache Log Viewer (Java 8)

Apache アクセスログを Web UI で閲覧・検索する **Java 8 実装** です。

- **依存関係管理は Maven**（`pom.xml`）。Java 8 向けにコンパイラを `1.8` 指定。
- **Web サーバ機能は自前**（JDK 内蔵の `com.sun.net.httpserver` を利用。Tomcat 等のアプリケーションサーバ不要）。
- **JSON 入出力は Gson**。`maven-shade-plugin` で依存込みの実行可能 JAR を生成する。
- **SQLite は使用しない**。解析結果を全件メモリに保持する。

## パフォーマンス設計（低リスクな高速化）

| 項目 | 内容 |
|------|------|
| 並列パース | 複数ログファイルを CPU コア数まで**並列**に解析し、各ファイルの時系列リストを作成 |
| k-way マージ | `PriorityQueue` による時刻順マージで全体を統合（各ファイル内の順序は保持） |
| I/O | 1 MiB バッファでまとめ読みし、改行走査で行を切り出して **byte offset** を記録 |
| メモリ | 生ログ行は保持せず、詳細表示・全文検索（grep）時に byte offset から読み出し |
| 日時 | `ZonedDateTime` 等を使わず civil calendar 計算で epoch millis へ直接変換。整列・範囲フィルタも millis（数値）で実施 |
| 省メモリ | ステータスは `int`、メソッド文字列は intern、client と remote が同値なら参照を共有 |
| クエリ | 安価な条件（日時・ステータス・メソッド）を先に評価し、I/O を伴う grep は最後に評価。grep 指定時のみファイルを開く |
| 配信 | HTTP はスレッドプールで処理、静的ファイルはメモリキャッシュ |

## 前提

- **Docker で動作確認:** リポジトリ直下の [README.md](../README.md#docker-で動作確認ローカル) を参照。`docker-up.bat` をダブルクリックするだけ（ホストに JDK/Maven 不要）
- **ローカル開発:** JDK 8 以上（`javac` を含む JDK）、Maven 3.6 以上

## ビルド

```powershell
cd D:\workspace\apache-log-viewer\alv-java
mvn -q clean package
```

依存（Gson）を同梱した実行可能 JAR `target/alv-java.jar` が生成されます。

## 起動

```powershell
java -jar target\alv-java.jar --dir ..\samples
# または
java -jar alv-java.jar --dir C:\logs\apache --port 8769
```

ブラウザ: http://127.0.0.1:8769

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--dir`  | 起動時に読み込むログディレクトリ（省略時は UI から選択） | — |
| `--host` | 待ち受けアドレス | `127.0.0.1` |
| `--port` | 待ち受けポート | `8769` |

## HTTP API

- `GET /` — Web UI
- `GET /api/meta` — 読み込み状態・件数・期間
- `GET /api/browse?path=` — ディレクトリ一覧
- `POST /api/load` — `{"directory": "..."}` を受け取り読み込み開始
- `GET /api/logs?status=&path=&method=&host=&grep=&source=&since=&until=&limit=&offset=` — フィルタ付き一覧
- `GET /api/logs/detail?source=&line_no=&timestamp=` — 生ログ行

## 対応ログ形式

Apache Common / Combined Log Format。VirtualHost プレフィックスと X-Forwarded-For 付きにも対応。

```
127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] "GET /index.html HTTP/1.1" 200 4523
203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] "GET /api/users HTTP/1.1" 200 1024 "-" "Mozilla/5.0"
```

- `%h %l %u %t "%r" %>s %b`（Combined の referer / user-agent は無視）
- 先頭に `X-Forwarded-For` がある場合、`leading` を XFF + remote host に分離（client は XFF 先頭 IP）
- 解析できない行はスキップ（エラーにしない）

## テスト

```powershell
mvn test
```

（JUnit 5 を使用。サンプルログ `..\samples` を参照します）

## ライセンス

MIT

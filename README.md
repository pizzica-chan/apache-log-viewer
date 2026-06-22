# Apache Log Viewer (alv)

Apache HTTP Server のアクセスログ（複数ファイル）を **Web UI** で閲覧・検索する障害調査用ツールです。

指定ディレクトリ配下のログファイルを **再帰的に探索** し、複数ファイルを **時系列順に統合** して表示します。

**Java 8 実装**（`alv-java/`）。Maven でビルドし、JDK 内蔵 HTTP サーバで Web UI を提供します。

## 機能

- ディレクトリを UI から選択し、配下のログファイルを再帰的に読み込み
- 複数ファイルを時系列ソートして一覧表示
- 各ログ行に **ソースファイルのフルパス** と行番号を保持
- ステータス / パス / メソッド / クライアント IP / 日時 / 全文 / ソースファイルでのフィルタ
- 行クリックで生ログとファイル情報を表示
- ページング対応

## 前提

- JDK 8 以上（`javac` を含む JDK）
- Maven 3.6 以上

## ビルド

```powershell
cd D:\workspace\apache-log-viewer\alv-java
mvn -q clean package
```

依存（Gson）を同梱した実行可能 JAR `target/alv-java.jar` が生成されます。

## 起動

```powershell
java -jar alv-java\target\alv-java.jar
```

ブラウザで http://127.0.0.1:8769 を開きます。

起動時にログディレクトリを指定する場合:

```powershell
java -jar alv-java\target\alv-java.jar --dir C:\logs\apache
java -jar alv-java\target\alv-java.jar --dir samples --port 8769
```

Windows では `start.bat` からも起動できます（JAR が無ければ自動ビルド）。

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--dir` | 起動時に読み込むログディレクトリ（省略時は UI から選択） | — |
| `--host` | 待ち受けアドレス | `127.0.0.1` |
| `--port` | 待ち受けポート | `8769` |

## Web UI の使い方

1. 画面上部の **ログディレクトリ** にパスを入力するか、**参照...** でディレクトリを選択
2. **読み込み** をクリックすると、配下のログファイルが再帰的に探索・読み込まれます
3. フィルタを設定して **検索**（Enter キーでも可）
4. 行をクリックすると、ソースファイル・行番号・生ログを表示

### 読み込み対象のログファイル

以下のファイル名パターンに一致するファイルを再帰的に探索します。

- `access_log*`, `error_log*`
- `access.log*`, `error.log*`
- `*.log`

`.git` や `node_modules` などのディレクトリはスキップします。圧縮ファイル（`.gz` 等）は未対応です。

### フィルタ

| 項目 | 説明 | 例 |
|------|------|-----|
| ステータス | ステータスコード | `500` / `4xx` / `500,502` |
| パス | リクエストパス（正規表現） | `/api/` |
| メソッド | HTTP メソッド | `GET` |
| クライアント IP | X-Forwarded-For を含む IP 検索（正規表現） | `192.168` |
| 開始 / 終了 | 日時範囲 | 日時ピッカー |
| 全文検索 | 生ログ行への正規表現 | `error` |
| ソースファイル | ファイルパスへの正規表現 | `access_log` |

### 表示列

| 列 | 説明 |
|----|------|
| Client | 実クライアント IP（X-Forwarded-For 先頭、なければ接続元 IP） |
| Remote | 接続元 IP（ロードバランサ等の `%h`） |
| Source | ソースファイルのフルパスと行番号 |

## 対応ログ形式

### Common / Combined / VirtualHost 付き

```
127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] "GET /apache_pb.gif HTTP/1.0" 200 2326
127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] "GET /apache_pb.gif HTTP/1.0" 200 2326 "http://example.com/" "Mozilla/5.0"
example.com:80 127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] "GET / HTTP/1.0" 200 2326
```

### X-Forwarded-For 付き Combined

LogFormat `%{X-Forwarded-For}i %h %l %u %t "%r" %>s %b "%{Referer}i" "%{User-Agent}i"` に対応しています。

```
203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] "GET /api/users HTTP/1.1" 200 1024 "-" "Mozilla/5.0"
203.0.113.51, 198.51.100.10 10.0.0.5 - - [20/Jun/2025:07:56:30 +0900] "POST /api/login HTTP/1.1" 401 128 "-" "curl/8.0"
```

解析できない行はスキップされます（エラーにはしません）。

## 障害調査の例

```powershell
# Web UI を起動してログディレクトリを指定
java -jar alv-java\target\alv-java.jar --dir C:\logs\apache

# ブラウザで以下のような調査を行う
# - ステータス: 5xx
# - 開始 / 終了: 障害時間帯を指定
# - パス: /api/
# - ソースファイル: access_log で特定ホストのログに絞り込み
```

## テスト

```powershell
cd alv-java
mvn test
```

## 詳細

パフォーマンス設計や API 仕様の詳細は [alv-java/README.md](alv-java/README.md) を参照してください。

## ライセンス

MIT

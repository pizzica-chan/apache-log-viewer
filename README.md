# Apache Log Viewer (alv)

Apache HTTP Server のアクセスログ（複数ファイル）を **Web UI** で閲覧・検索する障害調査用ツールです。

指定ディレクトリ配下のログファイルを **再帰的に探索** し、複数ファイルを **時系列順に統合** して表示します。

**Java 8 実装**（`alv-java/`）。Maven でビルドし、JDK 内蔵 HTTP サーバで Web UI を提供します。

## 機能

- ディレクトリを UI から選択し、配下のログファイルを再帰的に読み込み
- 複数ファイルを時系列ソートして一覧表示
- 各ログ行に **ログファイルのフルパス** と行番号を保持
- ステータス / パス / メソッド / クライアント IP / 日時 / 全文 / ログファイルでのフィルタ
- 行クリックで生ログとファイル情報を表示
- ページング対応

## 前提

- **Docker で動作確認（おすすめ）:** [Docker Desktop](https://www.docker.com/products/docker-desktop/)（ホストに JDK/Maven 不要）
- **ローカル開発:** JDK 8 以上（`javac` を含む JDK）、Maven 3.6 以上

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
| `--format` | ログ書式を固定する（`auto` / `combined` / `nginx` / `minimal`） | `auto`（自動判定） |

## Docker で動作確認（ローカル）

JDK/Maven をインストールせず、コンテナだけでサンプルログの閲覧まで試せます。

### ワンクリック起動（おすすめ）

1. [Docker Desktop](https://www.docker.com/products/docker-desktop/) をインストールする。
2. リポジトリ直下の **`docker-up.bat`** をダブルクリック（または `scripts\one-click-up.cmd`）。

Docker Desktop が止まっていれば **自動起動・待機**（最大約 3 分）→ **ビルド** → **起動** → **ブラウザで http://localhost:8769 を開く** まで一気に実行されます。`samples/` のログは自動読み込みされます。

| 操作 | コマンド |
|------|----------|
| 起動 | `docker-up.bat` |
| 停止 | `docker-down.bat` |
| ソース変更の反映 | `scripts\one-click-restart.cmd` |
| ログ追従 | `.\scripts\one-click-up.ps1 -FollowLogs` |

### 手動（docker compose のみ）

**Docker Desktop を先に起動**してから、リポジトリ直下で実行します。

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f app
docker compose down
```

### マウントと環境変数

| 項目 | 説明 |
|------|------|
| `./samples` → `/app/logs/samples` | サンプルログ（読み取り専用）。起動時に `--dir` で自動読み込み |
| `ALV_LOG_DIR` | ホスト側のログディレクトリを差し替え（例: `$env:ALV_LOG_DIR="C:\logs\apache"`） |

**ホストポート:** 既定は **`127.0.0.1:8769`**（ループバックのみ）です。競合する場合は `docker-compose.yml` の `ports` を `"127.0.0.1:18769:8769"` のように変更し、ブラウザも合わせてください。

**注意:** ローカル検証専用です。認証機能は無く、`/api/browse`・`/api/load` で任意のディレクトリを参照できるため、`ports` のバインドアドレスを `127.0.0.1` から広げないでください。

## Web UI の使い方

1. 画面上部の **ログディレクトリ** にパスを入力するか、**参照...** でディレクトリを選択
2. **読み込み** をクリックすると、配下のログファイルが再帰的に探索・読み込まれます
3. フィルタを設定して **検索**（Enter キーでも可）
4. 行をクリックすると、ログファイル・行番号・生ログを表示

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
| 開始 / 終了 | 日時範囲（ログに記録された現地時刻で解釈） | 日時ピッカー |
| 全文検索 | 生ログ行への正規表現 | `error` |
| ログファイル | 絶対パスへの正規表現 | `access_log` |

### 期間指定とタイムゾーン

期間（開始 / 終了）は **ログに記録された現地時刻（壁時計）** として解釈します。一覧の「時刻」列に表示されている数字をそのまま入力できます。

```
2000-10-10T13:55:36-07:00  ← この行を探すなら
開始: 2000-10-10 13:54 / 終了: 2000-10-10 13:56  ← 表示どおりに入力
```

閲覧環境やサーバのタイムゾーン設定には依存しません。タイムゾーン付きの指定（`2025-06-20 08:00:00+09:00` 等）は、壁時計として扱う以上意味を持たないためエラーになります。

**タイムゾーンが混在するログを読み込んだ場合**、期間指定は各ログ行の現地時刻と突き合わせます。たとえば `+0900` と `-0700` のログを同時に読み込んで `08:00〜09:00` を指定すると、両拠点の 8 時台（実時間では 16 時間離れた 2 つの塊）がヒットします。並び順は常に実時間順のままです。

### 表示列

| 列 | 説明 |
|----|------|
| Client | 実クライアント IP（X-Forwarded-For 先頭、なければ接続元 IP） |
| Remote | 接続元 IP（ロードバランサ等の `%h`） |
| ログファイル | 親フォルダ + ファイル名と行番号 |

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

### nginx

nginx の既定 `combined` は Apache と並びが同じため、そのまま読めます。
`main` 形式（末尾に `"$http_x_forwarded_for"` が付く並び）は書式 `nginx` を選ぶと、
**末尾の X-Forwarded-For を実クライアント**として Client 列に出します。
既定書式のままだと末尾を読まないため、プロキシ経由では Client にプロキシの IP が出ます。

```
10.0.0.5 - - [20/Jun/2025:08:01:12 +0900] "GET /api/users HTTP/1.1" 200 1024 "-" "Mozilla/5.0" "203.0.113.50"
```

### ident / authuser が無い構成

`%h %t "%r" %>s %b` のように ident と authuser を出力しない構成は、書式 `minimal` で読めます。

```
127.0.0.1 [20/Jun/2025:08:01:12 +0900] "GET /index.html HTTP/1.1" 200 4523
```

### タイムスタンプ

Apache の `dd/MMM/yyyy:HH:mm:ss ±HHMM` に加え、ISO8601（nginx の `$time_iso8601` や
Apache の `%{%Y-%m-%dT%H:%M:%S%z}t` など）も書式を問わず読めます。

### 書式の決め方

既定は自動判定で、先頭ファイルの冒頭 500 行をサンプリングします。`nginx` は `combined` の
部分集合にあたるため、末尾に X-Forwarded-For を持つ行が過半数のときだけ選びます。
判定結果は画面の概要行に出ます。外れた場合はログディレクトリ欄のセレクトで
明示指定できます（CLI は `--format <id>`）。同時に読み込むファイルはすべて
同じ書式である前提です。

解析できない行はスキップされます（エラーにはしません）。
IIS の W3C 拡張ログ、HAProxy のログ、Apache の error_log には対応していません。

## 障害調査の例

```powershell
# Web UI を起動してログディレクトリを指定
java -jar alv-java\target\alv-java.jar --dir C:\logs\apache

# ブラウザで以下のような調査を行う
# - ステータス: 5xx
# - 開始 / 終了: 障害時間帯を指定
# - パス: /api/
# - ログファイル: access_log で特定ホストのログに絞り込み
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

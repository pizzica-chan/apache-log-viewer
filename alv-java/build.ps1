# Maven が無くても JDK だけでビルドする補助スクリプト（Windows / PowerShell）。
# 外部依存が無いため javac と jar のみでビルドできる。
#
# 使い方:
#   .\build.ps1            # ビルドして alv-java.jar を生成
#   .\build.ps1 -Run       # ビルド後に ..\samples を読み込んで起動
#   .\build.ps1 -Run -Dir C:\logs\apache -Port 8769

param(
    [switch]$Run,
    [string]$Dir = "..\samples",
    [string]$ServerHost = "127.0.0.1",
    [int]$Port = 8769
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$srcDir = Join-Path $root "src\main\java"
$resDir = Join-Path $root "src\main\resources"
$outDir = Join-Path $root "out"
$classesDir = Join-Path $outDir "classes"
$jarPath = Join-Path $root "alv-java.jar"

Write-Host "[1/4] 出力ディレクトリを準備します..."
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Path $classesDir | Out-Null

Write-Host "[2/4] Java ソースをコンパイルします (target 1.8)..."
$sources = Get-ChildItem -Path $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 1.8 -target 1.8 -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw "コンパイルに失敗しました" }

Write-Host "[3/4] 静的リソースを取り込みます..."
Copy-Item -Path (Join-Path $resDir "*") -Destination $classesDir -Recurse -Force

Write-Host "[4/4] 実行可能 JAR を作成します..."
& jar cfe $jarPath com.example.alv.Main -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "JAR 作成に失敗しました" }
Write-Host "完成: $jarPath"

if ($Run) {
    Write-Host "起動します: http://${ServerHost}:${Port}"
    & java -jar $jarPath --dir $Dir --host $ServerHost --port $Port
}

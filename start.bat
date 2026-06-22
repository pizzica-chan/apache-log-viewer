@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
    echo Java が見つかりません。JDK 8 以上をインストールし、PATH に追加してください。
    pause
    exit /b 1
)

set "HOST=127.0.0.1"
set "PORT=8769"
set "JAR=alv-java\target\alv-java.jar"

if not exist "%JAR%" (
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo Maven が見つかりません。JAR をビルドするには Maven 3.6 以上が必要です。
        echo   cd alv-java ^&^& mvn -q clean package
        pause
        exit /b 1
    )
    echo JAR が見つからないためビルドしています...
    pushd alv-java
    mvn -q clean package
    if errorlevel 1 (
        popd
        echo ビルドに失敗しました。
        pause
        exit /b 1
    )
    popd
)

echo Apache Log Viewer を起動しています...
echo ブラウザ: http://%HOST%:%PORT%
echo 停止: Ctrl+C
echo.

start "" "http://%HOST%:%PORT%"
java -jar "%JAR%" --host %HOST% --port %PORT% %*

if errorlevel 1 (
    echo.
    echo 起動に失敗しました。
    pause
)

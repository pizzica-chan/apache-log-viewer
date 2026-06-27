@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "EXIT_CODE=0"

where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven が見つかりません。Maven 3.6 以上をインストールし、PATH に追加してください。
    set "EXIT_CODE=1"
    goto :end
)

where java >nul 2>&1
if errorlevel 1 (
    echo Java が見つかりません。JDK 8 以上をインストールし、PATH に追加してください。
    set "EXIT_CODE=1"
    goto :end
)

echo テストを実行しています...
echo.

pushd alv-java
call mvn test %*
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo.
    echo テストに失敗しました。
) else (
    echo.
    echo すべてのテストが成功しました。
)

:end
echo.
pause
exit /b %EXIT_CODE%

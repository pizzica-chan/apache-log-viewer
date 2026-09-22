<#
.SYNOPSIS
    単一ファイルで bind mount する設定ファイルを先に用意する。

.DESCRIPTION
    docker-compose.yml は alv-log-formats.txt を単一ファイルとして bind mount する。
    ホスト側にファイルが無いと Docker が同名のディレクトリを作ってしまい、以後アプリが
    「通常ファイルではありません」で失敗し続ける。起動前に空のファイルを置く。
#>

function New-EmptyFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Body
    )

    if (Test-Path -LiteralPath $Path -PathType Container) {
        throw "$Path がディレクトリになっています（Docker が作った可能性があります）。削除してから実行してください。"
    }
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        return
    }
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $Body, $utf8)
}

function Initialize-LogFormatsFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RepoRoot
    )

    New-EmptyFile `
        -Path (Join-Path $RepoRoot "alv-log-formats.txt") `
        -Body "# alv の利用者定義ログ書式（画面の「書式の管理」から編集できます）`r`n"
}

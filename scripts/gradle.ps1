param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'
$gradleVersion = '9.5.1'
$gradleSha256 = 'bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f'
$gradleMirror = "https://mirrors.cloud.tencent.com/gradle/gradle-$gradleVersion-bin.zip"
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$toolchainRoot = Join-Path $env:LOCALAPPDATA 'ShiftCatcher\toolchains'
$distributionRoot = Join-Path $toolchainRoot "gradle-$gradleVersion"
$gradleBat = Join-Path $distributionRoot "gradle-$gradleVersion\bin\gradle.bat"

if (-not (Test-Path -LiteralPath $gradleBat -PathType Leaf)) {
    New-Item -ItemType Directory -Path $distributionRoot -Force | Out-Null
    $archive = Join-Path $distributionRoot "gradle-$gradleVersion-bin.zip"
    & curl.exe -L --fail --retry 3 --connect-timeout 30 --output $archive $gradleMirror
    if ($LASTEXITCODE -ne 0) {
        throw "Could not download Gradle $gradleVersion from the checksum-pinned fallback mirror."
    }

    $actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $gradleSha256) {
        Remove-Item -LiteralPath $archive -Force
        throw "Gradle distribution checksum mismatch. The downloaded archive was removed."
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $distributionRoot -Force
}

$buildPath = $repository
if ($repository -notmatch '^[\x00-\x7F]+$') {
    $pathBytes = [System.Text.Encoding]::UTF8.GetBytes($repository)
    $pathHash = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($pathBytes)).Substring(0, 12).ToLowerInvariant()
    $junctionRoot = Join-Path $env:LOCALAPPDATA 'ShiftCatcher\build-paths'
    $buildPath = Join-Path $junctionRoot $pathHash
    New-Item -ItemType Directory -Path $junctionRoot -Force | Out-Null
    if (-not (Test-Path -LiteralPath $buildPath)) {
        New-Item -ItemType Junction -Path $buildPath -Target $repository | Out-Null
    }
}

if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @('verify', '--no-daemon')
}

Push-Location -LiteralPath $buildPath
try {
    & $gradleBat @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}


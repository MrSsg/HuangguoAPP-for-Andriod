param(
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$key = Join-Path $env:USERPROFILE '.android\huangguo-release.jks'
$passwordFile = Join-Path $env:LOCALAPPDATA 'HuangGuoSigning\release-password.txt'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1

if (-not (Test-Path -LiteralPath $key) -or -not (Test-Path -LiteralPath $passwordFile)) {
    throw '缺少发布密钥或密码文件，不能生成可更新的正式包。'
}
if (-not $buildTools) { throw '未找到 Android SDK Build Tools。' }

$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$releaseDir = Join-Path $project 'app\build\outputs\apk\release'
$unsigned = Join-Path $releaseDir 'app-release-unsigned.apk'
$aligned = Join-Path $releaseDir 'app-release-aligned.apk'
$password = [IO.File]::ReadAllText($passwordFile, [Text.Encoding]::UTF8).Trim()
$env:HG_RELEASE_PASSWORD = $password

try {
    Push-Location $project
    try {
        $arguments = @(':app:assembleRelease', '--no-daemon')
        if ($Offline) { $arguments += '--offline' }
        & (Join-Path $project 'gradlew.bat') @arguments
        if ($LASTEXITCODE -ne 0) { throw 'Release 构建失败。' }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $unsigned)) { throw '未找到 Gradle 生成的未签名 APK。' }
    $metadata = Get-Content -LiteralPath (Join-Path $releaseDir 'output-metadata.json') -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $signed = Join-Path $releaseDir "HuangGuo-Android-v$version-release.apk"

    & $zipalign -f -p 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败。' }
    & $apksigner sign --ks $key --ks-type PKCS12 --ks-key-alias huangguo `
        --ks-pass env:HG_RELEASE_PASSWORD --key-pass env:HG_RELEASE_PASSWORD `
        --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名失败。' }
    & $apksigner verify --verbose --print-certs $signed
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名验证失败。' }
    & $zipalign -c -p 4 $signed
    if ($LASTEXITCODE -ne 0) { throw 'APK 对齐验证失败。' }
    $manifestPath = Join-Path $releaseDir 'android-update.json'
    $manifest = [ordered]@{
        versionCode = [int]$metadata.elements[0].versionCode
        versionName = $version
        tag = "v$version"
        apk = [IO.Path]::GetFileName($signed)
        size = (Get-Item -LiteralPath $signed).Length
        sha256 = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash.ToLowerInvariant()
        notes = ''
    }
    $manifest | ConvertTo-Json -Compress | Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output "正式包：$signed"
    Write-Output "更新清单：$manifestPath"
} finally {
    Remove-Item Env:HG_RELEASE_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned }
}

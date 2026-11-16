$ErrorActionPreference = "Stop"

$RootDir = $PSScriptRoot
$ToolsDir = Join-Path $RootDir ".build-tools"
$JdkDir = Join-Path $ToolsDir "jdk"
$SdkDir = Join-Path $ToolsDir "android-sdk"
$BuildDir = Join-Path $RootDir "build"
$DistDir = Join-Path $RootDir "dist"

$AppMainDir = Join-Path $RootDir "app\src\main"
$ManifestPath = Join-Path $AppMainDir "AndroidManifest.xml"
$ResDir = Join-Path $AppMainDir "res"
$JavaSrcDir = Join-Path $AppMainDir "java"

New-Item -ItemType Directory -Force -Path $ToolsDir, $BuildDir, $DistDir | Out-Null

function Ensure-FileDownload($Url, $OutPath) {
    if (Test-Path $OutPath) {
        return
    }
    Write-Host "Downloading $Url ..."
    & curl.exe -fL --retry 3 -o $OutPath $Url
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to download $Url"
    }
}

# 1. Ensure Portable JDK 17
$JavacExe = Get-ChildItem -Path $JdkDir -Filter "javac.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $JavacExe) {
    $JdkZip = Join-Path $ToolsDir "jdk17.zip"
    Ensure-FileDownload "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip" $JdkZip
    Write-Host "Extracting JDK 17..."
    if (Test-Path $JdkDir) { Remove-Item -Recurse -Force $JdkDir }
    Expand-Archive -Path $JdkZip -DestinationPath $JdkDir -Force
    $JavacExe = Get-ChildItem -Path $JdkDir -Filter "javac.exe" -Recurse | Select-Object -First 1
}
if (-not $JavacExe) {
    throw "javac.exe not found under $JdkDir"
}
$JdkBin = $JavacExe.Directory.FullName
$JdkHome = $JavacExe.Directory.Parent.FullName
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkBin;$env:PATH"
Write-Host "Using JDK: $JdkHome"

# 2. Ensure Android SDK cmdline-tools, platform-34, build-tools;34.0.0
$SdkManagerBat = Join-Path $SdkDir "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SdkManagerBat)) {
    $CmdZip = Join-Path $ToolsDir "cmdline-tools.zip"
    Ensure-FileDownload "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" $CmdZip
    Write-Host "Extracting Android Command-line Tools..."
    $TempCmdExtract = Join-Path $ToolsDir "cmdline-extract"
    if (Test-Path $TempCmdExtract) { Remove-Item -Recurse -Force $TempCmdExtract }
    Expand-Archive -Path $CmdZip -DestinationPath $TempCmdExtract -Force
    $LatestDir = Join-Path $SdkDir "cmdline-tools\latest"
    New-Item -ItemType Directory -Force -Path (Split-Path $LatestDir) | Out-Null
    if (Test-Path $LatestDir) { Remove-Item -Recurse -Force $LatestDir }
    Move-Item -Path (Join-Path $TempCmdExtract "cmdline-tools") -Destination $LatestDir
    Remove-Item -Recurse -Force $TempCmdExtract
}

$PlatformJar = Join-Path $SdkDir "platforms\android-34\android.jar"
$BuildToolsDir = Join-Path $SdkDir "build-tools\34.0.0"
if (-not (Test-Path $PlatformJar) -or -not (Test-Path $BuildToolsDir)) {
    Write-Host "Installing Android SDK platform 34 and build-tools 34.0.0..."
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    1..15 | ForEach-Object { "y" } | & $SdkManagerBat --sdk_root="$SdkDir" --licenses | Out-Null
    & $SdkManagerBat --sdk_root="$SdkDir" "platforms;android-34" "build-tools;34.0.0"
    $sdkExit = $LASTEXITCODE
    $ErrorActionPreference = $prevPref
    if ($sdkExit -ne 0) {
        throw "sdkmanager failed with exit code $sdkExit"
    }
}

$Aapt2 = Join-Path $BuildToolsDir "aapt2.exe"
$D8Bat = Join-Path $BuildToolsDir "d8.bat"
$ZipAlign = Join-Path $BuildToolsDir "zipalign.exe"
$ApkSignerBat = Join-Path $BuildToolsDir "apksigner.bat"
$KeytoolExe = Join-Path $JdkBin "keytool.exe"
$JavaExe = Join-Path $JdkBin "java.exe"

# 3. Clean build intermediates
$GenDir = Join-Path $BuildDir "gen"
$ClassesDir = Join-Path $BuildDir "classes"
$DexDir = Join-Path $BuildDir "dex"
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $GenDir, $ClassesDir, $DexDir | Out-Null

# 4. Compile resources with aapt2
$CompiledResZip = Join-Path $BuildDir "compiled_res.zip"
Write-Host "Compiling resources..."
& $Aapt2 compile --dir $ResDir -o $CompiledResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 5. Link resources & generate R.java
$UnalignedApk = Join-Path $BuildDir "unaligned.apk"
Write-Host "Linking resources and manifest..."
& $Aapt2 link `
    -o $UnalignedApk `
    -I $PlatformJar `
    --manifest $ManifestPath `
    --java $GenDir `
    --min-sdk-version 26 `
    --target-sdk-version 30 `
    --auto-add-overlay `
    $CompiledResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 6. Compile Java sources
Write-Host "Compiling Java sources..."
$JavaFiles = @()
$JavaFiles += Get-ChildItem -Path $JavaSrcDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
$JavaFiles += Get-ChildItem -Path $GenDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }

& $JavacExe `
    --release 11 `
    -encoding UTF-8 `
    -classpath $PlatformJar `
    -d $ClassesDir `
    @JavaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 7. Convert .class files to classes.dex using d8
Write-Host "Converting bytecode to DEX..."
$ClassFiles = Get-ChildItem -Path $ClassesDir -Filter "*.class" -Recurse | ForEach-Object { $_.FullName }
& $D8Bat `
    --release `
    --min-api 26 `
    --lib $PlatformJar `
    --output $DexDir `
    @ClassFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$DexFile = Join-Path $DexDir "classes.dex"
if (-not (Test-Path $DexFile)) { throw "classes.dex not produced" }

# 8. Add classes.dex into unaligned.apk
Write-Host "Packaging classes.dex into APK..."
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::Open($UnalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $Existing = $Zip.GetEntry("classes.dex")
    if ($Existing) { $Existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $Zip,
        $DexFile,
        "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal
    ) | Out-Null
} finally {
    $Zip.Dispose()
}

# 9. Zipalign APK
$AlignedApk = Join-Path $BuildDir "aligned.apk"
Write-Host "Aligning APK..."
& $ZipAlign -f -p 4 $UnalignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# 10. Generate local signing keystore if needed & sign APK
$KeystorePath = Join-Path $ToolsDir "ottocast-release.keystore"
if (-not (Test-Path $KeystorePath)) {
    Write-Host "Generating local signing keystore..."
    & $KeytoolExe -genkeypair `
        -v `
        -keystore $KeystorePath `
        -alias ottocast_dimmer `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -storepass ottocast123 `
        -keypass ottocast123 `
        -dname "CN=OttocastDimmer, OU=CarDisplay, O=Local, C=KR"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$FinalApk = Join-Path $DistDir "OttocastDimmer.apk"
Write-Host "Signing APK -> $FinalApk"
& $ApkSignerBat sign `
    --ks $KeystorePath `
    --ks-key-alias ottocast_dimmer `
    --ks-pass pass:ottocast123 `
    --key-pass pass:ottocast123 `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $FinalApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Write-Host "Verifying APK signature..."
& $ApkSignerBat verify --min-sdk-version 21 --verbose $FinalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host "Dumping APK badging..."
& $Aapt2 dump badging $FinalApk | Select-Object -First 15

$ApkSizeKB = [math]::Round((Get-Item $FinalApk).Length / 1024, 1)
Write-Host ""
Write-Host "SUCCESS: Built $FinalApk ($ApkSizeKB KB)"

param(
    [Parameter(Mandatory = $true)]
    [string]$StarsectorPath,
    [string]$ApiJar,
    [string]$JdkPath,
    [string]$LunaLibPath,
    [string]$NexerelinJar,
    [string]$KotlincPath
)

$ErrorActionPreference = "Stop"
$modRoot = $PSScriptRoot
$buildPath = Join-Path $modRoot "build"
$classesPath = Join-Path $buildPath "classes"
$stagePath = Join-Path $buildPath "stage"
$baseJar = Join-Path $modRoot "lib\PortraitChanger-1.0.7-base.jar"
$sourcePath = Join-Path $modRoot "src\portrait_changer\PortraitChangerIntel.kt"
$jarPath = Join-Path $modRoot "jars\PortraitChanger.jar"

if ([string]::IsNullOrWhiteSpace($ApiJar)) {
    $ApiJar = Join-Path $StarsectorPath "starsector-core\starfarer.api.jar"
}
if ([string]::IsNullOrWhiteSpace($JdkPath)) {
    $jdk = Get-ChildItem -LiteralPath $StarsectorPath -Directory -Filter "jdk-*" | Select-Object -First 1
    if ($null -eq $jdk) { throw "No bundled JDK found in the Starsector folder." }
    $JdkPath = $jdk.FullName
}
if ([string]::IsNullOrWhiteSpace($LunaLibPath)) {
    $LunaLibPath = Join-Path $StarsectorPath "mods\LunaLib"
}
if ([string]::IsNullOrWhiteSpace($NexerelinJar)) {
    $NexerelinJar = Join-Path $StarsectorPath "mods\Nexerelin\jars\ExerelinCore.jar"
}

$javaExe = Join-Path $JdkPath "bin\java.exe"
$jarExe = Join-Path $JdkPath "bin\jar.exe"
$required = @($ApiJar, $javaExe, $jarExe, $baseJar, $sourcePath, $NexerelinJar)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file not found: $path" }
}
$lunaJars = @(Get-ChildItem -LiteralPath (Join-Path $LunaLibPath "jars") -File -Filter "*.jar")
if ($lunaJars.Count -eq 0) { throw "No LunaLib JARs found under $LunaLibPath\jars" }

New-Item -ItemType Directory -Force -Path $buildPath, (Split-Path $jarPath) | Out-Null
if ([string]::IsNullOrWhiteSpace($KotlincPath)) {
    $KotlincPath = Join-Path $buildPath "kotlinc\bin\kotlinc.bat"
    if (-not (Test-Path -LiteralPath $KotlincPath)) {
        $compilerZip = Join-Path $buildPath "kotlin-compiler.zip"
        Invoke-WebRequest "https://github.com/JetBrains/kotlin/releases/download/v2.2.0/kotlin-compiler-2.2.0.zip" -OutFile $compilerZip
        Expand-Archive -LiteralPath $compilerZip -DestinationPath $buildPath -Force
        Remove-Item -LiteralPath $compilerZip
    }
}
if (-not (Test-Path -LiteralPath $KotlincPath -PathType Leaf)) { throw "Kotlin compiler not found: $KotlincPath" }
$kotlinHome = Split-Path (Split-Path $KotlincPath)
$kotlinStdlib = Join-Path $kotlinHome "lib\kotlin-stdlib.jar"
if (-not (Test-Path -LiteralPath $kotlinStdlib -PathType Leaf)) { throw "Kotlin standard library not found: $kotlinStdlib" }

foreach ($path in @($classesPath, $stagePath)) {
    if (Test-Path -LiteralPath $path) { Remove-Item -Recurse -Force -LiteralPath $path }
    New-Item -ItemType Directory -Path $path | Out-Null
}

$env:JAVA_HOME = $JdkPath
$classpath = (@($baseJar, $ApiJar, $NexerelinJar, $kotlinStdlib) + $lunaJars.FullName) -join [IO.Path]::PathSeparator
& $javaExe -cp (Join-Path $kotlinHome "lib\*") org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    $sourcePath -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $classesPath
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

Push-Location $stagePath
try { & $jarExe xf $baseJar } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "Base JAR extraction failed." }
Get-ChildItem -LiteralPath (Join-Path $stagePath "portrait_changer") -File -Filter "PortraitChangerIntel*.class" | Remove-Item -Force
Copy-Item -Path (Join-Path $classesPath "*") -Destination $stagePath -Recurse -Force

& $jarExe --create --file $jarPath -C $stagePath .
if ($LASTEXITCODE -ne 0) { throw "JAR creation failed." }

$entries = & $jarExe tf $jarPath
$intelBytes = [Text.Encoding]::GetEncoding(28591).GetString(
    [IO.File]::ReadAllBytes((Join-Path $stagePath "portrait_changer\PortraitChangerIntel.class"))
)
if ($entries -notcontains "portrait_changer/PCModPlugin.class" -or
    $entries -notcontains "portrait_changer/PortraitChangerIntel.class" -or
    -not $intelBytes.Contains("Apply Gender") -or
    -not $intelBytes.Contains("Current Market NPC") -or
    -not $intelBytes.Contains("portraitChanger_currentMarketNpc")) {
    throw "JAR verification failed."
}

Write-Host "Built and verified $jarPath"

# Run unit tests directly via JUnitCore.
#
# WHY: `gradlew :app:testDebugUnitTest` fails in this AGP 9.x environment with
# "ClassNotFoundException: com.dingdinggo.app.*Test" even though the test classes
# ARE compiled cleanly. Running JUnitCore against the compiled test/main classes
# executes the full unit test suite deterministically.
#
# USAGE: powershell -ExecutionPolicy Bypass -File run-tests.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "[1/2] Compiling main + test sources..." -ForegroundColor Cyan
& .\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw "compilation failed" }

$cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"

function Find-Jar([string]$pattern) {
    $f = Get-ChildItem $cache -Recurse -Filter $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $f) { throw "jar not found: $pattern" }
    return $f.FullName
}

$junit    = Find-Jar "junit-4.13.2.jar"
$hamcrest = Find-Jar "hamcrest-core-1.3.jar"
$kotlinStd = (Get-ChildItem $cache -Recurse -Filter "kotlin-stdlib-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch "sources" } | Select-Object -First 1)
if (-not $kotlinStd) { throw "kotlin-stdlib not found" }

$testClasses = Join-Path $PSScriptRoot "app\build\intermediates\built_in_kotlinc\debugUnitTest\compileDebugUnitTestKotlin\classes"
$mainClasses = Join-Path $PSScriptRoot "app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes"

if (-not (Test-Path $testClasses)) { throw "test classes missing: $testClasses" }
if (-not (Test-Path $mainClasses)) { throw "main classes missing: $mainClasses" }

$cp = @($testClasses, $mainClasses, $junit, $hamcrest, $kotlinStd.FullName) -join ";"

$tests = @(
    "PunchScheduleTest", "DayLabelTest", "SkipDateTest", "CompletedSlotTest",
    "PunchWindowTest", "TooEarlyGateTest", "SafetyGateTest", "SchedulerGuardTest",
    "TargetDoneTest", "PunchResultJudgeTest", "UiHierarchyTest", "BtPunchRuleTest",
    "SchedulerSelfHealTest", "RetryTargetTest", "ScanWindowTest", "ScanRenewalWindowTransitionTest", "GuardGapTest",
    "ActualPunchTimeTest", "DingMascotMoodTest", "ControlHudCopyTest"
)

Write-Host ("[2/2] Running {0} test classes..." -f $tests.Count) -ForegroundColor Cyan
$failed  = 0
$totalRun = 0

foreach ($t in $tests) {
    $out = (& java -cp $cp org.junit.runner.JUnitCore "com.dingdinggo.app.$t" 2>&1 | Out-String)
    if ($out -match "OK \((\d+) test") {
        $totalRun += [int]$Matches[1]
        Write-Host ("  [PASS] {0} ({1} cases)" -f $t, $Matches[1]) -ForegroundColor Green
    }
    elseif ($out -match "Tests run: (\d+),\s+Failures: (\d+)") {
        $totalRun += [int]$Matches[1]
        Write-Host ("  [FAIL] {0}" -f $t) -ForegroundColor Red
        Write-Host $out
        $failed++
    }
    else {
        Write-Host ("  [ERR ] {0}" -f $t) -ForegroundColor Yellow
        Write-Host $out
        $failed++
    }
}

Write-Host ""
Write-Host ("Total cases run: {0}" -f $totalRun)
if ($failed -eq 0) {
    Write-Host "ALL TESTS PASSED" -ForegroundColor Green
    exit 0
}
else {
    Write-Host ("{0} test class(es) FAILED" -f $failed) -ForegroundColor Red
    exit 1
}
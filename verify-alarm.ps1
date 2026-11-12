# 快速验证：钉钉自动打卡 App 的闹钟与运行状态
#
# 用法：.\verify-alarm.ps1 [-Package <包名>]
# 默认包名是 debug 变体

param(
    [string]$Package = "com.redmi.autoattend.debug"
)

$ErrorActionPreference = "Continue"

# 自动查找 adb
$adb = $null
$candidates = @(
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "E:\dev\android-sdk\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    Write-Host "❌ 找不到 adb，请把 Android SDK 的 platform-tools 加进 PATH" -ForegroundColor Red
    exit 1
}

# 【必现 bug 修正】这里原来写的是 "device$"。
#
# adb 的输出是 **CRLF**，PowerShell 把整行读进来时行尾的 \r 会保留在字符串里，
# 于是行末尾的真实字符是 \r 而不是 \n —— $ 锚点永远对不上，
# Select-String 一条都匹配不到，脚本**每次都报告"没有已连接的设备"**，
# 哪怕手机就好端端插着。
#
# 用 \s*$ 吞掉行尾的 \r（也可以写成 -match 'device'，但那样会把
# "offline"/"unauthorized" 也匹配进来，反而掩盖"设备在但没授权"这种真问题）。
$devices = & $adb devices | Select-String "device\s*$"
if (-not $devices) {
    Write-Host "❌ 没有已连接的设备，请先连上手机或启动模拟器" -ForegroundColor Red
    exit 1
}

Write-Host "════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host " 目标包名: $Package" -ForegroundColor Cyan
Write-Host "════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# ── 1. 已注册的闹钟 ──
Write-Host "── 1. 已注册的闹钟 ─────────────────────────" -ForegroundColor Yellow
$alarms = & $adb shell dumpsys alarm 2>$null | Select-String -Pattern "autoattend"
if (-not $alarms) {
    Write-Host "❌ 没有找到任何闹钟！" -ForegroundColor Red
    Write-Host "   可能原因："
    Write-Host "   • 定时打卡开关被关闭"
    Write-Host "   • 自启动未开启，重启后闹钟丢失"
    Write-Host "   • App 从未启动过（首次启动才会注册）"
} else {
    $alarms | ForEach-Object { Write-Host $_ }
    Write-Host ""
    Write-Host "✅ 找到闹钟记录" -ForegroundColor Green
}
Write-Host ""

# ── 2. 进程是否存活 ──
Write-Host "── 2. 进程是否存活 ──────────────────────────" -ForegroundColor Yellow
# 【必现 bug 修正】变量原来叫 $pid。
#
# $PID 是 PowerShell 的**只读自动变量**（当前进程自己的 PID）。
# $ErrorActionPreference = "Continue" 之下，赋值失败只写一条错误就继续，
# 所以 $pid 永远是**脚本宿主 powershell.exe 自己的进程号**，
# 判断永远走 "正在运行" 分支，打印出的是无关的 PID ——
# 这项检查实际上从来没有检查过任何东西。
#
# 改名成 $appPid 后语义清楚，也不会再撞自动变量。
$appPid = (& $adb shell pidof $Package 2>$null | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($appPid)) {
    Write-Host "⚠️  App 进程未运行（这不一定是问题：闹钟靠静态 Receiver 触发）" -ForegroundColor Yellow
} else {
    Write-Host "✅ App 正在运行，PID = $appPid" -ForegroundColor Green
}
Write-Host ""

# ── 3. 无障碍服务 ──
Write-Host "── 3. 无障碍服务是否在线 ────────────────────" -ForegroundColor Yellow
$acc = (& $adb shell settings get secure enabled_accessibility_services 2>$null | Out-String)
if ($acc -match "autoattend") {
    Write-Host "✅ 无障碍服务已启用" -ForegroundColor Green
} else {
    Write-Host "⚠️  无障碍服务未启用，只能依赖 root 通道" -ForegroundColor Yellow
}
Write-Host ""

# ── 4. 权限状态 ──
Write-Host "── 4. 相关权限状态 ─────────────────────────" -ForegroundColor Yellow
& $adb shell dumpsys package $Package 2>$null |
    Select-String -Pattern "POST_NOTIFICATIONS|SCHEDULE_EXACT_ALARM|RECEIVE_BOOT_COMPLETED" |
    Select-Object -First 5 |
    ForEach-Object { Write-Host $_ }
Write-Host ""

# ── 5. 电池优化白名单 ──
Write-Host "── 5. 电池优化白名单 ───────────────────────" -ForegroundColor Yellow
$wl = & $adb shell dumpsys deviceidle whitelist 2>$null | Select-String -Pattern $Package
if (-not $wl) {
    Write-Host "⚠️  不在电池优化白名单里（精确闹钟会受限，后台也容易被清理）" -ForegroundColor Yellow
} else {
    Write-Host "✅ $wl" -ForegroundColor Green
}
Write-Host ""

# ── 6. 最近日志 ──
Write-Host "── 6. 最近执行日志 ─────────────────────────" -ForegroundColor Yellow
# tag 取自各类里的 private const val TAG，一一对应：
#   PunchEngine / PunchService / PunchAlarmReceiver —— 打卡链路本身
#   AlarmScheduler —— 闹钟注册/取消（"闹钟没排上"是最常见的故障点）
#   KeepAlive      —— 常驻保活服务（闹钟自检、进程复活）
#   RootShell      —— su 通道可用性与命令执行
#   A11yKeeper     —— 无障碍服务的启用/保持
# 少了后四个 tag 时，一旦出问题只能看到"打卡失败"这一条结果，
# 看不到它上游的闹钟/保活/通道状态，排查基本靠猜。
& $adb logcat -d -s PunchEngine:* PunchService:* PunchAlarmReceiver:* `
    AlarmScheduler:* KeepAlive:* RootShell:* A11yKeeper:* 2>$null |
    Select-Object -Last 25 |
    ForEach-Object { Write-Host $_ }
Write-Host ""

Write-Host "════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host " 提示："
Write-Host "  • 如果第 1 项为空，先手动打开一次 App"
Write-Host "  • 重启手机后再跑一遍本脚本，是验证自启动的唯一可靠方法"
Write-Host "  • 完整验证清单见 README.md 第五节"
Write-Host "════════════════════════════════════════════" -ForegroundColor Cyan

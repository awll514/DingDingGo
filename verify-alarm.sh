#!/usr/bin/env bash
# 快速验证：钉钉自动打卡 App 的闹钟与运行状态
#
# 用法：./verify-alarm.sh [包名]
# 默认包名是 debug 变体（com.redmi.autoattend.debug）

set -u

PKG="${1:-com.redmi.autoattend.debug}"

if ! command -v adb >/dev/null 2>&1; then
  echo "❌ 找不到 adb，请把 Android SDK 的 platform-tools 加进 PATH"
  exit 1
fi

DEVICES=$(adb devices | grep -c "device$")
if [ "$DEVICES" -eq 0 ]; then
  echo "❌ 没有已连接的设备，请先连上手机或启动模拟器"
  exit 1
fi

echo "════════════════════════════════════════════"
echo " 目标包名: $PKG"
echo "════════════════════════════════════════════"
echo

echo "── 1. 已注册的闹钟 ─────────────────────────"
ALARMS=$(adb shell dumpsys alarm | grep -i "autoattend" )
if [ -z "$ALARMS" ]; then
  echo "❌ 没有找到任何闹钟！"
  echo "   可能原因："
  echo "   • 定时打卡开关被关闭"
  echo "   • 自启动未开启，重启后闹钟丢失"
  echo "   • App 从未启动过（首次启动才会注册）"
else
  echo "$ALARMS"
  echo
  echo "✅ 找到闹钟记录"
fi
echo

echo "── 2. 进程是否存活 ──────────────────────────"
PID=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
if [ -z "$PID" ]; then
  echo "⚠️  App 进程未运行（这不一定是问题：闹钟靠静态 Receiver 触发）"
else
  echo "✅ App 正在运行，PID = $PID"
fi
echo

echo "── 3. 无障碍服务是否在线 ────────────────────"
ACC=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
if echo "$ACC" | grep -q "autoattend"; then
  echo "✅ 无障碍服务已启用"
else
  echo "⚠️  无障碍服务未启用，只能依赖 root 通道"
fi
echo

echo "── 4. 相关权限状态 ─────────────────────────"
adb shell dumpsys package "$PKG" 2>/dev/null | grep -E "POST_NOTIFICATIONS|SCHEDULE_EXACT_ALARM|RECEIVE_BOOT_COMPLETED" | head -5
echo

echo "── 5. 电池优化白名单 ───────────────────────"
WHITELIST=$(adb shell dumpsys deviceidle whitelist 2>/dev/null | grep -i "$PKG")
if [ -z "$WHITELIST" ]; then
  echo "⚠️  不在电池优化白名单里（精确闹钟会受限，后台也容易被清理）"
else
  echo "✅ $WHITELIST"
fi
echo

echo "── 6. 最近执行日志 ─────────────────────────"
adb logcat -d -s PunchEngine:* PunchService:* PunchAlarmReceiver:* 2>/dev/null | tail -25
echo

echo "════════════════════════════════════════════"
echo " 提示："
echo "  • 如果第 1 项为空，先手动打开一次 App"
echo "  • 重启手机后再跑一遍本脚本，是验证自启动的唯一可靠方法"
echo "  • 完整验证清单见 README.md 第五节"
echo "════════════════════════════════════════════"

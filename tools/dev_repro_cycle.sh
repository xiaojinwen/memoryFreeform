#!/system/bin/sh
# ★ 取证（设备侧）：录制期间自己把"打开小窗 → 关闭"跑几轮，不用人手点。
# 用法: dev_repro_cycle.sh [起始延迟秒] [轮数] [开窗停留秒] [关窗间隔秒] [组件]
# 为什么要它：前四轮录屏全都扑空（桌面/通知栏/AI 音箱页），缺陷只在开窗那一两秒里，
# 靠人配合按时机点屏幕根本对不齐录屏窗口。这里用模块自己那条启动命令
# （CornerWindowService.buildPlainFreeformStart）把复现变成确定性的。
DLY=${1:-3}
N=${2:-4}
OPEN=${3:-6}
GAP=${4:-3}
CMP=${5:-com.coolapk.market/.view.main.MainActivity}
PKG=${CMP%%/*}

sleep "$DLY"
i=1
while [ "$i" -le "$N" ]; do
  echo "REPRO_OPEN $i $(date +%s%3N)"
  am start-activity --windowingMode 5 -f 0x10000000 -n "$CMP" >/dev/null 2>&1
  sleep "$OPEN"
  echo "REPRO_CLOSE $i $(date +%s%3N)"
  am force-stop "$PKG"
  sleep "$GAP"
  i=$((i+1))
done
echo "REPRO_DONE $(date +%s%3N)"

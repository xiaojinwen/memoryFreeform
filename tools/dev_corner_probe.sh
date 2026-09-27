#!/system/bin/sh
# ★ 圆角取证（设备侧）：开窗期间按固定间隔采 SurfaceFlinger 图层圆角，输出「时刻 | 图层 | 圆角」。
# 用法: dev_corner_probe.sh [开窗后采样秒数] [间隔毫秒] [组件名]
# 只看正方形的那几帧属于哪个层：
#   adb shell /data/local/tmp/corner_probe.sh 4 120 com.coolapk.market/.view.main.MainActivity
 DLY=${1:-4}
 GAP=${2:-120}
 CMP=${3:-com.coolapk.market/.view.main.MainActivity}
 PKG=${CMP%%/*}
 OUT=/data/local/tmp/corner_probe.txt
 : > $OUT
 am force-stop "$PKG" >/dev/null 2>&1
 sleep 1.0
 T0=$(date +%s%3N)
 echo "T0=$T0 PKG=$CMP" >> $OUT
 am start-activity --windowingMode 5 -f 0x10000000 -n "$CMP" >/dev/null 2>&1
 echo "OPEN=$(date +%s%3N)" >> $OUT
 END=$((T0 + DLY * 1000))
 while [ "$(date +%s%3N)" -lt "$END" ]; do
   MS=$(( $(date +%s%3N) - T0 ))
   dumpsys SurfaceFlinger 2>/dev/null \
     | awk -v ms="$MS" '/Layer \[/{n=$0} /roundedCorner\{/{gsub(/^ *| *$/,"",n); print ms" | "n" | "$0}' \
     >> $OUT
   sleep "$GAP"
 done
 echo "DONE=$(date +%s%3N)" >> $OUT
 chmod 666 $OUT 2>/dev/null

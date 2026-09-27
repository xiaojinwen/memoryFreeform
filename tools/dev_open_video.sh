#!/system/bin/sh
# 验收录像：录屏期间做一次"打开小窗"，把关键毫秒戳写进 marks.txt。
# 只录屏、不跑 dumpsys（连续 dumpsys 会把 screenrecord 饿成一串黑帧）。
OUT=/data/local/tmp/vidtest
REC=${1:-8}
CMP=com.coolapk.market/.view.main.MainActivity
rm -rf $OUT; mkdir -p $OUT
input keyevent KEYCODE_WAKEUP
svc power stayon true
wm dismiss-keyguard
am force-stop com.coolapk.market
sleep 1.0
screenrecord --time-limit $REC --bit-rate 40M $OUT/vid.mp4 &
RCPID=$!
sleep 2.0
echo "MARK $(date +%s%3N)" > $OUT/marks.txt
am start-activity --windowingMode 5 -f 0x10000000 -n $CMP >/dev/null 2>&1
echo "OPEN $(date +%s%3N)" >> $OUT/marks.txt
wait $RCPID
echo "END $(date +%s%3N)" >> $OUT/marks.txt
chmod 666 $OUT/vid.mp4 2>/dev/null

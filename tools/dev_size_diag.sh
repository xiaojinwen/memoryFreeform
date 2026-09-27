#!/bin/bash
# ★ 尺寸记忆取证（host 侧，Git Bash 运行）：拉伸小窗 -> 关掉 -> 重开一遍后跑本脚本。
# 一键收集：记忆文件 / flags / 学得默认 / 记录探针 / LSPosed 关键日志。
# 用法: bash tools/dev_size_diag.sh [包名，可省=全部]
PKG="${1:-}"
D='/data/system'
echo "===== 1. flags（rememberSize 开关实际落盘值）"
adb shell su -c "cat $D/memoryfreeform_flags 2>/dev/null" || echo "(读不到)"
echo "===== 2. 记忆文件（$PKG 当前存的矩形）"
adb shell su -c "cat $D/memoryfreeform_window_memory 2>/dev/null" | grep -v '^#' | tail -40
echo "===== 3. 学得的系统默认（memoryfreeform_default_rect）"
adb shell su -c "cat $D/memoryfreeform_default_rect 2>/dev/null"
echo "===== 4. 记录探针尾部（memoryfreeform_record.state*）"
adb shell su -c "ls $D/memoryfreeform_record.state* 2>/dev/null"
for f in $(adb shell su -c "ls $D/memoryfreeform_record.state* 2>/dev/null" | tr -d '\r'); do
  echo "--- $f (tail 25)"; adb shell su -c "tail -25 $f" 2>/dev/null
done
echo "===== 5. LSPosed 日志：尺寸写入 / 钳制 / 越界修正（最近 60 条）"
adb shell su -c "cat /data/adb/lspd/log/modules_*.log 2>/dev/null" \
  | grep -E "size-write|resize-coord|clamp\(|越界修正|not-fit|default-size|memskip" \
  | grep -E "${PKG:+$PKG|}" | tail -60
echo "===== 6. logcat：App 侧开窗几何（windowMemory/resizeTask 行，最近 40 条）"
adb logcat -d 2>/dev/null | grep -E "windowMemory:|resizeTask:" | tail -40
echo "===== DONE"

#!/system/bin/sh
# WatchCat 数据面模块 — post-fs-data
#
# 这一阶段跑在开机最前面，任何阻塞都直接算进开机时长，所以这里只做四件事：
#   1) 把状态目录用 0700 建好（目录权限是第一道防线，规范 L5）；
#   2) 回看上一轮开机有没有写出心跳，据此维护 fail.count（L10.3「连续两次无心跳自动停用」）；
#   3) 清掉本轮不该继承的状态：armed（每次开机都要重新确认，L10.1）与上一轮的心跳；
#   4) 留一份能核对的启动痕迹，方便报问题时说清「哪一次开机的现场」。
#
# 为什么不能像原来那样无脑 rm 三个文件：fail.count 一旦每次开机清零，
# 「连续两次没有心跳」就永远数不到 2，L10.3 等于没实现。所以计数必须跨重启，
# 判据是 boot_id（/proc/sys/kernel/random/boot_id 每次开机都变）。
#
# 明确不做：不 arm、不写规则、不启动任何常驻进程。默认状态就是「装了等于没装」。

MODDIR=${0%/*}
WC=/data/adb/watchcat
HB_SYS=/data/system/watchcat.heartbeat

mkdir -p "$WC" 2>/dev/null
chmod 700 "$WC" 2>/dev/null
chown root:root "$WC" 2>/dev/null

BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\n')
PREV_BOOT=$(cat "$WC/last.boot" 2>/dev/null | tr -d '\n')

FAIL=$(cat "$WC/fail.count" 2>/dev/null)
case "$FAIL" in
  ''|*[!0-9]*) FAIL=0 ;;
esac

# 只在「确实换了一轮开机」时才结算上一轮：拿上一轮的 boot_id 去匹配心跳里的 bootId
if [ -n "$PREV_BOOT" ] && [ "$PREV_BOOT" != "$BOOT_ID" ]; then
  HB=""
  [ -f "$HB_SYS" ] && HB=$(cat "$HB_SYS" 2>/dev/null)
  [ -z "$HB" ] && [ -f "$WC/heartbeat" ] && HB=$(cat "$WC/heartbeat" 2>/dev/null)
  if echo "$HB" | grep -q "bootId=$PREV_BOOT"; then
    FAIL=0
  else
    FAIL=$((FAIL + 1))
  fi
  echo "$FAIL" > "$WC/fail.count" 2>/dev/null
  chmod 600 "$WC/fail.count" 2>/dev/null
else
  # 首次开机（没有上一轮可比）：只把计数文件初始化出来，不计数
  [ -n "$PREV_BOOT" ] || echo "$FAIL" > "$WC/fail.count" 2>/dev/null
fi

echo "$BOOT_ID" > "$WC/last.boot" 2>/dev/null
chmod 600 "$WC/last.boot" 2>/dev/null

# armed 不跨重启（L10.1：装完即生效是禁止行为，每次开机都要用户重新确认）
rm -f "$WC/armed" 2>/dev/null
# A 阶段起闸门落在 /data/system（/data/adb 是 0700 root:root，system_server 读不到，L17.1），
# 两个位置一起清，避免"adb 侧删了、system 侧还留着"的半启用状态。
rm -f /data/system/watchcat.armed 2>/dev/null

# 连续两轮拿不到心跳 → 自动停用，并在诊断页可见的位置留一条 tripped
if [ "$FAIL" -ge 2 ]; then
  {
    echo "stage=tripped"
    echo "reason=no-heartbeat"
    echo "fail=$FAIL"
    echo "bootId=$BOOT_ID"
    echo "uptime=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)"
    echo "timeMs=$(date +%s)000"
  } > /data/system/watchcat.tripped.marker 2>/dev/null
  chmod 600 /data/system/watchcat.tripped.marker 2>/dev/null
fi

# 上一轮的心跳到这里已经核对完，清掉它，避免下一轮把旧心跳当成新心跳
rm -f "$HB_SYS" "$WC/heartbeat" 2>/dev/null

{
  echo "stage=post-fs-data"
  echo "module=$MODDIR"
  echo "bootId=$BOOT_ID"
  echo "fail=$FAIL"
  echo "uptime=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)"
  echo "fingerprint=$(getprop ro.build.fingerprint 2>/dev/null)"
  echo "sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
} > "$WC/boot.marker" 2>/dev/null
chmod 600 "$WC/boot.marker" 2>/dev/null

exit 0

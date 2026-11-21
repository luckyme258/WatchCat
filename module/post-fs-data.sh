#!/system/bin/sh
# WatchCat 数据面模块 — post-fs-data
#
# 这一阶段跑在开机最前面，任何阻塞都直接算进开机时长，所以这里只做三件事：
#   1) 把状态目录用 0700 建好（规则将来会落这里，目录权限是第一道防线，规范 L5）；
#   2) 清掉跨重启不该继承的脏状态（armed / heartbeat / 失败计数）；
#   3) 留一份能核对的启动痕迹，方便用户报问题时说清楚「哪一次开机的现场」。
#
# 明确不做：不 arm、不写规则、不启动任何常驻进程。P0 的默认状态就是「装了等于没装」。

MODDIR=${0%/*}
WC=/data/adb/watchcat

mkdir -p "$WC" 2>/dev/null
chmod 700 "$WC" 2>/dev/null
chown root:root "$WC" 2>/dev/null

# 状态机不允许跨重启继承：上一次开机残留的「临时放行」不能变成这一次的默认
rm -f "$WC/armed" "$WC/heartbeat" "$WC/fail.count" 2>/dev/null

{
  echo "stage=post-fs-data"
  echo "module=$MODDIR"
  echo "uptime=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)"
  echo "fingerprint=$(getprop ro.build.fingerprint 2>/dev/null)"
  echo "sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
} > "$WC/boot.marker" 2>/dev/null
chmod 600 "$WC/boot.marker" 2>/dev/null

exit 0

#!/system/bin/sh
# WatchCat 数据面模块 — service
#
# 走 late_start service 阶段：这里阻塞等开机完成是允许的（post-fs-data 里不允许）。
# P0 只写一行「服务阶段活着」的痕迹；真正的加载探针要等 shim 进来之后才有东西可写。

MODDIR=${0%/*}
WC=/data/adb/watchcat

mkdir -p "$WC" 2>/dev/null
chmod 700 "$WC" 2>/dev/null

# 有上限的等待：最多等 60 秒，超时也照样写痕迹，不把开机拖死
i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ $i -lt 60 ]; do
  sleep 1
  i=$((i + 1))
done

{
  echo "stage=service"
  echo "boot_completed=$(getprop sys.boot_completed 2>/dev/null)"
  echo "uptime=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)"
  echo "armed=$([ -f "$WC/armed" ] && echo yes || echo no)"
  echo "fail=$(cat "$WC/fail.count" 2>/dev/null)"
} > "$WC/service.marker" 2>/dev/null
chmod 600 "$WC/service.marker" 2>/dev/null

# 心跳是 L10.2 的判据：由 system_server 里的模块在 onSystemServerStarting 时写，
# 落 /data/system（/data/adb 它写不进来）。这里只做「有没有」的旁证，不参与判定。
if [ -f /data/system/watchcat.heartbeat ]; then
  echo "heartbeat=yes" >> "$WC/service.marker" 2>/dev/null
else
  echo "heartbeat=no" >> "$WC/service.marker" 2>/dev/null
fi

exit 0

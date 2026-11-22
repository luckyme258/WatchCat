#!/system/bin/sh
# 卸载时清干净：规则、审计、标记文件全部随模块消失。
# 之所以要单独写：Magisk 卸载模块只删模块目录，/data/adb/watchcat 不在其中，
# 留着就是「用户以为删了、配置还在」的状态。
# /data/system/watchcat.* 也要删（那是 system_server 写出来的证据/心跳），否则重装后
# 会读到上一轮开机的旧文件，看起来像"模块还在跑"。
WC=/data/adb/watchcat
rm -rf "$WC" 2>/dev/null
# armed / 策略 / 事件也必须清：留着会让重装后的 App 显示"已启用"，而闸门里的 bootId 早已过期
rm -f /data/system/watchcat.*.marker /data/system/watchcat.heartbeat /data/system/watchcat.p0.probe \
      /data/system/watchcat.armed /data/system/watchcat.clip.policy /data/system/watchcat.events 2>/dev/null
exit 0

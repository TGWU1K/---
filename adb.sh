#!/usr/bin/env bash
# 真机 adb 小包装（用 MuMu 自带的 adb 客户端：只有它能在这个沙箱里建起 server）
# 用法： bash adb.sh devices -l
#        bash adb.sh -s FJH7N19103003046 shell screencap -p /sdcard/x.png
ADB="/d/Netease/MuMu Player 12/shell/adb.exe"
mkdir -p /c/tmp/ljq
exec "$ADB" "$@"

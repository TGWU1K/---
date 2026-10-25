#!/usr/bin/env bash
# 单独跑一个 adb server（真机用）。DSH 里沙箱禁止 adb 自带的 fork+pipe，
# 所以必须把 server 长期挂着，客户端只走 tcp:5037。
DIR="$(cd "$(dirname "$0")" && pwd)"
export TMP='C:\tmp\ljq'
export TEMP='C:\tmp\ljq'
mkdir -p /c/tmp/ljq
exec "$DIR/sdkdl/pt/platform-tools/adb.exe" -L tcp:5037 nodaemon server

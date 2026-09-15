#!/bin/bash
# 停止本目录 client（指定引擎或缺省全部）
cd "$(dirname "$0")"
for eng in bes tomcat; do
    PID_FILE="client-$eng.pid"
    if [ -f "$PID_FILE" ] && kill -0 "$(cat $PID_FILE)" 2>/dev/null; then
        kill "$(cat $PID_FILE)" && rm -f "$PID_FILE" && echo "[OK] stopped client-$eng"
    else
        rm -f "$PID_FILE"
    fi
done

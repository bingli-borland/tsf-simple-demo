#!/bin/bash
cd "$(dirname "$0")"
PORT=${1:?用法: ./stop-server.sh <端口>}
PID_FILE="server-$PORT.pid"
if [ -f "$PID_FILE" ] && kill -0 "$(cat $PID_FILE)" 2>/dev/null; then
    kill "$(cat $PID_FILE)" && rm -f "$PID_FILE" && echo "[OK] stopped $PORT"
else
    echo "[WARN] 端口 $PORT 未在运行"; rm -f "$PID_FILE"
fi

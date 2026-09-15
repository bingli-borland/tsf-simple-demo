#!/bin/bash
# 用法: ./start-server.sh <tomcat|bes> <端口> [--应用参数...]
# 环境变量: JAVA_OPTS 追加 JVM 参数（如 -Dhttp.keepAlive=false）
set -e
cd "$(dirname "$0")"
ENG=${1:?用法: ./start-server.sh <tomcat|bes> <端口> [--参数...]}
PORT=${2:?缺少端口}
shift 2
APP=tsf-ribbon-server.jar
LICENSE_OPTS=""
if [ "$ENG" = "bes" ]; then
    APP=tsf-ribbon-server-bes.jar
    LICENSE_OPTS="-Dcom.bes.enterprise.licenseDir=license"
fi
PID_FILE="server-$PORT.pid"; LOG_FILE="server-$PORT.log"
[ -f "$APP" ] || { echo "[ERROR] $APP 不存在"; exit 1; }
if [ -f "$PID_FILE" ] && kill -0 "$(cat $PID_FILE)" 2>/dev/null; then
    echo "[WARN] 端口 $PORT 已在运行 pid=$(cat $PID_FILE)"; exit 1
fi
nohup java -Xms256m -Xmx384m $JAVA_OPTS $LICENSE_OPTS \
     -cp "$APP" org.springframework.boot.loader.PropertiesLauncher \
     "--server.port=$PORT" "$@" > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "[OK] $ENG:$PORT pid=$(cat $PID_FILE) log=$LOG_FILE extra: $*"

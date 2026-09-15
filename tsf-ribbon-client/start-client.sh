#!/bin/bash
# 用法: ./start-client.sh <tomcat|bes> [--应用参数...]
# 固定端口: tomcat=18093, bes=18193；服务列表等参数透传
set -e
cd "$(dirname "$0")"
ENG=${1:?用法: ./start-client.sh <tomcat|bes> [--参数...]}
shift
if [ "$ENG" = "bes" ]; then
    APP=tsf-ribbon-client-bes.jar; PORT=18193
    LICENSE_OPTS="-Dcom.bes.enterprise.licenseDir=license"
else
    APP=tsf-ribbon-client.jar; PORT=18093
    LICENSE_OPTS=""
fi
PID_FILE="client-$ENG.pid"; LOG_FILE="client-$ENG-18193.log"
[ "$ENG" = "tomcat" ] && LOG_FILE="client-$ENG-18093.log"
[ -f "$APP" ] || { echo "[ERROR] $APP 不存在"; exit 1; }
if [ -f "$PID_FILE" ] && kill -0 "$(cat $PID_FILE)" 2>/dev/null; then
    echo "[WARN] client-$ENG 已在运行 pid=$(cat $PID_FILE)"; exit 1
fi
nohup java -Xms256m -Xmx384m $JAVA_OPTS $LICENSE_OPTS \
     -cp "$APP" org.springframework.boot.loader.PropertiesLauncher \
     "--server.port=$PORT" "$@" > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "[OK] client-$ENG port=$PORT pid=$(cat $PID_FILE) log=$LOG_FILE extra: $*"

#!/usr/bin/env bash
#set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$BASE_DIR/target/consumer-demo-1.46.20-SpringCloud2021-RELEASE.jar"
JMX="$BASE_DIR/keepalive.jmx"
JMETER="/mnt/c/appserver/apache-jmeter-5.6.3/bin/jmeter"
LOG_DIR="$BASE_DIR/target/keepalive-jmeter-loop"

THREADS="${THREADS:-1}"
SLEEP_MILLIS="${SLEEP_MILLIS:-9985}"
SLEEP_STEP="${SLEEP_STEP:-0}"
ROUNDS="${ROUNDS:-10}"
STARTUP_WAIT_SECONDS="${STARTUP_WAIT_SECONDS:-20}"

SERVICE_PID=""
FAILED_SUMMARY="$LOG_DIR/failed-summary.txt"

stop_service() {
  if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
    echo "Stopping service pid=$SERVICE_PID"
    kill "$SERVICE_PID" 2>/dev/null || true
    sleep 2
    if kill -0 "$SERVICE_PID" 2>/dev/null; then
      kill -9 "$SERVICE_PID" 2>/dev/null || true
    fi
  fi
  SERVICE_PID=""
}

trap stop_service EXIT INT TERM

if [[ ! -f "$JAR" ]]; then
  echo "Jar not found: $JAR" >&2
  exit 1
fi

if [[ ! -f "$JMETER" ]]; then
  echo "JMeter not found: $JMETER" >&2
  exit 1
fi

mkdir -p "$LOG_DIR"
rm -f "$FAILED_SUMMARY"

for round in $(seq 1 "$ROUNDS"); do
  CURRENT_SLEEP_MILLIS="$SLEEP_MILLIS"
  echo
  echo "===== Round $round/$ROUNDS, threads=$THREADS, sleepMillis=$CURRENT_SLEEP_MILLIS ====="

  java -Dtsf_consul_ip=8.134.165.212 -Dtsf_consul_port=8500  -jar "$JAR" >"$LOG_DIR/service-round-$round.out.log" 2>"$LOG_DIR/service-round-$round.err.log" &
  SERVICE_PID="$!"
  echo "Started service pid=$SERVICE_PID"

  sleep "$STARTUP_WAIT_SECONDS"

  rm -f "$LOG_DIR/result-round-$round.jtl" "$LOG_DIR/jmeter-round-$round.log"

  "$JMETER" \
    -n \
    -t "$JMX" \
    -Jthreads="$THREADS" \
    -JsleepMillis="$CURRENT_SLEEP_MILLIS" \
    -l "$LOG_DIR/result-round-$round.jtl" \
    -j "$LOG_DIR/jmeter-round-$round.log"

  if [[ -f "$LOG_DIR/result-round-$round.jtl" ]]; then
    failed_count="$(awk -F, 'NR > 1 && $8 != "true" { count++ } END { print count + 0 }' "$LOG_DIR/result-round-$round.jtl")"
    if [[ "$failed_count" -gt 0 ]]; then
      echo "Round $round sleepMillis=$CURRENT_SLEEP_MILLIS failed=$failed_count result=$LOG_DIR/result-round-$round.jtl" | tee -a "$FAILED_SUMMARY"
    fi
  else
    echo "Round $round sleepMillis=$CURRENT_SLEEP_MILLIS failed=unknown result file missing" | tee -a "$FAILED_SUMMARY"
  fi

  stop_service
  SLEEP_MILLIS=$((SLEEP_MILLIS + SLEEP_STEP))
done

echo
echo "All $ROUNDS rounds finished."
if [[ -f "$FAILED_SUMMARY" ]]; then
  echo
  echo "Failed rounds:"
  cat "$FAILED_SUMMARY"
else
  echo "Failed rounds: none"
fi

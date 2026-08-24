#!/bin/bash
SP="$(cd "$(dirname "$0")" && pwd)"
SCRIPT=$1; shift
LEVELS="$@"
H='Authorization: Bearer loadops'
sample() {  # Hikari 상태를 재는 동안 샘플링
  local out=$1
  while true; do
    a=$(curl -s -H "$H" http://127.0.0.1:8080/actuator/metrics/hikaricp.connections.active | python3 -c "import sys,json;print(json.load(sys.stdin)['measurements'][0]['value'])" 2>/dev/null)
    p=$(curl -s -H "$H" http://127.0.0.1:8080/actuator/metrics/hikaricp.connections.pending | python3 -c "import sys,json;print(json.load(sys.stdin)['measurements'][0]['value'])" 2>/dev/null)
    echo "$a $p" >> "$out"
    sleep 0.5
  done
}
for v in $LEVELS; do
  : > $SP/hikari-$SCRIPT-$v.txt
  sample $SP/hikari-$SCRIPT-$v.txt & SPID=$!
  k6 run -q -e VUS=$v -e DUR=30s -e OUT=$SP/res-$SCRIPT-$v $SP/$SCRIPT.js > /dev/null 2>&1
  kill $SPID 2>/dev/null
  echo "  VUS=$v 끝"
done

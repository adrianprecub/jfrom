#!/bin/sh
# Starts the sample app, attaching the jfr2grafana agent when a jar is
# actually present and able to load. Never blocks the app from starting:
#   - if AGENT_JAR does not point at a real file, skip -javaagent entirely.
#   - if the JVM exits almost immediately with the agent attached (typical of
#     a missing/broken Premain-Class, e.g. before the agent's premain exists),
#     retry once without it instead of crash-looping the container.
set -u

JAVA_OPTS="${JAVA_OPTS:--Xmx512m}"
APP_JAR="${APP_JAR:-/app/sample-app.jar}"
AGENT_JAR="${AGENT_JAR:-/app/agent/jfr2grafana-agent.jar}"
AGENT_OPTS="${AGENT_OPTS:-port=9404}"
AGENT_STARTUP_GRACE_SECONDS="${AGENT_STARTUP_GRACE_SECONDS:-3}"

if [ -f "$AGENT_JAR" ]; then
  echo "jfr2grafana: attaching agent '$AGENT_JAR' with options '$AGENT_OPTS'"
  # shellcheck disable=SC2086
  java $JAVA_OPTS -javaagent:"$AGENT_JAR=$AGENT_OPTS" -jar "$APP_JAR" &
  PID=$!
  sleep "$AGENT_STARTUP_GRACE_SECONDS"
  if kill -0 "$PID" 2>/dev/null; then
    echo "jfr2grafana: app is running with the agent attached"
    wait "$PID"
    exit $?
  fi
  wait "$PID"
  STATUS=$?
  echo "jfr2grafana: process exited quickly (status $STATUS) while starting with the agent attached; retrying without -javaagent"
else
  echo "jfr2grafana: no agent jar found at '$AGENT_JAR' - starting without -javaagent"
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar "$APP_JAR"

#!/bin/bash
# =============================================================================
# task-scheduler 部署脚本
# 用法: bash deploy.sh [install|start|stop|restart|status]
# =============================================================================
set -e

APP_NAME="task-scheduler"
DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="/usr/task-scheduler"
JAR_FILE="${INSTALL_DIR}/${APP_NAME}.jar"
CONFIG_DIR="${INSTALL_DIR}/config"
LOG_DIR="${INSTALL_DIR}/logs"
PID_FILE="${INSTALL_DIR}/${APP_NAME}.pid"

JVM_OPTS="-server -Xms256m -Xmx1024m -XX:+UseG1GC"
JAVA_OPTS="-Dfile.encoding=UTF-8"

install() {
    echo "Installing ${APP_NAME}..."
    mkdir -p ${INSTALL_DIR} ${CONFIG_DIR} ${LOG_DIR}

    if [ -f "${DEPLOY_DIR}/../target/${APP_NAME}-1.0.0.jar" ]; then
        cp -f "${DEPLOY_DIR}/../target/${APP_NAME}-1.0.0.jar" "${JAR_FILE}"
    elif [ -f "${DEPLOY_DIR}/${APP_NAME}.jar" ]; then
        cp -f "${DEPLOY_DIR}/${APP_NAME}.jar" "${JAR_FILE}"
    else
        echo "ERROR: jar file not found"
        exit 1
    fi

    cp -n ${DEPLOY_DIR}/config/*.yml ${CONFIG_DIR}/ 2>/dev/null || true
    cp -n ${DEPLOY_DIR}/config/*.sql ${CONFIG_DIR}/ 2>/dev/null || true
    echo "Install complete."
}

start() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "${APP_NAME} already running (PID: $(cat "$PID_FILE"))"
        return 0
    fi

    echo "Starting ${APP_NAME}..."
    nohup java ${JVM_OPTS} ${JAVA_OPTS} \
        -jar ${JAR_FILE} \
        --spring.config.location=${CONFIG_DIR}/ \
        > ${LOG_DIR}/stdout.log 2>&1 &

    echo $! > "$PID_FILE"
    sleep 2
    if kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "${APP_NAME} started (PID: $(cat "$PID_FILE"))"
    else
        echo "Start failed. Check ${LOG_DIR}/stdout.log"
        exit 1
    fi
}

stop() {
    if [ ! -f "$PID_FILE" ]; then
        echo "${APP_NAME} not running"
        return 0
    fi
    PID=$(cat "$PID_FILE")
    echo "Stopping ${APP_NAME} (PID: $PID)..."
    kill "$PID" 2>/dev/null
    for i in $(seq 1 30); do
        kill -0 "$PID" 2>/dev/null || break
        sleep 1
    done
    kill -9 "$PID" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "${APP_NAME} stopped"
}

status() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "${APP_NAME} running (PID: $(cat "$PID_FILE"))"
    else
        echo "${APP_NAME} not running"
    fi
}

case "$1" in
    install) install ;;
    start)   start ;;
    stop)    stop ;;
    restart) stop; start ;;
    status)  status ;;
    *)
        echo "Usage: $0 {install|start|stop|restart|status}"
        exit 1 ;;
esac

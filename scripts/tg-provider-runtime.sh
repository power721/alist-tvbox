#!/bin/sh

TG_PROVIDER_BIN="${TG_PROVIDER_BIN:-/usr/local/bin/tg-provider}"
TG_PROVIDER_DATA="${TG_PROVIDER_DATA:-/data/tg-provider}"
TG_PROVIDER_CONFIG="${TG_PROVIDER_CONFIG:-$TG_PROVIDER_DATA/config.yaml}"
TG_PROVIDER_HOST="${TG_PROVIDER_HOST:-127.0.0.1}"
TG_PROVIDER_PORT="${TG_PROVIDER_PORT:-6000}"
TG_PROVIDER_LOG_DIR="${TG_PROVIDER_LOG_DIR:-$TG_PROVIDER_DATA/logs}"
TG_PROVIDER_PID=""
TG_PROVIDER_STARTED=0
ATV_APP_PID=""

tg_provider_log() {
  echo "[tg-provider] $*"
}

tg_provider_prepare_config() {
  mkdir -p "$TG_PROVIDER_DATA" "$TG_PROVIDER_DATA/sessions" "$TG_PROVIDER_DATA/backup" "$TG_PROVIDER_LOG_DIR"

  if [ -r "$TG_PROVIDER_CONFIG" ]; then
    tg_provider_log "Using config $TG_PROVIDER_CONFIG"
    return 0
  fi

  api_id="${API_ID:-${TG_API_ID:-26375241}}"
  api_hash="${API_HASH:-${TG_API_HASH:-70f574f48a016d683c64f2f7a217d04f}}"

  old_umask="$(umask)"
  umask 077
  cat > "$TG_PROVIDER_CONFIG" <<EOF
telegram:
  api_id: ${api_id}
  api_hash: ${api_hash}
server:
  host: ${TG_PROVIDER_HOST}
  port: ${TG_PROVIDER_PORT}
sync:
  workers: 5
  history_batch_size: 100
storage:
  path: ${TG_PROVIDER_DATA}
EOF
  umask "$old_umask"
  tg_provider_log "Generated config $TG_PROVIDER_CONFIG"
}

tg_provider_start() {
  if [ ! -x "$TG_PROVIDER_BIN" ]; then
    tg_provider_log "Binary not found or not executable: $TG_PROVIDER_BIN"
    return 1
  fi

  tg_provider_prepare_config || return 1

  "$TG_PROVIDER_BIN" -config "$TG_PROVIDER_CONFIG" >> "$TG_PROVIDER_LOG_DIR/stdout.log" 2>> "$TG_PROVIDER_LOG_DIR/stderr.log" &
  TG_PROVIDER_PID="$!"
  TG_PROVIDER_STARTED=1
  tg_provider_log "Started pid $TG_PROVIDER_PID on ${TG_PROVIDER_HOST}:${TG_PROVIDER_PORT}"
}

tg_provider_stop_pid() {
  pid="$1"
  name="$2"
  if [ -z "$pid" ]; then
    return 0
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    return 0
  fi

  tg_provider_log "Stopping $name pid $pid"
  kill "$pid" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  tg_provider_log "Force stopping $name pid $pid"
  kill -9 "$pid" 2>/dev/null || true
}

tg_provider_shutdown() {
  status="${1:-0}"
  tg_provider_stop_pid "$ATV_APP_PID" "alist-tvbox"
  tg_provider_stop_pid "$TG_PROVIDER_PID" "tg-provider"
  exit "$status"
}

tg_provider_run_app() {
  "$@" &
  ATV_APP_PID="$!"
  trap 'tg_provider_shutdown 143' INT TERM

  while true; do
    if ! kill -0 "$ATV_APP_PID" 2>/dev/null; then
      wait "$ATV_APP_PID"
      status="$?"
      tg_provider_stop_pid "$TG_PROVIDER_PID" "tg-provider"
      exit "$status"
    fi

    if [ "$TG_PROVIDER_STARTED" = "1" ] && ! kill -0 "$TG_PROVIDER_PID" 2>/dev/null; then
      wait "$TG_PROVIDER_PID" 2>/dev/null
      status="$?"
      tg_provider_log "Exited with status $status"
      tg_provider_stop_pid "$ATV_APP_PID" "alist-tvbox"
      exit 1
    fi

    sleep 2
  done
}

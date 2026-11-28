#!/system/bin/sh
DIR=$(dirname "$(realpath "$0")")
# shellcheck source=../settings.sh
. "$DIR"/../settings.sh
mkdir -p "$TS_RUN_DIR"

start_bridge() {
  if [ -x "${TS_DIR}/bin/tailscaled-bridge" ]; then
    for PID in $(busybox pidof tailscaled-bridge 2>/dev/null); do
      kill "$PID" 2>/dev/null || true
    done
    nohup "${TS_DIR}/bin/tailscaled-bridge" \
      --socket="${TS_SOCKET_PATH}" \
      --tailscale="${TS_DIR}/bin/tailscale" \
      --service="${TS_DIR}/scripts/tailscaled.service" \
      --modprop="${TS_MOD_PROP}" \
      --vpn-bypass-file="${TS_VPN_BYPASS_FILE}" \
      > "${TS_RUN_DIR}/bridge.log" 2>&1 &
  fi
}

case "$1" in
    postinstall)
      rm -rf "$TS_RUN_DIR" && mkdir -p "$TS_RUN_DIR"
      start_bridge
      tailscaled.service restart >> "/dev/null" 2>&1 &
      return 0
    ;;
esac

start_service() {
  if [ ! -f "${TS_MOD_DIR}/disable" ]; then
    tailscaled.service start >> "/dev/null" 2>&1
    start_bridge
  fi
}

start_inotifyd() {
  for PID in $(busybox pidof inotifyd); do
    if grep -q "tailscaled.inotify" "/proc/$PID/cmdline"; then
      kill -9 "$PID"
    fi
  done
  echo "${CURRENT_TIME} [Info]: Starting tailscaled inotify service" > "${TS_RUN_LOG_FILE}"
  inotifyd "tailscaled.inotify" "${TS_MOD_DIR}" >> "/dev/null" 2>&1 &
}

module_version=$(busybox awk -F'=' '!/^ *#/ && /version=/ { print $2 }' "$TS_MOD_PROP" 2>/dev/null)
log Info "Magisk REtailscaled version : ${module_version}."
start_service
start_inotifyd

#!/system/bin/sh
set -e

# Custom settings

# Set Magisk Tailscale module variables
TS_MOD_DIR="/data/adb/modules/magisk-retailscaled"
export TS_MOD_PROP="${TS_MOD_DIR}/module.prop"

# Set tailscale directory variables
TS_DIR="/data/adb/tailscale"

# Set path environment for busybox & other.
export PATH="${TS_DIR}/bin:${TS_DIR}/scripts:/data/adb/magisk:/data/adb/ksu/bin:$PATH:/system/bin:${TS_MOD_DIR}/system/bin"
export HOME="/data/adb/tailscale/" # Because tailscaled will write log to $HOME

# Set tailscaled & tailscale configuration (userspace-networking + local SOCKS5 for FlClash/VPN coexistence)
export TS_SOCKS5_ADDR="${TS_SOCKS5_ADDR:-127.0.0.1:1055}"
export TS_SOCKET_PATH="${TS_DIR}/tailscaled.sock"
export TS_STATE_PATH="${TS_DIR}/tailscaled.state"
export TS_VPN_BYPASS_FILE="${TS_DIR}/vpn_bypass"
if [ -f "${TS_VPN_BYPASS_FILE}" ]; then
  _vpn_bypass_val=$(tr -d ' \t\r\n' < "${TS_VPN_BYPASS_FILE}" 2>/dev/null || echo "1")
  case "${_vpn_bypass_val}" in
    0|false|FALSE|off|OFF|no|NO) export TS_BYPASS_VPN="0" ;;
    *) export TS_BYPASS_VPN="1" ;;
  esac
else
  export TS_BYPASS_VPN="${TS_BYPASS_VPN:-1}"
fi
export TS_DAEMON_CMD="tailscaled --tun=userspace-networking --socks5-server=${TS_SOCKS5_ADDR} --socket=${TS_SOCKET_PATH} --state=${TS_STATE_PATH} -no-logs-no-support"

# Set tailscaled directory variables
export TS_RUN_DIR="${TS_DIR}/run"

# Set tailscaled log variables
export TS_LOG_FILE="${TS_RUN_DIR}/tailscaled.log"
export TS_RUN_LOG_FILE="${TS_RUN_DIR}/runs.log"

# Take the current time
CURRENT_TIME=$(date +"%I:%M %P")

# Coloring
normal="\033[0m"
orange="\033[1;38;5;208m"
red="\033[1;31m"
green="\033[1;32m"
yellow="\033[1;33m"
blue="\033[1;34m"

# Logs function
log() {
  # Selects the text color according to the parameters
  case $1 in
  Info) color="${blue}" ;;
  Success) color="${green}" ;;
  Error) color="${red}" ;;
  Warning) color="${yellow}" ;;
  Debug) color="${orange}" ;;
  *) color="${normal}" ;;
  esac
  # Add messages to time and parameters
  message="${CURRENT_TIME} [$1]: $2"
  if [ -z "$color" ]; then
    echo "${CURRENT_TIME} [Debug]: $1 $2" >>${TS_RUN_LOG_FILE} 2>&1
    return
  fi
  if [ -t 1 ]; then
    # Prints messages to the console
    echo "${color}${message}${normal}"
    echo "${message}" >>${TS_RUN_LOG_FILE} 2>&1
  else
    # Print messages to a log file
    echo "${message}" >>${TS_RUN_LOG_FILE} 2>&1
  fi
}

[ -n "${DEBUG:-}" ] && set -u && set -x && PS4='+ ${0##*/}:${LINENO}: ' || true

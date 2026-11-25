#!/system/bin/sh

# DEBUG=1
[ -n "${DEBUG:-}" ] && {
	PS4="+ \${0##*/}:\${LINENO}: "
	set -e
	set -u
	set -x
	set
} || true

# Github download helper
gh_download() {
	REPO=$1
	MATCH=$2
	DOWNLOAD_URL=$(
		wget --no-check-certificate --timeout=10 -qO- "https://api.github.com/repos/${REPO}/releases/latest" |
			grep "browser_download_url" |
			grep "${MATCH}" |
			sed 's/.*"browser_download_url": "\([^"]*\)".*/\1/' ||
			true
	)
	if [ -z "$DOWNLOAD_URL" ]; then
		ui_print "! Unable to get release from https://github.com/${REPO}/releases"
		return 1
	fi
	FILENAME=$(basename "$DOWNLOAD_URL")
	ui_print "- Downloading $FILENAME..."
	wget --no-check-certificate --timeout=100 -qO "$TMPDIR/$FILENAME" "$DOWNLOAD_URL" || {
		ui_print "! Download timeout or failed"
		return 1
	}
}

# shellcheck disable=SC2034
SKIPUNZIP=1
# shellcheck disable=SC2034
SKIPMOUNT=false

if [ "$BOOTMODE" != true ]; then
	ui_print "! Please install in Magisk Manager or KernelSU Manager"
	ui_print "! Install from recovery is NOT supported"
	abort "-----------------------------------------------------------"
elif [ "$KSU" = true ] && [ "$KSU_VER_CODE" -lt 10670 ]; then
	abort "error: Please update your KernelSU and KernelSU Manager"
fi

SERVICE_DIR="/data/adb/service.d"

TS_DIR="/data/adb/tailscale"
TS_BIN_DIR="$TS_DIR/bin"
TS_SCRIPTS_DIR="$TS_DIR/scripts"

case $ARCH in
arm) F_ARCH="armv7a" ;;
arm64) F_ARCH="aarch64" ;;
*)
	ui_print "Unsupported architecture: $ARCH"
	abort
	;;
esac
ui_print "- Detected architecture: $ARCH"

if [ -d "$TS_DIR" ]; then
	[ -f "$TS_DIR/tmp/tailscaled.state" ] && mv -f "$TS_DIR/tmp/tailscaled.state" "$TS_DIR/tailscaled.state"
	ui_print "- Backup old files"
	PROP_FILE="$(echo "$MODPATH" | sed 's/_update//')/module.prop"
	[ ! -f "$PROP_FILE" ] && PROP_FILE="$MODPATH/module.prop"
	versionCode=$(grep '^versionCode=' "$PROP_FILE" | cut -d= -f2)
	# shellcheck disable=SC2154
	rm -rf "$TS_DIR/backups/$versionCode/" && mkdir -p "$TS_DIR/backups/$versionCode/"
	for p in "$TS_DIR"/*; do
		case "$(basename "$p")" in
		ssh | certs | backups | tailscaled.state | state) ;;
		*)
			mv -f -- "$p" "$TS_DIR/backups/$versionCode/"
			;;
		esac
	done
fi

mkdir -p "$TS_BIN_DIR" "$TS_BIN_DIR/.cli"
unzip -qqjo "$ZIPFILE" "tailscale/bin/*-$ARCH" -d "$TS_BIN_DIR" 2>/dev/null || true
for f in "$TS_BIN_DIR"/*-"$ARCH"; do
	[ -f "$f" ] && mv "$f" "${f%-"$ARCH"}"
done

[ -f "$TS_BIN_DIR/tailscaled" ] || {
	gh_download "DR34M3R-T/magisk-retailscaled" "tailscale_.*_${ARCH}\.tgz" || abort "error: Unable to download tailscaled."
	tar -xzf "$TMPDIR/$FILENAME" -C "$TS_BIN_DIR" || abort "error: Unable to extract tailscaled archive."
}

[ -f "$TS_BIN_DIR/jq" ] || {
	gh_download "theshoqanebi/jq-build-for-android" "jq-${F_ARCH}-linux-android" || abort "error: Unable to download jq."
	mv -f "$TMPDIR/$FILENAME" "$TS_BIN_DIR/jq" || abort "error: Unable to move jq binary."
}

ui_print "- Extracting files..."
unzip -qqo "$ZIPFILE" -x 'META-INF/*' 'tailscale/*' 'gui/*' 'bridge/*' -d "$MODPATH"

mkdir -p "$TS_DIR" "$TS_DIR/run" "$TS_SCRIPTS_DIR" "$MODPATH/system/bin/"
unzip -qqjo "$ZIPFILE" 'tailscale/scripts/*' -d "$TS_SCRIPTS_DIR"
unzip -qqjo "$ZIPFILE" 'tailscale/settings.sh' -d "$TS_DIR"

# Multi-call CLI entrypoint (.cli/tailscale -> tailscaled) + wrapper that defaults --socket and Android --hostname
ln -sf "$TS_BIN_DIR/tailscaled" "$TS_BIN_DIR/.cli/tailscale"
cat <<'EOF' > "$TS_BIN_DIR/tailscale"
#!/system/bin/sh
HAS_SOCKET=0
HAS_HOSTNAME=0
SUBCMD=""
for arg in "$@"; do
  case "$arg" in
    --socket|--socket=*|-socket|-socket=*) HAS_SOCKET=1 ;;
    --hostname|--hostname=*|-hostname|-hostname=*) HAS_HOSTNAME=1 ;;
    up|login) [ -z "$SUBCMD" ] && SUBCMD="$arg" ;;
  esac
done

get_android_hostname() {
  BRAND=$(getprop ro.product.brand 2>/dev/null | tr '[:upper:]' '[:lower:]')
  MODEL=$(getprop ro.product.model 2>/dev/null | tr '[:upper:]' '[:lower:]')
  case "$MODEL" in
    "$BRAND"*) RAW="$MODEL" ;;
    *) RAW="${BRAND}-${MODEL}" ;;
  esac
  CLEAN=$(echo "$RAW" | sed 's/[^a-z0-9-]/-/g; s/--*/-/g; s/^-//; s/-$//')
  [ -n "$CLEAN" ] && echo "$CLEAN" || echo "android-device"
}

if [ "$HAS_SOCKET" -eq 0 ]; then
  set -- --socket=/data/adb/tailscale/tailscaled.sock "$@"
fi

if [ -n "$SUBCMD" ] && [ "$HAS_HOSTNAME" -eq 0 ]; then
  DEV_HOST=$(get_android_hostname)
  exec /data/adb/tailscale/bin/.cli/tailscale "$@" --hostname="$DEV_HOST"
fi

exec /data/adb/tailscale/bin/.cli/tailscale "$@"
EOF

ln -sf "$TS_BIN_DIR/tailscale" "$MODPATH/system/bin/tailscale"
ln -sf "$TS_BIN_DIR/tailscaled" "$MODPATH/system/bin/tailscaled"
ln -sf "$TS_BIN_DIR/jq" "$MODPATH/system/bin/jq"
ln -sf "$TS_SCRIPTS_DIR/tailscaled.service" "$MODPATH/system/bin/tailscaled.service"

ui_print "- Setting permissions"
set_perm_recursive "$TS_BIN_DIR/" 0 0 0755 0755 "u:object_r:system_file:s0"
set_perm_recursive "$TS_SCRIPTS_DIR/" 0 0 0755 0755 "u:object_r:system_file:s0"
set_perm_recursive "$MODPATH/system/bin/" 0 0 0755 0755 "u:object_r:system_file:s0"
set_perm "$MODPATH/service.sh" 0 0 0755 "u:object_r:system_file:s0"

# Install bundled REtailscaled APK (if present in zip) as a normal user app
if unzip -qqjo "$ZIPFILE" 'tailscale/apk/REtailscaled.apk' -d "$TMPDIR" 2>/dev/null && [ -f "$TMPDIR/REtailscaled.apk" ]; then
	ui_print "- Installing REtailscaled app..."
	cp -f "$TMPDIR/REtailscaled.apk" "$TS_DIR/REtailscaled.apk"
	chmod 0644 "$TS_DIR/REtailscaled.apk"
	if pm install -r "$TMPDIR/REtailscaled.apk" >/dev/null 2>&1; then
		ui_print "- REtailscaled installed successfully."
	else
		ui_print "! Automatic APK install failed. You can manually install:"
		ui_print "  $TS_DIR/REtailscaled.apk"
	fi
fi

ui_print "- Starting service in background."
${TS_SCRIPTS_DIR}/start.sh postinstall >/dev/null 2>&1 &

if [ ! -f "/system/bin/tailscale" ] || ! cmp --silent "/system/bin/tailscale" "$MODPATH/system/bin/tailscale"; then
	ui_print "- Link file to /dev/."
	ln -sf "$TS_SCRIPTS_DIR/tailscaled.service" /dev/tailscaled.service
	ln -sf "$TS_BIN_DIR/tailscaled" /dev/tailscaled
	ln -sf "$TS_BIN_DIR/tailscale" /dev/tailscale
	ui_print "-----------------------------------------------------------"
	ui_print " Instructions"
	ui_print "-----------------------------------------------------------"
	ui_print "- Open the RootTailscale app (grant Magisk Root permission)"
	ui_print "  or use /dev/tailscale before rebooting."
	ui_print "- Default mode: userspace-networking + SOCKS5 (127.0.0.1:1055)."
	ui_print "- After reboot, you can use tailscale and tailscaled.service directly."
else
	ui_print "- Open the RootTailscale app or run: su -c 'tailscale status'"
fi
[ -n "${DEBUG:-}" ] && { set +ue; } || true

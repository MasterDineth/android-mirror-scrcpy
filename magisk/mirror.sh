#!/system/bin/sh
# Magisk auto-start service script
# Deploy to: /data/adb/service.d/mirror.sh
# Permissions: chmod 755 /data/adb/service.d/mirror.sh

MODDIR="${0%/*}"
SERVER_APK="/data/local/tmp/mirror-server.apk"
MAIN_CLASS="com.mirror.server.MirrorServerMain"
PORT="27183"
WIDTH="1080"
HEIGHT="2400"
BITRATE="12000000"
FPS="60"
LOG_FILE="/data/local/tmp/mirror-server.log"
PID_FILE="/data/local/tmp/mirror-server.pid"

log_msg() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Rotate log if > 2 MB
if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE")" -gt 2097152 ]; then
    mv "$LOG_FILE" "${LOG_FILE}.old"
fi

log_msg "mirror.sh started; waiting for boot completion..."

# Wait for Android framework and SurfaceFlinger to finish booting
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Give SurfaceFlinger / DisplayManager a brief grace period on headless boot
sleep 5

if [ ! -f "$SERVER_APK" ]; then
    log_msg "ERROR: Server APK not found at $SERVER_APK. Exiting."
    exit 1
fi

# Terminate any previous instance tracked by PID_FILE
if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE")"
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        log_msg "Stopping previous MirrorServer instance (PID $OLD_PID)"
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
fi

# Run supervised loop in background so Magisk service.d does not block
(
    while true; do
        log_msg "Launching MirrorServer on port $PORT (${WIDTH}x${HEIGHT} @ ${FPS}fps, ${BITRATE}bps)"
        CLASSPATH="$SERVER_APK" /system/bin/app_process /system/bin "$MAIN_CLASS" \
            --port "$PORT" \
            --width "$WIDTH" \
            --height "$HEIGHT" \
            --bitrate "$BITRATE" \
            --fps "$FPS" >> "$LOG_FILE" 2>&1 &

        CHILD_PID=$!
        echo "$CHILD_PID" > "$PID_FILE"
        wait "$CHILD_PID"
        EXIT_CODE=$?

        log_msg "MirrorServer exited with code $EXIT_CODE; restarting in 3s..."
        sleep 3
    done
) &

exit 0

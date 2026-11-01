#!/bin/sh
# Samples XCover 4 BLE scanner registrations every 30 s (diagnostic for "scan failed 2").
export ADB_SERVER_SOCKET=tcp:adb-server:5037
OUT=${1:-host/results/xcover4-scan-samples.tsv}
[ -f "$OUT" ] || printf 'time\tregistered_app_size\tlab_scanner_entries\tregular_scan_clients\tgms_ongoing_scans\tlab_pid\n' > "$OUT"
while :; do
  D=$(adb -s xcover4 shell dumpsys bluetooth_manager 2>/dev/null)
  reg=$(printf '%s\n' "$D" | sed -n '/GATT Scanner Map/,/AppScanStats/p' | grep -m1 'Registered AppSize' | awk '{print $NF}')
  lab=$(printf '%s\n' "$D" | sed -n '/GATT Scanner Map/,/AppScanStats/p' | grep -c 'appName: ch.trancee.lab.host')
  rsc=$(printf '%s\n' "$D" | grep -m1 'Regular Scan Clients' | awk '{print $NF}')
  gms=$(printf '%s\n' "$D" | sed -n '/com.google.uid.shared (Registered)/,/Application ID/p' | sed -n '/Ongoing scans/,/Application ID/p' | grep -c ' - ')
  pid=$(adb -s xcover4 shell pidof ch.trancee.lab.host 2>/dev/null)
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%H:%M:%S)" "$reg" "$lab" "$rsc" "$gms" "$pid" >> "$OUT"
  sleep 30
done

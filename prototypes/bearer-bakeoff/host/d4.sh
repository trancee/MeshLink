#!/usr/bin/env bash
# D4 (PROTOTYPE): iPhone 15 hub with N centrals (bakeoff.py phub) plus a bluetoothd syslog capture of the hub.
#   host/d4.sh RUN STAGGER_S PEER...
# The syslog (scan spam removed) goes to host/results/RUN/ip15-bluetoothd.log; it is a diagnostic, not shipped.
set -euo pipefail
cd "$(dirname "$0")/.."
run=$1; stagger=$2; shift 2
mkdir -p "host/results/$run"
exec 3> >(grep --line-buffered -v -E 'Found device|No match found|Already tracking|Not sending|scan' \
  > "host/results/$run/ip15-bluetoothd.log")
idevicesyslog -u ip15 -p bluetoothd 2>/dev/null >&3 &
syslog=$!
trap 'kill $syslog 2>/dev/null || true; exec 3>&-' EXIT
python3 -u host/bakeoff.py phub --hub ip15 --peers "$@" --run "$run" --timeout 600 --stagger "$stagger" phy=2m connect_retries=3 || true

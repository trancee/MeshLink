#!/bin/bash
# PROTOTYPE ticket 73: runs n200-low seeds 1-5 for each rule given, keeps results under OUT.
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
OUT=prototype-73
mkdir -p $OUT
for r in "$@"; do
  rm -f meshlink-testing/build/density/n200-low*
  ./gradlew -q :meshlink-testing:densityTest -Pdensity.measureOnly=true -Pdensity.cells=n200-low -Pdensity.seeds=1,2,3,4,5 -Pproto.rule=$r > $OUT/$r.log 2>&1
  mkdir -p $OUT/$r && cp meshlink-testing/build/density/n200-low* $OUT/$r/
  echo "== $r $(date +%T)"; grep "^| n" $OUT/$r/n200-low.md
done

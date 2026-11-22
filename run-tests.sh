#!/bin/sh
# Needs a Java JDK 17+ and Node. Run from this folder:  sh run-tests.sh
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -proc:none -d out -sourcepath stubs stubs/com/ghibli/calendar/LogicTest.java android-src/StateStore.java android-src/BoardModel.java
echo "== Android data + board logic =="; java -cp out com.ghibli.calendar.LogicTest
echo; echo "== Cloud-sync merge (simulated GitHub) =="; node sync-test.js
echo; echo "== Bridge script injected into the website =="; node bridge-test.js

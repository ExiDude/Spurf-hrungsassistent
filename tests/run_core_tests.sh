#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p /tmp/fieldguide-tests
kotlinc app/src/main/java/de/exi/fieldguide/core/*.kt tests/CoreTests.kt -include-runtime -d /tmp/fieldguide-tests/test.jar
java -jar /tmp/fieldguide-tests/test.jar

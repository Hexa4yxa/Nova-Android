#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" "$DIR/app/src/main/java/ai/motionwall/cars/LoopMath.java" "$DIR/tests/LoopMathTest.java"
java -cp "$OUT" LoopMathTest

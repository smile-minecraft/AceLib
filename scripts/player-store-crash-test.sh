#!/usr/bin/env bash
#
# scripts/player-store-crash-test.sh — 定期保存的「異常終止遺失上限」驗證。
#
# 用獨立 JVM 跑真 PlayerDataService + 真 SQLite store，持續把 counter 遞增並
# markDirty，再以 SIGKILL 直接殺掉行程（不走 shutdown / flush），模擬伺服器崩潰；
# 之後重開同一資料檔讀回 counter，與被殺前最後一次變更比較，斷言遺失筆數不超過
# 一個保存週期內累積的變更量。
#
# 設計紀律：
#   - 不依賴 Docker：只用 JDK + 已下載的 sqlite-jdbc 測試依賴。
#   - 只寫 /tmp 下的暫存目錄；不碰任何真實 plugin 資料。
#   - Gradle test 不執行此腳本；此腳本只讀 build 產物，不改 repo。
#
# 用法：
#   scripts/player-store-crash-test.sh [--interval-ms <n>] [--run-ms <n>] [--out <file>]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

INTERVAL_MS=500
RUN_MS=4000
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --interval-ms) INTERVAL_MS="$2"; shift 2 ;;
        --run-ms) RUN_MS="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

WORK="$(mktemp -d /tmp/acelib-player-crash-XXXXXX)"
DB="$WORK/players.db"
PROGRESS="$WORK/progress.log"
WRITER_LOG="$WORK/writer.log"
cleanup() {
    if [ -n "${WRITER_PID:-}" ] && kill -0 "$WRITER_PID" 2>/dev/null; then
        kill -9 "$WRITER_PID" 2>/dev/null || true
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

# sqlite-jdbc 由測試依賴解析；位置取自 Gradle 快取。
SQLITE_JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc" \
    -name 'sqlite-jdbc-*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort | tail -1 || true)"
if [ -z "$SQLITE_JAR" ]; then
    echo "sqlite-jdbc jar not found in Gradle cache; run ./gradlew testClasses first" >&2
    exit 2
fi

./gradlew testClasses --console=plain -q
CP="$ROOT/build/classes/java/main:$ROOT/build/classes/java/test:$ROOT/build/resources/main:$SQLITE_JAR"

# writer 端以 UUID.nameUUIDFromBytes 決定玩家 UUID（腳本不另實作 UUID 規則）；
# writer 就绪後從其輸出解析，避免兩處各自推導同一個 UUID。
echo "crash evidence workdir: $WORK (interval=${INTERVAL_MS}ms run=${RUN_MS}ms)"

java -cp "$CP" com.smile.acelib.data.PlayerStoreCrashMain writer \
    "$DB" "$INTERVAL_MS" "$PROGRESS" >"$WRITER_LOG" 2>&1 &
WRITER_PID=$!

# 等 writer 就緒（join 完成、開始遞增）再計時
for _ in $(seq 1 100); do
    if grep -q "writer-ready" "$WRITER_LOG" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$WRITER_PID" 2>/dev/null; then
        echo "writer exited before ready:" >&2
        cat "$WRITER_LOG" >&2
        exit 1
    fi
    sleep 0.1
done
if ! grep -q "writer-ready" "$WRITER_LOG" 2>/dev/null; then
    echo "writer did not become ready in time:" >&2
    cat "$WRITER_LOG" >&2
    exit 1
fi
WRITER_UUID="$(sed -n 's/.*writer-ready uuid=\([0-9a-f-]*\).*/\1/p' "$WRITER_LOG" | head -1)"
echo "writer ready: uuid=$WRITER_UUID pid=$WRITER_PID"

sleep "$(awk -v ms="$RUN_MS" 'BEGIN { printf "%.3f", ms / 1000 }')"
CHANGES_BEFORE_KILL="$(wc -l < "$PROGRESS" | tr -d ' ')"
echo "SIGKILL writer (no shutdown / no flush); changes recorded before kill: $CHANGES_BEFORE_KILL"
kill -9 "$WRITER_PID"
wait "$WRITER_PID" 2>/dev/null || true

if [ -z "$OUT" ]; then
    OUT="/tmp/acelib-player-crash-$(date +%Y%m%d-%H%M%S).log"
fi
{
    echo "================================================================"
    echo "crash-run at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "interval=${INTERVAL_MS}ms run=${RUN_MS}ms changes-before-kill=$CHANGES_BEFORE_KILL"
    echo "--- writer output ---"
    cat "$WRITER_LOG"
    echo "--- verify (reopen store after SIGKILL) ---"
    java -cp "$CP" com.smile.acelib.data.PlayerStoreCrashMain verify \
        "$DB" "$WRITER_UUID" "$INTERVAL_MS" "$PROGRESS"
} 2>&1 | tee "$OUT"

if grep -q "verify-result PASS" "$OUT"; then
    echo "evidence saved: $OUT"
    exit 0
fi
echo "evidence saved: $OUT (verify FAILED)" >&2
exit 1
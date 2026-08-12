#!/usr/bin/env bash
#
# scripts/smoke-server.sh — v0.1.0 milestone 的 Paper / Folia smoke harness。
#
# 此腳本不是 Gradle test 的替代品，而是用來在「可離線重現」的前提下，於
# 隔離的 temporary runtime 目錄內啟動真實 Paper 或 Folia 服務端，並驗證：
#   1. AceLib jar 已被複製進 plugins/ 且服務端能載入
#   2. 服務端啟動後印出 "AceLib <version> enabled on <platform>"
#   3. plugin lifecycle 完整：onEnable → ... → onDisable
#   4. stdin 送 stop 後服務端乾淨退出，並留下 "AceLib disabled" log
#
# 設計紀律：
#   - **不自動下載**：預設必須由呼叫端提供 SERVER_JAR，避免在 CI/開發者機器
#     偷偷拉大量 binary。只有顯式傳 `--download` 才會走預先驗證過的固定 URL +
#     SHA-256 路徑（fill-data.papermc.io 內容分發端點；舊版 API 已回 410 Gone）。
#   - **每跑一次就建全新 runtime**：`mktemp -d` + `trap` 確保上一輪殘留
#     world/log/eula 都不會影響下一輪。runtime 目錄即 server CWD：服務端
#     透過 `plugins/`、`config/`、`worlds/` 標準 layout 找到對應資源，
#     因此 server CLI 不需任何目錄覆寫參數（Paper/Folia 26.1 拒絕）。
#   - **--timeout 由 watchdog 強制執行**：以 background subshell 跑
#     `sleep "$timeout_seconds"` + `kill server_pid`，避免依賴 GNU-only
#     `timeout` 指令（macOS 缺），亦避免 server hang 住整個 CI step。
#   - **失敗時只印最後 200 行 log**：避免 CI log 被數 MB 啟動紀錄灌爆，但仍
#     保留足夠 context。
#   - **不修改專案內任何檔案**：runtime 目錄在 mktemp 內，AceLib jar 從
#     `build/libs/AceLib-*.jar` 唯讀複製；退出時 trap 清乾淨。watcher /
#     watchdog / server 三個 PID 都在 cleanup 內終止並 reap，避免 zombie。
#
# 此腳本僅在 AceLib repository 根目錄下被呼叫（路徑推斷相對於 CWD）。

set -euo pipefail

# ---------------------------------------------------------------------------
# 常數
# ---------------------------------------------------------------------------

# 官方固定 artifact（Evidence Pack 指定）：
#   - Paper 26.1.2 build 72
#   - Folia 26.1.2 build 8
# 透過 fill-data.papermc.io 內容分發端點下載；URL 內含 SHA-256 物件鍵以
# 鎖定 immutable artifact（舊版 projects API 端點已回 410 Gone）。
PAPER_SHA256="0555a0b0468a5198d8fb1a16e1f9e95c81a917a2dc8f2e09867b4044742f6401"
FOLIA_SHA256="607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b"
PAPER_URL="https://fill-data.papermc.io/v1/objects/${PAPER_SHA256}/paper-26.1.2-72.jar"
FOLIA_URL="https://fill-data.papermc.io/v1/objects/${FOLIA_SHA256}/folia-26.1.2-8.jar"

DEFAULT_TIMEOUT_SECONDS=180
SERVER_READY_TIMEOUT_SECONDS=120
LOG_TAIL_LINES=200
EXPECTED_PLATFORM_PAPER="Paper"
EXPECTED_PLATFORM_FOLIA="Folia"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

usage() {
    cat <<'EOF'
Usage: scripts/smoke-server.sh <paper|folia> [options]

在隔離 temporary runtime 目錄內啟動 Paper 或 Folia 服務端，載入 AceLib plugin，
等待 startup 訊號、發 stop、驗證 plugin enable / disable log；最後清理 runtime。

Options:
  --help                 印此說明並 exit 0
  --download             若 SERVER_JAR 未設定，下載並 SHA-256 校驗官方固定
                         artifact（需網路；預設不啟用）
  --timeout SECONDS      啟動 + 等待 ready + 等待 disable 的總 timeout
                         （預設 180）。watchdog 在超時時強制終止 server
  --acelib-jar PATH      要複製進 runtime 的 AceLib jar；
                         預設 build/libs/AceLib-*.jar（排除 -sources /
                         -javadoc auxiliary jar）

Environment:
  SERVER_JAR             服務端 jar 的絕對路徑。若與 --download 同時指定，
                         以 SERVER_JAR 為準（--download 僅在缺 SERVER_JAR 時
                         才生效）。

Exit codes:
  0   smoke 成功（server 啟動 → AceLib enabled → stop → plugin disabled）
  2   參數錯誤（缺平台、無效平台、缺 SERVER_JAR 等等）
  3   script 環境錯誤（缺 java、jar 不存在、download 校驗失敗 等等）
  4   server 啟動 timeout
  5   plugin enable 失敗
  6   stop / disable 失敗

Examples:
  SERVER_JAR=/path/to/paper-26.1.2.jar scripts/smoke-server.sh paper
  scripts/smoke-server.sh folia --download
  scripts/smoke-server.sh --help
EOF
}

die() {
    # die <exit_code> <message>
    local code=$1
    shift
    echo "smoke-server: $*" >&2
    exit "$code"
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 \
        || die 3 "需要指令 '$1'，但 PATH 找不到。請先安裝或調整 PATH。"
}

sha256_of_file() {
    # sha256_of_file <path>  →  印小寫 hex digest；缺 sha256sum/shasum 時 die。
    local path=$1
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$path" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$path" | awk '{print $1}'
    else
        die 3 "需要 sha256sum 或 shasum 才能校驗下載"
    fi
}

verify_sha256() {
    # verify_sha256 <path> <expected_hex>
    # match：silent, exit 0；mismatch：印 actual hex 到 stdout, exit 1。
    # 不在此 die：caller 才能在收到非零退出時決定後續動作（典型情境：
    # download_artifact 會先 rm $path 再 die，避免 function-local tmp 殘留）。
    # sha256_of_file 內仍會在 sha256sum/shasum 皆缺時 die 3。
    local path=$1 expected=$2 actual
    actual=$(sha256_of_file "$path")
    if [[ "$actual" != "$expected" ]]; then
        echo "$actual"
        return 1
    fi
}

download_artifact() {
    # download_artifact <url> <expected_sha256> <dest_path>
    local url=$1 expected=$2 dest=$3
    echo "smoke-server: 下載 $url"
    require_cmd curl
    local tmp actual
    tmp=$(mktemp)
    if ! curl --fail --silent --show-error --location --output "$tmp" "$url"; then
        rm -f "$tmp"
        die 3 "下載失敗：$url"
    fi
    # SHA mismatch：tmp 是 function-local 變數，cleanup trap 透過
    # `downloaded_jar` 變數追蹤的 destination 路徑是另一條；trap 讀不到
    # tmp，必須在 die 之前先 rm，否則 tmp.XXX 殘留在 $TMPDIR 直到手動清理。
    if ! actual=$(verify_sha256 "$tmp" "$expected"); then
        rm -f "$tmp"
        die 3 "SHA-256 校驗失敗：expected=$expected actual=$actual path=$tmp"
    fi
    mv "$tmp" "$dest"
    echo "smoke-server: 下載並校驗完成 → $dest"
}

# ---------------------------------------------------------------------------
# 早期 cleanup state + trap（在 --download mktemp 之前安裝）
# ---------------------------------------------------------------------------

# --download 建立的暫存 server jar 路徑。**必須在 mktemp 之前先宣告空字串**，
# 後續 cleanup() 才能在 runtime 尚未建立的情況下仍安全處理：trap 觸發時若
# downloaded_jar 已被 mktemp 指派為真實路徑就刪除，否則略過。**不可在 mktemp
# 之後又把此變數清空**（先前 bug 把變數在 runtime 建立區段重設為 ""，導致
# cleanup 內 rm -f 失效，暫存檔殘留）。
downloaded_jar=""

# cleanup 函式對所有外部變數用 `${var:-}` 守門：trap 可能早於 runtime / PID /
# FIFO 初始化而觸發（例如 --download 失敗 → die 3 → exit），`set -u` 下若
# 直接讀取 unset 變數會終止 trap，後續步驟就會被略過。
cleanup() {
    local exit_code=$?
    local pid_watchdog=${watchdog_pid:-}
    local pid_watcher=${watcher_pid:-}
    local pid_server=${server_pid:-}
    local fifo_was_open=${fifo_opened:-0}
    local log_file=${acelib_plugin_log:-}
    local runtime_dir=${runtime:-}
    local temp_download=${downloaded_jar:-}

    # 1. 終止三個背景進程（watchdog → watcher → server，順序反推以釋放 fd）
    if [[ -n "$pid_watchdog" ]] && kill -0 "$pid_watchdog" 2>/dev/null; then
        kill -TERM "$pid_watchdog" 2>/dev/null || true
    fi
    if [[ -n "$pid_watcher" ]] && kill -0 "$pid_watcher" 2>/dev/null; then
        kill -TERM "$pid_watcher" 2>/dev/null || true
    fi
    if [[ -n "$pid_server" ]] && kill -0 "$pid_server" 2>/dev/null; then
        kill -TERM "$pid_server" 2>/dev/null || true
        sleep 2
        if kill -0 "$pid_server" 2>/dev/null; then
            kill -KILL "$pid_server" 2>/dev/null || true
        fi
    fi
    # 2. Reap（避免 zombie）
    if [[ -n "$pid_server" ]]; then
        wait "$pid_server" 2>/dev/null || true
    fi
    if [[ -n "$pid_watcher" ]]; then
        wait "$pid_watcher" 2>/dev/null || true
    fi
    if [[ -n "$pid_watchdog" ]]; then
        wait "$pid_watchdog" 2>/dev/null || true
    fi
    # 3. 關閉 FIFO fd（讓任何殘留 reader 收到 EOF）
    if [[ $fifo_was_open -eq 1 ]]; then
        exec 3>&- 2>/dev/null || true
    fi
    # 4. 失敗時印 bounded log（避免 CI log 被灌爆）
    if [[ "$exit_code" -ne 0 ]]; then
        if [[ -n "$log_file" && -f "$log_file" ]]; then
            echo "smoke-server: ===== AceLib plugin 日誌（最後 $LOG_TAIL_LINES 行）====="
            tail -n "$LOG_TAIL_LINES" "$log_file" || true
        fi
        if [[ -n "$runtime_dir" && -f "$runtime_dir/server-stdout.log" ]]; then
            echo "smoke-server: ===== server-stdout.log（最後 $LOG_TAIL_LINES 行）====="
            tail -n "$LOG_TAIL_LINES" "$runtime_dir/server-stdout.log" || true
        fi
    fi
    # 5. 清 runtime 目錄（idempotent：trap 重入也安全；runtime 未建立時跳過）
    if [[ -n "$runtime_dir" && -d "$runtime_dir" ]]; then
        rm -rf "$runtime_dir"
    fi
    # 6. 清 --download 建立的暫存 server jar（位於 $TMPDIR，不在 runtime 內）。
    # 只刪除 script 自行建立的 download temp jar；未指定 --download 時此變數
    # 維持空字串、`-n` 守門跳過；不可刪除使用者提供的 SERVER_JAR。
    if [[ -n "$temp_download" && -f "$temp_download" ]]; then
        rm -f "$temp_download"
    fi
    exit "$exit_code"
}

# 在 mktemp download temp / runtime 建立之前先安裝 trap：確保 --download 失敗
# （curl 或 SHA）或 runtime 尚未建立時仍能正確清理，不留下 $TMPDIR 殘留檔。
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

platform=""
do_download=0
timeout_seconds=$DEFAULT_TIMEOUT_SECONDS
acelib_jar_override=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help)
            usage
            exit 0
            ;;
        paper|folia)
            if [[ -n "$platform" ]]; then
                die 2 "只能指定一個平台（paper 或 folia）；已指定 '$platform'"
            fi
            platform=$1
            shift
            ;;
        --download)
            do_download=1
            shift
            ;;
        --timeout)
            [[ $# -ge 2 ]] || die 2 "--timeout 需要一個數字參數"
            timeout_seconds=$2
            shift 2
            ;;
        --acelib-jar)
            [[ $# -ge 2 ]] || die 2 "--acelib-jar 需要一個路徑參數"
            acelib_jar_override=$2
            shift 2
            ;;
        *)
            die 2 "無效的參數 '$1'；第一個位置參數必須是 paper 或 folia 之一（用 --help 看用法）"
            ;;
    esac
done

if [[ -z "$platform" ]]; then
    die 2 "缺少必要參數：<paper|folia>。用 --help 看用法。"
fi

if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
    die 2 "--timeout 必須是正整數秒；收到 '$timeout_seconds'"
fi

if [[ "$platform" == "paper" ]]; then
    server_url=$PAPER_URL
    server_sha256=$PAPER_SHA256
    expected_platform_label=$EXPECTED_PLATFORM_PAPER
else
    server_url=$FOLIA_URL
    server_sha256=$FOLIA_SHA256
    expected_platform_label=$EXPECTED_PLATFORM_FOLIA
fi

# ---------------------------------------------------------------------------
# 環境檢查
# ---------------------------------------------------------------------------

require_cmd java

# SERVER_JAR 處理
server_jar="${SERVER_JAR:-}"
if [[ -z "$server_jar" ]]; then
    if [[ "$do_download" -eq 1 ]]; then
        downloaded_jar=$(mktemp -t "smoke-server-XXXXXX.jar")
        download_artifact "$server_url" "$server_sha256" "$downloaded_jar"
        server_jar=$downloaded_jar
    else
        die 2 "未提供 SERVER_JAR，且未指定 --download。"
    fi
fi

if [[ ! -f "$server_jar" ]]; then
    die 3 "SERVER_JAR 指向不存在的檔案：$server_jar"
fi

# AceLib jar — 須排除 -sources.jar / -javadoc.jar auxiliary artifact。
# Gradle build/libs 可能同時產出 AceLib-X.Y.Z-SNAPSHOT.jar 與
# AceLib-X.Y.Z-SNAPSHOT-sources.jar；字典序會把 -sources.jar 排在前面，
# 若直接 glob 排序，server 會拒絕載入 sources jar。
if [[ -n "$acelib_jar_override" ]]; then
    if [[ ! -f "$acelib_jar_override" ]]; then
        die 3 "--acelib-jar 指向不存在的檔案：$acelib_jar_override"
    fi
    acelib_jar=$acelib_jar_override
else
    if [[ ! -d "build/libs" ]]; then
        die 3 "找不到 build/libs 目錄；請先執行 ./gradlew jar 或 ./gradlew build"
    fi
    shopt -s nullglob
    candidates=( build/libs/AceLib-*.jar )
    shopt -u nullglob
    if [[ ${#candidates[@]} -eq 0 ]]; then
        die 3 "build/libs 內找不到 AceLib-*.jar；請先 ./gradlew jar"
    fi
    filtered=()
    for c in "${candidates[@]}"; do
        # 排除 -sources.jar / -javadoc.jar auxiliary artifact
        case "$c" in
            *-sources.jar|*-javadoc.jar) ;;
            *) filtered+=("$c") ;;
        esac
    done
    if [[ ${#filtered[@]} -eq 0 ]]; then
        die 3 "build/libs 內找不到 AceLib runtime jar（候選皆為 -sources.jar / -javadoc.jar）"
    fi
    # 取字典序第一個（決定性選擇）
    IFS=$'\n' sorted=($(printf '%s\n' "${filtered[@]}" | sort))
    unset IFS
    acelib_jar=${sorted[0]}
fi

echo "smoke-server: platform=$platform"
echo "smoke-server: server_jar=$server_jar"
echo "smoke-server: acelib_jar=$acelib_jar"
echo "smoke-server: timeout=${timeout_seconds}s"

# ---------------------------------------------------------------------------
# 建 runtime（標準 layout：plugins/、config/、worlds/、logs/）
# ---------------------------------------------------------------------------

runtime=$(mktemp -d -t "acelib-smoke-XXXXXX")
# Watchdog 透過此 sentinel 檔通知 watcher 已 timeout：flag 留空代表未觸發，
# watchdog 寫入 "1" 後 watcher 會在下一個輪詢週期立刻退出（避免等滿自己的
# SERVER_READY_TIMEOUT_SECONDS=120 秒輪詢上限）。檔案路徑放在 runtime 內，
# cleanup rm -rf "$runtime" 時會一併移除。
timeout_flag="$runtime/.watchdog-timed-out.flag"
: > "$timeout_flag"
acelib_plugin_log=""
server_pid=""
watcher_pid=""
watchdog_pid=""
fifo_opened=0
# downloaded_jar 在 helpers 後即宣告（mktemp 之前），cleanup trap 也已安裝；
# 此處不再重設該變數，避免覆蓋 mktemp 指派的路徑導致 rm -f 失效。

mkdir -p "$runtime/plugins" "$runtime/logs"
cp "$acelib_jar" "$runtime/plugins/"
# EULA 同意（純 smoke；不聯外、不存真實 world）
printf 'eula=true\n' > "$runtime/eula.txt"

# AceLib 日誌檔（最新輪替的 latest.log 即 service log）
acelib_plugin_log="$runtime/logs/latest.log"
: > "$acelib_plugin_log"

# ---------------------------------------------------------------------------
# 啟動服務端
# ---------------------------------------------------------------------------

# 切到 runtime 目錄作為 server CWD：Paper/Folia 透過標準 plugins/、config/、
# worlds/ layout 找到對應資源，無需任何自訂目錄覆寫 CLI（已驗證 Paper/Folia
# 26.1 CLI 不支援此類參數）。
cd "$runtime"

echo "smoke-server: runtime=$runtime"
echo "smoke-server: 啟動服務端（timeout=${timeout_seconds}s）"

server_log="$runtime/server-stdout.log"

# 用 FIFO 送 stop 指令；以 `exec 3<>"$fifo"` 同時持讀寫兩端，避免 reader/
# writer open 互相 block。watcher 透過 fd 3 寫 "stop"；server 透過 stdin 讀。
fifo="$runtime/stop.fifo"
mkfifo "$fifo"
exec 3<>"$fifo"
fifo_opened=1

# 先啟動 server，再啟動 watcher 與 watchdog（確保 $server_pid 在後兩者 fork
# 時已被父 shell 設定，subshell 透過 shell variable inheritance 取得）。
# 注意：Paper/Folia 26.1 CLI 不接受自訂目錄覆寫參數；標準 layout 由
# `cd "$runtime"` 提供。
java \
    -Xms512m -Xmx1024m \
    -jar "$server_jar" \
    --nojline \
    < "$fifo" > "$server_log" 2>&1 &
server_pid=$!

# Watcher subshell：等 server 印出 "AceLib ... enabled on <platform>"，送 stop。
# 使用 `exit`（非 `return`）以符合 set -euo pipefail 契約：subshell 內 `return`
# 會在 strict mode 下拋 "can only 'return' from a function or sourced script"。
#
# 三種快速退出信號（讓 --timeout 能真正 bounded exit，避免等滿 120 秒輪詢）：
#   - timeout flag：watchdog 寫入 → 視為 timeout（exit 4 = ready timeout）
#   - server 死亡：kill -0 失敗 → 視為 server 已下線（exit 0）
#   - ready log：傳統訊號 → 送 stop 並 exit 0
(
    deadline=$(( SECONDS + SERVER_READY_TIMEOUT_SECONDS ))
    ready=0
    while (( SECONDS < deadline )); do
        # 信號 1：watchdog timeout 已觸發（sentinel flag 為非空檔案）
        if [[ -s "$timeout_flag" ]]; then
            echo "smoke-server: watcher 偵測到 watchdog timeout flag（--timeout 到期）" >&2
            exit 4
        fi
        # 信號 2：server 已死亡（被 watchdog kill、自然 crash、或成功 stop 後）
        if [[ -n "${server_pid:-}" ]] && ! kill -0 "$server_pid" 2>/dev/null; then
            echo "smoke-server: watcher 偵測到 server_pid=$server_pid 已退出" >&2
            exit 0
        fi
        # 傳統 ready log 輪詢
        if [[ -f "$acelib_plugin_log" ]]; then
            if grep -q "AceLib .* enabled on ${expected_platform_label}" "$acelib_plugin_log" 2>/dev/null; then
                ready=1
                break
            fi
        fi
        sleep 1
    done

    if [[ $ready -ne 1 ]]; then
        echo "smoke-server: 等待 AceLib ready timeout（${SERVER_READY_TIMEOUT_SECONDS}s）" >&2
        exit 4
    fi

    echo "smoke-server: AceLib enabled on ${expected_platform_label}，送 stop"
    # 透過 fd 3 送 stop；fd 3 在父 shell 開啟，subshell 繼承之。
    echo "stop" >&3
    exit 0
) &
watcher_pid=$!

# Watchdog subshell：--timeout 由背景 sleep + kill 強制執行（POSIX-macOS
# 相容；不依賴 GNU-only `timeout` 指令）。server_pid 在此 subshell fork 之前
# 已由父 shell 設定，subshell 透過 shell variable inheritance 取得 snapshot。
#
# 兩件事：
#   1. 寫入 timeout_flag sentinel：讓 watcher 在下一輪 (<=1s) 內看到並退出，
#      不再等滿自己的 120 秒輪詢上限。
#   2. kill server_pid：讓 server 也離開，避免 parent `wait server_pid` 卡住。
(
    sleep "$timeout_seconds"
    # 先寫 sentinel flag，再 kill server；順序重要：flag 寫完後即使 server
    # 死亡信號傳遞有 race，watcher 也能透過 flag 看到 timeout 已觸發。
    echo "1" > "$timeout_flag"
    echo "smoke-server: watchdog timeout（${timeout_seconds}s）到期，終止 server_pid=$server_pid" >&2
    if [[ -n "${server_pid:-}" ]] && kill -0 "$server_pid" 2>/dev/null; then
        kill -TERM "$server_pid" 2>/dev/null || true
        sleep 5
        if kill -0 "$server_pid" 2>/dev/null; then
            kill -KILL "$server_pid" 2>/dev/null || true
        fi
    fi
    exit 0
) &
watchdog_pid=$!

# 等 server 進程退出（自然 stop、crash、或 watchdog kill）。server 是整個
# lifecycle 中活得最久的進程；watchdog 只在 --timeout 到期時會 kill 它，
# 否則 server 會在收到 FIFO "stop" 之後自行 graceful shutdown。
server_exit=0
if [[ -n "$server_pid" ]]; then
    wait "$server_pid" || server_exit=$?
fi

# server 一退出就立刻收 watchdog，避免 race：watchdog 的 sleep 也許尚未到期，
# 卻在 server 退出後才被 TERM 中斷；這正是我們要的——這樣 watchdog 來不及
# 寫 timeout_flag，整個 lifecycle 就會被當作「正常成功路徑」處理。
if [[ -n "$watchdog_pid" ]] && kill -0 "$watchdog_pid" 2>/dev/null; then
    kill -TERM "$watchdog_pid" 2>/dev/null || true
fi

# 等 watcher（看到 server 死亡或 timeout_flag 就會 <1s 內退出）
watcher_status=0
if [[ -n "$watcher_pid" ]]; then
    wait "$watcher_pid" || watcher_status=$?
fi

# Reap watchdog（可能已 exit 或剛被 TERM）
if [[ -n "$watchdog_pid" ]]; then
    wait "$watchdog_pid" 2>/dev/null || true
fi
watchdog_pid=""

# 判定最終 exit code：timeout > watcher > server
if [[ -s "${timeout_flag:-}" ]]; then
    echo "smoke-server: --timeout ${timeout_seconds}s 到期，整個 lifecycle 由 watchdog 終止" >&2
    exit 4
fi

if [[ $watcher_status -ne 0 ]]; then
    exit "$watcher_status"
fi

if [[ $server_exit -ne 0 ]]; then
    echo "smoke-server: server exit code=$server_exit"
    exit 6
fi

# ---------------------------------------------------------------------------
# 收尾驗證
# ---------------------------------------------------------------------------

if [[ ! -f "$acelib_plugin_log" ]]; then
    echo "smoke-server: 找不到 plugin log"
    exit 6
fi

if ! grep -q "AceLib .* enabled on ${expected_platform_label}" "$acelib_plugin_log"; then
    echo "smoke-server: plugin log 內找不到 'enabled on ${expected_platform_label}'"
    exit 5
fi

# 獨立 disabled lifecycle grep：disable 階段必須印 "AceLib disabled"，不可
# 只看 log 內含 AceLib 字樣（過於寬鬆，無法驗證 disable hook 確實執行）。
if ! grep -q "AceLib disabled" "$acelib_plugin_log"; then
    echo "smoke-server: plugin log 內找不到 'AceLib disabled'（disable lifecycle 未記錄）"
    exit 6
fi

echo "smoke-server: 全部 smoke 檢查通過"
exit 0

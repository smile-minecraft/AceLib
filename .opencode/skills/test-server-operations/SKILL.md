---
name: test-server-operations
description: Folia 測試服操作管理。Use when the user wants to start, stop, restart, check status, deploy plugins, execute local RCON commands, or verify logs on the Folia test server at /Volumes/Smile-Data/MainData/Test-Server-Folia.
---

# Test Server Operations

管理 Folia 測試服的生命週期、插件部署、日誌與本機 RCON 指令驗證。

## 觸發條件

- 使用者要求啟動、停止、重啟、檢查 Folia 測試服
- 使用者要求部署、更新或驗證插件
- 使用者要求查看測試服日誌或診斷啟動問題
- 使用者要求確認測試服是否正在運行
- 使用者要求透過 RCON 執行伺服器或插件指令

## 固定路徑與安全範圍

測試服固定路徑：

```text
/Volumes/Smile-Data/MainData/Test-Server-Folia
```

所有操作必須以此目錄為 workdir。只操作此目錄，不觸碰正式服、秘密或無關檔案；外部下載須確認官方 URL、版本與 checksum。不要刪除備份，也不要在未讀取 log 時宣稱驗證成功。

## 啟動

先確認沒有殘留 server process：

```bash
pgrep -af 'server.jar'
```

以測試服目錄為 workdir 執行：

```bash
LOG="logs/console-$(date +%Y%m%d-%H%M%S).log"
nohup ./start.sh > "$LOG" 2>&1 < /dev/null &
disown 2>/dev/null || true
```

macOS 沒有 `setsid`，不要使用 `rtk setsid`；`nohup ... &` 即可背景執行。啟動後驗證：

```bash
pgrep -af 'server.jar'
lsof -iTCP:25565 -sTCP:LISTEN -P
rtk grep -E 'Done \(.*\)!|Could not load plugin|Exception|ERROR' "$LOG"
```

`Done (...)! For help, type "help"` 表示啟動完成；仍須確認沒有插件載入錯誤。若 RCON 已啟用，也應看到 `RCON running on 127.0.0.1:25575`。

## 本機 RCON

測試服的 RCON 僅綁定本機，避免對區域網路或外網開放：

```properties
enable-rcon=true
server-ip=127.0.0.1
rcon.port=25575
```

Folia/Paper 會拒絕空白 `rcon.password`；啟動 log 會顯示 `No rcon password set in server.properties, rcon disabled!`。因此即使是無人遊玩的本地測試服，也必須使用非空密碼。密碼只可放在本機設定或受控的 `RCON_PASSWORD` 環境變數中，禁止輸出、提交或寫入文件。

啟動後先確認 listener：

```bash
lsof -iTCP:25575 -sTCP:LISTEN -P
rtk grep -E 'RCON running on 127\.0\.0\.1:25575|No rcon password' "$LOG"
```

目前環境不預設提供 `mcrcon` 或 `rcon-cli`。若本機已安裝 RCON client，可用下列形式執行；不要把密碼直接寫進命令列或回報內容：

```bash
[ -n "${RCON_PASSWORD:-}" ] || { printf "%s\n" "RCON_PASSWORD 未設定" >&2; exit 1; }
mcrcon -H 127.0.0.1 -P 25575 -p "$RCON_PASSWORD" list
mcrcon -H 127.0.0.1 -P 25575 -p "$RCON_PASSWORD" acelib status
```

若沒有 RCON client，使用只存在於核准暫存目錄的 Python standard-library probe，透過 `RCON_PASSWORD` 環境變數認證；不要把 probe 或密碼存進 repository。Minecraft console/RCON 指令不需要前置 `/`，例如 `list`、`acelib status`。

RCON 驗證必須同時保留：

1. `rcon_auth=ok` 或 client 的成功回應
2. 指令回應內容
3. server log 中沒有因該指令產生的 `Exception` 或 `ERROR`

只有 port 開啟不能代表 RCON 可用；也不要把 RCON 認證失敗時的訊息當成插件載入成功。

## 停止與重啟

只對實際 `server.jar` 的 Java PID 發送 SIGTERM，不要停止 VSCode/Gradle 等其他 Java process：

```bash
SERVER_PID=$(pgrep -f 'java.*server\.jar')
kill "$SERVER_PID"
sleep 10
pgrep -af 'server.jar' || true
```

等候正常儲存（例如 `Saving chunks`、`All dimensions are saved`）。除非 SIGTERM 超過 30 秒無反應，否則不要使用 `kill -9`；若仍無法停止，回報 PID 與 log 尾端後才考慮強制停止。

## 插件部署

1. 先依停止流程停止測試服。
2. 備份既有 JAR 與資料夾，保留備份：

```bash
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
cp Plugin.jar "Plugin.jar.backup-${TIMESTAMP}"
[ -d Plugin ] && cp -R Plugin "Plugin.backup-${TIMESTAMP}"
```

3. 從已確認的官方來源下載或複製新插件；來源提供 checksum 時執行 `shasum -a 256 Plugin.jar` 或指定演算法校驗。
4. 啟動後檢查：

```bash
rtk grep -iE 'Could not load plugin|Exception|ERROR' logs/latest.log
```

若命中錯誤，不得宣稱部署成功。

## 已知 Vault 狀態

測試服目前使用 `VaultUnlocked-2.20.2.jar`，舊檔備份格式為 `Vault.jar.backup-<timestamp>`。VaultUnlocked 為相容既有插件，日誌可能以 `[Vault]` 顯示，這是預期行為。它支援 Folia / Minecraft 26.2；來源：[Modrinth VaultUnlocked](https://modrinth.com/plugin/vaultunlocked)。

## 故障排查

- 啟動立即退出：查看 log 尾端，確認 Java 版本、`server.jar` 完整性與 25565 是否被占用。
- RCON disabled：確認 `enable-rcon=true` 且 `rcon.password` 非空；不要把密碼寫入回報。
- RCON 認證失敗：確認 client 使用受控的 `RCON_PASSWORD`，listener 是 `127.0.0.1:25575`，並檢查 log。
- 插件載入失敗：搜尋 `Could not load plugin`、`Exception`、`ERROR`，檢查 API 版本與依賴。
- VaultUnlocked 顯示 `[Vault]`：確認同時出現 `Enabled Version 2.20.2`；名稱相容不代表可跳過 log 驗證。
- port 被占用：用 `lsof -iTCP:25565 -sTCP:LISTEN -P` 找出 PID，只處理確認為測試服的 process。

## 命令速查

| 操作 | 命令 |
| --- | --- |
| 狀態 | `pgrep -af 'server.jar'` |
| Minecraft 埠號 | `lsof -iTCP:25565 -sTCP:LISTEN -P` |
| RCON 埠號 | `lsof -iTCP:25575 -sTCP:LISTEN -P` |
| 最新日誌 | `tail -50 logs/latest.log` |
| 啟動 | `nohup ./start.sh > logs/console-$(date +%Y%m%d-%H%M%S).log 2>&1 < /dev/null &` |
| 搜尋錯誤 | `rtk grep -iE 'error|exception|could not load' logs/latest.log` |
| RCON | `mcrcon -H 127.0.0.1 -P 25575 -p "$RCON_PASSWORD" list` |

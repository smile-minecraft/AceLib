# 部署、診斷與平台限制

本頁解決伺服器管理員如何從 AceLib repository 建立 runtime JAR、放入伺服器、啟動下游插件並確認服務狀態的問題。

## 前置條件

- Java 25。
- Paper 或 Folia `26.1.2` 伺服器。
- AceLib repository 已在本機 checkout。
- 下游插件 JAR 已準備完成，且其 `plugin.yml` 宣告 `depend: [AceLib]`。

`1.0.0` 仍是 Release Candidate；repository 為 private，尚未發布到外部 Maven 或 JitPack。不要從外部 Maven／JitPack 下載，直接使用本機建置產物。

## 可複製的部署流程

以下變數只代表本機路徑，依環境替換；不需要使用特定絕對路徑。

```bash
# AceLib repository 根目錄
export ACELIB_ROOT="/path/to/AceLib"

# Paper 或 Folia 伺服器目錄
export SERVER_DIR="/path/to/minecraft-server"

# 下游插件 JAR；替換成實際檔案
export DOWNSTREAM_PLUGIN_JAR="/path/to/MyPlugin.jar"

# 伺服器啟動 JAR；替換成實際檔案
export SERVER_LAUNCHER="$SERVER_DIR/paper-26.1.2.jar"

# 1. 在 AceLib repository 根目錄建立 runtime JAR
cd "$ACELIB_ROOT"
./gradlew clean build --no-daemon --console=plain

# 2. 只複製 runtime JAR；不要複製 -sources 或 -javadoc artifact
mkdir -p "$SERVER_DIR/plugins"
cp "$ACELIB_ROOT/build/libs/AceLib-1.0.0.jar" \
   "$SERVER_DIR/plugins/AceLib-1.0.0.jar"

# 3. 放入下游插件
cp "$DOWNSTREAM_PLUGIN_JAR" "$SERVER_DIR/plugins/"

# 4. 啟動伺服器
cd "$SERVER_DIR"
java -jar "$SERVER_LAUNCHER" --nogui
```

runtime JAR 的固定位置是 `build/libs/AceLib-1.0.0.jar`。`build/libs/` 可能同時有 `AceLib-1.0.0-sources.jar` 與 `AceLib-1.0.0-javadoc.jar`；伺服器只需要 `AceLib-1.0.0.jar`。

## 啟動後確認

伺服器完成啟動後，在 console 或具備 `acelib.admin` 權限的玩家執行：

```text
/acelib status
```

預期報告包含：

```text
Version: 1.0.0
Platform: Paper
Ready: true
Modules:
  scheduler: READY - ...
  config: READY - ...
```

Folia 伺服器的 `Platform` 應顯示 `Folia`；Paper 伺服器應顯示 `Paper`。`Ready: true` 表示 AceLib 已啟用並可供下游插件使用。`Modules` 會列出模組狀態，例如 `READY`、`NOT_INITIALIZED`、`UNAVAILABLE`、`FAILED` 或 `DEGRADED`；異常狀態需繼續查看啟動日誌與 `ACELIB-*` 錯誤碼。

## 常見失敗

- 找不到 `build/libs/AceLib-1.0.0.jar`：確認命令是在 AceLib repository 根目錄執行，並重新執行 `./gradlew clean build`。
- 只放入下游插件、未放入 AceLib：`depend: [AceLib]` 會使下游插件無法載入。
- `Ready: false`：檢查 AceLib 啟用日誌與 provider 狀態。
- `ACELIB-CTX-003`：Folia 上在錯誤 region context 操作玩家、實體或方塊。
- `ACELIB-SCHED-005`：目前平台不支援指定排程模式。
- 不要使用 Bukkit `/reload`；AceLib 的 reload 只指內部交易式重載，Bukkit `/reload` 可能造成狀態不一致。

## 下一步

- `/acelib status` 與完整錯誤表：根 [README.md](../../README.md)
- 平台與 Folia 限制：根 [README.md](../../README.md#平台差異folia-與-paper)
- 版本與發布限制：[Consumer 相容性指南](../consumer/compatibility.md)

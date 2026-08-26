# 在 Paper 或 Folia 部署 AceLib

AceLib 1.0.0 需要 Java 25，採用的 server 版本是 Paper 或 Folia 26.1.2。GitHub Release 目前沒有 JAR asset，請從公開 repository 建置。

## 建立 runtime JAR

```bash
git clone https://github.com/smile-minecraft/AceLib.git
cd AceLib
git checkout v1.0.0
./gradlew clean build --no-daemon --console=plain
```

建置成功後會產生：

```text
build/libs/AceLib-1.0.0.jar
```

`build/libs/` 也可能包含 sources 與 javadoc JAR。Server 只需要沒有後綴的 `AceLib-1.0.0.jar`。

## 放進 server

1. 停止 server。
2. 把 `AceLib-1.0.0.jar` 複製到 server 的 `plugins/`。
3. 把需要 AceLib 的下游 plugin JAR 也放進 `plugins/`。下游 plugin 的 `plugin.yml` 應含 `depend: [AceLib]`。
4. 以 Java 25 啟動 Paper 或 Folia。

例如：

```bash
cp build/libs/AceLib-1.0.0.jar /path/to/server/plugins/
cd /path/to/server
java -jar paper-26.1.2.jar --nogui
```

Folia 請將最後一行換成實際的 Folia server JAR 檔名。

## 啟動後檢查

在 console 執行：

```text
acelib status
```

玩家使用 `/acelib status` 需要 `acelib.admin`；此權限預設只給 op。正常輸出會包含：

```text
=== AceLib Diagnostics Report ===
Version: 1.0.0
Platform: Paper
Ready: true
```

Folia server 的 `Platform` 應顯示 `Folia`。報告也會列出模組狀態與最近的錯誤。`Ready: true` 表示 AceLib 可以提供服務；若為 `false`，請回頭查看啟動日誌。

## Paper 與 Folia 的差異

Paper 使用全域同步排程；Folia 會把玩家、實體與世界位置分配到各自的 region。AceLib 會依平台選擇排程方式，但下游 plugin 仍須遵守 Folia 的 region 規則。

若 Folia 日誌出現 `ACELIB-CTX-*` 或 `ACELIB-SCHED-*`，通常是下游 plugin 在錯誤的執行緒操作玩家、實體或方塊。請將錯誤碼連同前後日誌交給 plugin 開發者，並對照[錯誤碼頁](../reference/error-codes.md)。

MockBukkit 測試不能代替真實 Folia region scheduler 驗證。升級或加入新的 region 相關功能後，應在測試 server 上先做完整啟動與操作測試。

## 不要使用 Bukkit `/reload`

AceLib 不支援 Bukkit `/reload`。請正常停止並重新啟動 server。文件或 API 中提到的 AceLib reload 是函式庫自己的生命週期操作，不等於 Bukkit 指令。

## 基岩玩家支援（Geyser/Floodgate）

AceLib 可偵測基岩版玩家（經 Geyser 連線、由 Floodgate 提供身分）並傳送原生表單。相關 API 見[基岩版玩家模組](../modules/bedrock.md)與[表單模組](../modules/form.md)。

### 前提

server 需安裝 Floodgate plugin（搭配 Geyser，可同機或位於 proxy）。

### Geyser 裝在 proxy 時的必要設定

- proxy 端 `send-floodgate-data: true`（來源：GeyserMC Floodgate Setup 文件）。
- 所有後端 server 使用同一份 `key.pem`，並妥善保護金鑰。

### 啟動後如何確認

執行 `acelib status`，或查看啟動日誌中的 Floodgate 整合狀態。未安裝 Floodgate 時 AceLib 仍正常運作，只是基岩查詢永遠回 false、表單發送被拒。

版本資訊見[相容性](../consumer/compatibility.md)的基岩版支援段落。

## 常見問題

### 下游 plugin 顯示 missing dependency

確認 `plugins/` 同時有 AceLib JAR，並檢查 AceLib 是否在啟用時先發生錯誤。

### 找不到 `build/libs/AceLib-1.0.0.jar`

確認你在 AceLib repository 根目錄執行建置，並使用 Java 25。重新執行完整的 `./gradlew clean build --no-daemon --console=plain`，不要只找 GitHub Release asset。

### `/acelib status` 沒有權限

從 server console 執行，或授予玩家 `acelib.admin`。

### 想升級到 26.2

Paper 與 Folia 26.2 尚未驗證。先在獨立測試 server 驗證，再決定是否升級正式環境。完整版本資訊見[相容性](../consumer/compatibility.md)。

# 指令相容性探針（AceLib Command Compatibility Probe）

型別化指令框架（v1.4.0）在**真實 Paper／Folia 伺服器**上的觀察工具。本探針只使用 AceLib 公開的 Supported API（`BrigadierRegistrar`、`TypedCommand`、`TypedSubCommand`、`Arguments`、`CommandArgument`），不碰 internal package。

探針本身也驗收了一項行為：**它的 `plugin.yml` 不宣告 `commands` 區塊**，指令完全由 Paper 的 `LifecycleEvents.COMMANDS` 註冊。若 `/cprobe` 與 `/cprobe-args` 仍可用，即證明「Brigadier 註冊取代 `plugin.yml` 宣告需求」成立。

## 建置

```bash
# 1. 先在 AceLib 根目錄發布本機產物（探針以 mavenLocal 解析 AceLib）
./gradlew publishToMavenLocal
# 2. 建置探針
./gradlew -p examples/command-compatibility-probe build
```

產出 jar：`examples/command-compatibility-probe/build/libs/acelib-command-compatibility-probe-1.0.0-SNAPSHOT.jar`

案例目錄的完整性測試可獨立執行（不需伺服器）：

```bash
./gradlew -p examples/command-compatibility-probe test
```

## 部署

部署前先備份 jar 與 `plugins/` 現況。將探針 jar 與 AceLib jar 一起放進測試服的 `plugins/`，啟動後確認日誌出現：

```
registered 2 probe commands (no plugin.yml commands block)
probe cases: 42
```

若出現 `probe registration failed`，代表註冊被拒（例如與其他插件同名衝突），請看後續 SEVERE 訊息。

## 指令

| 指令 | 權限 | 用途 |
| --- | --- | --- |
| `/cprobe parse <player>` | `acelibcmdprobe.use` | 在線玩家解析 |
| `/cprobe parse-offline <name>` | 同上 | 離線玩家解析 |
| `/cprobe parse-int <value>` | 同上 | 整數（範圍 1–64） |
| `/cprobe parse-double <value>` | 同上 | 小數（範圍 0–10） |
| `/cprobe parse-duration <value>` | 同上 | 時間長度（ticks） |
| `/cprobe parse-world <name>` | 同上 | 世界 |
| `/cprobe parse-mode <buy\|sell>` | 同上 | 列舉固定選項 |
| `/cprobe parse-fixed <buy\|sell>` | 同上 | 固定字串選項 |
| `/cprobe parse-material <name>` | 同上 | 材質 |
| `/cprobe-args trade <buy\|sell> <amount>` | 無 | 開放式引數觀察面（無權限限制） |
| `/cprobe lifecycle <re-register\|shutdown\|status>` | `acelibcmdprobe.admin`（僅 console） | 生命週期殘留檢查 |

別名：`/cp`。

每個 handler 都會回報 **執行緒名稱**（`thread=...`）。這是執行緒驗收的主要證據：Paper 應為主執行緒（`Server thread`），Folia 應為 region 執行緒名稱。探針不在該執行緒阻塞，也不跨執行緒操作實體，因此不會違反 Folia 規則。

## 觀察清單

案例 id、輸入與預期觀察由 `CommandProbeCases` 固定（可在 server log 用 `/cprobe lifecycle status` 確認案例數）。執行 `/cprobe lifecycle status` 可取得已註冊根指令數、最後一次 handler 的執行緒與平台版本。

### 一、解析路徑（每項應回 `ok value=... type=... thread=...`）

- `parse <在線玩家名>` → `ok value=<名> (online=true)`
- `parse STEVE` → 與上一項同結果（大小寫不敏感）
- `parse-offline <玩過的玩家名>` → `ok value=<名> (playedBefore=true)`
- `parse-int 1` / `parse-int 64` → 範圍端點 inclusive
- `parse-double 2.5` → `ok value=2.5`
- `parse-duration 100` → `ok value=100`（ticks）
- `parse-duration 1s` → `ok value=20`
- `parse-duration 1d` → `ok value=24000`
- `parse-duration 1.5s` → `ok value=30`
- `parse-world overworld` → `ok value=overworld`（預設維度名為 `overworld`；其他世界名稱請覆寫）
- `parse-mode buy` → `ok value=BUY`
- `parse-fixed sell` → `ok value=sell`
- `parse-material stone` → `ok value=STONE`

### 二、錯誤路徑（每項應回 `ACELIB-CMD-015`，玩家離線為 `ACELIB-CMD-007`）

- `parse-int abc`、超範圍 `0` / `65`、`99999999999999999999`（溢位不 wrap）
- `parse-double NaN`、`Infinity`、`99`
- `parse-duration 5h`（兩端皆拒絕，與 vanilla time 一致）、`99999999999999999999d`（溢位）、超長小數位
- `parse-world nosuchworld`、`parse-material nosuchmaterial`
- `parse-mode unknownmode`、`parse-fixed unknownmode`
- `parse-offline NeverPlayedPlayer`（從未上線 → `ACELIB-CMD-015`）
- `parse NobodyOnline`（離線玩家 → `ACELIB-CMD-007`）
- `parse-int 1 2`（參數數量不符）

### 三、補全

用 Java 版按 Tab 觀察；基岩版需維護者用基岩客戶端經 Geyser 連線觀察。

| 輸入位置 | Java 版（伺服器建議） | 基岩版（Geyser） |
| --- | --- | --- |
| `/cprobe parse-mode ` | 列出 `buy`／`sell` | **應可見**（literal 分支） |
| `/cprobe parse-fixed ` | 列出 `buy`／`sell` | **應可見**（literal 分支） |
| `/cprobe-args trade ` | 列出 `buy`／`sell` | **應可見**（literal 分支） |
| `/cprobe parse ` | 在線玩家名 | 不可見 |
| `/cprobe parse-world ` | 已載入世界名 | 不可見 |
| `/cprobe parse-material ` | 材質名 | 不可見 |
| `/cprobe parse-duration ` | 語法範例（`1s`／`1d`…） | 不可見 |
| `/cprobe parse-offline ` | 只列在線玩家 | 不可見 |
| `/cprobe parse-int ` | 無建議（範圍由客戶端驗證） | 不適用 |

**限制**：Geyser 只解析編譯進指令結構的固定選項，伺服器即時算出的建議送不到基岩版。基岩補全必須由真人基岩客戶端觀察；Java 機器人的 tab 請求走 Java 協議，**不能**替代基岩客戶端的結果。

固定選項的大小寫：literal 分支以小寫常數名編譯，基岩補全顯示小寫；兩條執行路徑的 `parse` 都大小寫不敏感，因此兩種大小寫都可執行。

### 四、生命週期殘留（需 `acelibcmdprobe.admin`，以 RCON／console 執行）

| 指令 | 預期觀察 |
| --- | --- |
| `/cprobe lifecycle status` | 回報已註冊根指令數、最後 handler 執行緒、平台版本 |
| `/cprobe lifecycle re-register` | 重複註冊被 `IllegalArgumentException` 原子拒絕；平台側不掛第二個節點；日誌出現 `RE-REGISTER UNEXPECTEDLY SUCCEEDED` 即為回歸 |
| `/cprobe lifecycle shutdown` | 之後 `/cprobe status` 一律回 `ACELIB-CMD-009`，不靜默執行 |

**reload**：探針的指令在 plugin reload 後仍應可用（平台在 disable 時移除、enable 時重新註冊）。reload 後 `/cprobe lifecycle status` 應仍回報 2 個根指令，且 `/cprobe parse <玩家>` 仍能解析。若 reload 後指令消失或回 `ACELIB-CMD-009`，代表註冊時機處理有誤。

**disable**：探針停用後 `/cprobe` 應完全不存在（平台移除節點），不留下可執行但回覆錯誤的殘留指令。

## 回報格式

請把觀察結果整理成下表回報（未觀察的欄位請填「未測」，不要留空或推測）：

| 案例 id | Java 版結果 | 基岩版結果 | 備註 |
| --- | --- | --- | --- |
| `parse-int-overflow` | | | |
| … | | | |
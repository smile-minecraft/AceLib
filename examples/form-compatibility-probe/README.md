# 表單相容性探針（form-compatibility-probe）

部署到 Folia 測試服後，用 `/fprobe` 指令把固定、可重複的 `FormSpec` 測試案例
經 AceLib `FormService` 發送給玩家，供真人用 Bedrock（經 Geyser）客戶端觀察。
本探針只使用 AceLib 公開 Supported API；轉換由 AceLib 內部翻譯層負責。

## 建置

探針以 mavenLocal 解析 AceLib 1.4.0（與 consumer-plugin 相同慣例），需要兩步。
產出 jar 位於 `build/libs/`。

```bash
./gradlew publishToMavenLocal
cd examples/form-compatibility-probe && ../../gradlew build
```

只跑案例目錄完整性測試：

```bash
cd examples/form-compatibility-probe && ../../gradlew test
```

## 部署

將 AceLib jar 與探針產出的 jar 同時放入測試服的 `plugins/` 目錄後啟動伺服器。
探針以 `depend: [AceLib]` 保證 AceLib 先載入；AceLib 缺席或未就緒時拒絕發送。

## 發送與觀察

```text
/fprobe list                     # 列出 15 個案例 id、說明與預期觀察點（不發送）
/fprobe send                     # 把全部案例依序發送給執令者本人
/fprobe send <player>            # 改發送給指定線上玩家（可對準基岩玩家）
/fprobe send <player> <caseId>   # 只發送指定識別碼的那一個案例（不分大小寫）
```

建議一次只送一個案例（例如 `/fprobe send <玩家名稱> icon-url`）：
表單為彈出式介面，連續發送多個表單會互相取代，不利逐案截圖觀察。

- 每個案例發送後，伺服器 log 寫入
  `[fprobe-send] case=<id> target=<name> kind=<SIMPLE|MODAL|CUSTOM>
  buttons=<n> result=<SENT|REJECTED>`（custom 表單以 `components=<n>` 代替
  `buttons`）。`SENT` 只代表已送出，不等於客戶端顯示正確。
- 玩家每次回應，伺服器 log 寫入
  `[fprobe-response] case=<id> player=<name> <response>`。
- Modal 案例（`modal-confirm`、`modal-default-buttons`）需分三次送出：
  點第一顆（`clickedButton` 為 0）、重送後點第二顆（為 1）、重送後直接關閉
  （CLOSED 且無索引）。
- Custom 案例（`custom-all-components`、`custom-defaults`）送出前先逐一確認
  各元件預設值與顯示順序，送出後再對照 `values` 順序（label 不產值、不佔位）。

觀察結果回填到 `docs/reference/bedrock-form-compatibility-matrix.md`。
只有真人客戶端觀察才能改狀態；只憑送出日誌的格子一律維持 `未驗證`。

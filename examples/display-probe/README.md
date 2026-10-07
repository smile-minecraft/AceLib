# 顯示模組 Paper／Folia 實機探針

`/dprobe` 透過 AceLib 公開 `DisplayService` 建立每玩家計分板、專屬 BossBar 與位置型 TextDisplay，全程只操作本探針建立的資源。

## 建置

```bash
# 在 AceLib 根目錄發布本機產物
./gradlew publishToMavenLocal
# 建置探針 plugin
./gradlew -p examples/display-probe build
# 驗證指令格式案例
./gradlew -p examples/display-probe test
```

JAR 位於 `examples/display-probe/build/libs/acelib-display-probe-1.0.0-SNAPSHOT.jar`。

## 執行與觀察

將探針 JAR 與 AceLib 一起放入測試伺服器的 `plugins/` 後重啟。以有權限的線上玩家執行：

```text
/dprobe [run]
/dprobe keep
/dprobe finish <id>
```

`/dprobe` 或 `/dprobe run` 會依序建立、更新、顯示／隱藏及移除三種顯示；有第二位玩家在線時，只有執行者會看見全息字。每一階段輸出 `[dprobe]` 結果，最後檢查玩家顯示與全息字追蹤是否仍有殘留。沒有第二位玩家時，日誌會註明可見性對照略過。

跨區驗證使用兩階段流程：先在全息字預定位置執行 `/dprobe keep`，記下回覆與伺服器記錄中的 `id`；等待生成完成後，把同一位執行者傳送到不同 region，再執行 `/dprobe finish <id>`。若回覆指出尚未生成，稍後重試 `finish`。它會依序更新文字、對遠端執行者顯示再隱藏全息字、移除，並檢查 AceLib 是否仍追蹤該 id。`tracked=false` 只代表 AceLib 追蹤已清除；世界中是否還有 TextDisplay 實體須另行檢查。

Paper／Folia 實機結果仍需由測試者確認：完整流程應觀察計分板、BossBar、全息字文字更新、第二玩家不可見、清理後顯示消失，以及最後的 `residue hologram=false player=false`。跨區流程則需確認 `finish` 的更新、遠端顯示／隱藏、移除與 `tracked=false`。MockBukkit 4.113.1 雖包含 `TextDisplayMock`，但其 `setVisibleByDefault` 尚未實作，不能替代此實測。

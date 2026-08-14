# Consumer Plugin Fixture（Quick Start 編譯驗證）

本目錄是 AceLib 下游消費者（consumer）plugin 的最小可編譯範例，
驗證 README / Quick Start 描述的方式真的能編譯出乾淨的 plugin。

## 這個 fixture 驗證什麼

- 依 `depend: [AceLib]` 宣告載入順序（plugin descriptor）。
- 以正式 `AceLibApi.AceLibProvider` contract 經 Bukkit `ServicesManager` 取得 API，
  **不** import `com.smile.acelib.AceLibPlugin`、**不**做 unchecked cast。
- 正確處理 provider missing / not-ready / disable 與平台能力分支。

## 前置

- Java 25（toolchain 自動下載，或本機已有 JDK 25）。
- AceLib 目前（`1.0.0` Release Candidate）**尚未發布到外部 repository**；
  fixture 依賴本地 Maven artifact，先發布一次：

```bash
# 在 AceLib 根目錄執行
./gradlew publishToMavenLocal
```

## 編譯與驗證

```bash
# 在 AceLib 根目錄執行（wrapper 為 9.5.1，fixture 是獨立 Gradle build）

# 1. 編譯 consumer plugin
./gradlew -p examples/consumer-plugin compileJava --console=plain

# 2. 完整驗證（compile + verifyConsumerDocs：stale symbol / relative link / version）
./gradlew -p examples/consumer-plugin build --console=plain

# 3. 只跑文件檢查（不需網路）
./gradlew -p examples/consumer-plugin verifyConsumerDocs --console=plain
```

## 錯誤示範（可重現 Red）

把 `QuickStartPlugin.onEnable()` 改成舊 README 的 stale contract：

```java
import com.smile.acelib.AceLib; // 不存在
AceLibApi api = AceLib.getApi(); // 不存在
```

編譯會失敗：

```text
error: cannot find symbol
  import com.smile.acelib.AceLib;
error: cannot find symbol
  AceLibApi api = AceLib.getApi();
```

這正是「README 曾教的 API 無法編譯」的證據；正式寫法見
`src/main/java/com/example/acelibconsumer/QuickStartPlugin.java`。

## 限制

- 本 fixture 只做**編譯驗證**，不會在 MockBukkit 或真實伺服器啟動
  （consumer plugin 的 runtime 行為由 AceLib 自身的測試覆蓋）。
- 不發布任何 artifact；`com.smile.consumer:acelib-consumer-quickstart` 僅為 fixture 自身座標。

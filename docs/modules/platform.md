# 平台偵測與能力

本頁解決插件如何判斷目前是 Folia、Paper 或 UNKNOWN，以及如何依平台能力選擇安全路徑的問題。

## 第一個安全操作

一般插件作者不需要自行建立 `PlatformDetector`。從 ready 的 `AceLibApi` 取得 `PlatformCapability`，再依能力分流：

```java
PlatformCapability capability = api.getPlatformCapability();
if (capability.regionScheduling()) {
    // Folia：使用 region-safe 排程
} else if (capability.globalScheduler()) {
    // Paper：使用全域同步排程
} else {
    // UNKNOWN：保守降級，不執行受限操作
}
```

`PlatformCapability` 是描述平台能力的不可變資料物件；UNKNOWN 的能力全部為 `false`。

## 不能做什麼

- 不要在一般下游插件中自行反射 classpath 取代 `AceLibApi` 的平台能力。
- 不要把 Paper 的全域 scheduler 當成 Folia 玩家、實體或方塊操作的預設路徑。
- 不要把 UNKNOWN 寫成已支援平台；降級時保留 `ACELIB-PLAT-004` warning。

## 深入說明

契約唯一來源是 `src/main/java/com/smile/acelib/platform/**` source 與 tests；本頁為導覽鏡像，不複製完整 JavaDoc。

### 取得方式與獨立偵測

一般下游插件**不需**自行建立 `PlatformDetector`；直接從 `AceLibApi` 讀取：

```java
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;

Platform platform = api.getPlatform();              // FOLIA / PAPER / UNKNOWN
PlatformCapability capability = api.getPlatformCapability(); // 永不為 null
```

需要獨立偵測（測試、工具類）時才建立 `PlatformDetector`：

```java
PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
Platform detected = detector.detect();
PlatformCapability cap = detector.detectCapability(detected);
```

### 最小安全範例

```java
// 依能力決定是否啟用 Folia regionized 排程
if (capability.regionScheduling()) {
    // 走 Folia entity/region scheduler
} else if (capability.globalScheduler()) {
    // 走 Paper 全域 scheduler（BukkitScheduler / GlobalRegionScheduler on Folia）
} else {
    // UNKNOWN：保守降級，不觸發受限能力
}
```

### 能力分流語意

| 能力欄位 | FOLIA | PAPER | UNKNOWN |
| --- | --- | --- | --- |
| `regionScheduling` | true | false | false |
| `globalScheduler` | true | true | false |
| `bukkitApi` | true | true | false |
| `foliaThreadedRegionsApi` | true | false | false |

- `PlatformCapability.forPlatform(Platform)` 對 UNKNOWN 一律回傳全 false
  （保守策略：不明環境不誤觸發受限能力）。
- `PlatformDetector.detect()` 判定順序：Folia marker
  （`io.papermc.paper.threadedregions.RegionizedServer`）→ Bukkit marker
  （`org.bukkit.Bukkit`）→ UNKNOWN。

### 執行緒與生命週期

- `Platform` 列舉與 `PlatformCapability` record 為不可變值型別，可在任何 thread
  使用；序列化相容：列舉常數順序凍結，不得更動。
- `PlatformDetector` 持有 final classloader reference（無 mutable 欄位），可在
  任何 thread 安全呼叫；但它會讀取 classpath / Bukkit 全域狀態並輸出 fine-level
  debug log（有 side effect），且回傳值依賴建構時注入的 classloader，因此不是
  嚴格意義的純函式。
- 版本偵測（`detectMinecraftVersion` / `detectJavaVersion`）為 null-safe，
  失敗回傳 `"unknown"`，不丟例外。

### 常見失敗與錯誤處理

- `PlatformDetector` 建構子對 null classloader 拋 `IllegalArgumentException`。
- `detectCapability(null)` 拋 `NullPointerException`。
- 平台偵測為 UNKNOWN 時，plugin 輸出 `ACELIB-PLAT-004` warning；
  下游可依 `capability` 全 false 決定降級行為。

### 查核來源

- 型別：`Platform`、`PlatformCapability`、`PlatformDetector`
- 測試：`src/test/java/com/smile/acelib/platform/PlatformCapabilityTest.java`、
  `PlatformDetectorTest.java`、`PlatformDetectorFoliaSimulationTest.java`
- 下一步：[docs/modules/scheduler.md](scheduler.md)、
  [docs/modules/core.md](core.md)

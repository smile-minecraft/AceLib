# 傳送基岩原生表單

> 適合要向基岩版玩家發送原生表單並處理回應的插件開發者。


AceLib 提供自有表單 DSL，讓你對基岩版玩家顯示 Geyser/Floodgate 的原生表單（simple / modal / custom）。先從 `BedrockService` 取得 `FormService`：

```java
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormService;

// bedrock 為 api.getBedrockService() 取得的實例
FormService forms = bedrock.forms();
```

## 目錄

- [建立表單](#建立表單)
- [發送表單](#發送表單)
- [讀取回應](#讀取回應)
- [回應派送保證](#回應派送保證)
- [生命週期](#生命週期)
- [錯誤碼](#錯誤碼)

## 建立表單

三種表單都從 `FormSpec` 的 static builder 開始，每個 builder 的參數在呼叫當下就驗證（空白或 null 會以 `IllegalArgumentException` 拒絕）。

**Simple（按鈕列表）**

```java
import com.smile.acelib.form.FormSpec;

FormSpec menu = FormSpec.simple("選單標題")
    .content("請選擇一個選項")
    .button("選項 A")
    .button("選項 B")
    .build();
```

**Modal（兩按鈕確認）**

```java
FormSpec confirm = FormSpec.modal("確認操作")
    .content("確定要執行嗎？")
    .button1("確定")
    .button2("取消")
    .build();
```

**Custom（元件式）**

`custom` 表單可組合 label、input、dropdown、slider、stepSlider 與 toggle：

```java
import java.util.List;

FormSpec settings = FormSpec.custom("設定")
    .label("下面是你的偏好")
    .input("暱稱", "輸入暱稱", "")
    .dropdown("主題", List.of("淺色", "深色"), 0)
    .slider("音量", 0f, 10f, 1f, 5f)
    .stepSlider("難度", List.of("簡單", "普通", "困難"), 0)
    .toggle("接收通知", true)
    .build();
```

`dropdown` 與 `stepSlider` 另有省略預設索引的多載（預設選第一項／第一步）。

## 發送表單

兩參數版本只回傳發送結果：

```java
import com.smile.acelib.form.FormSendResult;

FormSendResult result = forms.sendForm(player.getUniqueId(), menu);
if (result.isSent()) {
    // Floodgate 已接受送出；不代表玩家已看到或已回應
}
```

`FormSendResult` 只有兩種：`SENT`（Floodgate 接受遞送）與 `REJECTED`（Floodgate 拒絕，例如玩家已離線或非基岩玩家）。`SENT` 只代表 Floodgate 已接受送出，不代表玩家已看到或回應。

若 Floodgate 未安裝（表單傳輸層未啟用），`sendForm` 會拋出攜帶 `ACELIB-FORM-001` 的 `IllegalStateException`。

三參數版本多收一個 `Consumer<FormResponse>`，用來接收玩家回應：

```java
import com.smile.acelib.form.FormResponse;

forms.sendForm(player.getUniqueId(), menu, response -> {
    switch (response.status()) {
        case VALID -> { /* 玩家提交了有效回應 */ }
        case CLOSED -> { /* 玩家關閉表單，未提交任何資料 */ }
        case INVALID -> { /* 回應無法解析為有效內容 */ }
    }
});
```

## 讀取回應

`FormResponse.status()` 有三種：

- `VALID` — 玩家提交了有效回應（按下按鈕或填寫並送出元件）。
- `CLOSED` — 玩家直接關閉表單，沒有提交資料；這是正常行為，不是錯誤。
- `INVALID` — 玩家送出的資料無法解析；不要把這種狀態的內容當成玩家意圖。

Simple / Modal 表單用 `clickedButton()` 取得被點按的按鈕索引（`Optional<Integer>`）；Custom 表單用 `values()` 取得各元件答案，依「會產值的元件」順序排列（label 不產值）：

```java
import com.smile.acelib.form.FormValue;

for (FormValue value : response.values()) {
    switch (value) {
        case FormValue.Text t -> { String text = t.value(); }
        case FormValue.Option o -> { int index = o.index(); }
        case FormValue.Number n -> { float num = n.value(); }
        case FormValue.Switch s -> { boolean on = s.on(); }
    }
}
```

只有 `VALID` 的回應才攜帶內容；`CLOSED` / `INVALID` 的 `clickedButton()` 為 empty、`values()` 為空清單。

## 回應派送保證

註冊的回應 consumer 有以下保證：

- **執行緒無關**——不論 Floodgate 的回呼發生在哪個執行緒，consumer 一律先重新派送到玩家所屬的 region context 才執行（Folia 上對應 entity scheduler，Paper 上對應 main thread），絕不在回呼來源執行緒直接執行。
- **最多一次**——有效且屬於目前服務生命週期的結果最多執行一次；重複回呼、查無 token、已 shutdown 或生命週期代謝都會被丟棄。
- **失效即清理**——玩家離線、發送被拒、服務 shutdown／reload／disable 時，consumer 執行零次，且不留 pending 狀態。

## 生命週期

AceLib reload 或停用後，舊的回應 callback 不會再執行。服務 shutdown 後，`sendForm` 以攜帶 `ACELIB-FORM-002` 的 `IllegalStateException` 拒絕。

正在送出的表單可能仍回傳 `SENT`（表單已送達），但其回應註冊已一併清空，遲到的回呼同樣零執行。

## 錯誤碼

表單服務相關錯誤見[錯誤碼頁](../reference/error-codes.md#表單服務form)的 `ACELIB-FORM-*` 段落。

## 相關頁面

- [基岩版玩家](bedrock.md)
- [外部整合](external.md)
- [錯誤碼](../reference/error-codes.md)

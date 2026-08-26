package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormValue.Number;
import com.smile.acelib.form.FormValue.Option;
import com.smile.acelib.form.FormValue.Switch;
import com.smile.acelib.form.FormValue.Text;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FormResponse / FormValue 公開值型別契約：immutable、存取器語意、
 * sealed 窮舉與輸入驗證。
 */
@DisplayName("FormResponse / FormValue 值型別")
class FormResponseTypesTest {

    // -----------------------------------------------------------------
    // FormResponse
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FormResponse：status/clickedButton/values 存取器語意；null values 正規化為空清單")
    void formResponse_accessorsAndNullNormalization() {
        FormResponse withButton = new FormResponse(
            FormResponseStatus.VALID, 2, List.of(new Option(1)));
        assertEquals(FormResponseStatus.VALID, withButton.status());
        assertEquals(2, withButton.clickedButton().orElseThrow());
        assertEquals(List.of(new Option(1)), withButton.values());

        FormResponse withoutContent = new FormResponse(FormResponseStatus.CLOSED, null, null);
        assertTrue(withoutContent.clickedButton().isEmpty(), "無按鈕語意必須回 empty");
        assertTrue(withoutContent.values().isEmpty(), "null values 必須正規化為空清單");
    }

    @Test
    @DisplayName("FormResponse：values 防禦性複製——外部清單事後修改不影響內部狀態")
    void formResponse_valuesDefensivelyCopied() {
        List<FormValue> mutable = new ArrayList<>();
        mutable.add(new Text("a"));
        FormResponse response = new FormResponse(FormResponseStatus.VALID, 0, mutable);

        mutable.add(new Switch(true));

        assertEquals(List.of(new Text("a")), response.values(),
            "values 必須在建構當下快照");
        assertThrows(UnsupportedOperationException.class,
            () -> response.values().add(new Number(1.0f)),
            "values 必須為 immutable 清單");
    }

    @Test
    @DisplayName("FormResponse：status 為 null → NullPointerException；equals/hashCode 依值")
    void formResponse_nullStatusAndValueEquality() {
        assertThrows(NullPointerException.class,
            () -> new FormResponse(null, 0, List.of()));

        FormResponse a = new FormResponse(FormResponseStatus.VALID, 1, List.of(new Option(0)));
        FormResponse b = new FormResponse(FormResponseStatus.VALID, 1, List.of(new Option(0)));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // -----------------------------------------------------------------
    // FormValue：sealed 窮舉
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FormValue：四種 nested record 可窮舉且攜帶各自答案型別")
    void formValue_fourKindsCarryTheirPayload() {
        FormValue[] values = {
            new Text("hello"),
            new Option(3),
            new Number(42.5f),
            new Switch(true),
        };

        assertEquals("hello", ((Text) values[0]).value());
        assertEquals(3, ((Option) values[1]).index());
        assertEquals(42.5f, ((Number) values[2]).value());
        assertTrue(((Switch) values[3]).on());

        // sealed permits 與 nested record 型別一致（switch 窮舉可編譯即證明）
        String kind = switch (values[0]) {
            case Text t -> "text";
            case Option o -> "option";
            case Number n -> "number";
            case Switch s -> "switch";
        };
        assertEquals("text", kind);
    }

    @Test
    @DisplayName("FormValue.Text：null value → NullPointerException")
    void formValueText_rejectsNull() {
        assertThrows(NullPointerException.class, () -> new Text(null));
    }
}

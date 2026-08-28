package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adventure 相容邊界：production 位元組碼防護網。
 *
 * <p>同一份 production JAR 必須同時在 Adventure 4.26.1 與 5.2.0 runtime 建立基岩
 * fallback hint。Adventure 5 移除了 {@code ClickEvent.value()}、將
 * {@code ClickEvent.Action} 由 enum 改為 sealed class，因此 production 不得引用：</p>
 * <ul>
 *   <li>{@code ClickEvent.value()}（method ref）；</li>
 *   <li>{@code ClickEvent.Action.values()} / {@code valueOf()}（method ref，
 *       含 enum switch 產生的 synthetic class 對 {@code values()} 的引用）；</li>
 *   <li>{@code ClickEvent.Action} 的 static 常數欄位（RUN_COMMAND / SUGGEST_COMMAND /
 *       OPEN_URL / COPY_TO_CLIPBOARD / CHANGE_PAGE / OPEN_FILE / SHOW_DIALOG / CUSTOM）。</li>
 * </ul>
 *
 * <p>本測試以最小常數池解析器（不引入 ASM 等新依賴）掃描 message 套件所有
 * production .class（含 synthetic / nested class），還原 method / field reference 的
 * {@code owner.name:descriptor} 並比對禁止模式。現況（舊寫法）必須因
 * {@code ClickEvent.value} 與 {@code Action.values} 被偵測而失敗；抽離 package-private
 * click descriptor helper 後方得通過。</p>
 */
@DisplayName("Adventure 相容邊界：production 位元組碼不得引用 v5 移除的 Adventure API")
class AdventureCompatBytecodeGateTest {

    /** 掃描樣本下限：低於此數代表編譯輸出不完整，防護網失效。 */
    private static final int MIN_SCANNED_CLASSES = 5;

    /** 禁止出現的 method / field reference 子串（internal name 形式）。 */
    private static final List<String> FORBIDDEN = List.of(
        // ClickEvent.value() 在 v5 已移除
        "ClickEvent.value:()Ljava/lang/String;",
        // enum switch 產生的 synthetic class 會引用 Action.values()
        "ClickEvent$Action.values:()",
        // 直接引用 Action 常數（enum 欄位）或 valueOf()
        "ClickEvent$Action.valueOf:(",
        "ClickEvent$Action.RUN_COMMAND:",
        "ClickEvent$Action.SUGGEST_COMMAND:",
        "ClickEvent$Action.OPEN_URL:",
        "ClickEvent$Action.COPY_TO_CLIPBOARD:",
        "ClickEvent$Action.CHANGE_PAGE:",
        "ClickEvent$Action.OPEN_FILE:",
        "ClickEvent$Action.SHOW_DIALOG:",
        "ClickEvent$Action.CUSTOM:"
    );

    /**
     * 禁止出現的 class 引用 owner 前綴（internal name 形式）。
     * Adventure 5 將 {@code ClickEvent.Action} 改為 sealed class，其子型別
     * （{@code ClickEvent$Action$RunCommand} 等）在 production 中不得被直接參照，
     * 否則會綁死 v5 專屬型別。注意 {@code ClickEvent$Action} 基型本身不在禁止之列。
     */
    private static final List<String> FORBIDDEN_CLASS_PREFIXES = List.of(
        "net/kyori/adventure/text/event/ClickEvent$Action$"
    );

    @Test
    @DisplayName("message 套件 production .class 不含 v5 移除的 ClickEvent / Action 引用")
    void productionBytecode_freeOfAdventureV5RemovedRefs() throws IOException {
        Path classesDir = productionClassesDir();
        Path messagePackage = classesDir.resolve("com/smile/acelib/message");
        assertTrue(Files.isDirectory(messagePackage), "缺少 message 套件編譯輸出：" + messagePackage);

        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(messagePackage)) {
            classFiles = walk
                .filter(p -> p.toString().endsWith(".class"))
                .sorted()
                .toList();
        }
        assertTrue(classFiles.size() >= MIN_SCANNED_CLASSES,
            "掃描樣本僅 " + classFiles.size() + " 個 .class，低於防護網下限 "
                + MIN_SCANNED_CLASSES + "，編譯輸出可能不完整");

        List<String> violations = new ArrayList<>();
        for (Path classFile : classFiles) {
            ParsedCp parsed = parseConstantPool(Files.readAllBytes(classFile));
            for (String v : findViolations(parsed.refs, parsed.classRefs)) {
                violations.add(classFile.getFileName() + " -> " + v);
            }
        }
        assertTrue(violations.isEmpty(),
            "production 位元組碼不得引用 v5 移除的 Adventure API（違規 "
                + violations.size() + " 筆）：\n" + String.join("\n", violations));
    }

    private static Path productionClassesDir() {
        Path dir = Paths.get(System.getProperty("user.dir"),
            "build", "classes", "java", "main");
        assertTrue(Files.isDirectory(dir),
            "找不到 production 編譯輸出目錄：" + dir + "（test task 應已先編譯 main sourceset）");
        return dir;
    }

    /**
     * 常數池解析結果：method / field reference 與 class reference（internal name 形式）。
     */
    private static final class ParsedCp {
        final List<String> refs;
        final List<String> classRefs;
        ParsedCp(List<String> refs, List<String> classRefs) {
            this.refs = refs;
            this.classRefs = classRefs;
        }
    }

    /**
     * 解析 class 檔常數池，還原所有 method / field reference 與 class reference。
     *
     * <p>常數池條目可能參照到「較後面」的索引（例如 {@code Class} 的 name_index 指向
     * 之後才出現的 {@code Utf8}），因此分兩趟：先讀出所有原始條目，再解析名稱。</p>
     */
    private static ParsedCp parseConstantPool(byte[] b) {
        List<String> refs = new ArrayList<>();
        List<String> classRefs = new ArrayList<>();
        // u4 magic, u2 minor, u2 major
        int pos = 8;
        int cpCount = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
        pos += 2;
        String[] utf8 = new String[cpCount];
        int[] classUtf8 = new int[cpCount];
        int[] natName = new int[cpCount];
        int[] natType = new int[cpCount];
        int[] refClass = new int[cpCount];
        int[] refNat = new int[cpCount];
        boolean[] isClass = new boolean[cpCount];
        boolean[] isNat = new boolean[cpCount];
        boolean[] isRef = new boolean[cpCount];

        // 第一趟：讀取原始條目
        for (int i = 1; i < cpCount; i++) {
            int tag = b[pos++] & 0xff;
            switch (tag) {
                case 1 -> { // Utf8
                    int len = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
                    pos += 2;
                    utf8[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                    pos += len;
                }
                case 3, 4 -> pos += 4; // Integer, Float
                case 5, 6 -> { pos += 8; i++; } // Long, Double 佔兩格
                case 7 -> { // Class
                    classUtf8[i] = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
                    pos += 2;
                    isClass[i] = true;
                }
                case 8 -> pos += 2; // String
                case 9, 10, 11 -> { // Fieldref, Methodref, InterfaceMethodref
                    refClass[i] = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
                    refNat[i] = ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
                    pos += 4;
                    isRef[i] = true;
                }
                case 12 -> { // NameAndType
                    natName[i] = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
                    natType[i] = ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
                    pos += 4;
                    isNat[i] = true;
                }
                case 15 -> pos += 3; // MethodHandle
                case 16 -> pos += 2; // MethodType
                case 17, 18 -> pos += 4; // Dynamic, InvokeDynamic
                case 19, 20 -> pos += 2; // Module, Package
                default -> fail("無法解析常數池，未知 tag：" + tag + " @ " + i);
            }
        }

        // 第二趟：解析名稱（處理前向參照）
        String[] classNames = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            if (isClass[i]) {
                String cn = utf8[classUtf8[i]];
                classNames[i] = cn;
                classRefs.add(cn);
            }
        }
        String[] nameAndTypes = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            if (isNat[i]) {
                nameAndTypes[i] = utf8[natName[i]] + ":" + utf8[natType[i]];
            }
        }
        for (int i = 1; i < cpCount; i++) {
            if (isRef[i]) {
                String owner = classNames[refClass[i]];
                String nat = nameAndTypes[refNat[i]];
                if (owner != null && nat != null) {
                    refs.add(owner + "." + nat);
                }
            }
        }
        return new ParsedCp(refs, classRefs);
    }

    private static List<String> extractReferences(byte[] b) {
        return parseConstantPool(b).refs;
    }

    private static List<String> extractClassRefs(byte[] b) {
        return parseConstantPool(b).classRefs;
    }

    /**
     * 給定 method / field reference 與 class reference，回傳違反禁止清單的項目。
     * 抽出為可單測的純函式，便於對「子型別 class 引用」防護網做 Red / Green 驗證。
     */
    static List<String> findViolations(List<String> refs, List<String> classRefs) {
        List<String> violations = new ArrayList<>();
        for (String ref : refs) {
            for (String forbidden : FORBIDDEN) {
                if (ref.contains(forbidden)) {
                    violations.add(ref);
                }
            }
        }
        for (String c : classRefs) {
            for (String prefix : FORBIDDEN_CLASS_PREFIXES) {
                if (c.startsWith(prefix)) {
                    violations.add("class:" + c);
                }
            }
        }
        return violations;
    }

    // ---- 防護網單元測試：證明 findViolations 能攔截 / 不誤攔 ----

    @Test
    @DisplayName("detection: 能攔截 ClickEvent$Action$ 子型別 class 引用")
    void detection_flagsActionSubtypeReference() {
        List<String> classRefs = List.of(
            "net/kyori/adventure/text/event/ClickEvent$Action$RunCommand");
        List<String> violations = findViolations(List.of(), classRefs);
        assertFalse(violations.isEmpty(),
            "應攔截 ClickEvent$Action$RunCommand 子型別引用，但 findViolations 回傳空");
        assertTrue(violations.stream().anyMatch(s -> s.contains("ClickEvent$Action$RunCommand")),
            "違規項目應標示子型別：" + violations);
    }

    @Test
    @DisplayName("detection: 不誤攔 ClickEvent / ClickEvent$Action 基型 / Payload 子型別")
    void detection_doesNotFlagActionBaseOrClickEvent() {
        List<String> classRefs = List.of(
            "net/kyori/adventure/text/event/ClickEvent",
            "net/kyori/adventure/text/event/ClickEvent$Action",
            "net/kyori/adventure/text/event/ClickEvent$Payload$Text");
        List<String> violations = findViolations(List.of(), classRefs);
        assertTrue(violations.isEmpty(),
            "不應攔截 ClickEvent 本身 / Action 基型 / Payload 子型別：" + violations);
    }

    @Test
    @DisplayName("detection: 仍攔截 v5 移除的 method / field 引用")
    void detection_flagsRemovedMethodRef() {
        List<String> refs = List.of(
            "net/kyori/adventure/text/event/ClickEvent.value:()Ljava/lang/String;",
            "net/kyori/adventure/text/event/ClickEvent$Action.RUN_COMMAND:");
        List<String> violations = findViolations(refs, List.of());
        assertEquals(2, violations.size(), "應攔截兩筆 method / field 引用：" + violations);
    }
}

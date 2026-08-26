package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Floodgate 缺席環境保證驗證：Floodgate / Cumulus 型別完全不在類別空間時，
 * 缺席接線路徑（{@link FormService#forProduction} 綁定
 * {@link FormService.FormSender#absent()}）的 {@code sendForm} 只以攜帶
 * {@code ACELIB-FORM-001} 的 {@link IllegalStateException} 拒絕，
 * 全程零 {@code NoClassDefFoundError}。
 *
 * <p>既有接線測試的 test classpath 含 floodgate / cumulus jar，無法證明真缺席；
 * 本測試以兩層防護互補：</p>
 * <ul>
 *   <li><strong>動態</strong>：{@link URLClassLoader} 只指向 production 編譯輸出
 *       （parent 為 platform loader，不含 test classpath），並顯式拒絕
 *       {@code org.geysermc.*}，在真缺席類別空間內重現缺席接線。</li>
 *   <li><strong>靜態</strong>：掃描 form 與 bedrock 套件所有 production .class 的
 *       常數池位元組序列，防止未來把 Floodgate 型別滲進這兩個套件；另以 external
 *       seam 實作（合法引用者）作正向對照，確保掃描本身有效。</li>
 * </ul>
 */
@DisplayName("Floodgate 缺席環境：form / bedrock 套件零 geysermc 依賴")
class FormAbsentEnvironmentTest {

    /** 隔離空間必須無法解析的外部 marker；test classpath 上存在，供對照。 */
    private static final String FLOODGATE_MARKER = "org.geysermc.floodgate.api.FloodgateApi";
    private static final String CUMULUS_MARKER = "org.geysermc.cumulus.form.SimpleForm";

    /** 位元組碼防護網禁止出現的常數池引用（internal name 形式）。 */
    private static final String GEYSER_REF = "org/geysermc";

    /** 正向對照：external seam 實作是 codebase 中唯一允許引用 geysermc 的位置。 */
    private static final Path POSITIVE_CONTROL =
        Paths.get("com/smile/acelib/external/FloodgateFormSender.class");

    /** 掃描樣本下限：低於此數代表編譯輸出不完整，防護網失效。 */
    private static final int MIN_SCANNED_CLASSES = 20;

    // -----------------------------------------------------------------
    // 隔離類別空間建構
    // -----------------------------------------------------------------

    private static Path productionClassesDir() {
        Path dir = Paths.get(System.getProperty("user.dir"),
            "build", "classes", "java", "main");
        assertTrue(Files.isDirectory(dir),
            "找不到 production 編譯輸出目錄：" + dir + "（test task 應已先編譯 main sourceset）");
        return dir;
    }

    /**
     * 只含 production classes 的隔離類別空間：parent 取 platform loader
     * （JDK 可見、test classpath 不可見），並顯式拒絕 {@code org.geysermc.*}
     * 以鎖死缺席語意——即使未來 parent 結構改變也不會靜默漏進外部型別。
     */
    private static URLClassLoader isolatedLoader() throws IOException {
        URL url = productionClassesDir().toUri().toURL();
        return new URLClassLoader(new URL[] {url}, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("org.geysermc.")) {
                    throw new ClassNotFoundException(
                        "absent-environment 隔離空間拒絕載入：" + name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    // -----------------------------------------------------------------
    // 防護網前提：隔離空間確實不含 Floodgate / Cumulus
    // -----------------------------------------------------------------

    @Test
    @DisplayName("隔離類別空間無法解析 Floodgate / Cumulus（對照：app classpath 上存在）")
    void isolatedClassLoader_excludesFloodgateAndCumulus() throws Exception {
        assertNotNull(getClass().getResource(classResource(FLOODGATE_MARKER)),
            "前提失效：test classpath 應含 floodgate api jar，否則本測試未證明任何事");
        assertNotNull(getClass().getResource(classResource(CUMULUS_MARKER)),
            "前提失效：test classpath 應含 cumulus jar");

        try (URLClassLoader loader = isolatedLoader()) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName(FLOODGATE_MARKER, false, loader),
                "隔離空間必須無法載入 floodgate api");
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName(CUMULUS_MARKER, false, loader),
                "隔離空間必須無法載入 cumulus form");
        }
    }

    private static String classResource(String className) {
        return "/" + className.replace('.', '/') + ".class";
    }

    // -----------------------------------------------------------------
    // 動態驗證：缺席接線路徑零連結錯誤
    // -----------------------------------------------------------------

    @Test
    @DisplayName("缺席接線：forms().sendForm(...) 只得攜帶 ACELIB-FORM-001 的 ISE，零 NoClassDefFoundError")
    void absentWiring_sendForm_rejectsWithNotReadyCode() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> serviceClass = Class.forName(
                "com.smile.acelib.form.FormService", true, loader);
            Class<?> senderClass = Class.forName(
                "com.smile.acelib.form.FormService$FormSender", true, loader);
            Class<?> specClass = Class.forName(
                "com.smile.acelib.form.FormSpec", true, loader);

            Object absentSender = senderClass.getMethod("absent").invoke(null);
            Object service = serviceClass
                .getMethod("forProduction", senderClass)
                .invoke(null, absentSender);
            assertEquals(loader, service.getClass().getClassLoader(),
                "服務實作必須由隔離類別空間定義（IllegalStateException 本身屬 JDK，"
                    + "以其 defining loader 驗證隔離會恆為 bootstrap null）");

            Object spec = buildSimpleSpec(specClass);

            Method sendForm = serviceClass.getMethod("sendForm", UUID.class, specClass);
            InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> sendForm.invoke(service, UUID.randomUUID(), spec),
                "缺席接線必須以例外拒絕發送");

            Throwable cause = failure.getCause();
            IllegalStateException rejected = assertInstanceOf(IllegalStateException.class,
                cause, "缺席拒絕必須是 IllegalStateException；"
                    + "若為 NoClassDefFoundError / LinkageError，代表 form 套件滲入外部型別");
            assertTrue(rejected.getMessage()
                    .contains(FormErrorCodes.ACELIB_FORM_SERVICE_NOT_READY),
                "拒絕必須攜帶 ACELIB-FORM-001，實際：" + rejected.getMessage());
        }
    }

    /** 在隔離類別空間內以反射建立 simple 表單 spec（builder DSL 全程不跨 loader）。 */
    private static Object buildSimpleSpec(Class<?> specClass) throws Exception {
        Object builder = specClass.getMethod("simple", String.class)
            .invoke(null, "缺席環境測試");
        builder = builder.getClass().getMethod("content", String.class)
            .invoke(builder, "Floodgate 缺席");
        builder = builder.getClass().getMethod("button", String.class)
            .invoke(builder, "關閉");
        return builder.getClass().getMethod("build").invoke(builder);
    }

    // -----------------------------------------------------------------
    // 靜態防護網：production 位元組碼不得引用 geysermc
    // -----------------------------------------------------------------

    @Test
    @DisplayName("位元組碼防護網：form 與 bedrock 套件的 production .class 不含 org/geysermc 引用")
    void productionBytecode_formAndBedrockPackages_freeOfGeyserReferences() throws Exception {
        assertPositiveControlDetectable();

        int scanned = 0;
        for (String internalPackage : List.of(
                "com/smile/acelib/form/", "com/smile/acelib/bedrock/")) {
            scanned += assertPackageFreeOfGeyserReferences(productionClassesDir(), internalPackage);
        }
        assertTrue(scanned >= MIN_SCANNED_CLASSES,
            "掃描樣本僅 " + scanned + " 個 .class，低於防護網下限 "
                + MIN_SCANNED_CLASSES + "，編譯輸出可能不完整");
    }

    /**
     * 正向對照：seam 實作類別必須被掃描器偵測出 geysermc 引用；
     * 若對照失敗代表掃描邏輯失效，防護網會空轉通過。
     */
    private static void assertPositiveControlDetectable() throws IOException {
        Path control = productionClassesDir().resolve(POSITIVE_CONTROL);
        assertTrue(Files.isRegularFile(control), "缺少正向對照檔：" + control);
        assertTrue(readClassBytes(control).contains(GEYSER_REF),
            "掃描器正向對照失敗：" + POSITIVE_CONTROL
                + " 是合法的 geysermc 引用者，應被偵測出來");
    }

    private static int assertPackageFreeOfGeyserReferences(Path classesDir,
            String internalPackage) throws IOException {
        Path packageDir = classesDir.resolve(internalPackage);
        assertTrue(Files.isDirectory(packageDir), "缺少套件編譯輸出：" + packageDir);

        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(packageDir)) {
            classFiles = walk
                .filter(path -> path.toString().endsWith(".class"))
                .sorted()
                .toList();
        }
        assertFalse(classFiles.isEmpty(), "套件沒有任何 .class 輸出：" + packageDir);

        for (Path classFile : classFiles) {
            assertFalse(readClassBytes(classFile).contains(GEYSER_REF),
                "production 位元組碼不得引用 " + GEYSER_REF + "：" + classFile
                    + "（form / bedrock 套件只准經 external seam 觸及 Floodgate）");
        }
        return classFiles.size();
    }

    /**
     * 以 ISO-8859-1 解碼保留原始位元組序列：class 常數池字串以 modified UTF-8
     * 儲存，ASCII 區段（如 {@code org/geysermc}）逐位元組對應，可直接子串搜尋。
     */
    private static String readClassBytes(Path classFile) throws IOException {
        return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
    }
}

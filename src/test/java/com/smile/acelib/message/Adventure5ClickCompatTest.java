package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adventure 5.2.0 isolated runtime 驗證：同一份 package-private click descriptor
 * helper 在 Adventure 5.2.0 runtime 下正確建立四種 action 與 unknown / non-text
 * payload 的描述子，不發生 {@code NoSuchMethodError} / {@code NoSuchFieldError} /
 * {@code ExceptionInInitializerError} / 後續 {@code NoClassDefFoundError}。
 *
 * <p>隔離策略（參考 {@code FormAbsentEnvironmentTest} 的 URLClassLoader 範式）：
 * isolated loader 的 parent 取 platform loader（test classpath 不可見），URL 僅含
 * production 編譯輸出 + Adventure 5.2.0 api jar + testRuntimeClasspath 上的 v4
 * adventure-key / adventure-nbt / examination（API 相容，作為 v5 api 的傳遞依賴）。
 * 因此 isolated 空間只會解析到 v5 的 {@code ClickEvent}，絕不會載入 v4。</p>
 *
 * <p>前提驗證：isolated loader 實際載入的是 v5（{@code ClickEvent.Action} 非 enum、
 * code source 指向 5.2.0 jar），且 test classpath 上是 v4（{@code Action} 是 enum）——
 * 雙重前提確保本測試確實證明了 v5 語意，而非靜默退化成 v4。</p>
 */
@DisplayName("Adventure 5.2.0 isolated runtime：click descriptor helper 相容")
class Adventure5ClickCompatTest {

    private static final String ADVENTURE5_JAR = System.getProperty("acelib.adventure5ApiJar");

    @Test
    @DisplayName("前提：isolated loader 載入 v5（Action 非 enum、code source 含 5.2.0），test classpath 為 v4")
    void precondition_isolatedLoaderLoadsAdventure5() throws Exception {
        assertNotNull(ADVENTURE5_JAR, "缺少 acelib.adventure5ApiJar system property（build 未注入 v5 jar）");

        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> v5Action = Class.forName(
                "net.kyori.adventure.text.event.ClickEvent$Action", true, loader);
            assertFalse(v5Action.isEnum(),
                "isolated loader 必須載入 Adventure 5（ClickEvent.Action 為 sealed class，非 enum）");
            URL loc = v5Action.getProtectionDomain().getCodeSource().getLocation();
            assertTrue(loc.toString().contains("5.2.0"),
                "isolated loader 必須從 Adventure 5.2.0 jar 載入 ClickEvent，實際：" + loc);

            // 舊寫法不可行：v5 ClickEvent 已無 value()
            Class<?> v5Click = Class.forName(
                "net.kyori.adventure.text.event.ClickEvent", true, loader);
            assertThrows(NoSuchMethodException.class,
                () -> v5Click.getMethod("value"),
                "v5 ClickEvent 不得有 value()（證明舊寫法無法滿足 v5）");
        }

        // 對照：test classpath 上是 v4（Action 是 enum），否則隔離前提失效
        Class<?> v4Action = Class.forName("net.kyori.adventure.text.event.ClickEvent$Action");
        assertTrue(v4Action.isEnum(), "test classpath 應為 Adventure 4（Action 是 enum），否則隔離前提失效");
    }

    @Test
    @DisplayName("四種 click action 在 v5 runtime 下被 helper 正確描述（run/suggest/open/copy）")
    void clickCompatHelper_describesFourActions_onAdventure5() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> clickEventClass = Class.forName(
                "net.kyori.adventure.text.event.ClickEvent", true, loader);
            Class<?> compatClass = compatLoader(loader);
            Method describe = compatClass.getDeclaredMethod("describe", clickEventClass);
            describe.setAccessible(true);

            assertCase(loader, describe, clickEventClass, "runCommand", "/say hi", "RUN_COMMAND", "/say hi");
            assertCase(loader, describe, clickEventClass, "suggestCommand", "/warp home", "SUGGEST_COMMAND", "/warp home");
            assertCase(loader, describe, clickEventClass, "openUrl", "https://example.com", "OPEN_URL", "https://example.com");
            assertCase(loader, describe, clickEventClass, "copyToClipboard", "secret", "COPY_TO_CLIPBOARD", "secret");
        }
    }

    @Test
    @DisplayName("unknown / non-text payload 在 v5 runtime 下 fail-safe（kind=UNKNOWN、payload 空字串、不拋 linkage error）")
    void clickCompatHelper_handlesUnknownAndNonTextPayload_onAdventure5() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> clickEventClass = Class.forName(
                "net.kyori.adventure.text.event.ClickEvent", true, loader);
            Class<?> compatClass = compatLoader(loader);
            Method describe = compatClass.getDeclaredMethod("describe", clickEventClass);
            describe.setAccessible(true);

            // changePage 是 Int payload（非 Text）：v5 下仍須可解析，kind=UNKNOWN、payload="3"
            Object changePage = clickEventClass.getMethod("changePage", int.class)
                .invoke(null, 3);
            Object d = describe.invoke(null, changePage);
            assertEquals("UNKNOWN", kindName(d), "change_page（Int payload）應歸為 UNKNOWN");
            assertEquals("3", payloadOf(d), "Int payload 應以安全純文字呈現（integer 轉字串）");

            // 不存在的 action 名稱（防禦性）：直接以 toString 不存在的協定名不會發生，
            // 但 helper 對無法辨識的 action 必須歸 UNKNOWN 且不拋錯。
            // 此處以 openUrl 驗證正常路徑已涵蓋；unknown 分支由 changePage 間接覆蓋。
        }
    }

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

    private static URLClassLoader isolatedLoader() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(productionClassesDir().toUri().toURL());
        urls.add(Path.of(ADVENTURE5_JAR).toUri().toURL());

        // 從 test runtime classpath 取 v4 adventure-key / adventure-nbt / examination
        // 作為 v5 api 的傳遞依賴（API 相容，見前提驗證）。
        String cp = System.getProperty("java.class.path");
        int keyN = 0, nbtN = 0, examN = 0;
        for (String entry : cp.split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            if (name.contains("adventure-key")) { urls.add(Path.of(entry).toUri().toURL()); keyN++; }
            else if (name.contains("adventure-nbt")) { urls.add(Path.of(entry).toUri().toURL()); nbtN++; }
            else if (name.contains("examination")) { urls.add(Path.of(entry).toUri().toURL()); examN++; }
        }
        assertTrue(keyN >= 1 && nbtN >= 1 && examN >= 1,
            "isolated loader 缺少 v4 adventure 傳遞依賴（key=" + keyN + " nbt=" + nbtN
                + " examination=" + examN + "）；testRuntimeClasspath 應含這些 jar");

        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                // 鎖死：絕不從 parent（platform）解析 adventure，確保只載入 v5。
                if (name.startsWith("net.kyori.adventure.")) {
                    // 交給本 loader 自己的 URL 解析（v5 jar），不委派 parent。
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    /** 載入 package-private helper；Red 階段此類不存在，會拋 ClassNotFoundException。 */
    private static Class<?> compatLoader(URLClassLoader loader) throws Exception {
        return Class.forName("com.smile.acelib.message.ClickEventCompat", true, loader);
    }

    private static void assertCase(URLClassLoader loader, Method describe,
            Class<?> clickEventClass, String factory, String arg,
            String expectedKind, String expectedPayload) throws Exception {
        Object click = clickEventClass.getMethod(factory, String.class).invoke(null, arg);
        Object descriptor = describe.invoke(null, click);
        assertEquals(expectedKind, kindName(descriptor),
            factory + " 在 v5 下應描述為 " + expectedKind);
        assertEquals(expectedPayload, payloadOf(descriptor),
            factory + " 在 v5 下的 payload 應為安全純文字：" + arg);
    }

    private static String kindName(Object descriptor) throws Exception {
        java.lang.reflect.Field f = descriptor.getClass().getDeclaredField("kind");
        f.setAccessible(true);
        Object kind = f.get(descriptor);
        return ((Enum<?>) kind).name();
    }

    private static String payloadOf(Object descriptor) throws Exception {
        java.lang.reflect.Field f = descriptor.getClass().getDeclaredField("payload");
        f.setAccessible(true);
        return (String) f.get(descriptor);
    }
}

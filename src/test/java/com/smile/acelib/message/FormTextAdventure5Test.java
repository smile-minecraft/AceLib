package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Constructor;
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
 * Adventure 5.2.0 isolated runtime 驗證：同一份 production {@code FormText}
 * 在 Adventure 5.2.0 下實際執行轉換案例並取得輸出。
 *
 * <p>隔離策略沿用 {@link Adventure5ClickCompatTest}：parent 取 platform loader，
 * URL 僅含 production 輸出 + Adventure 5.2.0 api / legacy / plain jar +
 * testRuntimeClasspath 上的 v4 adventure-key / adventure-nbt / examination。
 * Component 全以 reflection 在 isolated 空間內建構，{@code FormText.render}
 * 同以 isolated loader 載入後呼叫，確保執行的是 v5 語意。</p>
 */
@DisplayName("Adventure 5.2.0 isolated runtime：FormText 實際執行轉換")
class FormTextAdventure5Test {

    private static final String ADVENTURE5_JAR = System.getProperty("acelib.adventure5ApiJar");
    private static final String ADVENTURE5_LEGACY_JAR = System.getProperty("acelib.adventure5LegacyJar");
    private static final String ADVENTURE5_PLAIN_JAR = System.getProperty("acelib.adventure5PlainJar");

    @Test
    @DisplayName("前提：isolated 空間載入 v5（Action 非 enum、legacy 來自 5.2.0），test classpath 為 v4")
    void precondition_isolatedSpaceLoadsAdventure5() throws Exception {
        assertNotNull(ADVENTURE5_JAR, "缺少 acelib.adventure5ApiJar（build 未注入 v5 jar）");
        assertNotNull(ADVENTURE5_LEGACY_JAR, "缺少 acelib.adventure5LegacyJar（build 未注入 v5 legacy jar）");
        assertNotNull(ADVENTURE5_PLAIN_JAR, "缺少 acelib.adventure5PlainJar（build 未注入 v5 plain jar）");
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> v5Action = Class.forName(
                "net.kyori.adventure.text.event.ClickEvent$Action", true, loader);
            assertFalse(v5Action.isEnum(), "isolated 空間必須是 Adventure 5（Action 非 enum）");
            URL loc = v5Action.getProtectionDomain().getCodeSource().getLocation();
            assertTrue(loc.toString().contains("5.2.0"), "實際：" + loc);
            Class<?> legacy = Class.forName(
                "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer", true, loader);
            URL legacyLoc = legacy.getProtectionDomain().getCodeSource().getLocation();
            assertTrue(legacyLoc.toString().contains("5.2.0"),
                "legacy serializer 必須來自 5.2.0，實際：" + legacyLoc);
        }
        Class<?> v4Action = Class.forName("net.kyori.adventure.text.event.ClickEvent$Action");
        assertTrue(v4Action.isEnum(), "test classpath 應為 Adventure 4");
    }

    @Test
    @DisplayName("v5：具名色保留且每行以 §r 結尾")
    void formText_namedColor_onAdventure5() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Object red = namedColor(loader, "RED");
            Object comp = text(loader, "hi");
            comp = color(loader, comp, red);
            String out = render(loader, comp, false, 0, null);
            assertTrue(out.contains("hi"), "實際：" + escape(out));
            assertTrue(out.contains("§c"), "紅色應為 §c，實際：" + escape(out));
            assertTrue(out.endsWith("§r"), "實際：" + escape(out));
        }
    }

    @Test
    @DisplayName("v5：hex 色降為 16 色且不含 §x")
    void formText_hexColor_downgraded_onAdventure5() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Object hex = textColor(loader, 0xFF5555);
            Object comp = color(loader, text(loader, "hi"), hex);
            String out = render(loader, comp, false, 0, null);
            // 0xFF5555 恰為具名紅 §c 的值，兩版最近色皆為紅色
            assertTrue(out.contains("§c"), "v5 應降為紅色 §c，實際：" + escape(out));
            assertFalse(out.contains("§x"), "實際：" + escape(out));
        }
    }

    @Test
    @DisplayName("v5：click 移除；clickHints=true 附加提示")
    void formText_clickHandling_onAdventure5() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Object click = runCommand(loader, "/say hi");
            Object comp = clickEvent(loader, text(loader, "click me"), click);
            String plain = render(loader, comp, false, 0, null);
            assertTrue(plain.contains("click me"), "實際：" + escape(plain));
            assertFalse(plain.contains("/say hi"), "實際：" + escape(plain));
            String hinted = render(loader, comp, true, 0, null);
            assertTrue(hinted.contains("click me"), "實際：" + escape(hinted));
            assertTrue(hinted.contains("/say hi"), "實際：" + escape(hinted));
        }
    }

    @Test
    @DisplayName("v5：hover 移除；底線／刪除線移除；粗體保留")
    void formText_hoverAndDecorations_onAdventure5() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Object hover = showText(loader, text(loader, "tooltip"));
            Object comp = hoverEvent(loader, text(loader, "main"), hover);
            comp = decorate(loader, comp, "UNDERLINED");
            String out = render(loader, comp, false, 0, null);
            assertTrue(out.contains("main"), "實際：" + escape(out));
            assertFalse(out.contains("tooltip"), "實際：" + escape(out));
            assertFalse(out.contains("§n"), "實際：" + escape(out));

            Object bold = decorate(loader, text(loader, "b"), "BOLD");
            String boldOut = render(loader, bold, false, 0, null);
            assertTrue(boldOut.contains("§l"), "實際：" + escape(boldOut));
        }
    }

    @Test
    @DisplayName("v5：translatable fallback 與 key；換行 §r；截斷上限")
    void formText_translatableNewlineTruncation_onAdventure5() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Object fallbackComp = translatable(loader, "no.such.key.anywhere", "Fallback Text");
            String fallbackOut = render(loader, fallbackComp, false, 0, null);
            assertTrue(fallbackOut.contains("Fallback Text"), "實際：" + escape(fallbackOut));

            Object keyComp = translatable(loader, "no.such.key.anywhere", null);
            String keyOut = render(loader, keyComp, false, 0, null);
            assertTrue(keyOut.contains("no.such.key.anywhere"), "實際：" + escape(keyOut));

            Object multi = text(loader, "a\nb");
            String multiOut = render(loader, multi, false, 0, null);
            assertTrue(multiOut.endsWith("§r"), "實際：" + escape(multiOut));

            Object longText = text(loader, "hello");
            String truncated = render(loader, longText, false, 4, null);
            assertTrue(truncated.contains("…"), "實際：" + escape(truncated));
        }
    }

    // -----------------------------------------------------------------
    // reflection 建構（isolated 空間）
    // -----------------------------------------------------------------

    private static String render(URLClassLoader loader, Object component,
            boolean clickHints, int maxLength, Locale locale) throws Exception {
        Class<?> formText = Class.forName("com.smile.acelib.message.FormText", true, loader);
        Class<?> optionsClass = Class.forName("com.smile.acelib.message.FormTextOptions", true, loader);
        Constructor<?> ctor = optionsClass.getConstructor(boolean.class, int.class, Locale.class);
        Object options = ctor.newInstance(clickHints, maxLength, locale);
        Method render = formText.getMethod("render",
            Class.forName("net.kyori.adventure.text.Component", true, loader), optionsClass);
        return (String) render.invoke(null, component, options);
    }

    private static Object text(URLClassLoader loader, String content) throws Exception {
        Class<?> component = Class.forName("net.kyori.adventure.text.Component", true, loader);
        return component.getMethod("text", String.class).invoke(null, content);
    }

    private static Object namedColor(URLClassLoader loader, String name) throws Exception {
        Class<?> named = Class.forName("net.kyori.adventure.text.format.NamedTextColor", true, loader);
        return named.getField(name).get(null);
    }

    private static Object textColor(URLClassLoader loader, int rgb) throws Exception {
        Class<?> color = Class.forName("net.kyori.adventure.text.format.TextColor", true, loader);
        return color.getMethod("color", int.class).invoke(null, rgb);
    }

    private static Object color(URLClassLoader loader, Object component, Object color) throws Exception {
        Class<?> textColor = Class.forName("net.kyori.adventure.text.format.TextColor", true, loader);
        return component.getClass().getMethod("color", textColor).invoke(component, color);
    }

    private static Object decorate(URLClassLoader loader, Object component, String decoration)
            throws Exception {
        Class<?> decoClass = Class.forName("net.kyori.adventure.text.format.TextDecoration", true, loader);
        Object deco = Enum.valueOf(asEnum(decoClass), decoration);
        Method decorate = component.getClass().getMethod("decorate", decoClass.arrayType());
        return decorate.invoke(component, (Object) newArray(decoClass, deco));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends Enum> asEnum(Class<?> c) {
        return (Class<? extends Enum>) c;
    }

    private static Object newArray(Class<?> componentType, Object element) {
        Object arr = java.lang.reflect.Array.newInstance(componentType, 1);
        java.lang.reflect.Array.set(arr, 0, element);
        return arr;
    }

    private static Object runCommand(URLClassLoader loader, String command) throws Exception {
        Class<?> click = Class.forName("net.kyori.adventure.text.event.ClickEvent", true, loader);
        return click.getMethod("runCommand", String.class).invoke(null, command);
    }

    private static Object clickEvent(URLClassLoader loader, Object component, Object click)
            throws Exception {
        Class<?> clickClass = Class.forName("net.kyori.adventure.text.event.ClickEvent", true, loader);
        return component.getClass().getMethod("clickEvent", clickClass).invoke(component, click);
    }

    private static Object showText(URLClassLoader loader, Object content) throws Exception {
        Class<?> hover = Class.forName("net.kyori.adventure.text.event.HoverEvent", true, loader);
        Class<?> component = Class.forName("net.kyori.adventure.text.Component", true, loader);
        return hover.getMethod("showText", component).invoke(null, content);
    }

    private static Object hoverEvent(URLClassLoader loader, Object component, Object hover)
            throws Exception {
        Class<?> hoverSource = Class.forName(
            "net.kyori.adventure.text.event.HoverEventSource", true, loader);
        return component.getClass().getMethod("hoverEvent", hoverSource).invoke(component, hover);
    }

    private static Object translatable(URLClassLoader loader, String key, String fallback)
            throws Exception {
        Class<?> component = Class.forName("net.kyori.adventure.text.Component", true, loader);
        Object builder = component.getMethod("translatable").invoke(null);
        java.lang.reflect.Method keyMethod = builder.getClass().getMethod("key", String.class);
        keyMethod.setAccessible(true);
        keyMethod.invoke(builder, key);
        if (fallback != null) {
            java.lang.reflect.Method fallbackMethod =
                builder.getClass().getMethod("fallback", String.class);
            fallbackMethod.setAccessible(true);
            fallbackMethod.invoke(builder, fallback);
        }
        java.lang.reflect.Method buildMethod = builder.getClass().getMethod("build");
        buildMethod.setAccessible(true);
        return buildMethod.invoke(builder);
    }

    private static Path productionClassesDir() {
        Path dir = Paths.get(System.getProperty("user.dir"), "build", "classes", "java", "main");
        assertTrue(Files.isDirectory(dir), "找不到 production 編譯輸出目錄：" + dir);
        return dir;
    }

    private static URLClassLoader isolatedLoader() throws Exception {
        assertNotNull(ADVENTURE5_JAR, "缺少 acelib.adventure5ApiJar");
        List<URL> urls = new ArrayList<>();
        urls.add(productionClassesDir().toUri().toURL());
        urls.add(Path.of(ADVENTURE5_JAR).toUri().toURL());
        urls.add(Path.of(ADVENTURE5_LEGACY_JAR).toUri().toURL());
        urls.add(Path.of(ADVENTURE5_PLAIN_JAR).toUri().toURL());
        String cp = System.getProperty("java.class.path");
        int keyN = 0;
        int nbtN = 0;
        int examN = 0;
        for (String entry : cp.split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            if (name.contains("adventure-key")) {
                urls.add(Path.of(entry).toUri().toURL());
                keyN++;
            } else if (name.contains("adventure-nbt")) {
                urls.add(Path.of(entry).toUri().toURL());
                nbtN++;
            } else if (name.contains("examination")) {
                urls.add(Path.of(entry).toUri().toURL());
                examN++;
            }
        }
        assertTrue(keyN >= 1 && nbtN >= 1 && examN >= 1, "isolated loader 缺少傳遞依賴");
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("net.kyori.adventure.")) {
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

    private static String escape(String s) {
        return s.replace("§", "<S>").replace("\n", "<NL>");
    }
}

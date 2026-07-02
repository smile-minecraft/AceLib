package com.smile.acelib.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Folia 環境模擬測試（Plan §六 line 167：「模擬 Folia 環境並確認判斷正確」）。
 *
 * <p>既有 {@link PlatformDetectorTest} 只能寬鬆斷言 FOLIA/PAPER/UNKNOWN 任一結果，
 * 因為真實測試 classpath 內沒有 Folia marker class。本測試改用
 * <b>Java bytecode {@code defineClass}</b> 在執行期<b>合成</b>一個最小的 fake
 * {@code io.papermc.paper.threadedregions.RegionizedServer} class，並注入到一個
 * 隔離的 {@link ClassLoader}，使 {@link PlatformDetector#detect()} 的 Folia 偵測
 * 分支被<b>真正觸發</b>，斷言結果為 {@link Platform#FOLIA}。</p>
 *
 * <h2>為何 parent 使用 bootstrap（{@code new ClassLoader(null)}）</h2>
 * <p>測試 classloader 內含 {@code org.bukkit.Bukkit}（見既有
 * {@code isPaperClasspathAvailable_trueOnTestClassloader}）。若把 fake classloader
 * 的 parent 設為測試 classloader，parent delegation 會讓 {@code isPaperClasspathAvailable()}
 * 意外找到 Bukkit 而回傳 true，無法驗證「純 Folia marker、無 Bukkit」的隔離情境。
 * 因此改用 bootstrap classloader（{@code null} parent）：它只負責 {@code java.*}
 * （defineClass 需要解析 super class {@code java/lang/Object}），不含 Bukkit，
 * 而 fake RegionizedServer 則由 {@link #findClass} 合成。</p>
 *
 * <p>不使用 ASM / javassist / sun.misc.Unsafe，僅用 JDK 內建
 * {@link DataOutputStream} 手寫最小 class file 二進位。</p>
 */
@DisplayName("PlatformDetector — Folia 環境模擬")
class PlatformDetectorFoliaSimulationTest {

    /** Folia 判定 marker class 的完整名稱（dot 形式）。 */
    private static final String FAKE_FOLIA_CLASS =
        "io.papermc.paper.threadedregions.RegionizedServer";

    /** Folia marker class 的 internal name（slash 形式，寫入 class file constant pool）。 */
    private static final String FAKE_FOLIA_INTERNAL =
        "io/papermc/paper/threadedregions/RegionizedServer";

    @Test
    @DisplayName("detect() 在合成 RegionizedServer 載入後回傳 FOLIA")
    void detect_returnsFolia_whenFakeRegionizedServerLoaded() {
        PlatformDetector detector = new PlatformDetector(newFoliaFakeClassLoader());
        assertEquals(Platform.FOLIA, detector.detect(),
            "合成 RegionizedServer 後，detect() 必須走 Folia 分支");
    }

    @Test
    @DisplayName("isFoliaClasspathAvailable() 在合成 RegionizedServer 載入後回傳 true")
    void isFoliaClasspathAvailable_true_whenFakeRegionizedServerLoaded() {
        PlatformDetector detector = new PlatformDetector(newFoliaFakeClassLoader());
        assertTrue(detector.isFoliaClasspathAvailable(),
            "合成 RegionizedServer 後，isFoliaClasspathAvailable() 必須 true");
    }

    @Test
    @DisplayName("isPaperClasspathAvailable() 在僅有 fake Folia marker 時回傳 false")
    void isPaperClasspathAvailable_false_whenOnlyFakeFoliaPresent() {
        PlatformDetector detector = new PlatformDetector(newFoliaFakeClassLoader());
        assertFalse(detector.isPaperClasspathAvailable(),
            "fake classloader（bootstrap parent）不含 org.bukkit.Bukkit，必須 false");
    }

    // ------------------------------------------------------------------
    // 測試輔助：隔離 classloader + bytecode 合成
    // ------------------------------------------------------------------

    /**
     * 建立一個隔離的 classloader：parent 為 bootstrap（{@code null}），
     * 只會為 {@link #FAKE_FOLIA_CLASS} 合成 bytecode，其餘一律
     * {@link ClassNotFoundException}。
     */
    private static ClassLoader newFoliaFakeClassLoader() {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (FAKE_FOLIA_CLASS.equals(name)) {
                    byte[] b = synthesizeFoliaMarkerBytecode();
                    return defineClass(name, b, 0, b.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
    }

    /**
     * 手寫合成一個最小可載入的 Java class file（{@code io/papermc/paper/threadedregions/RegionizedServer}）。
     *
     * <p>class file 結構（JVMS §4.1）：</p>
     * <pre>
     *   magic            = 0xCAFEBABE
     *   minor_version    = 0
     *   major_version    = 65 (Java 21；可被 JDK 21+/25 載入)
     *   constant_pool[]  = 4 entries（count=5）
     *     #1 Utf8  "io/papermc/paper/threadedregions/RegionizedServer"
     *     #2 Class name_index=#1
     *     #3 Utf8  "java/lang/Object"
     *     #4 Class name_index=#3
     *   access_flags     = 0x0021 (ACC_PUBLIC | ACC_SUPER)
     *   this_class       = #2
     *   super_class      = #4
     *   interfaces_count = 0
     *   fields_count     = 0
     *   methods_count    = 0
     *   attributes_count = 0
     * </pre>
     */
    private static byte[] synthesizeFoliaMarkerBytecode() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeInt(0xCAFEBABE);              // magic
            out.writeShort(0);   // minor_version
            out.writeShort(65);  // major_version

            out.writeShort(5);   // constant_pool_count = entries + 1

            out.writeByte(1);                      // #1 CONSTANT_Utf8
            out.writeUTF(FAKE_FOLIA_INTERNAL);
            out.writeByte(7);                      // #2 CONSTANT_Class
            out.writeShort(1);                     //    name_index -> #1
            out.writeByte(1);                      // #3 CONSTANT_Utf8
            out.writeUTF("java/lang/Object");
            out.writeByte(7);                      // #4 CONSTANT_Class
            out.writeShort(3);                     //    name_index -> #3

            out.writeShort(0x0021);                // access_flags ACC_PUBLIC|ACC_SUPER
            out.writeShort(2);                     // this_class -> #2
            out.writeShort(4);                     // super_class -> #4
            out.writeShort(0);                     // interfaces_count
            out.writeShort(0);                     // fields_count
            out.writeShort(0);                     // methods_count
            out.writeShort(0);                     // attributes_count

            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new AssertionError("bytecode 合成失敗", e);
        }
    }
}

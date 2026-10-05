package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link YamlFileWriter} 暫存檔清理測試。
 *
 * <p>鎖住兩條規則：temp 建出來之後 {@code writeString} 失敗必須清掉孤兒檔；
 * 清理自己失敗時不得吞掉，必須以 {@code suppressed} 留痕且保留原始 cause。</p>
 */
@DisplayName("YamlFileWriter 暫存檔清理（寫入失敗保舊檔、可診斷）")
class YamlFileWriterTest {

    @TempDir
    Path tempDir;

    /** 可靠注入的檔案操作假實作：只讓 {@code writeString} 失敗，不冒充 {@code createTempFile} 失敗。 */
    private static final class FailingWriteOps implements YamlFileWriter.FileOps {
        private final YamlFileWriter.FileOps delegate = YamlFileWriter.FileOps.defaultOps();
        private final List<Path> createdTemps = new ArrayList<>();
        private final IOException writeFailure;
        private final IOException cleanupFailure;
        private final List<Path> cleanupAttempts = new ArrayList<>();

        FailingWriteOps(IOException writeFailure, IOException cleanupFailure) {
            this.writeFailure = writeFailure;
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
            Path tmp = delegate.createTempFile(dir, prefix, suffix);
            createdTemps.add(tmp);
            return tmp;
        }

        @Override
        public void writeString(Path path, String content) throws IOException {
            throw writeFailure;
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            delegate.move(source, target);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            cleanupAttempts.add(path);
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
            return delegate.deleteIfExists(path);
        }
    }

    private static YamlConfiguration sampleConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("greeting", "new-value");
        return cfg;
    }

    @Test
    @DisplayName("writeString 在 temp 建立後失敗 → 保舊檔、清孤兒、cause 可查")
    void writeAfterTempFails_keepsOldFile_cleansOrphan() throws IOException {
        Path target = tempDir.resolve("config.yml");
        String original = "greeting: 'keepme'\n";
        Files.writeString(target, original, StandardCharsets.UTF_8);

        IOException writeBoom = new IOException("disk full");
        FailingWriteOps ops = new FailingWriteOps(writeBoom, null);

        ConfigException ex = assertThrows(ConfigException.class,
            () -> YamlFileWriter.writeAtomically(sampleConfig(), target, "ACELIB-CFG-001", "設定檔", ops));

        assertTrue(ex.getCause() == writeBoom || containsCause(ex, writeBoom),
            "原始寫入 cause 必須保留，實際 cause：" + ex.getCause());
        assertEquals(original, Files.readString(target, StandardCharsets.UTF_8),
            "寫入失敗必須保留舊檔，不得截斷目標檔");
        for (Path tmp : ops.createdTemps) {
            assertTrue(Files.notExists(tmp), "temp 孤兒檔必須清掉：" + tmp);
        }
        assertEquals(1, ops.cleanupAttempts.size(), "寫入失敗後必須嘗試清理 temp");
    }

    @Test
    @DisplayName("write 失敗且清理自己也失敗 → suppressed 留痕、仍保舊檔")
    void writeFailsAndCleanupFails_suppressedKept_oldFileKept() throws IOException {
        Path target = tempDir.resolve("config.yml");
        String original = "greeting: 'keepme'\n";
        Files.writeString(target, original, StandardCharsets.UTF_8);

        IOException writeBoom = new IOException("disk full");
        IOException cleanupBoom = new IOException("cannot delete tmp");
        FailingWriteOps ops = new FailingWriteOps(writeBoom, cleanupBoom);

        ConfigException ex = assertThrows(ConfigException.class,
            () -> YamlFileWriter.writeAtomically(sampleConfig(), target, "ACELIB-CFG-001", "設定檔", ops));

        assertTrue(containsCause(ex, writeBoom), "原始寫入 cause 必須保留");
        assertTrue(containsSuppressed(ex, cleanupBoom), "清理失敗必須以 suppressed 留痕，不得吞掉");
        assertEquals(original, Files.readString(target, StandardCharsets.UTF_8),
            "即使清理失敗，舊檔仍必須保留");
    }

    @Test
    @DisplayName("move 失敗且清理失敗 → suppressed 留痕")
    void moveFailsAndCleanupFails_suppressedKept() throws IOException {
        Path target = tempDir.resolve("config.yml");
        Files.writeString(target, "greeting: 'keepme'\n", StandardCharsets.UTF_8);

        YamlFileWriter.FileOps moveFailOps = new YamlFileWriter.FileOps() {
            private final YamlFileWriter.FileOps delegate = YamlFileWriter.FileOps.defaultOps();
            private Path tmp;

            @Override
            public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
                tmp = delegate.createTempFile(dir, prefix, suffix);
                return tmp;
            }

            @Override
            public void writeString(Path path, String content) throws IOException {
                delegate.writeString(path, content);
            }

            @Override
            public void move(Path source, Path target) throws IOException {
                throw new IOException("cross-device move failed");
            }

            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                throw new IOException("cleanup failed");
            }
        };

        ConfigException ex = assertThrows(ConfigException.class,
            () -> YamlFileWriter.writeAtomically(sampleConfig(), target, "ACELIB-CFG-001", "設定檔", moveFailOps));

        assertTrue(ex.getCause() != null && ex.getCause().getMessage().contains("cross-device"),
            "move 的原始 cause 必須保留，實際：" + ex.getCause());
        assertEquals(1, ex.getSuppressed().length, "清理失敗必須以 suppressed 留痕");
        assertTrue(ex.getSuppressed()[0].getMessage().contains("cleanup"),
            "suppressed 必須是清理失敗，實際：" + ex.getSuppressed()[0]);
    }

    private static boolean containsCause(Throwable root, Throwable expected) {
        for (Throwable t = root; t != null; t = t.getCause()) {
            if (t == expected) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSuppressed(Throwable root, Throwable expected) {
        for (Throwable s : root.getSuppressed()) {
            if (s == expected) {
                return true;
            }
        }
        return false;
    }
}

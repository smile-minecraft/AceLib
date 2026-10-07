package com.smile.acelib.data;

import com.smile.acelib.player.PlayerDataService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 異常終止保存上限驗證的子行程入口（測試源碼，僅供
 * {@code scripts/player-store-crash-test.sh} 呼叫；Gradle test 不執行）。
 *
 * <p>用法有兩段，同一個 class 依第一個參數切換：</p>
 * <pre>
 *   writer  &lt;dbFile&gt; &lt;saveIntervalMs&gt; &lt;progressFile&gt;
 *   verify &lt;dbFile&gt; &lt;uuid&gt; &lt;saveIntervalMs&gt; &lt;progressFile&gt;
 * </pre>
 *
 * <p><strong>writer</strong>：以真 SQLite store 與真 {@link PlayerDataService}
 * 啟動定期保存，持續把 {@code counter} 遞增並 {@code markDirty}，每遞增一行寫入
 * progress 檔（含毫秒時間戳）後 {@code flush()}，讓 SIGKILL 後仍可讀出「最後一次
 * 變更」的時間。接著無限等待，被 {@code SIGKILL} 直接殺掉（不走 shutdown／flush），
 * 以模擬伺服器崩潰。</p>
 *
 * <p><strong>verify</strong>：重開同一檔案，讀回 {@code counter} 與其落盤時間，
 * 與 progress 檔的最後一次變更比較，輸出遺失的變更筆數與時間差。</p>
 */
final class PlayerStoreCrashMain {

    private PlayerStoreCrashMain() {
    }

    /** counter 欄位名；verify 與 writer 共用。 */
    static final String COUNTER_FIELD = "counter";

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "writer".equals(args[0])) {
            writer(args);
        } else if (args.length >= 1 && "verify".equals(args[0])) {
            verify(args);
        } else {
            System.err.println("usage: PlayerStoreCrashMain writer|verify ...");
            System.exit(2);
        }
    }

    private static void writer(String[] args) throws Exception {
        Path dbFile = Path.of(args[1]);
        long saveIntervalMs = Long.parseLong(args[2]);
        Path progressFile = Path.of(args[3]);
        UUID uuid = UUID.nameUUIDFromBytes("player-store-crash".getBytes(
            StandardCharsets.UTF_8));

        Files.createDirectories(dbFile.toAbsolutePath().getParent());
        Files.deleteIfExists(progressFile);

        PlayerDataStore store = PlayerDataStores.sqlite(dbFile, SchemaVersion.V1_0);
        store.init();
        ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "crash-test-io");
            t.setDaemon(true);
            return t;
        });
        PlayerDataService service =
            new PlayerDataService(store, io, saveIntervalMs, 5_000L);

        service.onPlayerJoin(uuid, "CrashTester").join();
        System.out.println("writer-ready uuid=" + uuid + " interval=" + saveIntervalMs + "ms");

        // 不設上限：靠 SIGKILL 終止，才能證明「未經 shutdown 的週期保存」有效。
        for (long counter = 1L; ; counter++) {
            long value = counter;
            service.getData(uuid).ifPresent(rec -> {
                rec.set(COUNTER_FIELD, value);
                service.markDirty(uuid);
            });
            // 每一次變更都落一行 + flush：SIGKILL 後仍能判定「最後變更時刻」。
            Files.writeString(progressFile,
                counter + "\t" + System.currentTimeMillis() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            Thread.sleep(20L);
        }
    }

    private static void verify(String[] args) throws Exception {
        Path dbFile = Path.of(args[1]);
        UUID uuid = UUID.fromString(args[2]);
        long saveIntervalMs = Long.parseLong(args[3]);
        Path progressFile = Path.of(args[4]);

        long lastChange = 0L;
        long lastChangeAt = 0L;
        if (Files.isRegularFile(progressFile)) {
            for (String line : Files.readAllLines(progressFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length == 2) {
                    lastChange = Long.parseLong(parts[0]);
                    lastChangeAt = Long.parseLong(parts[1]);
                }
            }
        }

        PlayerDataStore store = PlayerDataStores.sqlite(dbFile, SchemaVersion.V1_0);
        store.init();
        Optional<Record> loaded = store.load(uuid);
        long persisted = loaded.map(rec -> rec.getLong(COUNTER_FIELD, 0L)).orElse(0L);
        store.close();

        long lostChanges = Math.max(0L, lastChange - persisted);
        // 變更速率為每 20ms 一筆；一個保存週期內最多累積的變更筆數即遺失上限。
        long intervalBudget = Math.max(1L, saveIntervalMs / 20L);
        // 最後一次變更距今的時間：用來對照遺失筆數是否落在週期預算內。
        long sinceLastChangeMs = System.currentTimeMillis() - lastChangeAt;
        System.out.println("verify last-change=" + lastChange
            + " persisted=" + persisted
            + " lost-changes=" + lostChanges
            + " interval-budget=" + intervalBudget
            + " interval=" + saveIntervalMs + "ms"
            + " since-last-change=" + sinceLastChangeMs + "ms");
        if (persisted <= 0L) {
            System.out.println("verify-result FAIL（完全沒有變更落盤：週期保存未生效）");
            System.exit(1);
        }
        if (lostChanges > intervalBudget) {
            System.out.println("verify-result FAIL（遺失變更 " + lostChanges
                + " 筆，超過一個保存週期的預算 " + intervalBudget + " 筆）");
            System.exit(1);
        }
        System.out.println("verify-result PASS（遺失 " + lostChanges
            + " 筆變更，在一個保存週期 " + saveIntervalMs + "ms 的預算內）");
    }
}
package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.smile.acelib.data.MemoryRecord;
import com.smile.acelib.data.Record;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LockedPlayerRecordTest {

    @Test
    void nestedRecords_shareSnapshotLock() throws Exception {
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("nested", new LinkedHashMap<String, Object>());
        LockedPlayerRecord root = new LockedPlayerRecord(new MemoryRecord("", initial));

        Record nested = root.getRecord("nested", null);
        assertNotNull(nested);
        assertInstanceOf(LockedPlayerRecord.class, nested);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    nested.set("value", i);
                }
            });
            Future<?> snapshotter = executor.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    root.snapshotLocked();
                }
            });
            writer.get();
            snapshotter.get();
        } finally {
            executor.shutdownNow();
        }
    }
}

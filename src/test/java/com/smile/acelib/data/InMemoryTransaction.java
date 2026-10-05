package com.smile.acelib.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 測試專用極簡 transaction 模擬：維護 staging 與主 database。
 *
 * <p>commit：把 staging 寫入主 database；rollback：丟棄 staging。
 * 對 {@link JdbcDataStore} 而言，這足以驗證 init / save 是否在
 * 「失敗時回滾、既有資料不變」。</p>
 *
 * @since Phase 8 (Plan §十三)
 */
final class InMemoryTransaction {

    private final List<Op> staging = new ArrayList<>();
    private final InMemoryDatabase db;
    private boolean active;

    InMemoryTransaction(InMemoryDatabase db) {
        this.db = db;
    }

    boolean isActive() {
        return active;
    }

    void begin() {
        active = true;
        staging.clear();
    }

    /**
     * 套用 staging 到 database；之後 staging 清空。
     */
    void commit() throws java.sql.SQLException {
        if (!active) {
            return;
        }
        for (Op op : staging) {
            switch (op.type) {
                case DELETE -> db.delete(op.table, op.storeName);
                case INSERT -> db.insertLegacyRow(op.table, op.storeName, op.k, op.v);
                case INSERT_HASHED -> db.insertHashedRow(
                    op.table, op.storeName, op.kHash, op.k, op.v);
            }
        }
        staging.clear();
    }

    void rollback() {
        staging.clear();
        active = false;
    }

    void recordInsert(String table, String storeName, String k, String v)
            throws java.sql.SQLException {
        if (active) {
            staging.add(new Op(Op.Type.INSERT, table, storeName, k, null, v));
        } else {
            db.insertLegacyRow(table, storeName, k, v);
        }
    }

    void recordInsertHashed(
            String table, String storeName, String kHash, String k, String v)
            throws java.sql.SQLException {
        if (active) {
            staging.add(new Op(Op.Type.INSERT_HASHED, table, storeName, k, kHash, v));
        } else {
            db.insertHashedRow(table, storeName, kHash, k, v);
        }
    }

    void recordDelete(String table, String storeName) {
        if (active) {
            staging.add(new Op(Op.Type.DELETE, table, storeName, null, null, null));
        } else {
            db.delete(table, storeName);
        }
    }

    private static final class Op {
        enum Type { DELETE, INSERT, INSERT_HASHED }
        final Type type;
        final String table;
        final String storeName;
        final String k;
        final String kHash;
        final String v;

        Op(Type type, String table, String storeName, String k, String kHash, String v) {
            this.type = type;
            this.table = table;
            this.storeName = storeName;
            this.k = k;
            this.kHash = kHash;
            this.v = v;
        }
    }
}
package com.smile.acelib.data;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 測試專用極簡 in-memory 資料庫：用 {@link LinkedHashMap} 模擬單張表。
 *
 * <p>支援兩種 table shape（模擬 MySQL/MariaDB 相容任務的新舊 schema）：</p>
 * <ul>
 *   <li>legacy shape（舊版 DDL）：{@code (store_name, k, v)}，
 *       主鍵為 {@code (store_name, k)}。寫入時強制執行 utf8mb4 InnoDB
 *       索引上限（key 字元數 × 4 {@code > 3072} 即拋
 *       {@code Specified key was too long}），以重現真實 MySQL
 *       {@code ERROR 1071}；不支援 {@code k_hash} 相關 SQL。</li>
 *   <li>new shape（新版 DDL）：{@code (store_name, k_hash, k, v)}，
 *       主鍵為 {@code (store_name, k_hash)}，無 key 長度上限；
 *       舊式三欄 {@code INSERT} 會因缺少 {@code k_hash} 被拒絕。</li>
 * </ul>
 *
 * <p>支援：</p>
 * <ul>
 *   <li>{@code CREATE TABLE IF NOT EXISTS}（依 DDL 是否含 {@code k_hash} 決定 shape；
 *       已存在則保持原 shape）</li>
 *   <li>{@code SELECT k, v FROM <table> WHERE store_name = ?}（兩種 shape 皆可）</li>
 *   <li>{@code SELECT store_name, k, v FROM <table>}（整表掃描，兩種 shape 皆可）</li>
 *   <li>{@code SELECT v FROM <table> WHERE store_name = ? AND k = ?}（兩種 shape 皆可）</li>
 *   <li>{@code SELECT v FROM <table> WHERE store_name = ? AND k_hash = ?}（僅 new shape）</li>
 *   <li>{@code DELETE FROM <table> WHERE store_name = ?}</li>
 *   <li>{@code INSERT INTO <table> (store_name, k, v) VALUES (?, ?, ?)}（僅 legacy shape）</li>
 *   <li>{@code INSERT INTO <table> (store_name, k_hash, k, v) VALUES (?, ?, ?, ?)}
 *       （僅 new shape）</li>
 *   <li>{@code DROP TABLE IF EXISTS <table>}</li>
 *   <li>{@code ALTER TABLE <tmp> RENAME TO <table>}</li>
 * </ul>
 *
 * <p>不實作 vendor 專屬語法（{@code ON DUPLICATE KEY UPDATE}、{@code ON CONFLICT}），
 * 強制 JdbcDataStore 使用 vendor-portable 寫法。</p>
 *
 * @since Phase 8 (Plan §十三)
 */
final class InMemoryDatabase {

    /**
     * utf8mb4 下 InnoDB 單一索引鍵上限（bytes）。legacy shape 以
     * {@code (store_name 字元數 + k 字元數) * 4} 估算最壞 bytes，
     * 超過即模擬 {@code ERROR 1071}。
     */
    static final int LEGACY_INDEX_BYTE_LIMIT = 3072;

    /** table name → table（含 shape 與 rows）。 */
    private final Map<String, Table> tables = new LinkedHashMap<>();

    synchronized boolean tableExists(String name) {
        return tables.containsKey(name);
    }

    /**
     * 依 DDL 是否含 {@code k_hash} 欄位決定 shape；表已存在時保持原 shape
     *（與真實 {@code CREATE TABLE IF NOT EXISTS} 語意一致）。
     */
    synchronized void createTableIfMissing(String name, String ddl) {
        boolean hasHash = ddl != null && ddl.toLowerCase().contains("k_hash");
        tables.computeIfAbsent(name, k -> new Table(hasHash));
    }

    /**
     * 直接建立 legacy shape 表（模擬舊版 AceLib 建出的表，供遷移測試使用）。
     * 表已存在時保持原樣。
     */
    synchronized void createLegacyTable(String name) {
        tables.computeIfAbsent(name, k -> new Table(false));
    }

    /** 回傳表是否為 new shape（含 {@code k_hash}）；表不存在時回傳 {@code false}。 */
    synchronized boolean tableHasKeyHash(String name) {
        Table t = tables.get(name);
        return t != null && t.hasKeyHash;
    }

    /** 依 DDL 建立的新表應為 new shape（供測試斷言 DDL 內容）。 */
    synchronized boolean isNewShape(String name) {
        return tableHasKeyHash(name);
    }

    synchronized void dropTableIfExists(String name) {
        tables.remove(name);
    }

    synchronized void renameTable(String from, String to) throws SQLException {
        Table t = tables.get(from);
        if (t == null) {
            throw new SQLException("table '" + from + "' doesn't exist");
        }
        if (tables.containsKey(to)) {
            throw new SQLException("table '" + to + "' already exists");
        }
        tables.remove(from);
        tables.put(to, t);
    }

    synchronized List<Row> select(String table, String storeName) {
        Table t = tables.get(table);
        if (t == null) {
            return List.of();
        }
        List<Row> matched = new ArrayList<>();
        for (Row r : t.rows) {
            if (storeName == null || storeName.equals(r.storeName)) {
                matched.add(r);
            }
        }
        return matched;
    }

    synchronized String selectValue(String table, String storeName, String key) {
        Table t = tables.get(table);
        if (t == null) {
            return null;
        }
        for (Row r : t.rows) {
            if ((storeName == null || storeName.equals(r.storeName)) && key.equals(r.k)) {
                return r.v;
            }
        }
        return null;
    }

    synchronized String selectValueByHash(String table, String storeName, String hash)
            throws SQLException {
        Table t = tables.get(table);
        if (t == null) {
            return null;
        }
        if (!t.hasKeyHash) {
            throw new SQLException(
                "Unknown column 'k_hash' in 'where clause'", "42S22", 1054);
        }
        for (Row r : t.rows) {
            if ((storeName == null || storeName.equals(r.storeName))
                    && hash.equals(r.kHash)) {
                return r.v;
            }
        }
        return null;
    }

    synchronized int delete(String table, String storeName) {
        Table t = tables.get(table);
        if (t == null) {
            return 0;
        }
        int before = t.rows.size();
        t.rows.removeIf(r -> storeName == null || storeName.equals(r.storeName));
        return before - t.rows.size();
    }

    /**
     * 測試 fixture 用的直接寫入（模擬「表裡早就有資料」）。
     *
     * <p>表不存在時自動以 legacy shape 建立（等同舊版留下的表）；new shape 表
     * 會一併計算 {@code k_hash}。此方法不強制索引上限（既有資料不受新限制影響，
     * 由遷移路徑處理）；SQL 路徑的寫入限制由 statement 層模擬。</p>
     */
    synchronized void insert(String table, String storeName, String k, String v) {
        Table t = tables.computeIfAbsent(table, key -> new Table(false));
        t.rows.add(new Row(storeName, k, t.hasKeyHash ? JdbcDataStore.hashKey(k) : null, v));
    }

    /**
     * SQL 路徑的三欄寫入（舊式 INSERT）。僅 legacy shape 接受，並強制執行
     * legacy 索引上限；new shape 表拒收（缺少 {@code k_hash}）。
     */
    synchronized void insertLegacyRow(String table, String storeName, String k, String v)
            throws SQLException {
        Table t = tables.get(table);
        if (t == null) {
            t = new Table(false);
            tables.put(table, t);
        }
        if (t.hasKeyHash) {
            throw new SQLException(
                "Field 'k_hash' doesn't have a default value", "HY000", 1364);
        }
        checkLegacyIndexLimit(storeName, k);
        t.rows.add(new Row(storeName, k, null, v));
    }

    /**
     * SQL 路徑的四欄寫入（新式 INSERT）。僅 new shape 接受；legacy 表
     * 沒有 {@code k_hash} 欄位。
     */
    synchronized void insertHashedRow(
            String table, String storeName, String kHash, String k, String v)
            throws SQLException {
        Table t = tables.get(table);
        if (t == null) {
            t = new Table(true);
            tables.put(table, t);
        }
        if (!t.hasKeyHash) {
            throw new SQLException(
                "Unknown column 'k_hash' in 'field list'", "42S22", 1054);
        }
        t.rows.add(new Row(storeName, k, kHash, v));
    }

    /**
     * 模擬 utf8mb4 InnoDB 索引上限：{@code (store_name + k) 最壞 bytes > 3072}
     * 即拋 {@code ERROR 1071}。
     */
    private static void checkLegacyIndexLimit(String storeName, String k) throws SQLException {
        int chars = (storeName == null ? 0 : storeName.length()) + (k == null ? 0 : k.length());
        if ((long) chars * 4L > LEGACY_INDEX_BYTE_LIMIT) {
            throw new SQLException(
                "Specified key was too long; max key length is 3072 bytes", "42000", 1071);
        }
    }

    synchronized String selectHash(String table, String storeName, String key) {
        Table t = tables.get(table);
        if (t == null) {
            return null;
        }
        for (Row r : t.rows) {
            if ((storeName == null || storeName.equals(r.storeName)) && key.equals(r.k)) {
                return r.kHash;
            }
        }
        return null;
    }

    synchronized int rowCount(String table) {
        Table t = tables.get(table);
        return t == null ? 0 : t.rows.size();
    }

    /** 模擬 INFORMATION_SCHEMA：回傳表擁有的欄位名（小寫）。 */
    synchronized List<String> columnNames(String table) {
        Table t = tables.get(table);
        if (t == null) {
            return List.of();
        }
        if (t.hasKeyHash) {
            return List.of("store_name", "k_hash", "k", "v");
        }
        return List.of("store_name", "k", "v");
    }

    private static final class Table {
        final boolean hasKeyHash;
        final List<Row> rows = new ArrayList<>();

        Table(boolean hasKeyHash) {
            this.hasKeyHash = hasKeyHash;
        }
    }

    /** 對應一筆資料；{@code kHash} 僅 new shape 有值。 */
    static final class Row {
        final String storeName;
        final String k;
        final String kHash;
        final String v;

        Row(String storeName, String k, String kHash, String v) {
            this.storeName = storeName;
            this.k = k;
            this.kHash = kHash;
            this.v = v;
        }
    }
}

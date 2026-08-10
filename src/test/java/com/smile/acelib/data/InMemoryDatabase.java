package com.smile.acelib.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only 極簡 in-memory 資料庫：用 {@link LinkedHashMap} 模擬單張表。
 *
 * <p>支援：</p>
 * <ul>
 *   <li>{@code CREATE TABLE IF NOT EXISTS} (no-op，若表已存在則保持)</li>
 *   <li>{@code SELECT k, v FROM <table> WHERE store_name = ?}</li>
 *   <li>{@code SELECT v FROM <table> WHERE store_name = ? AND k = ?}</li>
 *   <li>{@code DELETE FROM <table> WHERE store_name = ?}</li>
 *   <li>{@code INSERT INTO <table> (store_name, k, v) VALUES (?, ?, ?)}</li>
 * </ul>
 *
 * <p>不實作 vendor 專屬語法（{@code ON DUPLICATE KEY UPDATE}、{@code ON CONFLICT}），
 * 強制 JdbcDataStore 使用 vendor-portable 寫法。</p>
 *
 * @since Phase 8 (Plan §十三)
 */
final class InMemoryDatabase {

    /** table name → rows（每筆 row = (store_name, k, v)）。 */
    private final Map<String, List<Row>> tables = new LinkedHashMap<>();

    synchronized boolean tableExists(String name) {
        return tables.containsKey(name);
    }

    synchronized void createTableIfMissing(String name) {
        tables.computeIfAbsent(name, k -> new ArrayList<>());
    }

    synchronized List<Row> select(String table, String storeName) {
        List<Row> rows = tables.getOrDefault(table, List.of());
        List<Row> matched = new ArrayList<>();
        for (Row r : rows) {
            if (storeName == null || storeName.equals(r.storeName)) {
                matched.add(r);
            }
        }
        return matched;
    }

    synchronized String selectValue(String table, String storeName, String key) {
        List<Row> rows = tables.getOrDefault(table, List.of());
        for (Row r : rows) {
            if ((storeName == null || storeName.equals(r.storeName)) && key.equals(r.k)) {
                return r.v;
            }
        }
        return null;
    }

    synchronized int delete(String table, String storeName) {
        List<Row> rows = tables.get(table);
        if (rows == null) {
            return 0;
        }
        int before = rows.size();
        rows.removeIf(r -> storeName == null || storeName.equals(r.storeName));
        return before - rows.size();
    }

    synchronized void insert(String table, String storeName, String k, String v) {
        List<Row> rows = tables.computeIfAbsent(table, key -> new ArrayList<>());
        rows.add(new Row(storeName, k, v));
    }

    synchronized int rowCount(String table) {
        return tables.getOrDefault(table, List.of()).size();
    }

    /** 對應 (store_name, k, v) 一筆資料。 */
    static final class Row {
        final String storeName;
        final String k;
        final String v;

        Row(String storeName, String k, String v) {
            this.storeName = storeName;
            this.k = k;
            this.v = v;
        }
    }
}
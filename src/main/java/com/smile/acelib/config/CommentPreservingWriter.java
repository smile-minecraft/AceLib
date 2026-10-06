package com.smile.acelib.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 保留註解的 YAML 合併寫回（套件私有）。
 *
 * <p>背景：{@link YamlConfiguration} 解析後只剩鍵與值，
 * 直接 {@code saveToString} 會丟掉管理員寫在檔案裡的註解與排版。
 * 本類別改走「行級合併」：讀入原檔文字，只改「值真的變了」的行內值段、
 * 只補「缺的 key」，其餘行（含註解、空行、排版）逐位元保留。</p>
 *
 * <h2>合併規則</h2>
 * <ul>
 *   <li>純量值變更：替換冒號後的值段，行尾註解保留</li>
 *   <li>清單／節點整段變更：替換整個縮排區塊（區塊內部註解不保留，
 *       區塊前後的註解保留）</li>
 *   <li>明確刪除（{@code set null}／migration 移除）：刪行（節頭刪整段），
 *       因此變空的祖先節頭一併修剪</li>
 *   <li>缺的 key：補在既有父節點下（不產生重複父節點），
 *       並附上欄位說明註解；父節點不存在才在檔尾建新節</li>
 *   <li>原檔有但新值沒有、且非明確刪除的 key：一律保留，不刪除使用者資料</li>
 *   <li>原檔不存在：整份新生成，key 旁附欄位說明</li>
 *   <li>值含多行字串、或合併後語意比對不一致：退回全量序列化，
 *       該次寫回註解不保留（語意正確優先）</li>
 * </ul>
 *
 * <p>限制：key 本身不可含 {@code :}；值內含 {@code #} 且未被引號包住時，
 * 行尾註解切分採「引號外第一個 {@code #}」啟發式，極端手寫排版可能誤判，
 * 語意仍以解析後的值為準（合併前後會做語意比對，不一致時退回全量序列化）。</p>
 */
final class CommentPreservingWriter {

    private CommentPreservingWriter() {
        // 工具類別，不提供實例
    }

    /**
     * 把巢狀 map 攤平成點分隔路徑（map 遞迴，list 視為葉子）。
     *
     * @param nested 巢狀值；不可為 null
     * @return 保持插入序的路徑→葉子 map
     */
    static Map<String, Object> flatten(Map<String, Object> nested) {
        Objects.requireNonNull(nested, "nested");
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto("", ConfigSnapshot.plainCopy(nested), out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(String prefix, Map<String, Object> node,
                                    Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                Map<String, Object> childMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> childEntry : child.entrySet()) {
                    childMap.put(String.valueOf(childEntry.getKey()), childEntry.getValue());
                }
                flattenInto(path, childMap, out);
            } else if (value != null) {
                out.put(path, value);
            }
        }
    }

    /**
     * 合併新值與原檔文字。
     *
     * @param originalText 原檔全文；檔案不存在時傳 null（整份新生成）
     * @param newValues    攤平後的新值（路徑→葉子）；不可為 null
     * @param descriptions 欄位說明（路徑→註解文字）；不可為 null，可為空
     * @param removed      明確刪除的路徑（{@code set null}／migration 移除）；
     *                     不可為 null，可為空；原檔有但新值沒有、且不在此集合的 key
     *                     一律保留（不刪除使用者資料）
     * @return 合併後的全文（結尾恰一個換行）
     */
    static String merge(String originalText, Map<String, Object> newValues,
                        Map<String, String> descriptions, java.util.Set<String> removed) {
        Objects.requireNonNull(newValues, "newValues");
        Objects.requireNonNull(descriptions, "descriptions");
        Objects.requireNonNull(removed, "removed");
        if (originalText == null) {
            return generateFresh(newValues, descriptions);
        }
        Document document = Document.parse(originalText);
        Map<String, Object> oldValues = flatten(parseToMap(originalText));

        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String path = entry.getKey();
            Object fresh = entry.getValue();
            if (oldValues.containsKey(path) && valuesEqual(oldValues.get(path), fresh)) {
                continue;
            }
            if (document.hasLine(path)) {
                document.replaceValue(path, fresh);
            } else {
                document.appendMissing(path, fresh, descriptions.get(path));
            }
        }
        for (String removedPath : removed) {
            document.removePath(removedPath);
        }
        String merged = document.render();
        // 語意保險：合併結果解析後必須與新值一致，否則退回全量序列化
        if (!semanticallyEqual(merged, newValues)) {
            return serializeFully(newValues, descriptions);
        }
        return merged;
    }

    // -----------------------------------------------------------------
    // 語意比對與全量序列化（退路）
    // -----------------------------------------------------------------

    private static Map<String, Object> parseToMap(String text) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(text);
        } catch (Exception ex) {
            return Map.of();
        }
        return parsed.getValues(false);
    }

    private static boolean semanticallyEqual(String merged, Map<String, Object> newValues) {
        Map<String, Object> mergedFlat = flatten(parseToMap(merged));
        if (!mergedFlat.keySet().containsAll(newValues.keySet())) {
            return false;
        }
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            if (!valuesEqual(mergedFlat.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                Object leftItem = leftList.get(i);
                Object rightItem = rightList.get(i);
                if (leftItem == null || rightItem == null) {
                    if (leftItem != rightItem) {
                        return false;
                    }
                } else if (!leftItem.toString().equals(rightItem.toString())) {
                    return false;
                }
            }
            return true;
        }
        return left.toString().equals(right.toString());
    }

    private static String serializeFully(Map<String, Object> newValues,
                                         Map<String, String> descriptions) {
        YamlConfiguration full = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            full.set(entry.getKey(), entry.getValue());
        }
        StringBuilder out = new StringBuilder();
        for (String path : newValues.keySet()) {
            if (path.contains(".")) {
                continue;
            }
            String description = descriptions.get(path);
            if (description != null && !description.isBlank()) {
                out.append("# ").append(description.strip()).append('\n');
            }
        }
        out.append(full.saveToString());
        return out.toString();
    }

    private static String generateFresh(Map<String, Object> newValues,
                                        Map<String, String> descriptions) {
        // 依頂層分組，保持首次出現順序
        Map<String, List<Map.Entry<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String top = entry.getKey().contains(".")
                ? entry.getKey().substring(0, entry.getKey().indexOf('.'))
                : entry.getKey();
            groups.computeIfAbsent(top, ignored -> new ArrayList<>()).add(entry);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<Map.Entry<String, Object>>> group : groups.entrySet()) {
            List<Map.Entry<String, Object>> entries = group.getValue();
            if (entries.size() == 1 && !entries.get(0).getKey().contains(".")) {
                // 頂層純量
                appendDescribed(out, 0, entries.get(0).getKey(),
                    entries.get(0).getValue(), descriptions.get(entries.get(0).getKey()));
            } else {
                // 節點：先寫父頭，再寫子鍵（兩格縮排）
                out.append(group.getKey()).append(":\n");
                for (Map.Entry<String, Object> entry : entries) {
                    String relative = entry.getKey().substring(group.getKey().length() + 1);
                    if (relative.contains(".")) {
                        // 深於兩層：退回整段渲染後重新縮排
                        out.append(renderNestedBlock(entry.getKey(), entry.getValue(), 0));
                    } else {
                        appendDescribed(out, 1, relative, entry.getValue(),
                            descriptions.get(entry.getKey()));
                    }
                }
            }
        }
        return out.toString();
    }

    private static void appendDescribed(StringBuilder out, int depth, String key,
                                       Object value, String description) {
        String indent = "  ".repeat(depth);
        if (description != null && !description.isBlank()) {
            out.append(indent).append("# ").append(description.strip()).append('\n');
        }
        if (value instanceof List<?> || value instanceof Map<?, ?>) {
            out.append(renderNestedBlock(key, value, depth));
        } else {
            out.append(indent).append(key).append(": ").append(renderInline(value)).append('\n');
        }
    }

    /**
     * 以暫存 YamlConfiguration 渲染單一值的區塊行（含 key 頭行）。
     *
     * @param key   該層的 key 名（不含路徑）
     * @param value map 或 list 值
     * @param depth 縮排層級（每層兩格）
     * @return 以換行結尾的區塊文字
     */
    @SuppressWarnings("unchecked")
    private static String renderNestedBlock(String dottedPath, Object value, int depth) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("k", value);
        String rendered = tmp.saveToString();
        String[] lines = rendered.split("\n", -1);
        String key = dottedPath.contains(".")
            ? dottedPath.substring(dottedPath.lastIndexOf('.') + 1) : dottedPath;
        StringBuilder out = new StringBuilder();
        String indent = "  ".repeat(depth);
        boolean first = true;
        for (String line : lines) {
            if (first) {
                first = false;
                out.append(indent).append(key).append(":\n");
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            out.append(indent).append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * 以暫存 YamlConfiguration 渲染純量（借用 Bukkit 的引號與跳脫規則）。
     */
    static String renderInline(Object value) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("k", value);
        String rendered = tmp.saveToString();
        // 形如 "k: <值>\n"；strip 首尾後取冒號後段
        int colon = rendered.indexOf(':');
        return rendered.substring(colon + 1).strip();
    }

    // -----------------------------------------------------------------
    // 行文件模型
    // -----------------------------------------------------------------

    /**
     * 原檔的行模型：記錄每條「值行」的路徑、縮排與區塊範圍，支援原地替換與插入。
     */
    private static final class Document {
        private final List<String> lines;
        /** 路徑 → 值行行號（純量行或節頭行）。 */
        private final Map<String, Integer> lineOf = new LinkedHashMap<>();

        private Document(List<String> lines) {
            this.lines = lines;
        }

        static Document parse(String text) {
            List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
            // split 尾端空字串代表結尾換行：保留為行模型的一部分，render 時還原
            Document document = new Document(lines);
            document.index();
            return document;
        }

        /** 建立路徑索引（縮排堆疊）。 */
        private void index() {
            List<String> stack = new ArrayList<>();
            List<Integer> indents = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                int indent = leadingSpaces(line);
                String stripped = line.strip();
                if (stripped.startsWith("- ")) {
                    // 清單項目：屬於堆疊頂節點的區塊，不建路徑
                    continue;
                }
                int colon = stripped.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = stripped.substring(0, colon).strip();
                if (key.isEmpty()) {
                    continue;
                }
                while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                    indents.remove(indents.size() - 1);
                    stack.remove(stack.size() - 1);
                }
                String rest = stripped.substring(colon + 1);
                if (rest.isBlank()) {
                    stack.add(key);
                    indents.add(indent);
                    lineOf.put(String.join(".", stack), i);
                } else {
                    String path = stack.isEmpty() ? key : String.join(".", stack) + "." + key;
                    lineOf.put(path, i);
                }
            }
        }

        boolean hasLine(String path) {
            return lineOf.containsKey(path);
        }

        /** 原地替換路徑的值（純量換行、區塊換段），行尾註解保留。 */
        void replaceValue(String path, Object fresh) {
            int at = lineOf.get(path);
            String line = lines.get(at);
            int indent = leadingSpaces(line);
            String stripped = line.strip();
            int colon = stripped.indexOf(':');
            String key = stripped.substring(0, colon).strip();
            String afterColon = stripped.substring(colon + 1);
            String comment = extractTrailingComment(afterColon);
            if (fresh instanceof List<?> || fresh instanceof Map<?, ?>) {
                replaceBlock(at, indent, key, fresh, comment);
                reindex();
                return;
            }
            String indentStr = " ".repeat(indent);
            lines.set(at, indentStr + key + ": " + renderInline(fresh) + comment);
        }

        /** 替換整段區塊（舊區塊的起止以縮排判定）。 */
        private void replaceBlock(int at, int indent, String key, Object fresh, String comment) {
            int end = blockEnd(at, indent);
            List<String> replacement = new ArrayList<>();
            String indentStr = " ".repeat(indent);
            if (!comment.isEmpty()) {
                // 節頭行尾註解保留在新節頭
                replacement.add(indentStr + key + ":" + comment);
            } else {
                replacement.add(indentStr + key + ":");
            }
            YamlConfiguration tmp = new YamlConfiguration();
            tmp.set("k", fresh);
            String[] rendered = tmp.saveToString().split("\n", -1);
            for (int i = 1; i < rendered.length; i++) {
                if (rendered[i].isBlank()) {
                    continue;
                }
                replacement.add(indentStr + rendered[i]);
            }
            lines.subList(at, end).clear();
            lines.addAll(at, replacement);
        }

        /** 缺 key 時插入：接在最長已存在祖先的區塊尾；祖先全無則在檔尾建鏈。 */
        void appendMissing(String path, Object fresh, String description) {
            int dot = path.lastIndexOf('.');
            if (dot < 0) {
                if (description != null && !description.isBlank()) {
                    lines.add("# " + description.strip());
                }
                if (fresh instanceof List<?> || fresh instanceof Map<?, ?>) {
                    for (String blockLine : renderNestedBlock(path, fresh, 0).split("\n")) {
                        if (!blockLine.isEmpty()) {
                            lines.add(blockLine);
                        }
                    }
                } else {
                    lines.add(path + ": " + renderInline(fresh));
                }
                reindex();
                return;
            }
            // 找最長的已存在祖先（父、祖父……逐層上溯）
            String ancestor = path.substring(0, dot);
            while (!ancestor.isEmpty() && !lineOf.containsKey(ancestor)) {
                int up = ancestor.lastIndexOf('.');
                ancestor = up < 0 ? "" : ancestor.substring(0, up);
            }
            String relative = ancestor.isEmpty() ? path : path.substring(ancestor.length() + 1);
            String[] chain = relative.split("\\.", -1);
            int baseIndent;
            int insertAt;
            if (ancestor.isEmpty()) {
                baseIndent = -2;
                insertAt = lines.size();
                // split(-1) 的尾端空字串元素會讓檔尾多一空行：先清掉再建鏈
                while (insertAt > 0 && lines.get(insertAt - 1).isEmpty()) {
                    lines.remove(insertAt - 1);
                    insertAt--;
                }
            } else {
                int ancestorLine = lineOf.get(ancestor);
                baseIndent = leadingSpaces(lines.get(ancestorLine));
                insertAt = blockEnd(ancestorLine, baseIndent);
            }
            List<String> addition = new ArrayList<>();
            // 中間層只建節頭，說明只掛在葉子上
            for (int i = 0; i < chain.length - 1; i++) {
                addition.add(" ".repeat(baseIndent + 2 * (i + 1)) + chain[i] + ":");
            }
            int leafIndent = baseIndent + 2 * chain.length;
            String leaf = chain[chain.length - 1];
            if (description != null && !description.isBlank()) {
                addition.add(" ".repeat(leafIndent) + "# " + description.strip());
            }
            if (fresh instanceof List<?> || fresh instanceof Map<?, ?>) {
                for (String blockLine : renderNestedBlock(leaf, fresh, 0).split("\n")) {
                    if (!blockLine.isEmpty()) {
                        addition.add(" ".repeat(leafIndent) + blockLine);
                    }
                }
            } else {
                addition.add(" ".repeat(leafIndent) + leaf + ": " + renderInline(fresh));
            }
            lines.addAll(insertAt, addition);
            reindex();
        }

        /** 區塊尾：第一個「非空非註解且縮排小於等於 base」的行號（不含）。 */
        private int blockEnd(int from, int baseIndent) {
            int i = from + 1;
            while (i < lines.size()) {
                String line = lines.get(i);
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    i++;
                    continue;
                }
                if (leadingSpaces(line) <= baseIndent) {
                    break;
                }
                i++;
            }
            return i;
        }

        private void reindex() {
            lineOf.clear();
            index();
        }

        /**
         * 刪除路徑：葉子刪單行，節頭刪整段；因此變空的祖先節頭一併修剪。
         * 路徑不在檔案中時為 no-op。
         */
        void removePath(String path) {
            Integer at = lineOf.get(path);
            if (at == null) {
                return;
            }
            String line = lines.get(at);
            int indent = leadingSpaces(line);
            if (isHeaderLine(line)) {
                lines.subList(at, blockEnd(at, indent)).clear();
            } else {
                lines.remove((int) at);
            }
            reindex();
            pruneEmptyAncestors(path);
        }

        /** 是否為節頭行（冒號後空白或只有行尾註解）。 */
        private static boolean isHeaderLine(String line) {
            String stripped = line.strip();
            int colon = stripped.indexOf(':');
            if (colon < 0) {
                return false;
            }
            String rest = stripped.substring(colon + 1).strip();
            return rest.isEmpty() || rest.startsWith("#");
        }

        /** 由近而遠修剪「已無任何值行」的祖先節頭；還有值的祖先保留。 */
        private void pruneEmptyAncestors(String path) {
            String ancestor = parentOf(path);
            while (ancestor != null) {
                Integer at = lineOf.get(ancestor);
                if (at == null || !isHeaderLine(lines.get(at))) {
                    break;
                }
                if (blockHasValue(at, leadingSpaces(lines.get(at)))) {
                    break;
                }
                lines.remove((int) at);
                reindex();
                ancestor = parentOf(ancestor);
            }
        }

        /** 區塊內是否還有值行（非空、非註解即算）。 */
        private boolean blockHasValue(int from, int baseIndent) {
            int end = blockEnd(from, baseIndent);
            for (int i = from + 1; i < end; i++) {
                String line = lines.get(i);
                if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
                    return true;
                }
            }
            return false;
        }

        private static String parentOf(String path) {
            int dot = path.lastIndexOf('.');
            return dot < 0 ? null : path.substring(0, dot);
        }

        String render() {
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                out.append(line).append('\n');
            }
            // split(-1) 會讓結尾換行多出一個空字串元素；render 每行補換行會多一個
            String rendered = out.toString();
            if (rendered.endsWith("\n\n")) {
                rendered = rendered.substring(0, rendered.length() - 1);
            }
            return rendered;
        }

        private static int leadingSpaces(String line) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == ' ') {
                count++;
            }
            return count;
        }

        /**
         * 切出行尾註解：引號外的第一個 {@code #} 起（含前導空白）。
         * 冒號後段傳入（例如 {@code " 'v'  # 說明"} → {@code "  # 說明"}，
         * 無註解回傳空字串）。
         */
        private static String extractTrailingComment(String afterColon) {
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < afterColon.length(); i++) {
                char c = afterColon.charAt(i);
                if (c == '\'' && !inDouble) {
                    inSingle = !inSingle;
                } else if (c == '"' && !inSingle) {
                    inDouble = !inDouble;
                } else if (c == '#' && !inSingle && !inDouble) {
                    return afterColon.substring(i == 0 ? 0 : i - (afterColon.charAt(i - 1) == ' ' ? 1 : 0));
                }
            }
            return "";
        }
    }
}

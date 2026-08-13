package com.smile.acelib.external;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小可用版本比較工具（內部工具）。
 *
 * <p>不引入外部 semver 函式庫；支援下列格式：</p>
 * <ul>
 *   <li>{@code "1.0"}、{@code "1.0.0"} — 元件數不同時缺省視為 0</li>
 *   <li>{@code "1.0.0-SNAPSHOT"} — 忽略 {@code '-'} 之後的 suffix</li>
 *   <li>非數值元件（例如 {@code "beta"}）— 視為無法比較，拋
 *       {@link IllegalArgumentException}，由 caller 採保守策略</li>
 * </ul>
 *
 * <p>本類別為 package-private 內部工具，不構成 public API。</p>
 */
final class VersionComparator {

    private VersionComparator() {
        // utility class
    }

    /**
     * 比較兩個版本字串。
     *
     * @param a 版本字串；不可為 null
     * @param b 版本字串；不可為 null
     * @return 負數（a &lt; b）、0（相等）、正數（a &gt; b）
     * @throws IllegalArgumentException 任一版本含非數值元件（無法比較）
     */
    static int compare(String a, String b) {
        List<Integer> left = parse(a);
        List<Integer> right = parse(b);
        int max = Math.max(left.size(), right.size());
        for (int i = 0; i < max; i++) {
            int l = i < left.size() ? left.get(i) : 0;
            int r = i < right.size() ? right.get(i) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    /**
     * 解析版本字串為數值元件序列。
     *
     * <p>先截斷 {@code '-'} 之後的 suffix（例如 {@code -SNAPSHOT}），再以
     * {@code '.'} 分割；每個元件必須為非負整數，否則拋
     * {@link IllegalArgumentException}（無法比較）。</p>
     */
    private static List<Integer> parse(String version) {
        String core = version;
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.", -1);
        List<Integer> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                // 空元件（例如 "1."）視為 0，與缺省元件語意一致
                result.add(0);
                continue;
            }
            if (!trimmed.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException(
                    "version component is not numeric: '" + trimmed + "' in '" + version + "'");
            }
            result.add(Integer.parseInt(trimmed));
        }
        return result;
    }
}
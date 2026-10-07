package com.smile.acelib.lifecycle;

import com.smile.acelib.AceLibApi;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.plugin.Plugin;

/**
 * 下游向生命週期宿主宣告的模組。
 *
 * <p>模組 id 在同一 AceLib 宿主內唯一，建議使用 {@code plugin-name:module-name}
 * 命名。相依必須指向同一宿主中已註冊的模組；宿主會先驗證完整圖，再呼叫
 * {@link Starter#enable(Context)}。啟用若在交回 handle 前失敗，模組本身必須
 * 清理尚未交由宿主管理的部分資源。</p>
 *
 * @param id 穩定且非空的全域模組 id
 * @param dependsOn 此模組需要先啟用的模組 id
 * @param starter 建立此模組並交回其生命週期 handle 的回呼
 * @since 1.4.0
 */
public record LifecycleModule(String id, Set<String> dependsOn, Starter starter) {

    /** 建立不可變模組宣告，拒絕空 id、空相依 id 或缺少啟用回呼。 */
    public LifecycleModule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("[ACELIB-LIFE-001] module id must not be blank");
        }
        Objects.requireNonNull(dependsOn, "dependsOn");
        TreeSet<String> dependencies = new TreeSet<>();
        for (String dependency : dependsOn) {
            if (dependency == null || dependency.isBlank()) {
                throw new IllegalArgumentException(
                    "[ACELIB-LIFE-001] dependency id must not be blank");
            }
            dependencies.add(dependency);
        }
        dependsOn = Collections.unmodifiableSet(dependencies);
        Objects.requireNonNull(starter, "starter");
    }

    /** 建立模組執行期上下文，提供擁有者及會隨 reload 更新的 API provider。 */
    public record Context(Plugin owner, AceLibApi.AceLibProvider apiProvider) {

    /**
     * 建立非空上下文；應保存 provider，而非保存其當次回傳的 facade。
     *
     * @param owner 模組擁有者；不可為 null
     * @param apiProvider reload 後仍指向目前 facade 的 provider；不可為 null
     */
    public Context {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(apiProvider, "apiProvider");
        }
    }

    /** 啟用模組並回傳宿主唯一擁有的清理 handle。 */
    @FunctionalInterface
    public interface Starter {

        /**
         * 啟用模組。
         *
         * @param context 模組擁有者與動態 AceLib API provider
         * @return 模組的清理 handle；不可為 null
         * @throws Exception 啟用失敗；回呼必須自行清理尚未交回的資源
         */
        Handle enable(Context context) throws Exception;
    }

    /** 模組交給宿主管理的單一清理 handle。 */
    @FunctionalInterface
    public interface Handle {

        /** 釋放模組自行建立且交由宿主管理的資源；應可安全重複呼叫。 */
        void close() throws Exception;
    }
}

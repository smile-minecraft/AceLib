package com.smile.acelib.command;

import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * 指令目錄：只存「指令描述」的共用目錄。
 *
 * <p>各插件把 {@link CommandSpec} 或純描述發布進來，其他插件讀出不可變快照
 * 產生說明頁。本目錄<strong>不註冊、不執行、不反射任何指令</strong>，
 * 快照中不存在任何可執行的物件或插件實例。</p>
 *
 * <h2>擁有者</h2>
 * <p>以傳入的 {@link Plugin} 實例識別（不是名稱字串）。同一個擁有者重複發布
 * 同名指令時覆蓋舊的；不同擁有者同名時兩者都保留。發布結果的判定優先序為
 * {@link CatalogResult#UNCHANGED} ＞ {@link CatalogResult#DUPLICATE_NAME} ＞
 * {@link CatalogResult#REPLACED} ＞ {@link CatalogResult#PUBLISHED}：
 * 只要該次發布的名稱有其他擁有者也在使用（無論本次是新增或覆蓋），
 * 就回報 {@link CatalogResult#DUPLICATE_NAME}；唯有內容完全相同的重發
 * 維持 {@link CatalogResult#UNCHANGED} 且不記錄警告。
 * 已發布的 {@link CommandDoc#owner()}
 * 一律等於擁有者插件的名稱；{@code publish(Plugin, CommandDoc)} 要求
 * {@code doc.owner()} 等於 {@code owner.getName()}，不一致以
 * {@link IllegalArgumentException} 拒絕（不能冒充他插件）。</p>
 *
 * <h2>比較與命名</h2>
 * <p>根指令名稱以小寫（{@link java.util.Locale#ROOT}）作為比較 key。</p>
 *
 * <h2>revision</h2>
 * <p>只在資料實際變更時 +1，單調遞增、不因清空而重設。
 * 注意 {@link #snapshot()} 與 {@link #revision()} 是兩次獨立呼叫、
 * 不是原子配對：消費者若需一致性判斷，應先讀 revision、再讀 snapshot、
 * 之後重讀 revision 檢查期間是否變動。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>內部以 copy-on-write 的不可變狀態（同時持有 entries 與 revision）
 * 原子替換；{@code publish}／{@code unpublishAll} 可在任意執行緒呼叫而不遺失資料；
 * {@code snapshot()}／{@code revision()} 各自執行緒安全。</p>
 *
 * <h2>服務可用性</h2>
 * <p>服務未就緒或已停用時：{@code publish} 一律回
 * {@link CatalogResult#REJECTED}（記錄 {@code ACELIB-CMD-014}），
 * {@code snapshot()} 回空清單，{@code unpublishAll} 為無害的 no-op。</p>
 *
 * @since 1.3.0
 */
public interface CommandCatalog {

    /**
     * Production factory：建立可用狀態的目錄實例。
     *
     * <p>實作類別 {@code CommandCatalogImpl} 為 package-private（不暴露為 public API）；
     * 本方法為 plugin 接線取得實例的唯一 public 入口，回傳型別為介面本身，
     * 隱藏內部實作。比照 {@link com.smile.acelib.gui.GuiService#forProduction}
     * 與 {@link com.smile.acelib.bedrock.BedrockService#forProduction} 的隱藏實作慣例。</p>
     *
     * @return 新的可用 {@link CommandCatalog} 實例；never null
     * @since 1.3.0
     */
    static CommandCatalog forProduction() {
        return new CommandCatalogImpl();
    }

    /**
     * Unavailable factory：建立未啟用／已停用狀態下的安全退回實例。
     *
     * <p>實作類別 {@code CommandCatalogUnavailableImpl} 為 package-private；
     * 本方法為內部 wiring 取得退回實例的唯一 public 入口。退回語意：
     * {@code publish} 一律回 {@link CatalogResult#REJECTED}（記錄
     * {@code ACELIB-CMD-014}），{@code snapshot()} 回空清單，
     * {@code unpublishAll} 為無害的 no-op。</p>
     *
     * @return 新的不可用 {@link CommandCatalog} 實例；never null
     * @since 1.3.0
     */
    static CommandCatalog forUnavailable() {
        return new CommandCatalogUnavailableImpl();
    }

    /**
     * 從 {@link CommandSpec} 投影描述性欄位並發布。
     *
     * <p>只取 name、aliases、description、usage、permission、category、icon，
     * 以及子指令的 name／description／usage／permission／argNames／minArgs／
     * maxArgs／playerOnly／consoleOnly／cooldownMillis；handler、completer、
     * {@link Plugin} 實例與 spec 實例本身不會進入目錄。
     * {@code meta.confirmSubcommands} 只比對該次發布已知的子指令名稱，
     * 出現未知名稱以 {@link IllegalArgumentException} 拒絕。</p>
     *
     * @param owner 擁有者插件；不可為 null
     * @param spec 指令規格；不可為 null
     * @param meta 目錄元資料；不可為 null
     * @return 發布結果；永不為 null
     * @throws NullPointerException 當任一參數為 null
     * @throws IllegalArgumentException 當確認子指令含未知名稱
     */
    CatalogResult publish(Plugin owner, CommandSpec spec, CatalogMeta meta);

    /**
     * 直接發布純描述。
     *
     * <p>{@code requiresConfirmation}、{@code category} 與 {@code icon}
     * 直接採用呼叫端在 {@link CommandDoc} 給的值。{@code doc.owner()}
     * 必須等於 {@code owner.getName()}，否則以
     * {@link IllegalArgumentException} 拒絕。</p>
     *
     * @param owner 擁有者插件；不可為 null
     * @param doc 指令描述；不可為 null
     * @return 發布結果；永不為 null
     * @throws NullPointerException 當任一參數為 null
     * @throws IllegalArgumentException 當 {@code doc.owner()} 與
     *         {@code owner.getName()} 不一致
     */
    CatalogResult publish(Plugin owner, CommandDoc doc);

    /**
     * 撤下該擁有者的所有資料。
     *
     * <p>撤下不存在的不算變更、不丟例外。服務不可用時為無害的 no-op。</p>
     *
     * @param owner 擁有者插件；不可為 null
     * @throws NullPointerException 當 {@code owner} 為 null
     */
    void unpublishAll(Plugin owner);

    /**
     * 回傳不可變快照：深層不可變、順序固定為先比 {@code owner} 再比 {@code name}
     *（自然字串順序）。服務不可用時回空清單。
     *
     * @return 不可變的 {@link CommandDoc} 清單；永不為 null
     */
    List<CommandDoc> snapshot();

    /**
     * 回傳目前 revision：只在資料實際變更時 +1，單調遞增。
     *
     * @return 目前 revision；從 0 起算
     */
    long revision();

    /**
     * 停用服務（冪等）：清空全部資料並標記不可用。
     *
     * <p>呼叫後：{@code publish} 一律回 {@link CatalogResult#REJECTED}，
     * {@code snapshot()} 回空清單，{@code unpublishAll} 為無害的 no-op；
     * 透過舊參考仍不可再寫入。比照 {@code WorldService.shutdown()}／
     * {@code GuiService.shutdown()} 的生命週期慣例，由擁有者插件在停用時呼叫。</p>
     */
    void shutdown();
}

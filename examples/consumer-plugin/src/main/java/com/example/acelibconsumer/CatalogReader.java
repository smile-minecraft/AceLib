package com.example.acelibconsumer;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.command.CommandCatalog;
import com.smile.acelib.command.CommandDoc;
import java.util.List;
import java.util.logging.Logger;

/**
 * 指令目錄消費範例（只用公開 API）。
 *
 * <p>與 {@code docs/modules/command.md}「消費範例」節相同寫法：
 * 取得目錄、讀 {@code snapshot()}、以前後 {@code revision()} 判斷快取是否失效。
 * 本類別存在是為了讓 fixture 的 {@code compileJava} 持續證明該範例可編譯。</p>
 */
public final class CatalogReader {

    private final AceLibApi api;
    private final Logger logger;

    public CatalogReader(AceLibApi api, Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    /** 讀出目錄快照並逐筆記錄；讀取期間若有變動會留下可追蹤訊息。 */
    public void printCatalog() {
        CommandCatalog catalog = api.getCommandCatalog();
        long before = catalog.revision();
        List<CommandDoc> docs = catalog.snapshot();
        long after = catalog.revision();
        if (before != after) {
            logger.info("command catalog changed while reading ("
                + before + " -> " + after + "); re-read if a stable view is needed.");
        }
        for (CommandDoc doc : docs) {
            logger.info(doc.owner() + "/" + doc.name()
                + ": " + doc.description());
        }
    }
}

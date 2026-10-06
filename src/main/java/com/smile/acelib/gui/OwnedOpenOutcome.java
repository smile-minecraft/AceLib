package com.smile.acelib.gui;

/**
 * 帶擁有者開啟的結果（Internal）。
 *
 * @param result 開啟結果；永不為 null
 * @param replaced 被取代的舊 session；無取代時為 null
 */
record OwnedOpenOutcome(GuiResult result, GuiSession replaced) {
}

package com.hify.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度分段器（K2 spec §2.4）：按字符滑动窗口切分，步长 = chunkSize − overlap，
 * 相邻段共享 overlap 个字符（避免语义被拦腰切断后两边都检索不到）。
 * 纯函数、零依赖，不感知 Spring/DB。
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> split(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("要求 chunkSize > 0 且 0 <= overlap < chunkSize");
        }
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int step = chunkSize - overlap;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + chunkSize, text.length());
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end == text.length()) {
                break; // 已到文末，避免因 overlap 回退产生重复尾段
            }
        }
        return chunks;
    }
}

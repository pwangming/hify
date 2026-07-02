package com.hify.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void 长文按窗口切分_步长等于size减overlap() {
        // 1200 字符，size=500 overlap=50 → 起点 0/450/900 → 3 段
        String text = "a".repeat(1200);
        List<String> chunks = TextChunker.split(text, 500, 50);
        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).length());
        assertEquals(500, chunks.get(1).length());
        assertEquals(300, chunks.get(2).length()); // 900..1200
    }

    @Test
    void 相邻段共享overlap字符() {
        // 用可辨识内容验证重叠：第 2 段开头 = 第 1 段末尾 overlap 个字符
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append((char) ('A' + i % 26));
        }
        List<String> chunks = TextChunker.split(sb.toString(), 100, 20);
        String tailOfFirst = chunks.get(0).substring(80); // 第 1 段最后 20 字符
        assertTrue(chunks.get(1).startsWith(tailOfFirst));
    }

    @Test
    void 文本短于一段_整体一段() {
        List<String> chunks = TextChunker.split("短文本", 500, 50);
        assertEquals(List.of("短文本"), chunks);
    }

    @Test
    void 段内容会trim首尾空白() {
        String text = "  hello  ";
        List<String> chunks = TextChunker.split(text, 500, 50);
        assertEquals(List.of("hello"), chunks);
    }

    @Test
    void 全空白文本_返回空列表() {
        assertEquals(List.of(), TextChunker.split("   \n\t  ", 500, 50));
    }

    @Test
    void null文本_返回空列表() {
        assertEquals(List.of(), TextChunker.split(null, 500, 50));
    }

    @Test
    void 纯空白的窗口段被跳过_不产生空段() {
        // 100 个空格 + 内容：第一窗口全空白应被跳过
        String text = " ".repeat(100) + "content";
        List<String> chunks = TextChunker.split(text, 100, 0);
        assertEquals(List.of("content"), chunks);
    }

    @Test
    void 参数非法_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("x", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("x", 500, -1));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("x", 500, 500));
    }
}

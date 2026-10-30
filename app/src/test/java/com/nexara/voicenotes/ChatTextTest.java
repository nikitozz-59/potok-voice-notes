package com.nexara.voicenotes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Тесты текстовой логики чата ChatText (wrapCn / truncateCn).
 *
 * Чистые функции без Android-зависимостей, вынесенные из ChatActivity
 * ради покрытия юнит-тестами.
 *
 * wrapCn: перенос по символьной ширине, уважающий существующие '\n'.
 * truncateCn: безопасная обрезка с многоточием.
 */
public class ChatTextTest {

    // === wrapCn ===

    @Test
    public void wrap_shortText_noBreaks() {
        String s = "короткий";
        assertEquals(s, ChatText.wrapCn(s, 60));
    }

    @Test
    public void wrap_longText_breaksAtWidth() {
        String s = "abcdefghij";
        String wrapped = ChatText.wrapCn(s, 5);
        assertEquals("abcde\nfghij", wrapped);
    }

    @Test
    public void wrap_exactWidth_noTrailingBreak() {
        // Ровно width символов — не должно быть лишней '\n' в конце.
        String s = "abcde";
        assertEquals("abcde", ChatText.wrapCn(s, 5));
    }

    @Test
    public void wrap_existingNewlines_preserved() {
        String s = "ab\ncd";
        assertEquals("ab\ncd", ChatText.wrapCn(s, 60));
    }

    @Test
    public void wrap_longWithNewlines_breaksBothWays() {
        // Ширина 3: перенос на границах и уважение существующего '\n'.
        // "abc\ndefgh" → "abc" перенос, затем исходный '\n', затем "def" перенос, "gh".
        String s = "abc\ndefgh";
        assertEquals("abc\ndef\ngh", ChatText.wrapCn(s, 3));
    }

    @Test
    public void wrap_null_returnsEmpty() {
        assertEquals("", ChatText.wrapCn(null, 10));
    }

    @Test
    public void wrap_empty_returnsEmpty() {
        assertEquals("", ChatText.wrapCn("", 10));
    }

    @Test
    public void wrap_widthOne_breaksEveryChar() {
        assertEquals("a\nb\nc", ChatText.wrapCn("abc", 1));
    }

    @Test
    public void wrap_widthZero_clampsToOne() {
        assertEquals("a\nb", ChatText.wrapCn("ab", 0));
    }

    @Test
    public void wrap_noTrailingNewlineAfterFullSplit() {
        // "abcdef" при width 2 — "ab\ncd\nef", без '\n' в конце.
        String s = "abcdef";
        String w = ChatText.wrapCn(s, 2);
        assertEquals("ab\ncd\nef", w);
        assertTrue(!w.endsWith("\n"));
    }

    // === truncateCn ===

    @Test
    public void truncate_shortText_unchanged() {
        assertEquals("привет", ChatText.truncateCn("привет", 10));
    }

    @Test
    public void truncate_null_returnsEmpty() {
        assertEquals("", ChatText.truncateCn(null, 10));
    }

    @Test
    public void truncate_empty_returnsEmpty() {
        assertEquals("", ChatText.truncateCn("", 10));
    }

    @Test
    public void truncate_exactLength_unchanged() {
        assertEquals("abcde", ChatText.truncateCn("abcde", 5));
    }

    @Test
    public void truncate_longText_addsEllipsis() {
        // Обрезка до 5: берём 4 символа + '…' = длина 5.
        assertEquals("abcd…", ChatText.truncateCn("abcdefghij", 5));
    }

    @Test
    public void truncate_respectsRussionChars() {
        // Кириллица не ломается: обрезка по char (без суррогатов).
        String s = "Очень длинная мысль которую надо обрезать";
        String t = ChatText.truncateCn(s, 12);
        assertEquals(12, t.length());
        assertTrue(t.endsWith("…"));
    }

    @Test
    public void truncate_widthZero_clampsToOne() {
        assertEquals("…", ChatText.truncateCn("ab", 0));
    }

    @Test
    public void truncate_countMatchesOriginalPrefix() {
        // substr(0, n-1) — первые n-1 символов оригинальны.
        String t = ChatText.truncateCn("0123456789", 4);
        assertEquals("012…", t);
        assertTrue(t.startsWith("012"));
    }
}

package com.nexara.voicenotes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Тесты модели сообщения чата ChatMessage.
 *
 * Чистая модель данных (role + content) без Android-зависимостей.
 * Покрытие: роли, фабрики user/assistant/system, null-контент.
 */
public class ChatMessageTest {

    @Test
    public void user_factory_setsRoleAndContent() {
        ChatMessage m = ChatMessage.user("привет");
        assertEquals("user", m.role);
        assertEquals("привет", m.content);
    }

    @Test
    public void assistant_factory_setsRoleAndContent() {
        ChatMessage m = ChatMessage.assistant("ответ");
        assertEquals("assistant", m.role);
        assertEquals("ответ", m.content);
    }

    @Test
    public void system_factory_setsRoleAndContent() {
        ChatMessage m = ChatMessage.system("система");
        assertEquals("system", m.role);
        assertEquals("система", m.content);
    }

    @Test
    public void content_canBeNull() {
        ChatMessage m = new ChatMessage("user", null);
        assertEquals("user", m.role);
        assertNull(m.content);
    }

    @Test
    public void emptyContent_keptAsIs() {
        ChatMessage m = new ChatMessage("assistant", "");
        assertEquals("", m.content);
    }

    @Test
    public void roles_areDistinct() {
        assertTrue(!ChatMessage.user("x").role.equals(ChatMessage.assistant("x").role));
        assertTrue(!ChatMessage.assistant("x").role.equals(ChatMessage.system("x").role));
        assertTrue(!ChatMessage.system("x").role.equals(ChatMessage.user("x").role));
    }

    @Test
    public void multilineContent_roundTrips() {
        ChatMessage m = new ChatMessage("user", "строка 1\nстрока 2\nстрока 3");
        assertEquals("строка 1\nстрока 2\nстрока 3", m.content);
        assertNotNull(m.role);
    }

    @Test
    public void factoryIsNotSingleton() {
        // Каждый вызов создаёт отдельный объект (история сохраняет порядок сообщений).
        ChatMessage a = ChatMessage.user("q1");
        ChatMessage b = ChatMessage.user("q2");
        assertTrue(a != b);
    }
}

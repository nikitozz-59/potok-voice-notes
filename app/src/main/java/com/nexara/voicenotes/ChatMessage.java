package com.nexara.voicenotes;

/**
 * Сообщение в чате с LLM (multi-turn). role: "system" | "user" | "assistant".
 * Чистая модель данных, без Android-зависимостей.
 */
public class ChatMessage {
    public final String role;
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
}

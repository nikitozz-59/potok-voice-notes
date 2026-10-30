package com.nexara.voicenotes;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Тесты пакетно-приватного парсера ответа /chat/completions
 * (OpenAiLlmProvider.parseChatResponse) — без сети, на чистом org.json.
 *
 * Это логика, которую chat() выполняет над ответом LLM: достаёт
 * choices[0].message.content и возвращает с обрезкой пробелов.
 */
public class OpenAiLlmChatResponseTest {

    private static JSONObject responseWith(String content) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", content);
        JSONObject choice = new JSONObject();
        choice.put("message", msg);
        JSONArray choices = new JSONArray();
        choices.put(choice);
        JSONObject resp = new JSONObject();
        resp.put("choices", choices);
        return resp;
    }

    @Test
    public void parse_returnContentTrimmed() throws Exception {
        JSONObject resp = responseWith("  Ответ LLM  ");
        assertEquals("Ответ LLM", OpenAiLlmProvider.parseChatResponse(resp));
    }

    @Test
    public void parse_multilineContentKept() throws Exception {
        JSONObject resp = responseWith("строка 1\nстрока 2\n\nстрока 3");
        assertEquals("строка 1\nстрока 2\n\nстрока 3", OpenAiLlmProvider.parseChatResponse(resp));
    }

    @Test
    public void parse_secondChoiceIgnored_firstUsed() throws Exception {
        // Берём первый выбор, даже если второй содержит другой текст.
        JSONObject first = new JSONObject().put("message",
            new JSONObject().put("role", "assistant").put("content", "первый ответ"));
        JSONObject second = new JSONObject().put("message",
            new JSONObject().put("role", "assistant").put("content", "второй"));
        JSONObject resp = new JSONObject();
        resp.put("choices", new JSONArray().put(first).put(second));
        assertEquals("первый ответ", OpenAiLlmProvider.parseChatResponse(resp));
    }

    @Test(expected = Exception.class)
    public void parse_noChoices_throws() throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("choices", new JSONArray());
        OpenAiLlmProvider.parseChatResponse(resp);
    }

    @Test(expected = Exception.class)
    public void parse_missingChoicesField_throws() throws Exception {
        OpenAiLlmProvider.parseChatResponse(new JSONObject());
    }

    @Test(expected = Exception.class)
    public void parse_emptyContent_throws() throws Exception {
        JSONObject resp = responseWith("");
        OpenAiLlmProvider.parseChatResponse(resp);
    }

    @Test(expected = Exception.class)
    public void parse_whitespaceOnlyContent_throws() throws Exception {
        JSONObject resp = responseWith("   \n  ");
        OpenAiLlmProvider.parseChatResponse(resp);
    }

    @Test(expected = Exception.class)
    public void parse_nullContent_throws() throws Exception {
        // content === null → optString вернёт "" → считаем пустым.
        JSONObject msg = new JSONObject().put("role", "assistant").put("content",
            org.json.JSONObject.NULL);
        JSONObject choice = new JSONObject().put("message", msg);
        JSONObject resp = new JSONObject().put("choices", new JSONArray().put(choice));
        OpenAiLlmProvider.parseChatResponse(resp);
    }

    @Test(expected = Exception.class)
    public void parse_messageFieldMissing_throws() throws Exception {
        JSONObject choice = new JSONObject();
        choice.put("message", new JSONObject()); // message без content
        JSONObject resp = new JSONObject();
        resp.put("choices", new JSONArray().put(choice));
        OpenAiLlmProvider.parseChatResponse(resp);
    }
}

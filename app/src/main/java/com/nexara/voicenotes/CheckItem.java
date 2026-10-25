package com.nexara.voicenotes;

/** Пункт списка дел с признаком выполнения. */
public class CheckItem {
    public String text;
    public boolean done;

    public CheckItem(String text, boolean done) {
        this.text = text == null ? "" : text;
        this.done = done;
    }

    /** Оборачивает сырые строки из LLM в невыполенные пункты. */
    public static java.util.List<CheckItem> wrap(java.util.List<String> raw) {
        java.util.List<CheckItem> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s != null && !s.trim().isEmpty()) out.add(new CheckItem(s.trim(), false));
        }
        return out;
    }
}

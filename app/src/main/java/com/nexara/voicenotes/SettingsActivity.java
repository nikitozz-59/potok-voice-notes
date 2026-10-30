package com.nexara.voicenotes;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private EditText keyInput;
    private EditText sttProviderInput;
    private EditText sttBaseUrlInput;
    private EditText sttModelInput;
    private EditText sttKeyInput;
    private EditText llmBaseUrlInput;
    private EditText llmModelInput;
    private EditText llmKeyInput;
    private TextView statusLine;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences("vn", MODE_PRIVATE);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());

        keyInput = findViewById(R.id.keyInput);
        sttProviderInput = findViewById(R.id.sttProviderInput);
        sttBaseUrlInput = findViewById(R.id.sttBaseUrlInput);
        sttModelInput = findViewById(R.id.sttModelInput);
        sttKeyInput = findViewById(R.id.sttKeyInput);
        llmBaseUrlInput = findViewById(R.id.llmBaseUrlInput);
        llmModelInput = findViewById(R.id.llmModelInput);
        llmKeyInput = findViewById(R.id.llmKeyInput);
        statusLine = findViewById(R.id.statusLine);
        Button save = findViewById(R.id.saveBtn);

        findViewById(R.id.processorsBtn).setOnClickListener(v ->
            startActivity(new Intent(this, ProcessorsActivity.class)));

        keyInput.setText(prefs.getString("nexara_key", ""));
        sttProviderInput.setText(prefs.getString("stt_provider", "nexara"));
        sttBaseUrlInput.setText(prefs.getString("stt_base_url", "https://api.openai.com/v1"));
        sttModelInput.setText(prefs.getString("stt_model", "whisper-1"));
        sttKeyInput.setText(prefs.getString("stt_key", ""));
        llmBaseUrlInput.setText(prefs.getString("llm_base_url", "https://plusvibeapi.ru/v1"));
        llmModelInput.setText(prefs.getString("llm_model", "z-ai/glm-5.2"));
        llmKeyInput.setText(prefs.getString("llm_key", ""));
        updateStatus();

        save.setOnClickListener(v -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putString("nexara_key", keyInput.getText().toString().trim());
            String sttProvider = sttProviderInput.getText().toString().trim().toLowerCase();
            ed.putString("stt_provider", sttProvider.isEmpty() ? "nexara" : sttProvider);
            ed.putString("stt_base_url", sttBaseUrlInput.getText().toString().trim());
            ed.putString("stt_model", sttModelInput.getText().toString().trim());
            ed.putString("stt_key", sttKeyInput.getText().toString().trim());
            ed.putString("llm_base_url", llmBaseUrlInput.getText().toString().trim());
            ed.putString("llm_model", llmModelInput.getText().toString().trim());
            ed.putString("llm_key", llmKeyInput.getText().toString().trim());
            ed.apply();
            updateStatus();
        });
    }

    private void updateStatus() {
        String sttProvider = prefs.getString("stt_provider", "nexara");
        String sttKey;
        String sttName;

        if ("openai".equals(sttProvider)) {
            sttKey = prefs.getString("stt_key", "");
            sttName = "STT: " + prefs.getString("stt_model", "whisper-1")
                + " @ " + prefs.getString("stt_base_url", "");
        } else {
            sttKey = prefs.getString("nexara_key", "");
            sttName = "STT: Nexara";
        }

        String lk = prefs.getString("llm_key", "");
        StringBuilder sb = new StringBuilder();

        if (sttKey != null && !sttKey.isEmpty()) {
            sb.append(sttName).append(" (").append(sttKey.substring(0, Math.min(sttKey.length(), 6))).append("…)");
        } else {
            sb.append("STT: НЕ задан — задайте ключ");
        }
        sb.append("\n");
        if (lk != null && !lk.isEmpty()) {
            sb.append("LLM: ").append(prefs.getString("llm_model", "z-ai/glm-5.2"))
              .append(" (").append(lk.substring(0, Math.min(lk.length(), 6))).append("…)");
        } else {
            sb.append("LLM: НЕ задан — типизация отключена");
        }
        statusLine.setText(sb.toString());
    }
}

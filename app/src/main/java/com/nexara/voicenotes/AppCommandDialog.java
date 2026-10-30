package com.nexara.voicenotes;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * «Скажите, что поменять» — управление приложением голосом или текстом.
 *
 * Никаких кнопок настроек, ползунков и списков тем. Два равных пути:
 * - написать фразу в строке и нажать Enter;
 * - зажать микрофон кружком, сказать вслух, отпустить.
 *
 * Фраза уходит в AppCommandParser (локальные правила + LLM),
 * результат применяет AppMutator, хост перекрашивается через onApplied.
 */
public class AppCommandDialog extends Dialog {

    private final Context ctx;
    private LinearLayout log;
    private EditText input;
    private ProgressBar progress;
    private TextView micView;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // запись голоса
    private MediaRecorder recorder;
    private File recFile;
    private boolean recording = false;
    private long recStart = 0;

    public interface OnApplied {
        void onApplied();
    }

    private OnApplied onApplied;

    public AppCommandDialog(Context ctx) {
        super(ctx);
        this.ctx = ctx;
    }

    public void setOnApplied(OnApplied l) { this.onApplied = l; }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = dp(18);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(14), pad, dp(16));

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(rounded(ThemeManager.BG_CARD, 20));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }

        TextView hint = new TextView(ctx);
        hint.setText("Скажите, что изменить");
        hint.setTextAppearance(ctx, R.style.TextAppearance_Potok_Title);
        hint.setTextColor(ThemeManager.TEXT_PRIMARY);
        root.addView(hint);

        TextView sub = new TextView(ctx);
        sub.setText("Напишите или зажмите микрофон и скажите вслух");
        sub.setTextAppearance(ctx, R.style.TextAppearance_Potok_Caption);
        sub.setTextColor(ThemeManager.TEXT_SECONDARY);
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        // лог ответов
        log = new LinearLayout(ctx);
        log.setOrientation(LinearLayout.VERTICAL);
        root.addView(log);

        progress = new ProgressBar(ctx);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(4);
        progress.setLayoutParams(plp);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        status = new TextView(ctx);
        status.setTextAppearance(ctx, R.style.TextAppearance_Potok_Caption);
        status.setTextColor(ThemeManager.TEXT_SECONDARY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(2), 0, dp(2));
        root.addView(status);

        // ---- строка ввода + микрофон ----
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        inputRow.setLayoutParams(rlp);

        input = new EditText(ctx);
        input.setHint("Что поменять?");
        input.setHintTextColor(ThemeManager.TEXT_SECONDARY);
        input.setTextColor(ThemeManager.TEXT_PRIMARY);
        input.setTextAppearance(ctx, R.style.TextAppearance_Potok_Title);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(ilp);
        input.setBackground(rounded(0xFFF2F4F8, 14));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));

        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submit(input.getText().toString().trim());
                return true;
            }
            return false;
        });
        inputRow.addView(input);

        // микрофон: зажать — говорите, отпустить — применить
        micView = new TextView(ctx);
        micView.setText("⏺");
        micView.setTextSize(24); // glyph-иконка записи, не текст — вне типошкалы
        micView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(56), dp(56));
        mlp.leftMargin = dp(10);
        micView.setLayoutParams(mlp);
        micView.setBackground(circle(ThemeManager.accent(ctx)));
        micView.setTextColor(0xFFFFFFFF);

        micView.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startVoice();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (recording) stopVoiceAndApply();
                    return true;
            }
            return false;
        });
        inputRow.addView(micView);
        root.addView(inputRow);

        setContentView(root);

        input.requestFocus();
        if (w != null) w.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    @Override
    public void show() {
        super.show();
        ui.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    // ---------- голос ----------

    private void startVoice() {
        if (recording) return;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("Нет доступа к микрофону — разрешите в настройках Android");
            return;
        }
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            recFile = new File(ctx.getCacheDir(), "cmd_" + ts + ".m4a");

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            recording = true;
            recStart = System.currentTimeMillis();
            micView.setBackground(circle(ThemeManager.STOP_RED));
            micView.setText("■");
            status.setText("Слушаю… отпустите, когда закончите");
            hideKeyboard();
        } catch (Exception e) {
            recording = false;
            status.setText("Ошибка записи: " + e.getMessage());
        }
    }

    private void stopVoiceAndApply() {
        recording = false;
        long durMs = System.currentTimeMillis() - recStart;
        try { recorder.stop(); recorder.release(); recorder = null; } catch (Exception e) {}
        micView.setBackground(circle(ThemeManager.accent(ctx)));
        micView.setText("⏺");

        final File f = recFile;
        if (f == null || !f.exists() || f.length() == 0) {
            status.setText("Не записалось");
            return;
        }
        // слишком короткое нажатие — почти наверняка случайное
        if (durMs < 400) {
            f.delete();
            status.setText("Слишком коротко — зажмите и говорите");
            return;
        }

        status.setText("Распознаю…");
        setBusy(true);
        new Thread(() -> {
            String text = null;
            try {
                SttProvider stt = ProviderFactory.createSttProvider(ctx);
                if (stt != null) {
                    byte[] data = Files.readAllBytes(f.toPath());
                    text = stt.transcribe(data, f.getName(), "ru");
                }
            } catch (Exception e) {
                text = null;
            } finally {
                f.delete();
            }
            final String recognized = text;
            ui.post(() -> {
                setBusy(false);
                if (recognized == null || recognized.trim().isEmpty()) {
                    status.setText("Не расслышал, попробуйте ещё раз или напишите");
                    return;
                }
                status.setText("");
                submit(recognized.trim());
            });
        }).start();
    }

    // ---------- обработка команды ----------

    private void submit(String phrase) {
        if (phrase.isEmpty()) return;

        addLine(phrase, ThemeManager.TEXT_PRIMARY, true);
        input.setText("");
        setBusy(true);
        status.setText("Думаю…");

        new Thread(() -> {
            AppCommandParser.Command cmd = AppCommandParser.parse(ctx, phrase);
            AppMutator.Result res = AppMutator.apply(ctx, cmd);
            ui.post(() -> {
                setBusy(false);
                status.setText("");
                boolean ok = res.changed;
                addLine(res.message, ok ? ThemeManager.accent(ctx) : 0xFFB3261E, false);
                if (ok) {
                    addSwatch(ThemeManager.accent(ctx));
                    // перекрашиваем сам диалог, чтобы было видно результат немедленно
                    micView.setBackground(circle(ThemeManager.accent(ctx)));
                    if (onApplied != null) onApplied.onApplied();
                }
                input.requestFocus();
            });
        }).start();
    }

    private void setBusy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        input.setEnabled(!b);
    }

    private void addLine(String text, int color, boolean isUser) {
        TextView tv = new TextView(ctx);
        tv.setText((isUser ? "вы: " : "поток: ") + text);
        tv.setTextAppearance(ctx, R.style.TextAppearance_Potok_Body);
        tv.setTextColor(color);
        if (!isUser) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(4), 0, dp(3));
        log.addView(tv);
        trimLog();
    }

    private void addSwatch(int color) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        lp.topMargin = dp(1);
        lp.bottomMargin = dp(5);
        v.setLayoutParams(lp);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(3));
        d.setColor(color);
        v.setBackground(d);
        log.addView(v);
        trimLog();
    }

    private void trimLog() {
        while (log.getChildCount() > 10) log.removeViewAt(0);
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radius));
        d.setColor(color);
        return d;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}

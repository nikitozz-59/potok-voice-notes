package com.nexara.voicenotes;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Настраиваемая шапка экрана: заголовок + до двух иконок-кнопок + опциональная
 * кнопка «назад». Заменяет системный ActionBar (тема NoActionBar).
 *
 * <p>Использование в layout:
 * <pre>{@code
 * <com.nexara.voicenotes.TopBarLayout
 *     android:id="@+id/topBar"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:topBar_title="Поток" />
 * }</pre>
 */
public class TopBarLayout extends LinearLayout {

    private ImageButton backBtn;
    private TextView titleText;
    private ImageButton actionBtn1;
    private ImageButton actionBtn2;

    public TopBarLayout(Context context) {
        this(context, null);
    }

    public TopBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_top_bar, this, true);

        backBtn = findViewById(R.id.backBtn);
        titleText = findViewById(R.id.titleText);
        actionBtn1 = findViewById(R.id.actionBtn1);
        actionBtn2 = findViewById(R.id.actionBtn2);

        // Заголовок и начальные значения из атрибутов
        if (attrs != null) {
            android.content.res.TypedArray ta2 = context.obtainStyledAttributes(attrs,
                    R.styleable.TopBarLayout);
            try {
                CharSequence t = ta2.getText(R.styleable.TopBarLayout_topBar_title);
                if (t != null) titleText.setText(t);
                if (ta2.getBoolean(R.styleable.TopBarLayout_topBar_showBack, false)) {
                    showBack();
                }
                int a1 = ta2.getResourceId(R.styleable.TopBarLayout_topBar_icon1, 0);
                if (a1 != 0) {
                    actionBtn1.setImageResource(a1);
                    actionBtn1.setColorFilter(ThemeManager.TEXT_PRIMARY);
                    actionBtn1.setVisibility(VISIBLE);
                }
                int a2 = ta2.getResourceId(R.styleable.TopBarLayout_topBar_icon2, 0);
                if (a2 != 0) {
                    actionBtn2.setImageResource(a2);
                    actionBtn2.setColorFilter(ThemeManager.TEXT_PRIMARY);
                    actionBtn2.setVisibility(VISIBLE);
                }
            } finally {
                ta2.recycle();
            }
        }
    }

    /** Устанавливает заголовок. */
    public TopBarLayout setTitle(String title) {
        titleText.setText(title);
        return this;
    }

    /** Показывает кнопку «назад» и подписывает клик. */
    public TopBarLayout showBack(OnClickListener listener) {
        backBtn.setVisibility(VISIBLE);
        backBtn.setOnClickListener(listener);
        return this;
    }

    public TopBarLayout showBack() {
        return showBack(v -> {
            if (getContext() instanceof android.app.Activity) {
                ((android.app.Activity) getContext()).finish();
            }
        });
    }

    /** Настраивает первую иконку-действие (справа). Возвращает её для доп. стилизации. */
    public ImageButton setAction1(int iconRes, OnClickListener listener) {
        return configureAction(actionBtn1, iconRes, listener);
    }

    /** Настраивает вторую иконку-действие. */
    public ImageButton setAction2(int iconRes, OnClickListener listener) {
        return configureAction(actionBtn2, iconRes, listener);
    }

    private ImageButton configureAction(ImageButton btn, int iconRes, OnClickListener l) {
        btn.setImageResource(iconRes);
        btn.setColorFilter(ThemeManager.TEXT_PRIMARY);
        btn.setOnClickListener(l);
        btn.setVisibility(VISIBLE);
        return btn;
    }

    public TextView getTitleView() {
        return titleText;
    }
}

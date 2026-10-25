package com.nexara.voicenotes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Автозапуск релея и перепланировка напоминаний при загрузке телефона. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent i = new Intent(ctx, RelayService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            // AlarmManager не переживает перезагрузку — перепланируем все
            // напоминания из хранилища (только будущие, прошедшие skip).
            ReminderScheduler.rescheduleAll(ctx);
        }
    }
}

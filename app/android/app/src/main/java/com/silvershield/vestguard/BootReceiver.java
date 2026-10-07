package com.silvershield.vestguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts vest monitoring after the phone reboots (only if it was on before). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !"android.intent.action.MY_PACKAGE_REPLACED".equals(a)) return;
        if (!VestService.prefs(context).getBoolean("enabled", false)) return;
        try {
            VestService.start(context);
        } catch (Exception ignored) {
            // Android may refuse in rare states; the app restarts it when opened.
        }
    }
}

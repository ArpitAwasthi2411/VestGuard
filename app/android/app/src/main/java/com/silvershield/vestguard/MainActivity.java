package com.silvershield.vestguard;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VestPlugin.class);
        super.onCreate(savedInstanceState);
        showOverLockIfAlert(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showOverLockIfAlert(intent);
    }

    /** After the alarm is handled, the app no longer shows over the lock screen. */
    void releaseLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false);
            setTurnScreenOn(false);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    /** When opened from a fall alarm, show over the lock screen and turn the screen on. */
    private void showOverLockIfAlert(Intent intent) {
        boolean alert = intent != null && intent.hasExtra("vg_alert");
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(alert);
            setTurnScreenOn(alert);
        } else if (alert) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }
}

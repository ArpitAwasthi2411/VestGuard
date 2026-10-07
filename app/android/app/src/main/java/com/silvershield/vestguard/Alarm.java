package com.silvershield.vestguard;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * Loud, looping alarm on the ALARM audio stream (plays even when the ringer is on silent)
 * plus a repeating vibration. Stops when a caregiver responds, or after 5 minutes.
 */
final class Alarm {
    private static final String TAG = "VestGuardAlarm";
    private static final long AUTO_STOP_MS = 5 * 60 * 1000L;
    private static MediaPlayer player;
    private static Vibrator vibrator;
    private static int savedVolume = -1;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable autoStop = Alarm::stopNow;
    private static Context appCtx;

    private Alarm() {}

    static synchronized boolean isRinging() { return player != null; }

    static void start(Context ctx, long durationMs) {
        appCtx = ctx.getApplicationContext();
        main.post(() -> startNow(durationMs > 0 ? durationMs : AUTO_STOP_MS));
    }

    static void stop() { main.post(Alarm::stopNow); }

    private static synchronized void startNow(long durationMs) {
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, durationMs);
        if (player != null) return;
        Context ctx = appCtx;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int cur = am.getStreamVolume(AudioManager.STREAM_ALARM);
                int want = Math.max(1, (int) Math.ceil(max * 0.8));
                if (cur < want) {
                    savedVolume = cur;
                    am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "volume", e);
        }
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(ctx, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Log.w(TAG, "player", e);
            player = null;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            long[] pattern = {0, 700, 300, 700, 300, 1200, 600};
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), aa);
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrate", e);
        }
        // if the player could not start, still mark as ringing so stop() cleans up vibration
        if (player == null) player = new MediaPlayer();
    }

    private static synchronized void stopNow() {
        main.removeCallbacks(autoStop);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) { }
            vibrator = null;
        }
        if (savedVolume >= 0 && appCtx != null) {
            try {
                AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (Exception ignored) { }
            savedVolume = -1;
        }
    }
}

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
    private static boolean ringing = false;

    private Alarm() {}

    static synchronized boolean isRinging() { return ringing; }

    /** a short test ring; never shortens a real alarm that is already ringing */
    static void test(Context ctx) {
        appCtx = ctx.getApplicationContext();
        main.post(() -> { synchronized (Alarm.class) { if (ringing) return; } startNow(3000); });
    }

    static void start(Context ctx, long durationMs) {
        appCtx = ctx.getApplicationContext();
        main.post(() -> startNow(durationMs > 0 ? durationMs : AUTO_STOP_MS));
    }

    static void stop() { main.post(Alarm::stopNow); }

    private static synchronized void startNow(long durationMs) {
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, durationMs);
        if (ringing) return;
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
        // 1) the phone's alarm sound  2) its ringtone  3) our own bundled sound (always works)
        Uri sys = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM);
        if (sys == null) sys = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE);
        player = sys != null ? tryPlay(ctx, sys, 0) : null;
        if (player == null) player = tryPlay(ctx, null, R.raw.vg_alarm);
        ringing = true;
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
    }

    private static MediaPlayer tryPlay(Context ctx, Uri uri, int rawRes) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (uri != null) {
                mp.setDataSource(ctx, uri);
            } else {
                android.content.res.AssetFileDescriptor fd = ctx.getResources().openRawResourceFd(rawRes);
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            }
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            return mp;
        } catch (Exception e) {
            Log.w(TAG, "player " + uri, e);
            try { mp.release(); } catch (Exception ignored) { }
            return null;
        }
    }

    private static synchronized void stopNow() {
        main.removeCallbacks(autoStop);
        ringing = false;
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

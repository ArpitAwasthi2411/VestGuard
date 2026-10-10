package com.silvershield.vestguard;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/** Spoken prompts on the Home Hub phone ("Are you OK?") using the phone's own text-to-speech. */
final class Voice {
    private static final String TAG = "VestGuardVoice";
    private static TextToSpeech tts;
    private static boolean ready = false;

    private Voice() {}

    static synchronized void init(Context ctx) {
        if (tts != null) return;
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
            synchronized (Voice.class) {
                ready = status == TextToSpeech.SUCCESS;
                if (ready && tts != null) {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    tts.setSpeechRate(0.9f);
                }
            }
        });
    }

    /** lang "hi" or "en"; falls back to English if the phone has no Hindi voice. */
    static synchronized void say(String text, String lang) {
        if (tts == null || !ready || text == null) return;
        try {
            Locale want = "hi".equals(lang) ? new Locale("hi", "IN") : new Locale("en", "IN");
            int r = tts.setLanguage(want);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.ENGLISH);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vg");
        } catch (Exception e) {
            Log.w(TAG, "speak", e);
        }
    }

    static synchronized void stop() {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
    }

    static synchronized void shutdown() {
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
        tts = null;
        ready = false;
    }
}

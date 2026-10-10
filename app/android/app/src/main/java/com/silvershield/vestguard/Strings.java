package com.silvershield.vestguard;

import java.util.HashMap;
import java.util.Map;

/** Notification / SMS text in English and Hindi (the app UI has its own strings in i18n.js). */
final class Strings {
    private Strings() {}

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> HI = new HashMap<>();

    static {
        EN.put("ch_alert", "Fall alarms");
        EN.put("ch_status", "Vest connection");
        EN.put("ch_info", "Vest notices");
        EN.put("status_title", "Protecting {name}");
        EN.put("status_on", "Vest connected · {act}");
        EN.put("status_off", "Waiting for the vest. Is the phone hotspot on?");
        EN.put("fall_title", "Fall detected: {name}");
        EN.put("fall_title_demo", "Demo fall alarm: {name}");
        EN.put("fall_body", "{sev} fall. Tap to open and respond.");
        EN.put("fall_esc", "No one has responded for 1 minute. {sev} fall.");
        EN.put("fall_ack", "You are responding. Resolve it in the app when done.");
        EN.put("responding", "I'm responding");
        EN.put("open", "Open");
        EN.put("lost_title", "Vest disconnected");
        EN.put("lost_body", "{name}'s vest stopped sending data.");
        EN.put("back_title", "Vest reconnected");
        EN.put("sev_1", "Minor");
        EN.put("sev_2", "Serious");
        EN.put("sev_3", "Severe");
        EN.put("act_resting", "Resting");
        EN.put("act_light", "Moving lightly");
        EN.put("act_walking", "Walking");
        EN.put("act_active", "Very active");
        EN.put("act_lying", "Lying down");
        EN.put("act_-", "Starting up");
        EN.put("sms", "VestGuard ALERT: possible fall for {name} at {time} ({sev}). Location: {map} Please check on them now.");
        EN.put("wearer", "the wearer");
        EN.put("paused_title", "VestGuard protection paused");
        EN.put("paused_body", "Android stopped the vest connection. Tap to open VestGuard and resume.");
        EN.put("loc_old", "(location from {n} min ago)");
        EN.put("loc_home", "(home address)");
        EN.put("sos_title", "SOS from {name}");
        EN.put("tts_fall", "{name}, are you okay? If you are fine, press I am OK on the screen. Otherwise your family is being called.");
        EN.put("tts_sos", "Help is on the way. Your family is being called.");

        HI.put("ch_alert", "गिरने के अलार्म");
        HI.put("ch_status", "वेस्ट कनेक्शन");
        HI.put("ch_info", "वेस्ट सूचनाएँ");
        HI.put("status_title", "{name} की सुरक्षा चालू है");
        HI.put("status_on", "वेस्ट जुड़ी है · {act}");
        HI.put("status_off", "वेस्ट का इंतज़ार। क्या फ़ोन हॉटस्पॉट चालू है?");
        HI.put("fall_title", "गिरने का पता चला: {name}");
        HI.put("fall_title_demo", "डेमो अलार्म: {name}");
        HI.put("fall_body", "{sev} गिरावट। खोलकर जवाब दें।");
        HI.put("fall_esc", "1 मिनट से किसी ने जवाब नहीं दिया। {sev} गिरावट।");
        HI.put("fall_ack", "आप जा रहे हैं। काम पूरा होने पर ऐप में बंद करें।");
        HI.put("responding", "मैं जा रहा/रही हूँ");
        HI.put("open", "खोलें");
        HI.put("lost_title", "वेस्ट डिस्कनेक्ट हो गई");
        HI.put("lost_body", "{name} की वेस्ट से डेटा आना बंद हो गया।");
        HI.put("back_title", "वेस्ट फिर से जुड़ गई");
        HI.put("sev_1", "हल्की");
        HI.put("sev_2", "गंभीर");
        HI.put("sev_3", "बहुत गंभीर");
        HI.put("act_resting", "आराम कर रहे हैं");
        HI.put("act_light", "हल्की हलचल");
        HI.put("act_walking", "चल रहे हैं");
        HI.put("act_active", "बहुत सक्रिय");
        HI.put("act_lying", "लेटे हुए हैं");
        HI.put("act_-", "शुरू हो रहा है");
        HI.put("sms", "VestGuard चेतावनी: {name} शायद गिर गए हैं, समय {time} ({sev})। जगह: {map} कृपया तुरंत देखें।");
        HI.put("wearer", "पहनने वाले");
        HI.put("paused_title", "VestGuard सुरक्षा रुकी हुई है");
        HI.put("paused_body", "Android ने वेस्ट कनेक्शन रोक दिया। फिर शुरू करने के लिए टैप करें।");
        HI.put("loc_old", "({n} मिनट पुरानी जगह)");
        HI.put("loc_home", "(घर का पता)");
        HI.put("sos_title", "{name} ने मदद माँगी (SOS)");
        HI.put("tts_fall", "{name}, क्या आप ठीक हैं? ठीक हैं तो स्क्रीन पर मैं ठीक हूँ दबाइए। नहीं तो परिवार को फ़ोन किया जा रहा है।");
        HI.put("tts_sos", "मदद आ रही है। परिवार को फ़ोन किया जा रहा है।");
    }

    static String t(String lang, String key, String... kv) {
        Map<String, String> d = "hi".equals(lang) ? HI : EN;
        String s = d.get(key);
        if (s == null) s = EN.get(key);
        if (s == null) s = key;
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        return s;
    }
}

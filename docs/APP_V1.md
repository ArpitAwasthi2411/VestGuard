# VestGuard app v1: phone + vest, no laptop

```
 vest (ESP32-S3 + 2× BMI323)  ──Wi-Fi──▶  phone hotspot "VestGuard"
   • detects falls itself                   • VestGuard app (Android)
   • buzzes immediately                     • background service: loud alarm,
   • sends status, events, falls              lock-screen alert, optional SMS
```

## 1. Install the app
1. On the phone, open **https://github.com/ArpitAwasthi2411/VestGuard/releases/tag/app-latest** and download **VestGuard.apk**.
2. Open the file. If Android asks, allow **Install unknown apps** for your browser or Files app.
3. Newer builds install over the old one and keep your data, because every build is signed with the same key.

## 2. Phone hotspot
Set the hotspot to name `VestGuard`, password `vestguard123`, band **2.4 GHz**. The ESP32 can't see 5 GHz networks.
- Samsung: Hotspot → Configure → Band → 2.4 GHz
- Pixel: Hotspot → Extend compatibility **ON**

You can change the name and password later from the app (Profile → Vest Wi-Fi) without re-flashing.

## 3. Flash the vest (once)
Arduino IDE → open `firmware/vestguard_vest/vestguard_vest.ino` (keep `detector.h` and `vest_types.h` in the same folder).
- Board **ESP32S3 Dev Module**, USB CDC On Boot **Enabled**
- Upload. When it boots, the vest chirps once.

## 4. First run
1. Open the app, enter your name, and tap Continue. The "Connect the vest" guide opens.
2. Turn on the hotspot, then switch on the vest. Within about 20 s the top of the screen turns **green**.
3. Tap **Allow** for alarms. In Profile → **Alarm protection**, allow everything (full-screen alarm and run in background matter most).
4. Profile → **Calibrate**: the wearer stands straight and still for 3 s, and the vest beeps when done.
5. Profile → Demo tools → **Simulate a fall** to hear the real alarm.

## What happens on a fall
1. The vest detects it (impact > 2.5 g, posture change ≥ 45°, both sensors agree) and **buzzes**.
2. It sends the fall to the phone and repeats it every second until the phone confirms receipt.
3. The phone rings **at alarm volume**, even on silent, locked, or with the app closed, and shows a full-screen alert.
4. "I'm responding" (in the notification or the app) stops the ringing. If nobody responds within 60 s, it rings again and the alert is marked escalated.
5. Optional: SMS to every emergency contact, with the phone's GPS location (Profile → Text contacts on a fall).

## Laptop / data collection still works
`laptop/start.sh` and the dashboard are unchanged. A laptop on the same network finds the vest through its broadcast heartbeat and gets the raw 50 Hz stream, even while the phone is connected. In the app, Profile → Connect through → Laptop opens the laptop's caregiver page instead.

## Protocol (firmware 3.0)
| vest → phone (UDP 4210) | meaning |
|---|---|
| `S,posture,tilt,act,steps,cal,uptime,calibrating` | live status, 2 per second |
| `H,ok1,ok2,err1,err2,rssi,linked` | heartbeat, 1 per second |
| `F,id,sev,peak_g,tilt_change,tilt_after,lying` | fall (repeated until `K<id>`) |
| `E,id,STUMBLE,g` · `E,id,ACT,from,to` · `E,id,CAL,OK/FAIL` · `E,id,FROZEN,part` | events (repeated until `K<id>`) |
| `D,...` | raw samples, laptops only |

| phone → vest (UDP 4211) | |
|---|---|
| `PH` | phone keep-alive |
| `K<id>` | got the event |
| `C` | calibrate |
| `A0..A3` | buzzer |
| `W<ssid>\t<pass>` / `X` | set Wi-Fi / back to default |

## Build it yourself
GitHub Actions builds the APK and compiles the firmware on every push (see `.github/workflows/build.yml`).
To build locally: `cd app && npm ci && npx cap sync android && cd android && ./gradlew assembleDebug`.
The detector test runs on recorded data: `firmware/test/detector_test.cpp`.

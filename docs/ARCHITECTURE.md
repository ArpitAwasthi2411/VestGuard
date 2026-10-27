# Architecture

VestGuard has three layers. Each one keeps working if the layers after it fail.

| Layer | Runs on | What it does |
|---|---|---|
| **1. Vest (edge)** | ESP32-S3 inside the vest | Reads two BMI323 IMUs at 50 Hz, decides on board whether a fall happened, sounds the 120 dB buzzer, sends a fall event to the home phone |
| **2. Home and family** | Android app on the home phone and every family phone | "Are you OK?" with a 30 s cancel window; if not cancelled, alerts every family phone with the location and calls the first contact |
| **3. Health system (scale-up)** | PHC desk (`health-desk/`) | Shows households and live alerts; staff can call the family, send an ASHA worker or request 108 |

## Alert chain

```
fall on the vest ──► buzzer (no network needed)
        │
        ▼  Wi-Fi (home router or phone hotspot)
home phone: "Are you OK?"  ── cancelled ──► nothing sent
        │ not cancelled (30 s)
        ▼  mobile internet (push relay)
every family phone rings + location ──► "I'm going"
        │ nobody responds
        ▼
home phone calls the first contact · PHC desk (scale-up)
```

## Connectivity by situation

| Network available | What still works | Status |
|---|---|---|
| None | Detection and buzzer | Working |
| Local Wi-Fi / hotspot only | Home phone alert | Working |
| Mobile internet | Family phones with location; PHC desk | Working (desk = demo) |
| Weak signal, SMS only | SMS and voice call from the vest (4G/2G module) | Next version |
| No terrestrial network | Satellite SOS, village gateway | Future |

## Design principles

- **Decide at the edge** — no cloud round-trip, works offline.
- **Fail gracefully** — every layer stands on its own.
- **Give the elder a voice** — the cancel window keeps false alarms away from the family.
- **Privacy** — no camera, no microphone recording; only fall events leave the vest.

The detection engine itself is patent-pending and not described here.

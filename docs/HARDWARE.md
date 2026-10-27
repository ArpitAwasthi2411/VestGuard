# Hardware

## V2 prototype

| Component | Part | Cost (₹) |
|---|---|---|
| Vest | Himachali-style sleeveless vest with inner pockets | 2,000 |
| Microcontroller | ESP32-S3 | 550 |
| Motion sensors | 2 × Bosch BMI323 6-axis IMU (I²C 0x68 / 0x69) | 904 |
| Alarm | 120 dB piezo buzzer | 150 |
| Power | Rechargeable Li-ion battery + charger | 400 |
| Wiring | Flexible wires, connectors | 200 |
| **Total** | | **4,204** |

Estimated cost at about 3,000 units: **~₹3,000** (one custom PCB, bulk parts, local stitching).

## Sensor placement

- **Thoracic** — upper back on the spine; follows the upper body and head.
- **Lumbar** — lower back on the spine; close to the body's centre of mass.

Research on sensor placement ranks positions near the centre of mass well above the wrist for fall detection (Özdemir 2016, *Sensors* 16(8):1161; González-Cañete & Casilari 2021, *Sensors* 21(6):2254).

## Sensor wiring (data-collection firmware)

```
BMI323 #1 THORACIC: VIN->3.3V GND->GND SDA->GPIO8 SCL->GPIO9 SDO->GND  (0x68)
BMI323 #2 LUMBAR  : VIN->3.3V GND->GND SDA->GPIO8 SCL->GPIO9 SDO->VIN  (0x69)
Buzzer (optional) : (+)->GPIO10 (-)->GND
```

## V3 (in progress)

SIMCom A7672S LTE Cat-1 module (SMS, voice, GNSS), removable spine strip and sealed pod with magnetic charging, slim Li-Po pack, custom PCB.

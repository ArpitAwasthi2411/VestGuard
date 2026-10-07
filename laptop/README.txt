VestGuard — laptop server, data-collection dashboard, caregiver phone app
=========================================================================

WHAT'S IN HERE
  start.sh                  run this on the laptop (or: python3 vestguard_server.py)
  vestguard_server.py       server: ESP32 link, dashboard, caregiver app, raw CSV backup
  care.py                   caregiver engine: fall detection, activity, alerts, shared state
  care/                     caregiver phone app (works offline: map library + English/Hindi fonts bundled)
  care/i18n.js              all English and Hindi text in one place (edit wording here)
  vestguard_dashboard.html  data-collection dashboard
  esp32/vestguard_daq/      ESP32-S3 firmware 2.1 (no extra libraries)

1) WIFI — laptop, ESP32 and caregiver phones on the same 2.4 GHz network
   Phone hotspot (easiest), or laptop hotspot:
       nmcli device status
       nmcli device wifi hotspot ifname <wifi-device> ssid VestGuard password vestguard123 band bg
   Put that name/password at the top of vestguard_daq.ino (WIFI_SSID / WIFI_PASS) and flash it.

2) START THE SERVER
       ./start.sh            (or: python3 vestguard_server.py)
   The terminal prints the addresses to open:
       Dashboard      http://localhost:8000
       Caregiver app  http://<laptop-ip>:8000/care     <- open this on each caregiver's phone

3) CAREGIVER APP
   - First open: enter your name and role. Keep the page open; fall alerts take over the
     screen with alarm sound + vibration on every phone.
   - Profile -> Calibrate: wearer stands straight for 3 s (enables posture / lying detection).
   - Profile -> App settings: English / हिंदी, Auto / Light / Dark theme, Normal / Large text,
     and a switch to hide the demo vitals. Each phone keeps its own settings, so one caregiver
     can use Hindi while another uses English.
   - Profile -> Demo tools: simulate a fall or stumble without the vest (for presentations).
   - Android Chrome: menu -> "Add to Home screen" for an app icon.

REAL vs SIMULATED
   Real (from the vest): falls, stumbles, movement, posture, steps, fall-risk estimate,
                         vest/sensor status, WiFi signal.
   Logged by caregivers: meals, notes, alert responses, calls.
   Simulated (labelled in the app until sensors are added): heart rate, SpO2,
                         skin temperature, sleep, room. Location = home address (no GPS yet).

KNOWN LIMITS OF THIS VERSION
   - Runs on the local network. Background push notifications with the app closed need
     HTTPS + a cloud server (next step after the hackathon).
   - Map tiles need internet; without it the alert shows the address + "Open in Maps".
   - Shared state is saved in data/care_state.json (delete it to start fresh).

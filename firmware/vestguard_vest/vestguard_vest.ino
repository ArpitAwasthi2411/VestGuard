/*
  ============================================================
   VestGuard V2 vest firmware                      v3.0
   ESP32-S3 + 2x BMI323   ->   phone app (or laptop)
  ============================================================

  WHAT'S NEW IN 3.0
    * Fall detection runs ON THE VEST (detector.h). The vest buzzes by itself the moment
      it detects a fall, even if no phone is connected.
    * Works with the VestGuard Android app over the PHONE'S HOTSPOT. No laptop needed.
    * The laptop dashboard / server still work exactly as before (raw data stream).
    * Calibration and Wi-Fi settings are saved in the vest (survive power-off).
    * The app can change the vest's Wi-Fi name/password. If the new network can't be
      found, the vest falls back to the default below, so you can never lock yourself out.

  FIRST TIME
    Set your phone hotspot to:  name  VestGuard   password  vestguard123   band  2.4 GHz
    (ESP32 cannot see 5 GHz. On many phones: Hotspot > Advanced > AP band / "Extend compatibility")

  WIRING (unchanged)
    BMI323 #1 THORACIC: VIN->3.3V GND->GND SDA->GPIO8 SCL->GPIO9 SDO->GND  (0x68)
    BMI323 #2 LUMBAR  : VIN->3.3V GND->GND SDA->GPIO8 SCL->GPIO9 SDO->VIN  (0x69)
    Buzzer: (+)->GPIO10 (-)->GND

  NO EXTRA LIBRARIES (WiFi, UDP, Preferences are part of the ESP32 core).

  PROTOCOL  (UDP, text lines)  vest -> port 4210,  commands -> vest port 4211
    D,seq,t_ms,s1_ax..s1_gz,s2_ax..s2_gz         raw data (only to laptops / research)
    H,ok1,ok2,err1,err2,rssi,linked              heartbeat 1/s
    S,posture,tilt,act,steps,cal,uptime_s,calibrating   live status 2/s
    F,id,sev,peak_g,tilt_change,tilt_after,lying fall      (repeated until acked with K<id>)
    E,id,STUMBLE,peak_g | E,id,ACT,from,to | E,id,CAL,OK|FAIL | E,id,FROZEN,upper|lower
    I,...                                        info
  COMMANDS
    PH  phone keep-alive     P / HELLO  laptop keep-alive (gets raw data)
    K<id> ack event          C calibrate (stand straight 3 s)     A0..A3 buzzer
    W<ssid><TAB><pass> set Wi-Fi     ? info     R<hz> rate     I re-init sensors     S I2C scan
*/

#include <Wire.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Preferences.h>
#include <stdarg.h>
#include "detector.h"
#include "vest_types.h"

// ================= DEFAULT WI-FI (fallback) =================
const char* DEFAULT_SSID = "VestGuard";
const char* DEFAULT_PASS = "vestguard123";
// ============================================================

#define FW_VERSION  "3.0"
#define DATA_PORT   4210
#define CMD_PORT    4211
#define I2C_SDA     8
#define I2C_SCL     9
#define BUZZER_PIN  10          // -1 if no buzzer

// ---------------- BMI323 ----------------
#define REG_CHIP_ID   0x00
#define REG_ACC_X     0x03
#define REG_ACC_CONF  0x20
#define REG_GYR_CONF  0x21
#define REG_CMD       0x7E
#define CMD_SOFT_RST  0xDEAF
#define BMI323_ID     0x43
const uint16_t ACC_CONF_VAL = 0x4038;   // normal | +-16 g    | 100 Hz
const uint16_t GYR_CONF_VAL = 0x4048;   // normal | +-2000 dps | 100 Hz
const float ACC_LSB_PER_G   = 2048.0f;
const float GYR_LSB_PER_DPS = 16.384f;

struct Imu { uint8_t addr; const char* name; const char* part; bool ok; uint8_t consecFail; uint32_t errors;
             float a[3]; float g[3]; uint16_t last[6]; uint16_t stuck; uint32_t freezes; };
Imu imu[2] = {
  {0x68, "THORACIC", "upper", false, 0, 0, {0, 0, 0}, {0, 0, 0}, {0}, 0, 0},
  {0x69, "LUMBAR",   "lower", false, 0, 0, {0, 0, 0}, {0, 0, 0}, {0}, 0, 0},
};

uint32_t sampleRateHz = 50, periodUs = 1000000UL / 50, nextSampleUs = 0, seq = 0;
uint32_t lastHeartbeat = 0, lastStatus = 0, lastTick = 0, lastReinitTry = 0, lastFlush = 0;

vg::Detector det;
Preferences prefs;
WiFiUDP udp;

// ---------------- Wi-Fi credentials (saved) ----------------
String savedSsid, savedPass;
bool usingSaved = true;
uint32_t wifiAttemptAt = 0, wifiUpSince = 0;
bool wifiWasUp = false;
uint32_t pendingWifiSwitchAt = 0;

const char* curSsid() { return usingSaved && savedSsid.length() ? savedSsid.c_str() : DEFAULT_SSID; }
const char* curPass() { return usingSaved && savedSsid.length() ? savedPass.c_str() : DEFAULT_PASS; }
bool hasCustomWifi() { return savedSsid.length() && savedSsid != DEFAULT_SSID; }

// ---------------- peers (phones + laptops) ----------------
Peer peers[3];
const uint32_t PEER_TIMEOUT_MS = 8000;

bool netUp() { return WiFi.status() == WL_CONNECTED; }
bool peerAlive(const Peer& p) { return p.used && millis() - p.lastHeard < PEER_TIMEOUT_MS; }
int alivePeers() { int n = 0; for (auto& p : peers) if (peerAlive(p)) n++; return n; }
bool anyPhone() { for (auto& p : peers) if (peerAlive(p) && p.phone) return true; return false; }

// kind: 1 = phone keep-alive (PH), 0 = laptop keep-alive (P / HELLO / ?), -1 = other command (keep as is)
void touchPeer(IPAddress ip, int kind) {
  Peer* slot = nullptr;
  for (auto& p : peers) if (p.used && p.ip == ip) { slot = &p; break; }
  bool isNew = false;
  if (!slot) {
    for (auto& p : peers) if (!peerAlive(p)) { slot = &p; break; }
    if (!slot) { slot = &peers[0]; for (auto& p : peers) if (p.lastHeard < slot->lastHeard) slot = &p; }
    slot->ip = ip; slot->used = true; slot->phone = false; isNew = true;
  } else if (!peerAlive(*slot)) isNew = true;
  if (kind >= 0) slot->phone = kind == 1;      // a phone in Research mode sends "P" and then gets raw data too
  slot->lastHeard = millis();
  if (isNew) {
    char b[64]; snprintf(b, sizeof b, "I,LINK,%s,%s", ip.toString().c_str(), slot->phone ? "phone" : "laptop");
    if (Serial) Serial.println(b);
  }
}

// ---------------- output ----------------
void sendTo(IPAddress ip, const char* data, size_t len) {
  udp.beginPacket(ip, DATA_PORT);
  udp.write((const uint8_t*)data, len);
  udp.endPacket();
}

// control lines (status, events, info): to every live peer; with no peer yet, to the
// gateway (= the phone when on its hotspot) and to broadcast (= laptop discovery)
void sendCtl(const char* data, size_t len) {
  if (!netUp() || !len) return;
  if (alivePeers()) {
    for (auto& p : peers) if (peerAlive(p)) sendTo(p.ip, data, len);
  } else {
    IPAddress gw = WiFi.gatewayIP();
    if (gw != IPAddress(0, 0, 0, 0)) sendTo(gw, data, len);
    sendTo(IPAddress(255, 255, 255, 255), data, len);
  }
}

void emitCtl(const char* fmt, ...) {
  char b[220];
  va_list ap; va_start(ap, fmt); int n = vsnprintf(b, sizeof b - 2, fmt, ap); va_end(ap);
  if (n < 0) return;
  if (n > (int)sizeof b - 2) n = sizeof b - 2;
  if (Serial) Serial.println(b);
  b[n++] = '\n';
  sendCtl(b, n);
}
#define emitInfo emitCtl

// raw data: batched, only to laptops (non-phone peers); broadcast while nobody is linked
char txBuf[1400];
size_t txLen = 0;
void flushData(bool force) {
  if (!txLen) return;
  if (!force && millis() - lastFlush < 100 && txLen < 1100) return;
  if (netUp()) {
    if (!alivePeers()) sendTo(IPAddress(255, 255, 255, 255), txBuf, txLen);
    else for (auto& p : peers) if (peerAlive(p) && !p.phone) sendTo(p.ip, txBuf, txLen);
  }
  txLen = 0;
  lastFlush = millis();
}
bool serialRaw = true;          // raw lines on USB serial too (for the USB dashboard)
void emitData(const char* line, size_t len) {
  if (serialRaw && Serial) Serial.write((const uint8_t*)line, len);
  bool wanted = !alivePeers();
  for (auto& p : peers) if (peerAlive(p) && !p.phone) wanted = true;
  if (!wanted) return;
  if (txLen + len > sizeof txBuf) flushData(true);
  memcpy(txBuf + txLen, line, len);
  txLen += len;
}

// ---------------- reliable events ----------------
struct Pending { bool used; uint32_t id; uint32_t first; uint32_t last; uint32_t ttl; char line[110]; };
Pending pend[16];
uint16_t bootTag = 0;
uint16_t evCounter = 0;

uint32_t newId() { return ((uint32_t)bootTag << 16) | (++evCounter); }

void queueEvent(uint32_t id, uint32_t ttlMs, const char* line) {
  Pending* slot = nullptr;
  for (auto& p : pend) if (!p.used) { slot = &p; break; }
  if (!slot) {           // full: drop the oldest non-fall event (or the oldest overall)
    for (auto& p : pend) if (p.line[0] != 'F' && (!slot || p.first < slot->first)) slot = &p;
    if (!slot) { slot = &pend[0]; for (auto& p : pend) if (p.first < slot->first) slot = &p; }
  }
  slot->used = true; slot->id = id; slot->first = millis(); slot->last = millis(); slot->ttl = ttlMs;
  strncpy(slot->line, line, sizeof slot->line - 1); slot->line[sizeof slot->line - 1] = 0;
  emitCtl("%s", slot->line);
}
void ackEvent(uint32_t id) { for (auto& p : pend) if (p.used && p.id == id) p.used = false; }
void resendEvents() {
  uint32_t now = millis();
  for (auto& p : pend) {
    if (!p.used) continue;
    if (now - p.first > p.ttl) { p.used = false; continue; }
    if (now - p.last >= 1000) { p.last = now; emitCtl("%s", p.line); }
  }
}

void eventf(bool isFall, const char* fmt, ...) {
  char body[90];
  va_list ap; va_start(ap, fmt); vsnprintf(body, sizeof body, fmt, ap); va_end(ap);
  uint32_t id = newId();
  char line[110];
  snprintf(line, sizeof line, "%c,%08lx,%s", isFall ? 'F' : 'E', (unsigned long)id, body);
  queueEvent(id, isFall ? 600000UL : 120000UL, line);
}

// ---------------- I2C ----------------
bool readWords(uint8_t addr, uint8_t reg, uint16_t* out, uint8_t n) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  int want = 2 + 2 * n;
  if (Wire.requestFrom((int)addr, want) != want) return false;
  Wire.read(); Wire.read();                    // 2 dummy bytes
  for (uint8_t i = 0; i < n; i++) { uint8_t l = Wire.read(), m = Wire.read(); out[i] = ((uint16_t)m << 8) | l; }
  return true;
}
bool writeWord(uint8_t addr, uint8_t reg, uint16_t val) {
  Wire.beginTransmission(addr); Wire.write(reg); Wire.write(val & 0xFF); Wire.write(val >> 8);
  return Wire.endTransmission() == 0;
}

bool initImu(Imu& s, uint8_t idx) {
  writeWord(s.addr, REG_CMD, CMD_SOFT_RST);
  delay(5);
  uint16_t id = 0;
  bool idOk = readWords(s.addr, REG_CHIP_ID, &id, 1) && ((id & 0xFF) == BMI323_ID);
  bool cfgOk = false;
  if (idOk) {
    writeWord(s.addr, REG_ACC_CONF, ACC_CONF_VAL);
    writeWord(s.addr, REG_GYR_CONF, GYR_CONF_VAL);
    delay(20);
    uint16_t conf[2] = {0, 0};
    cfgOk = readWords(s.addr, REG_ACC_CONF, conf, 2) && conf[0] == ACC_CONF_VAL && conf[1] == GYR_CONF_VAL;
  }
  s.ok = idOk && cfgOk; s.consecFail = 0; s.stuck = 0;
  emitInfo("I,SENSOR,%u,0x%02X,%s,%s,0x%02X", idx + 1, s.addr, s.name,
           s.ok ? "OK" : (idOk ? "CONFIG_FAIL" : "NOT_FOUND"), id & 0xFF);
  return s.ok;
}

void i2cScan() {
  char b[160]; int n = snprintf(b, sizeof b, "I,SCAN");
  for (uint8_t a = 1; a < 127 && n < 150; a++) {
    Wire.beginTransmission(a);
    if (Wire.endTransmission() == 0) n += snprintf(b + n, sizeof b - n, ",0x%02X", a);
  }
  emitInfo("%s", b);
}

void printInfo() {
  emitInfo("I,FW,VestGuard-Vest,%s", FW_VERSION);
  emitInfo("I,WIFI,STA,%s,%s", curSsid(), WiFi.localIP().toString().c_str());
  emitInfo("I,WIFICFG,%s,%s", hasCustomWifi() ? savedSsid.c_str() : DEFAULT_SSID, hasCustomWifi() ? "custom" : "default");
  emitInfo("I,RANGE,ACC_16G,GYR_2000DPS");
  emitInfo("I,RATE,%lu", (unsigned long)sampleRateHz);
  emitInfo("I,CAL,%d", det.calibrated ? 1 : 0);
  for (uint8_t i = 0; i < 2; i++)
    emitInfo("I,SENSOR,%u,0x%02X,%s,%s,0x43", i + 1, imu[i].addr, imu[i].name, imu[i].ok ? "OK" : "NOT_FOUND");
  emitInfo("I,READY");
}

// ---------------- buzzer ----------------
const Note PAT_LOW[]  = {{2600,120},{0,120},{2600,120},{0,120},{2600,120},{0,600}};
const Note PAT_MED[]  = {{2900,180},{0,90},{2900,180},{0,90},{2900,180},{0,90},{2900,180},{0,600}};
const Note PAT_HIGH[] = {{3200,250},{2200,250},{3200,250},{2200,250},{3200,250},{2200,250},{0,300}};
const Note PAT_OK[]   = {{2400,90},{0,60},{3000,140}};
const Note* pat = nullptr; uint8_t patLen = 0, patIdx = 0, patRepeats = 0; uint32_t noteEnd = 0;
void buzzerStop() { pat = nullptr; if (BUZZER_PIN >= 0) noTone(BUZZER_PIN); }
void buzzerPlay(const Note* p, uint8_t len, uint8_t reps) {
  if (BUZZER_PIN < 0) return;
  buzzerStop(); pat = p; patLen = len; patRepeats = reps; patIdx = 0; noteEnd = 0;
}
void buzzerStart(uint8_t level) {
  if (level == 1) buzzerPlay(PAT_LOW, sizeof(PAT_LOW) / sizeof(Note), 2);
  if (level == 2) buzzerPlay(PAT_MED, sizeof(PAT_MED) / sizeof(Note), 3);
  if (level == 3) buzzerPlay(PAT_HIGH, sizeof(PAT_HIGH) / sizeof(Note), 6);
}
void buzzerUpdate() {
  if (!pat || millis() < noteEnd) return;
  if (patIdx >= patLen) { if (--patRepeats == 0) { buzzerStop(); return; } patIdx = 0; }
  const Note& n = pat[patIdx++];
  if (n.freq) tone(BUZZER_PIN, n.freq); else noTone(BUZZER_PIN);
  noteEnd = millis() + n.ms;
}

// ---------------- persistence ----------------
void loadSettings() {
  prefs.begin("vg", true);
  savedSsid = prefs.getString("ssid", "");
  savedPass = prefs.getString("pass", "");
  if (prefs.getBool("cal", false)) {
    float u1[3], u2[3];
    if (prefs.getBytes("u1", u1, 12) == 12 && prefs.getBytes("u2", u2, 12) == 12) det.setCalibration(u1, u2);
  }
  prefs.end();
}
void saveCalibration() {
  prefs.begin("vg", false);
  prefs.putBytes("u1", det.u1, 12);
  prefs.putBytes("u2", det.u2, 12);
  prefs.putBool("cal", true);
  prefs.end();
}
void saveWifi(const String& s, const String& p) {
  prefs.begin("vg", false);
  prefs.putString("ssid", s);
  prefs.putString("pass", p);
  prefs.end();
  savedSsid = s; savedPass = p;
}

// ---------------- Wi-Fi ----------------
void wifiBegin() {
  WiFi.disconnect(false, false);
  WiFi.begin(curSsid(), curPass());
  wifiAttemptAt = millis();
  if (Serial) Serial.printf("I,WIFI,CONNECTING,%s\n", curSsid());
}
void startWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  usingSaved = hasCustomWifi();
  wifiBegin();
  uint32_t t0 = millis();
  while (!netUp() && millis() - t0 < 12000) delay(200);
  udp.begin(CMD_PORT);
}
// non-blocking: if the current network isn't reachable for 15 s, try the other one
void wifiService() {
  uint32_t now = millis();
  if (pendingWifiSwitchAt && now >= pendingWifiSwitchAt) {
    pendingWifiSwitchAt = 0; usingSaved = true; wifiBegin(); return;
  }
  if (netUp()) {
    if (!wifiWasUp) { wifiWasUp = true; wifiUpSince = now; emitInfo("I,WIFI,STA,%s,%s", curSsid(), WiFi.localIP().toString().c_str()); }
    return;
  }
  wifiWasUp = false;
  if (now - wifiAttemptAt > 15000) {
    if (hasCustomWifi()) usingSaved = !usingSaved;   // alternate custom <-> default
    wifiBegin();
  }
}

// ---------------- sampling ----------------
void readImu(Imu& s) {
  if (!s.ok) return;
  uint16_t raw[6];
  if (!readWords(s.addr, REG_ACC_X, raw, 6)) { s.errors++; if (++s.consecFail > 25) s.ok = false; return; }
  s.consecFail = 0;
  // Freeze guard: a live BMI323 never repeats the same 6 raw words for 1 s, and returns
  // 0x8000 after a power glitch reset. Either way -> mark offline so re-init brings it back.
  bool same = true, invalid = true;
  for (uint8_t k = 0; k < 6; k++) { if (raw[k] != s.last[k]) same = false; if (raw[k] != 0x8000) invalid = false; s.last[k] = raw[k]; }
  if (same || invalid) {
    if (++s.stuck >= sampleRateHz) {
      s.ok = false; s.stuck = 0; s.freezes++;
      emitInfo("I,FROZEN,%s,0x%02X,%s", s.name, s.addr, invalid ? "sensor reset (power glitch?)" : "values stuck");
      eventf(false, "FROZEN,%s", s.part);
      return;
    }
  } else s.stuck = 0;
  for (uint8_t k = 0; k < 3; k++) {
    if (raw[k] != 0x8000)     s.a[k] = (int16_t)raw[k] / ACC_LSB_PER_G;
    if (raw[k + 3] != 0x8000) s.g[k] = (int16_t)raw[k + 3] / GYR_LSB_PER_DPS;
  }
}
void appendImu(char*& p, const Imu& s) {
  if (s.ok) p += sprintf(p, ",%.4f,%.4f,%.4f,%.2f,%.2f,%.2f", s.a[0], s.a[1], s.a[2], s.g[0], s.g[1], s.g[2]);
  else      p += sprintf(p, ",nan,nan,nan,nan,nan,nan");
}
void sampleAndSend() {
  readImu(imu[0]); readImu(imu[1]);
  uint32_t t = millis();
  static char line[200]; char* p = line;
  p += sprintf(p, "D,%lu,%lu", (unsigned long)seq++, (unsigned long)t);
  appendImu(p, imu[0]); appendImu(p, imu[1]);
  *p++ = '\n';
  emitData(line, p - line);
  if (imu[0].ok) det.feed(t, imu[0].a, imu[0].g, imu[1].a, imu[1].ok);
}

void handleDetectorEvents() {
  vg::Event e;
  while (det.pop(e)) {
    switch (e.kind) {
      case vg::Event::FALL:
        buzzerStart(e.severity);
        eventf(true, "%u,%.2f,%.0f,%.0f,%d", e.severity, e.peak, e.tiltChange, isnan(e.tiltAfter) ? -1.0f : e.tiltAfter, e.lying ? 1 : 0);
        break;
      case vg::Event::STUMBLE:  eventf(false, "STUMBLE,%.2f", e.peak); break;
      case vg::Event::ACTIVITY: eventf(false, "ACT,%s,%s", vg::actName(e.from), vg::actName(e.to)); break;
      case vg::Event::CAL_OK:
        saveCalibration();
        buzzerPlay(PAT_OK, sizeof(PAT_OK) / sizeof(Note), 1);
        eventf(false, "CAL,OK"); break;
      case vg::Event::CAL_FAIL: eventf(false, "CAL,FAIL"); break;
      default: break;
    }
  }
}

// ---------------- commands ----------------
void handleCommand(String cmd) {
  cmd.trim();
  if (!cmd.length()) return;
  char c = cmd.charAt(0);
  if (cmd == "PH" || cmd == "P" || cmd == "HELLO") return;            // keep-alives (peer already noted)
  if (c == '?') printInfo();
  else if (c == 'K') { ackEvent(strtoul(cmd.c_str() + 1, nullptr, 16)); }
  else if (c == 'C') {
    if (!imu[0].ok) emitInfo("I,ERR,upper sensor offline - cannot calibrate");
    else { det.startCalibration(); emitInfo("I,CAL,START"); }
  }
  else if (c == 'R') {
    long hz = cmd.substring(1).toInt();
    if (hz >= 10 && hz <= 100) { sampleRateHz = hz; periodUs = 1000000UL / hz; nextSampleUs = micros(); emitInfo("I,RATE,%lu", (unsigned long)hz); }
    else emitInfo("I,ERR,rate must be 10..100");
  }
  else if (c == 'I') { i2cScan(); initImu(imu[0], 0); initImu(imu[1], 1); }
  else if (c == 'A') { int l = cmd.substring(1).toInt(); if (l <= 0) buzzerStop(); else buzzerStart(constrain(l, 1, 3)); emitInfo("I,ALERT,%d", l); }
  else if (c == 'S') i2cScan();
  else if (c == 'W') {
    int tab = cmd.indexOf('\t');
    String s = tab < 0 ? cmd.substring(1) : cmd.substring(1, tab);
    String p = tab < 0 ? "" : cmd.substring(tab + 1);
    if (s.length() < 1 || s.length() > 32 || (p.length() && (p.length() < 8 || p.length() > 63))) {
      emitInfo("I,WIFI,REJECTED,name 1-32 chars and password 8-63 chars");
    } else {
      saveWifi(s, p);
      emitInfo("I,WIFI,SAVED,%s", s.c_str());
      pendingWifiSwitchAt = millis() + 1500;     // let the reply go out first
    }
  }
  else if (c == 'X') { prefs.begin("vg", false); prefs.remove("ssid"); prefs.remove("pass"); prefs.end();
                       savedSsid = ""; savedPass = ""; emitInfo("I,WIFI,RESET,%s", DEFAULT_SSID); pendingWifiSwitchAt = millis() + 1500; }
}

void readSerialCommands() {
  static String buf;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\n' || ch == '\r') { handleCommand(buf); buf = ""; }
    else if (buf.length() < 128) buf += ch;
  }
}

void readUdpCommands() {
  int sz = udp.parsePacket();
  while (sz > 0) {
    char b[256];
    int n = udp.read(b, sizeof b - 1);
    if (n < 0) n = 0;
    b[n] = 0;
    int kind = -1;
    {
      char tmp[256]; memcpy(tmp, b, n + 1);
      char* sv = nullptr;
      for (char* l = strtok_r(tmp, "\r\n", &sv); l; l = strtok_r(nullptr, "\r\n", &sv)) {
        if (!strcmp(l, "PH")) kind = 1;
        else if (!strcmp(l, "P") || !strcmp(l, "HELLO") || !strcmp(l, "?")) kind = 0;
      }
    }
    touchPeer(udp.remoteIP(), kind);
    char* save = nullptr;
    for (char* tok = strtok_r(b, "\r\n", &save); tok; tok = strtok_r(nullptr, "\r\n", &save)) handleCommand(String(tok));
    sz = udp.parsePacket();
  }
}

// ---------------- main ----------------
void setup() {
  Serial.begin(921600);
  delay(600);
  if (BUZZER_PIN >= 0) { pinMode(BUZZER_PIN, OUTPUT); digitalWrite(BUZZER_PIN, LOW); }
  bootTag = (uint16_t)(esp_random() & 0xFFFF);

  loadSettings();
  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(400000);

  startWifi();
  i2cScan();
  initImu(imu[0], 0);
  initImu(imu[1], 1);
  printInfo();

  if (BUZZER_PIN >= 0) tone(BUZZER_PIN, 2400, 120);       // boot chirp
  nextSampleUs = micros();
}

void loop() {
  readUdpCommands();
  readSerialCommands();
  buzzerUpdate();
  wifiService();

  uint32_t now = micros();
  if ((int32_t)(now - nextSampleUs) >= 0) {
    nextSampleUs += periodUs;
    if ((int32_t)(now - nextSampleUs) > (int32_t)(periodUs * 4)) nextSampleUs = now + periodUs;
    sampleAndSend();
  }
  flushData(false);

  uint32_t ms = millis();
  if (ms - lastTick >= 500) {
    lastTick = ms;
    if (imu[0].ok) det.tick(ms); else det.reset();
  }
  handleDetectorEvents();
  resendEvents();

  if (ms - lastStatus >= 500) {
    lastStatus = ms;
    emitCtl("S,%s,%d,%s,%lu,%d,%lu,%d", vg::postureName(det.posture), det.tilt, vg::actName(det.act),
            (unsigned long)det.steps, det.calibrated ? 1 : 0, (unsigned long)(ms / 1000), det.calibrating() ? 1 : 0);
  }
  if (ms - lastHeartbeat >= 1000) {
    lastHeartbeat = ms;
    char hb[80];
    int n = snprintf(hb, sizeof hb, "H,%d,%d,%lu,%lu,%d,%d\n", imu[0].ok, imu[1].ok, (unsigned long)imu[0].errors,
                     (unsigned long)imu[1].errors, netUp() ? WiFi.RSSI() : 0, alivePeers() ? 1 : 0);
    sendCtl(hb, n);
    // also broadcast the heartbeat so a laptop on the same network can always find the vest
    if (netUp() && alivePeers()) sendTo(IPAddress(255, 255, 255, 255), hb, n);
  }
  if ((!imu[0].ok || !imu[1].ok) && ms - lastReinitTry > 3000) {
    lastReinitTry = ms;
    if (!imu[0].ok) initImu(imu[0], 0);
    if (!imu[1].ok) initImu(imu[1], 1);
  }
}

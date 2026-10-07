/*
  ============================================================
   VestGuard V2 — Dual BMI323 -> laptop over WiFi (UDP)
   Board : ESP32-S3                         Firmware 2.0-udp
  ============================================================

  HOW IT WORKS
    The laptop runs vestguard_server.py (hosts the dashboard).
    This ESP32 joins a WiFi network and streams sensor data to the laptop over UDP.
    It finds the laptop by itself: it broadcasts until the laptop answers,
    then sends straight to the laptop. No laptop IP needs to be typed in.

  ONLY THING TO EDIT: the WiFi name/password below.
    Use the SAME network the laptop is on — a phone hotspot, or the laptop's own hotspot.
    (2.4 GHz only — the ESP32 cannot see 5 GHz networks.)

  NO EXTRA LIBRARIES NEEDED (WiFi + UDP are built into the ESP32 core).

  WIRING (unchanged)
    BMI323 #1 THORACIC: VIN->3.3V GND->GND SDA->GPIO8 SCL->GPIO9 SDO->GND  (0x68)
    BMI323 #2 LUMBAR  : VIN->3.3V GND->GND SDA->GPIO8 SCL->GPIO9 SDO->VIN  (0x69)
    Optional buzzer: (+)->GPIO10 (-)->GND

  PROTOCOL (newline separated, several lines per UDP packet)
    D,seq,t_ms,s1_ax,s1_ay,s1_az,s1_gx,s1_gy,s1_gz,s2_ax,...,s2_gz   (g, deg/s)
    I,...  info      H,ok1,ok2,err1,err2,rssi,linked   heartbeat 1/s
  COMMANDS from laptop:  ?  R<hz>  I  A0..A3  S
*/

#include <Wire.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <stdarg.h>

// ================= EDIT THESE =================
const char* WIFI_SSID = "VestGuard";        // your hotspot name
const char* WIFI_PASS = "vestguard123";     // your hotspot password
// Optional: fix the laptop IP (e.g. "10.42.0.1"). Leave "" for automatic discovery.
const char* LAPTOP_IP = "";
// ==============================================

#define DATA_PORT   4210        // ESP -> laptop
#define CMD_PORT    4211        // laptop -> ESP

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
const uint16_t ACC_CONF_VAL = 0x4038;   // normal | +-16 g    | 100 Hz ODR
const uint16_t GYR_CONF_VAL = 0x4048;   // normal | +-2000 dps | 100 Hz ODR
const float ACC_LSB_PER_G   = 2048.0f;
const float GYR_LSB_PER_DPS = 16.384f;

#define FW_VERSION "2.1-udp"

struct Imu { uint8_t addr; const char* name; bool ok; uint8_t consecFail; uint32_t errors; float a[3]; float g[3];
             uint16_t last[6]; uint16_t stuck; uint32_t freezes; };
Imu imu[2] = {
  {0x68, "THORACIC", false, 0, 0, {0, 0, 0}, {0, 0, 0}},
  {0x69, "LUMBAR",   false, 0, 0, {0, 0, 0}, {0, 0, 0}},
};

uint32_t sampleRateHz = 50, periodUs = 1000000UL / 50, nextSampleUs = 0, seq = 0;
uint32_t lastHeartbeat = 0, lastReinitTry = 0, lastFlush = 0, lastHeardLaptop = 0, lastWifiLog = 0;

WiFiUDP udp;
IPAddress laptop(255, 255, 255, 255);    // broadcast until the laptop answers
bool linked = false;                     // true once we know the laptop's IP
bool fixedLaptop = false;
char txBuf[1400];
size_t txLen = 0;

// ---------------- output ----------------
bool netUp() { return WiFi.status() == WL_CONNECTED; }

void sendPacket(const char* data, size_t len) {
  if (!netUp() || len == 0) return;
  udp.beginPacket(laptop, DATA_PORT);
  udp.write((const uint8_t*)data, len);
  udp.endPacket();
}

void flushData(bool force) {
  if (txLen == 0) return;
  if (!force && millis() - lastFlush < 100 && txLen < 1100) return;
  sendPacket(txBuf, txLen);
  txLen = 0;
  lastFlush = millis();
}

void emitInfo(const char* fmt, ...) {
  char b[200];
  va_list ap; va_start(ap, fmt); int n = vsnprintf(b, sizeof b - 2, fmt, ap); va_end(ap);
  if (n < 0) return;
  if (n > (int)sizeof b - 2) n = sizeof b - 2;
  if (Serial) Serial.println(b);
  b[n++] = '\n';
  sendPacket(b, n);
}

void emitData(const char* line, size_t len) {
  if (Serial) Serial.write((const uint8_t*)line, len);
  if (txLen + len > sizeof txBuf) flushData(true);
  memcpy(txBuf + txLen, line, len);
  txLen += len;
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
  s.ok = idOk && cfgOk; s.consecFail = 0;
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
  emitInfo("I,FW,VestGuard-DAQ,%s", FW_VERSION);
  emitInfo("I,WIFI,STA,%s,%s", WIFI_SSID, WiFi.localIP().toString().c_str());
  emitInfo("I,RANGE,ACC_16G,GYR_2000DPS");
  emitInfo("I,RATE,%lu", (unsigned long)sampleRateHz);
  for (uint8_t i = 0; i < 2; i++)
    emitInfo("I,SENSOR,%u,0x%02X,%s,%s,0x43", i + 1, imu[i].addr, imu[i].name, imu[i].ok ? "OK" : "NOT_FOUND");
  emitInfo("I,READY");
}

// ---------------- sampling ----------------
void readImu(Imu& s) {
  if (!s.ok) return;
  uint16_t raw[6];
  if (!readWords(s.addr, REG_ACC_X, raw, 6)) { s.errors++; if (++s.consecFail > 25) s.ok = false; return; }
  s.consecFail = 0;
  // Freeze guard: a live BMI323 never returns the same 6 raw words for 1 s, and returns
  // 0x8000 ("no data") after a power glitch resets it. Either way -> mark offline (sends nan)
  // so the auto re-init below brings it back, instead of repeating the last value forever.
  bool same = true, invalid = true;
  for (uint8_t k = 0; k < 6; k++) { if (raw[k] != s.last[k]) same = false; if (raw[k] != 0x8000) invalid = false; s.last[k] = raw[k]; }
  if (same || invalid) {
    if (++s.stuck >= sampleRateHz) {
      s.ok = false; s.stuck = 0; s.freezes++;
      emitInfo("I,FROZEN,%s,0x%02X,%s", s.name, s.addr, invalid ? "sensor reset (power glitch?)" : "values stuck");
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
  static char line[200]; char* p = line;
  p += sprintf(p, "D,%lu,%lu", (unsigned long)seq++, (unsigned long)millis());
  appendImu(p, imu[0]); appendImu(p, imu[1]);
  *p++ = '\n';
  emitData(line, p - line);
}

// ---------------- buzzer ----------------
struct Note { uint16_t freq; uint16_t ms; };
const Note PAT_LOW[]  = {{2600,120},{0,120},{2600,120},{0,120},{2600,120},{0,600}};
const Note PAT_MED[]  = {{2900,180},{0,90},{2900,180},{0,90},{2900,180},{0,90},{2900,180},{0,600}};
const Note PAT_HIGH[] = {{3200,250},{2200,250},{3200,250},{2200,250},{3200,250},{2200,250},{0,300}};
const Note* pat = nullptr; uint8_t patLen = 0, patIdx = 0, patRepeats = 0; uint32_t noteEnd = 0;
void buzzerStop() { pat = nullptr; if (BUZZER_PIN >= 0) noTone(BUZZER_PIN); }
void buzzerStart(uint8_t level) {
  if (BUZZER_PIN < 0) return;
  buzzerStop();
  if (level == 1) { pat = PAT_LOW;  patLen = sizeof(PAT_LOW)  / sizeof(Note); patRepeats = 2; }
  if (level == 2) { pat = PAT_MED;  patLen = sizeof(PAT_MED)  / sizeof(Note); patRepeats = 3; }
  if (level == 3) { pat = PAT_HIGH; patLen = sizeof(PAT_HIGH) / sizeof(Note); patRepeats = 6; }
  patIdx = 0; noteEnd = 0;
}
void buzzerUpdate() {
  if (!pat || millis() < noteEnd) return;
  if (patIdx >= patLen) { if (--patRepeats == 0) { buzzerStop(); return; } patIdx = 0; }
  const Note& n = pat[patIdx++];
  if (n.freq) tone(BUZZER_PIN, n.freq); else noTone(BUZZER_PIN);
  noteEnd = millis() + n.ms;
}

// ---------------- commands ----------------
void handleCommand(String cmd) {
  cmd.trim(); if (!cmd.length()) return;
  char c = cmd.charAt(0);
  if (c == '?') printInfo();
  else if (c == 'R') {
    long hz = cmd.substring(1).toInt();
    if (hz >= 10 && hz <= 200) { sampleRateHz = hz; periodUs = 1000000UL / hz; nextSampleUs = micros(); emitInfo("I,RATE,%lu", (unsigned long)hz); }
    else emitInfo("I,ERR,rate must be 10..200");
  }
  else if (c == 'I') { i2cScan(); initImu(imu[0], 0); initImu(imu[1], 1); }
  else if (c == 'A') { int l = cmd.substring(1).toInt(); if (l <= 0) buzzerStop(); else buzzerStart(constrain(l, 1, 3)); emitInfo("I,ALERT,%d", l); }
  else if (c == 'S') i2cScan();
  // anything else (e.g. "P" ping / "HELLO") is just a keep-alive
}

void readSerialCommands() {
  static String buf;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\n' || ch == '\r') { handleCommand(buf); buf = ""; }
    else if (buf.length() < 32) buf += ch;
  }
}

void readUdpCommands() {
  int sz = udp.parsePacket();
  while (sz > 0) {
    char b[256];
    int n = udp.read(b, sizeof b - 1);
    if (n < 0) n = 0;
    b[n] = 0;
    IPAddress from = udp.remoteIP();
    if (!fixedLaptop && (!linked || from != laptop)) {
      laptop = from; linked = true;
      emitInfo("I,LINK,%s", from.toString().c_str());
    }
    lastHeardLaptop = millis();
    // a packet may hold several newline-separated commands
    char* save = nullptr;
    for (char* tok = strtok_r(b, "\r\n", &save); tok; tok = strtok_r(nullptr, "\r\n", &save)) handleCommand(String(tok));
    sz = udp.parsePacket();
  }
  // laptop silent for 8 s (server restarted / IP changed) -> go back to broadcasting
  if (linked && !fixedLaptop && millis() - lastHeardLaptop > 8000) {
    linked = false;
    laptop = IPAddress(255, 255, 255, 255);
  }
}

// ---------------- WiFi ----------------
void startWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);                 // steadier latency
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  if (Serial) Serial.printf("I,WIFI,CONNECTING,%s\n", WIFI_SSID);
  uint32_t t0 = millis();
  while (!netUp() && millis() - t0 < 15000) { delay(250); if (Serial) Serial.print("."); }
  if (Serial) Serial.println();
  if (strlen(LAPTOP_IP)) { fixedLaptop = laptop.fromString(LAPTOP_IP); linked = fixedLaptop; }
  udp.begin(CMD_PORT);
  if (netUp()) emitInfo("I,WIFI,STA,%s,%s", WIFI_SSID, WiFi.localIP().toString().c_str());
  else if (Serial) Serial.println("I,WIFI,FAIL,check SSID/password and that the hotspot is 2.4 GHz - will keep retrying");
}

// ---------------- main ----------------
void setup() {
  Serial.begin(921600);
  delay(800);
  if (BUZZER_PIN >= 0) { pinMode(BUZZER_PIN, OUTPUT); digitalWrite(BUZZER_PIN, LOW); }

  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(400000);

  startWifi();
  i2cScan();
  initImu(imu[0], 0);
  initImu(imu[1], 1);
  emitInfo("I,RATE,%lu", (unsigned long)sampleRateHz);
  emitInfo("I,READY");

  if (BUZZER_PIN >= 0) tone(BUZZER_PIN, 2400, 120);       // boot chirp
  nextSampleUs = micros();
}

void loop() {
  readUdpCommands();
  readSerialCommands();
  buzzerUpdate();

  uint32_t now = micros();
  if ((int32_t)(now - nextSampleUs) >= 0) {
    nextSampleUs += periodUs;
    if ((int32_t)(now - nextSampleUs) > (int32_t)(periodUs * 4)) nextSampleUs = now + periodUs;
    sampleAndSend();
  }
  flushData(false);

  uint32_t ms = millis();
  if (ms - lastHeartbeat >= 1000) {
    lastHeartbeat = ms;
    emitInfo("H,%d,%d,%lu,%lu,%d,%d", imu[0].ok, imu[1].ok, (unsigned long)imu[0].errors,
             (unsigned long)imu[1].errors, netUp() ? WiFi.RSSI() : 0, linked ? 1 : 0);
    if (!netUp() && ms - lastWifiLog > 5000) { lastWifiLog = ms; if (Serial) Serial.println("I,WIFI,DOWN,reconnecting"); }
  }
  if ((!imu[0].ok || !imu[1].ok) && ms - lastReinitTry > 3000) {
    lastReinitTry = ms;
    if (!imu[0].ok) initImu(imu[0], 0);
    if (!imu[1].ok) initImu(imu[1], 1);
  }
}

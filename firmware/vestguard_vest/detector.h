/*
  VestGuard on-vest detector  (pure C++, no Arduino dependencies)
  ---------------------------------------------------------------
  Same logic as the laptop engine (laptop/care.py), tuned in 3.2 on the 6 + 8 Oct recordings
  (121 trials incl. jogging, jumping, lying down, bending, three fall types):

    3.5: every impact it checks is reported WITH its numbers (FALL / RISE / STUMBLE / NOFALL),
         so the app can show which check passed or failed and by how much.
    FALL     impact > 2.5 g, then within 1.5 s the upper-body posture changes >= 45 deg
             (mean accel 1.5..0.4 s before impact vs last 0.5 s), and the lower sensor agrees.
             Severity 3: peak >= 4 g, or lying and still.  2: peak >= 3 g or lying.  1: otherwise.
             3.4: NOT a fall if the body ends MORE upright than it started (getting up quickly
             from bed / floor): a real fall always ends further from upright.
    RECOVER  3.4: after a fall, the wearer is upright again for 5 s (within 2 min) -> one event.
    STUMBLE  impact > 2.5 g but posture didn't change (stayed upright).
    ACTIVITY resting / light / walking / active / lying, debounced 8 s.
    POSTURE  upright < 30 deg, bending < 60 deg, lying >= 60 deg from the calibrated "standing" vector.
    STEPS    peak counting on the upper sensor while moving.

  Times are uint32 milliseconds (safe across the 49-day millis() wrap).
  The test harness in firmware/test compiles this file on a PC and runs it on recorded data.
*/
#pragma once
#include <math.h>
#include <stdint.h>
#include <string.h>

namespace vg {

enum Act : int8_t { ACT_NONE = -1, ACT_RESTING = 0, ACT_LIGHT, ACT_WALKING, ACT_ACTIVE, ACT_LYING };
enum Posture : int8_t { POST_NONE = -1, POST_UPRIGHT = 0, POST_BENDING, POST_LYING };

inline const char* actName(int8_t a) {
  switch (a) {
    case ACT_RESTING: return "resting";
    case ACT_LIGHT:   return "light";
    case ACT_WALKING: return "walking";
    case ACT_ACTIVE:  return "active";
    case ACT_LYING:   return "lying";
    default:          return "-";
  }
}
inline const char* postureName(int8_t p) {
  switch (p) {
    case POST_UPRIGHT: return "upright";
    case POST_BENDING: return "bending";
    case POST_LYING:   return "lying";
    default:           return "-";
  }
}

struct Event {
  enum Kind : uint8_t { NONE, FALL, STUMBLE, ACTIVITY, CAL_OK, CAL_FAIL, RISE, RECOVER, NOFALL } kind = NONE;
  uint32_t t = 0;          // sample time of the event
  uint8_t severity = 0;    // FALL
  float peak = 0;          // FALL / STUMBLE, g
  float tiltChange = 0;    // FALL, deg
  float tiltAfter = NAN;   // FALL, deg from upright (NAN if not calibrated)
  float tiltBefore = NAN;  // FALL / RISE, deg from upright before the impact
  bool lying = false;      // FALL
  // 3.5 evidence: every impact the vest checks carries the numbers behind its decision
  float p1 = 0, p2 = 0;    // peak g, upper / lower sensor
  float a2 = NAN;          // lower-back turn, deg
  float still = NAN;       // movement after the impact (sd of |a|, g); ~0 = lying still
  const char* why = "";    // NOFALL: "one_sensor" (only one sensor felt it) | "upright_active" (stayed upright, jogging/jumping)
  int8_t from = ACT_NONE;  // ACTIVITY
  int8_t to = ACT_NONE;    // ACTIVITY
};

inline float norm3(const float* v) { return sqrtf(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }
inline float angleDeg(const float* a, const float* b) {
  float na = norm3(a), nb = norm3(b);
  if (na == 0 || nb == 0) return NAN;
  float d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb);
  if (d > 1) d = 1;
  if (d < -1) d = -1;
  return acosf(d) * 57.29578f;
}
inline bool after(uint32_t a, uint32_t b) { return (int32_t)(a - b) >= 0; }   // a >= b, wrap-safe

class Detector {
 public:
  // ---- tunables (same values as care.py) ----
  static constexpr float IMPACT_G = 2.5f;
  static constexpr uint32_t BUF_MS = 4000;
  static constexpr uint32_t ACT_DEBOUNCE_MS = 8000;

  // ---- calibration ----
  bool calibrated = false;
  float u1[3] = {0, 0, 1}, u2[3] = {0, 0, 1};
  void setCalibration(const float* a, const float* b) { memcpy(u1, a, 12); memcpy(u2, b, 12); calibrated = true; }
  void clearCalibration() { calibrated = false; }
  // 3.4: upper sensor offline -> keep detecting with the lower sensor alone (its own "standing" vector)
  bool lowerPrimary = false;
  void setLowerPrimary(bool on) { if (on != lowerPrimary) { lowerPrimary = on; reset(); } }
  const float* up() const { return lowerPrimary ? u2 : u1; }
  void startCalibration() { calRun = true; calStarted = false; calN = 0; calMotion = 0; memset(calS1, 0, 12); memset(calS2, 0, 12); }
  bool calibrating() const { return calRun; }

  // ---- live outputs ----
  int8_t act = ACT_NONE, actCand = ACT_NONE, posture = POST_NONE;
  int16_t tilt = -1;              // deg, -1 = unknown (not calibrated)
  uint32_t steps = 0;
  uint32_t actSince = 0;

  // Feed one sample. a1/g1 = upper (thoracic) sensor, a2 = lower (lumbar). s2ok=false -> use a1.
  void feed(uint32_t t, const float* a1, const float* g1, const float* a2, bool s2ok) {
    Row& r = buf[head];
    r.t = t;
    memcpy(r.a1, a1, 12);
    memcpy(r.a2, s2ok ? a2 : a1, 12);
    r.m1 = norm3(r.a1);
    r.m2 = norm3(r.a2);
    r.w1 = norm3(g1);
    head = (head + 1) % CAP;
    if (count < CAP) count++;
    while (count > 1 && (t - at(0).t) > BUF_MS) count--;     // drop rows older than 4 s
    lastT = t;
    hasData = true;

    if (calRun) calSample(t, r);
    stepSample(t, r.m1);
    detect(t, r);
  }

  // Call every ~500 ms while data is flowing: activity, posture, tilt.
  void tick(uint32_t now) {
    if (count < 20 || !hasData) return;
    uint32_t tEnd = at(count - 1).t;
    float mean = 0, wmean = 0; int n = 0;
    for (int i = 0; i < count; i++) { const Row& r = at(i); if (tEnd - r.t <= 2000) { mean += r.m1; wmean += r.w1; n++; } }
    if (n < 2) return;
    mean /= n; wmean /= n;
    float var = 0;
    for (int i = 0; i < count; i++) { const Row& r = at(i); if (tEnd - r.t <= 2000) var += (r.m1 - mean) * (r.m1 - mean); }
    float sd = sqrtf(var / n);

    float aNow[3];
    if (calibrated && meanVec(1, tEnd - 1000, tEnd, aNow)) {
      float tl = angleDeg(up(), aNow);
      tilt = isnan(tl) ? -1 : (int16_t)lroundf(tl);
      posture = tilt < 0 ? POST_NONE : tilt < 30 ? POST_UPRIGHT : tilt < 60 ? POST_BENDING : POST_LYING;
    } else { tilt = -1; posture = POST_NONE; }

    int8_t cls;
    if (posture == POST_LYING && sd < 0.1f) cls = ACT_LYING;
    else if (sd < 0.025f && wmean < 8) cls = ACT_RESTING;
    else if (sd < 0.08f) cls = ACT_LIGHT;
    else if (sd < 0.35f) cls = ACT_WALKING;
    else cls = ACT_ACTIVE;

    // 3.4: back on their feet after a fall?
    if (watchRecover) {
      if (!after(recoverUntil, now)) watchRecover = false;
      else if (posture == POST_UPRIGHT) {
        if (!upSince) upSince = now;
        else if (now - upSince >= 5000) {
          watchRecover = false;
          Event e; e.kind = Event::RECOVER; e.t = now; e.tiltAfter = tilt; push(e);
        }
      } else upSince = 0;
    }

    if (cls != actCand) { actCand = cls; actCandSince = now; }
    if (cls != act && (now - actCandSince) >= (act == ACT_NONE ? 2000u : ACT_DEBOUNCE_MS)) {
      int8_t prev = act;
      act = cls; actSince = actCandSince;
      if (prev != ACT_NONE) { Event e; e.kind = Event::ACTIVITY; e.t = now; e.from = prev; e.to = cls; push(e); }
    }
  }

  // Data stopped (vest lost both sensors) -> forget the window so stale data can't trigger anything.
  void reset() { count = 0; head = 0; st = IDLE; hasData = false; }

  // 3.4: thresholds exposed so tests can report them
  static constexpr float RISE_END_MAX = 40;   // ends at most this far from upright
  static constexpr float RISE_GAIN = 20;      // and at least this much more upright than before

  bool pop(Event& e) {
    if (qn == 0) return false;
    e = q[qh]; qh = (qh + 1) % QCAP; qn--;
    return true;
  }

 private:
  static constexpr int CAP = 512;          // >= 4 s at 100 Hz
  struct Row { uint32_t t; float a1[3]; float a2[3]; float m1, m2, w1; };
  Row buf[CAP];
  int head = 0, count = 0;
  uint32_t lastT = 0;
  bool hasData = false;
  const Row& at(int i) const { return buf[(head - count + i + CAP) % CAP]; }   // 0 = oldest

  // event queue
  static constexpr int QCAP = 8;
  Event q[QCAP]; int qh = 0, qn = 0;
  void push(const Event& e) {
    if (qn == QCAP) { qh = (qh + 1) % QCAP; qn--; }
    q[(qh + qn) % QCAP] = e; qn++;
  }

  // calibration
  bool calRun = false, calStarted = false; uint32_t calT0 = 0; int calN = 0; float calMotion = 0;
  float calS1[3], calS2[3];
  void calSample(uint32_t t, const Row& r) {
    if (!calStarted) { calStarted = true; calT0 = t; }
    for (int k = 0; k < 3; k++) { calS1[k] += r.a1[k]; calS2[k] += r.a2[k]; }
    calN++;
    if (r.w1 > calMotion) calMotion = r.w1;
    if (t - calT0 >= 3000) {
      calRun = false;
      Event e; e.t = t;
      if (calN < 20 || calMotion > 30) e.kind = Event::CAL_FAIL;
      else {
        for (int k = 0; k < 3; k++) { u1[k] = calS1[k] / calN; u2[k] = calS2[k] / calN; }
        calibrated = true;
        e.kind = Event::CAL_OK;
      }
      push(e);
    }
  }

  // steps
  bool stepArmed = true; uint32_t lastStep = 0;
  static bool moving(int8_t a) { return a == ACT_WALKING || a == ACT_ACTIVE || a == ACT_LIGHT; }
  void stepSample(uint32_t t, float m1) {
    if (!moving(act) && !moving(actCand)) return;
    if (stepArmed && m1 > 1.18f && (t - lastStep) > 280) { steps++; lastStep = t; stepArmed = false; }
    else if (m1 < 1.05f) stepArmed = true;
  }

  // window helpers
  bool meanVec(int which, uint32_t t0, uint32_t t1, float* out) const {
    float s[3] = {0, 0, 0}; int n = 0;
    for (int i = 0; i < count; i++) {
      const Row& r = at(i);
      if (after(r.t, t0) && after(t1, r.t)) {
        const float* a = which == 1 ? r.a1 : r.a2;
        s[0] += a[0]; s[1] += a[1]; s[2] += a[2]; n++;
      }
    }
    if (!n) return false;
    for (int k = 0; k < 3; k++) out[k] = s[k] / n;
    return true;
  }
  float sdMag(uint32_t t0, uint32_t t1) const {
    float sum = 0; int n = 0;
    for (int i = 0; i < count; i++) { const Row& r = at(i); if (after(r.t, t0) && after(t1, r.t)) { sum += r.m1; n++; } }
    if (n < 2) return 0;
    float m = sum / n, v = 0;
    for (int i = 0; i < count; i++) { const Row& r = at(i); if (after(r.t, t0) && after(t1, r.t)) v += (r.m1 - m) * (r.m1 - m); }
    return sqrtf(v / n);
  }

  // fall detection state machine
  enum { IDLE, ANALYZING } st = IDLE;
  uint32_t cool = 0; bool coolSet = false;
  struct Cand { uint32_t t; float p1, p2, w; float pre1[3], pre2[3]; bool hasPre1, hasPre2; } c;
  bool watchRecover = false; uint32_t recoverUntil = 0, upSince = 0;

  void detect(uint32_t t, const Row& r) {
    if (st == IDLE) {
      if ((coolSet && !after(t, cool)) || fmaxf(r.m1, r.m2) < IMPACT_G) return;
      c.t = t; c.p1 = r.m1; c.p2 = r.m2; c.w = r.w1;
      c.hasPre1 = meanVec(1, t - 1500, t - 400, c.pre1);
      c.hasPre2 = meanVec(2, t - 1500, t - 400, c.pre2);
      st = ANALYZING;
      return;
    }
    c.p1 = fmaxf(c.p1, r.m1); c.p2 = fmaxf(c.p2, r.m2); c.w = fmaxf(c.w, r.w1);
    if (t - c.t < 1500) return;

    float post1[3], post2[3];
    bool hp1 = meanVec(1, t - 500, t, post1), hp2 = meanVec(2, t - 500, t, post2);
    float a1 = (c.hasPre1 && hp1) ? angleDeg(c.pre1, post1) : NAN;
    float a2 = (c.hasPre2 && hp2) ? angleDeg(c.pre2, post2) : NAN;
    float still = sdMag(t - 500, t);
    st = IDLE; cool = t + 2500; coolSet = true;
    if (isnan(a1)) return;

    float tiltBefore = (calibrated && c.hasPre1) ? angleDeg(up(), c.pre1) : NAN;
    float tiltAfter = (calibrated && hp1) ? angleDeg(up(), post1) : NAN;
    float peak = fmaxf(c.p1, c.p2);
    // every outcome below carries the same evidence, so the app can show WHY
    Event e; e.t = t; e.peak = peak; e.p1 = c.p1; e.p2 = c.p2; e.tiltChange = a1; e.a2 = a2; e.still = still;
    e.tiltBefore = tiltBefore; e.tiltAfter = tiltAfter;
    // CHECK 2b (3.4): ended up MORE upright than before (e.g. jumped up from bed) -> getting up, not a fall
    if (!isnan(tiltBefore) && !isnan(tiltAfter) && tiltAfter < RISE_END_MAX && tiltAfter < tiltBefore - RISE_GAIN) {
      e.kind = Event::RISE;
      push(e);
      return;
    }

    // CHECK 3: the lower back felt it too (a fall moves the whole trunk; a loose wire or a bump moves one sensor)
    bool dual = c.p2 >= IMPACT_G * 0.6f && (isnan(a2) || a2 >= 27);
    // 3.2: a very hard impact with a smaller posture change still counts (e.g. fell and stayed kneeling)
    // CHECK 2: the upper body turned (standing -> on the floor), or turned less but after a very hard hit
    bool postureChanged = a1 >= 45 || (a1 >= 30 && c.p1 >= 6);
    if (postureChanged && dual) {
      bool lying = !isnan(tiltAfter) ? tiltAfter >= 60 : a1 >= 70;
      uint8_t sev = (c.p1 >= 4 || (lying && still < 0.05f)) ? 3 : (c.p1 >= 3 || lying) ? 2 : 1;
      e.kind = Event::FALL; e.severity = sev; e.lying = lying;
      watchRecover = calibrated; recoverUntil = t + 120000; upSince = 0;
    } else if (postureChanged) {
      e.kind = Event::NOFALL; e.why = "one_sensor";          // turned, but the lower back didn't agree
    } else if (c.p2 < IMPACT_G * 0.6f) {
      e.kind = Event::NOFALL; e.why = "one_sensor";          // 3.2: only one sensor felt the hit (wiring glitch)
    } else if (act == ACT_ACTIVE || actCand == ACT_ACTIVE) {
      e.kind = Event::NOFALL; e.why = "upright_active";      // 3.2: stayed upright while jogging / jumping
    } else {
      e.kind = Event::STUMBLE;                               // hit, both sensors felt it, stayed upright
    }
    push(e);
  }

  uint32_t actCandSince = 0;
};

}  // namespace vg

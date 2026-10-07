"""
VestGuard caregiver engine
--------------------------
Runs inside vestguard_server.py. It watches the live vest stream and keeps the
shared state that every caregiver phone syncs to.

  REAL (from the vest): falls, stumbles, activity, posture, steps, fall-risk estimate,
                        vest/sensor status, WiFi signal
  LOGGED by caregivers: meals, notes, alert responses, calls
  SIMULATED (labelled): heart rate, SpO2, skin temperature, sleep

Every message a person reads is sent as a code + parameters ("key"/"p") so each
phone shows it in its own language (English or Hindi). "text" is an English fallback.
"""
import asyncio
import collections
import datetime as dt
import json
import math
import pathlib
import random
import time
import uuid

ESCALATE_AFTER_S = 60
ACT_DEBOUNCE_S = 8


def _norm(v):
    return math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])


def _angle(a, b):
    if not a or not b:
        return float("nan")
    na, nb = _norm(a), _norm(b)
    if na == 0 or nb == 0:
        return float("nan")
    d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)
    return math.degrees(math.acos(max(-1.0, min(1.0, d))))


def _uid():
    return uuid.uuid4().hex[:10]


DEFAULT_PROFILE = {
    "wearer": {"name": "Kamla Devi", "age": 76, "phone": "+91 98160 00000",
               "notes": "Uses a walking stick. Hearing aid in left ear."},
    "contacts": [
        {"name": "Arpit", "relation": "Grandson", "phone": "+91 98160 11111"},
        {"name": "Nurse Sarah", "relation": "Home nurse", "phone": "+91 98160 22222"},
    ],
    "home": {"label": "Home, Bandla, Bilaspur", "lat": 31.3260, "lon": 76.7590},
}

ACT_EN = {"resting": "Resting", "light": "Moving lightly", "walking": "Walking",
          "active": "Very active", "lying": "Lying down"}
SENSOR = {0: "upper", 1: "lower"}

# feed kinds that matter to a caregiver; everything else is shown under "Show all"
IMPORTANT_KEYS = {"fall_detected", "escalated", "responding", "resolved", "stumble", "meal", "note",
                  "called", "vest_lost", "sensor_offline", "sensor_frozen", "act_feed_lying", "act_feed_gotup"}


class Care:
    def __init__(self, hub, data_dir: pathlib.Path):
        self.hub = hub
        self.data_dir = data_dir
        self.data_dir.mkdir(exist_ok=True)
        self.path = data_dir / "care_state.json"
        self.clients = {}
        self.seen_ops = collections.deque(maxlen=1000)

        self.profile = json.loads(json.dumps(DEFAULT_PROFILE))
        self.alerts = []
        self.feed = []
        self.calib = None
        self.steps = {"date": dt.date.today().isoformat(), "count": 0}
        self._load()

        self.buf = collections.deque()
        self.t_off = None
        self.last_rx = 0.0
        self.ever_connected = False
        self.was_online = False
        self.hb_seen = False
        self.sensor_ok = [False, False]
        self.rssi = 0
        self.frozen_until = 0.0
        self.act = None
        self.act_cand = None
        self.act_cand_since = 0.0
        self.act_since = time.time()
        self.lying_since = None
        self.posture = None
        self.tilt = None
        self.det = {"st": "idle", "cand": None, "cool": 0.0}
        self.step_armed = True
        self.last_step_t = 0.0
        self.cal_run = None
        self.stumbles = collections.deque()
        self.room = None
        self.room_since = time.time()
        self.fall_boost_t = 0.0
        self.minute_acc = None
        self.act_minutes = collections.deque(maxlen=7 * 24 * 60)
        self.dirty = True
        self.persist_dirty = False
        self.last_save = 0.0

        self.vit = {"hr": 72.0, "spo2": 97.0, "temp": 34.2}
        self.vhist = self._backfill_vitals()
        self.sleep = self._sleep_history()

    # ------------------------------------------------------------------ persistence
    def _load(self):
        try:
            d = json.loads(self.path.read_text())
        except Exception:
            return
        for k in ("wearer", "contacts", "home"):
            if k in d.get("profile", {}):
                self.profile[k] = d["profile"][k]
        self.alerts = d.get("alerts", [])[-200:]
        self.feed = [e for e in d.get("feed", []) if e.get("kind") != "medication"][-400:]
        self.calib = d.get("calib")
        st = d.get("steps")
        if st and st.get("date") == dt.date.today().isoformat():
            self.steps = st

    def _save(self):
        d = {"profile": self.profile, "alerts": self.alerts[-200:], "feed": self.feed[-400:],
             "calib": self.calib, "steps": self.steps}
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(d))
        tmp.replace(self.path)
        self.persist_dirty = False
        self.last_save = time.time()

    def _changed(self):
        self.dirty = True
        self.persist_dirty = True

    # ------------------------------------------------------------------ feed + alerts
    def add_feed(self, kind, key, text, p=None, source="vest", by=None, ref=None):
        e = {"id": _uid(), "ts": time.time(), "kind": kind, "key": key, "p": p or {}, "text": text,
             "source": source, "imp": key in IMPORTANT_KEYS}
        if by:
            e["by"] = by
        if ref:
            e["ref"] = ref
        self.feed.append(e)
        if len(self.feed) > 400:
            del self.feed[:-400]
        self._changed()
        return e

    def create_alert(self, severity, peak, tilt_change, tilt_after=None, demo=False):
        a = {
            "id": _uid(), "ts": time.time(), "kind": "fall", "severity": severity,
            "peak_g": round(peak, 2), "tilt_change": round(tilt_change, 0),
            "tilt_after": None if tilt_after is None or math.isnan(tilt_after) else round(tilt_after, 0),
            "status": "active", "responders": [], "escalated": False,
            "resolution": None, "resolved_by": None, "resolved_ts": None, "note": "",
            "location": dict(self.profile["home"], source="home"), "demo": demo,
        }
        self.alerts.append(a)
        self.add_feed("alert", "fall_detected", f"Fall detected — impact {peak:.1f} g" + (" (demo)" if demo else ""),
                      {"sev": severity, "g": round(peak, 1), "demo": demo}, ref=a["id"])
        self.fall_boost_t = time.time()
        self.hub.send_esp(f"A{severity}")
        self._broadcast({"t": "alert", "alert": a})
        print(f"  !!! FALL ALERT (severity {severity}) peak {peak:.2f} g, posture change {tilt_change:.0f}°", flush=True)
        return a

    def _find_alert(self, aid):
        return next((a for a in self.alerts if a["id"] == aid), None)

    # ------------------------------------------------------------------ vest input
    def on_heartbeat(self, parts):
        try:
            ok = [parts[1] == "1", parts[2] == "1"]
            self.rssi = int(parts[5]) if len(parts) > 5 else 0
        except (ValueError, IndexError):
            return
        if self.hb_seen and ok != self.sensor_ok:
            for i in range(2):
                if ok[i] != self.sensor_ok[i]:
                    if ok[i]:
                        self.add_feed("device", "sensor_online", "Sensor back online", {"s": SENSOR[i]})
                    else:
                        self.add_feed("device", "sensor_offline", "Sensor offline", {"s": SENSOR[i]})
        self.hb_seen = True
        self.sensor_ok = ok

    def on_info(self, parts):
        if len(parts) > 2 and parts[1] == "FROZEN":
            self.frozen_until = time.time() + 10
            s = "upper" if "THORACIC" in parts[2].upper() else "lower"
            self.add_feed("device", "sensor_frozen", "Sensor stopped updating — restarting it", {"s": s})

    def on_sample(self, t_ms, v):
        now = time.time()
        t = t_ms / 1000.0
        if self.t_off is None or abs((t + self.t_off) - now) > 2.0:
            self.t_off = now - t
        t += self.t_off
        if not self.ever_connected:
            self.ever_connected = True
            self.add_feed("device", "vest_connected", "Vest connected")
        elif self.last_rx and now - self.last_rx > 10:
            self.add_feed("device", "vest_reconnected", "Vest reconnected")
        self.last_rx = now

        if any(math.isnan(x) for x in v[0:3]):
            return
        a1 = (v[0], v[1], v[2])
        a2 = (v[6], v[7], v[8]) if not math.isnan(v[6]) else a1
        m1, m2 = _norm(a1), _norm(a2)
        w1 = _norm(v[3:6])
        self.buf.append((t, a1, a2, m1, m2, w1))
        while self.buf and t - self.buf[0][0] > 4.0:
            self.buf.popleft()

        if self.cal_run is not None:
            self._cal_sample(a1, a2, w1)
        self._steps(t, m1)
        self._detect(t, m1, m2, w1)

    # ------------------------------------------------------------------ calibration
    def start_calibration(self, by):
        self.cal_run = {"s1": [0, 0, 0], "s2": [0, 0, 0], "n": 0, "motion": 0.0, "by": by, "t0": time.time()}

    def _cal_sample(self, a1, a2, w1):
        r = self.cal_run
        for k in range(3):
            r["s1"][k] += a1[k]
            r["s2"][k] += a2[k]
        r["n"] += 1
        r["motion"] = max(r["motion"], w1)
        if time.time() - r["t0"] >= 3.0:
            self.cal_run = None
            if r["n"] < 20 or r["motion"] > 30:
                self._broadcast({"t": "toast", "key": "toast_cal_fail", "text": "Calibration failed"})
                return
            self.calib = {"u1": [x / r["n"] for x in r["s1"]], "u2": [x / r["n"] for x in r["s2"]], "at": time.time()}
            self.add_feed("device", "calibrated", "Vest calibrated", source="caregiver", by=r["by"])
            self._broadcast({"t": "toast", "key": "toast_cal_ok", "text": "Vest calibrated"})

    def _tilt(self, a):
        return _angle(self.calib["u1"], a) if self.calib else float("nan")

    # ------------------------------------------------------------------ steps
    def _steps(self, t, m1):
        today = dt.date.today().isoformat()
        if self.steps["date"] != today:
            self.steps = {"date": today, "count": 0}
        moving = ("walking", "active", "light")
        if self.act not in moving and self.act_cand not in moving:
            return
        if self.step_armed and m1 > 1.18 and t - self.last_step_t > 0.28:
            self.steps["count"] += 1
            self.last_step_t = t
            self.step_armed = False
            self.persist_dirty = True
        elif m1 < 1.05:
            self.step_armed = True

    # ------------------------------------------------------------------ fall detection
    def _mean_vec(self, idx, t0, t1):
        s = [0.0, 0.0, 0.0]
        n = 0
        for row in self.buf:
            if t0 <= row[0] <= t1:
                a = row[idx]
                s[0] += a[0]; s[1] += a[1]; s[2] += a[2]
                n += 1
        return [x / n for x in s] if n else None

    def _sd_mag(self, t0, t1):
        xs = [r[3] for r in self.buf if t0 <= r[0] <= t1]
        if len(xs) < 2:
            return 0.0
        m = sum(xs) / len(xs)
        return math.sqrt(sum((x - m) ** 2 for x in xs) / len(xs))

    def _detect(self, t, m1, m2, w1):
        d = self.det
        imp = 2.5
        if d["st"] == "idle":
            if t < d["cool"] or max(m1, m2) < imp:
                return
            d["cand"] = {"t": t, "p1": m1, "p2": m2, "w": w1,
                         "pre1": self._mean_vec(1, t - 1.5, t - 0.4),
                         "pre2": self._mean_vec(2, t - 1.5, t - 0.4)}
            d["st"] = "analyzing"
            return
        c = d["cand"]
        c["p1"] = max(c["p1"], m1); c["p2"] = max(c["p2"], m2); c["w"] = max(c["w"], w1)
        if t - c["t"] < 1.5:
            return
        post1 = self._mean_vec(1, t - 0.5, t)
        a1 = _angle(c["pre1"], post1)
        a2 = _angle(c["pre2"], self._mean_vec(2, t - 0.5, t))
        still = self._sd_mag(t - 0.5, t)
        d["st"] = "idle"
        d["cool"] = t + 2.5
        if math.isnan(a1):
            return
        dual = c["p2"] >= imp * 0.6 and (math.isnan(a2) or a2 >= 27)
        peak = max(c["p1"], c["p2"])
        if a1 >= 45 and dual:
            tilt_after = self._tilt(post1) if post1 else float("nan")
            lying = tilt_after >= 60 if not math.isnan(tilt_after) else a1 >= 70
            sev = 3 if (c["p1"] >= 4 or (lying and still < 0.05)) else 2 if (c["p1"] >= 3 or lying) else 1
            self.create_alert(sev, peak, a1, tilt_after)
        else:
            self.stumbles.append(time.time())
            self.add_feed("stumble", "stumble", f"Stumble — impact {peak:.1f} g, stayed upright", {"g": round(peak, 1)})

    # ------------------------------------------------------------------ periodic logic
    def tick(self):
        now = time.time()
        online = bool(self.last_rx and now - self.last_rx < 3)
        if self.ever_connected and not online and self.was_online:
            self.add_feed("device", "vest_lost", "Vest connection lost")
        self.was_online = online

        if online and len(self.buf) > 20:
            self._activity(now)
        self._vitals(now)

        for a in self.alerts:
            if a["status"] == "active" and not a["escalated"] and now - a["ts"] > ESCALATE_AFTER_S:
                a["escalated"] = True
                self.add_feed("alert", "escalated", "No caregiver responded within 60 s", ref=a["id"])
                self._broadcast({"t": "alert", "alert": a})

        while self.stumbles and now - self.stumbles[0] > 86400:
            self.stumbles.popleft()

        if self.persist_dirty and now - self.last_save > 5:
            try:
                self._save()
            except OSError as e:
                print(f"  ! could not save care state: {e}")

    def _activity(self, now):
        t_end = self.buf[-1][0]
        rows = [r for r in self.buf if r[0] >= t_end - 2.0]
        mags = [r[3] for r in rows]
        mean = sum(mags) / len(mags)
        sd = math.sqrt(sum((x - mean) ** 2 for x in mags) / len(mags))
        wmean = sum(r[5] for r in rows) / len(rows)
        a_now = self._mean_vec(1, t_end - 1.0, t_end)
        self.tilt = None if not self.calib or not a_now else round(self._tilt(a_now))
        if self.tilt is None:
            self.posture = None
        else:
            self.posture = "upright" if self.tilt < 30 else "bending" if self.tilt < 60 else "lying"

        if self.posture == "lying" and sd < 0.1:
            cls = "lying"
        elif sd < 0.025 and wmean < 8:
            cls = "resting"
        elif sd < 0.08:
            cls = "light"
        elif sd < 0.35:
            cls = "walking"
        else:
            cls = "active"

        if cls != self.act_cand:
            self.act_cand, self.act_cand_since = cls, now
        if cls != self.act and now - self.act_cand_since >= (2 if self.act is None else ACT_DEBOUNCE_S):
            prev = self.act
            self.act, self.act_since = cls, self.act_cand_since
            if prev is not None:
                if prev == "lying" and cls != "lying":
                    self.add_feed("activity", "act_feed_gotup", "Got up", {"state": cls})
                else:
                    self.add_feed("activity", "act_feed_" + cls, ACT_EN[cls])
            self.dirty = True
        self.lying_since = (self.lying_since or now) if self.act == "lying" else None

        m = int(now // 60)
        if self.minute_acc is None or self.minute_acc[0] != m:
            if self.minute_acc is not None:
                self.act_minutes.append((self.minute_acc[0] * 60, self.minute_acc[1]))
            self.minute_acc = [m, self.act]

    def _vitals(self, now):
        """SIMULATED vitals that react to the real activity and to falls."""
        hour = dt.datetime.now().hour
        base = 70 + (-6 if hour < 6 or hour >= 22 else 0)
        bump = {"resting": 0, "lying": -4, "light": 8, "walking": 18, "active": 32}.get(self.act, 0)
        boost = 0.0
        if self.fall_boost_t:
            el = now - self.fall_boost_t
            if el < 600:
                boost = 26 * math.exp(-el / 180)
        v = self.vit
        v["hr"] += (base + bump + boost - v["hr"]) * 0.08 + random.gauss(0, 0.5)
        v["spo2"] += ((97.3 - (0.8 if self.act == "active" else 0)) - v["spo2"]) * 0.05 + random.gauss(0, 0.08)
        v["spo2"] = max(94.0, min(99.0, v["spo2"]))
        v["temp"] += (34.3 - v["temp"]) * 0.01 + random.gauss(0, 0.01)
        m = int(now // 60) * 60
        if not self.vhist or self.vhist[-1][0] != m:
            self.vhist.append([m, round(v["hr"]), round(v["spo2"], 1), round(v["temp"], 2)])
            cutoff = now - 7 * 86400
            while self.vhist and self.vhist[0][0] < cutoff:
                self.vhist.pop(0)

    def _backfill_vitals(self):
        rng = random.Random(42)
        out = []
        now = int(time.time() // 60) * 60
        hr, sp, tp = 72.0, 97.2, 34.2
        for m in range(now - 7 * 86400, now, 60):
            h = dt.datetime.fromtimestamp(m).hour
            night = h < 6 or h >= 22
            act = 0 if night else (14 if h in (7, 9, 17, 18) and rng.random() < 0.35 else 4 if rng.random() < 0.4 else 0)
            hr += ((64 if night else 72) + act - hr) * 0.15 + rng.gauss(0, 0.8)
            sp += ((96.6 if night else 97.3) - sp) * 0.1 + rng.gauss(0, 0.1)
            tp += ((34.0 if night else 34.4) - tp) * 0.05 + rng.gauss(0, 0.02)
            out.append([m, round(hr), round(max(94, min(99, sp)), 1), round(tp, 2)])
        return out

    def _sleep_history(self):
        out = []
        today = dt.date.today()
        for i in range(7, 0, -1):
            day = today - dt.timedelta(days=i - 1)
            r = random.Random(day.toordinal())
            hours = round(r.uniform(5.6, 7.8), 1)
            wakes = r.randint(0, 4)
            score = int(max(40, min(96, 50 + (hours - 5.5) * 14 - wakes * 4 + r.uniform(-4, 4))))
            out.append({"date": day.isoformat(), "hours": hours, "wakeups": wakes, "score": score})
        return out

    def _room(self, now):
        """SIMULATED room presence (no indoor positioning hardware yet)."""
        if not (self.last_rx and now - self.last_rx < 3):
            return
        h = dt.datetime.now().hour
        if self.act == "lying" or h >= 22 or h < 6:
            room = "bedroom"
        elif h in (8, 13, 19) and self.act in ("light", "walking"):
            room = "kitchen"
        elif self.act in ("walking", "active") and h in (10, 16, 17):
            room = "garden"
        else:
            room = "living"
        if room != self.room:
            if self.room is not None and now - self.room_since > 60:
                self.add_feed("room", "room_moved", f"Moved to {room}", {"room": room}, source="simulated")
            self.room, self.room_since = room, now

    # ------------------------------------------------------------------ derived status
    def fall_risk(self):
        now = time.time()
        real = [a for a in self.alerts if not a.get("demo") and a.get("resolution") != "false_alarm"]
        falls_24 = sum(1 for a in real if now - a["ts"] < 86400)
        falls_7d = sum(1 for a in real if now - a["ts"] < 7 * 86400)
        st = len(self.stumbles)
        reasons = []
        if falls_24:
            reasons.append({"key": "fr_falls_24", "p": {"n": falls_24}})
        elif falls_7d:
            reasons.append({"key": "fr_falls_7d", "p": {"n": falls_7d}})
        if st:
            reasons.append({"key": "fr_stumbles", "p": {"n": st}})
        if falls_24 or st >= 3:
            level = "high"
        elif falls_7d or st >= 1:
            level = "moderate"
        else:
            level = "low"
            reasons.append({"key": "fr_none", "p": {}})
        return {"level": level, "reasons": reasons}

    def overall(self):
        now = time.time()
        open_alerts = [a for a in self.alerts if a["status"] != "resolved"]
        if open_alerts:
            a = open_alerts[-1]
            who = a["responders"][0]["name"] if a["responders"] else None
            return {"level": "emergency", "title": "st_emergency",
                    "detail": {"key": "st_responding", "p": {"who": who}} if who else {"key": "st_no_response", "p": {}},
                    "reasons": [], "alert": a["id"]}
        reasons = []
        online = self.last_rx and now - self.last_rx < 3
        if not self.ever_connected:
            reasons.append({"key": "r_not_connected", "p": {}})
        elif not online:
            mins = int((now - self.last_rx) // 60)
            reasons.append({"key": "r_offline_min", "p": {"n": mins}} if mins else {"key": "r_offline", "p": {}})
        elif not all(self.sensor_ok) or now < self.frozen_until:
            reasons.append({"key": "r_sensor", "p": {}})
        hour = dt.datetime.now().hour
        if self.lying_since and 6 <= hour < 22 and now - self.lying_since > 20 * 60:
            reasons.append({"key": "r_lying_long", "p": {"n": int((now - self.lying_since) // 60)}})
        if self.fall_risk()["level"] == "high":
            reasons.append({"key": "r_risk_high", "p": {}})
        if reasons:
            return {"level": "attention", "title": "st_attention", "detail": reasons[0], "reasons": reasons}
        return {"level": "clear", "title": "st_clear",
                "detail": {"key": "st_clear_detail", "p": {"name": self.profile["wearer"]["name"].split()[0],
                                                           "act": self.act}}, "reasons": []}

    # ------------------------------------------------------------------ outbound
    def live(self):
        now = time.time()
        online = bool(self.last_rx and now - self.last_rx < 3)
        return {
            "t": "live", "ts": now, "status": self.overall(),
            "activity": {"state": self.act, "since": self.act_since, "posture": self.posture,
                         "tilt": self.tilt, "steps": self.steps["count"], "calibrated": bool(self.calib)},
            "risk": self.fall_risk(),
            "vitals": {"hr": round(self.vit["hr"]), "spo2": round(self.vit["spo2"]),
                       "temp": round(self.vit["temp"], 1), "simulated": True},
            "device": {"online": online, "last_rx": self.last_rx or None, "sensors": self.sensor_ok,
                       "rssi": self.rssi, "ever": self.ever_connected},
        }

    def snapshot(self):
        return {
            "t": "snapshot", "profile": self.profile, "alerts": self.alerts[-100:], "feed": self.feed[-150:],
            "sleep": self.sleep, "calib": bool(self.calib),
            "caregivers": [{"id": c["id"], "name": c["name"], "role": c["role"]} for c in self.clients.values()],
            "live": self.live(),
        }

    def series(self, rng):
        now = time.time()
        span = {"1h": 3600, "24h": 86400, "7d": 7 * 86400}.get(rng, 86400)
        pts = [p for p in self.vhist if p[0] >= now - span]
        step = max(1, len(pts) // 120)
        out = []
        for i in range(0, len(pts), step):
            chunk = pts[i:i + step]
            out.append([chunk[-1][0], round(sum(c[1] for c in chunk) / len(chunk)),
                        round(sum(c[2] for c in chunk) / len(chunk), 1),
                        round(sum(c[3] for c in chunk) / len(chunk), 2)])
        hours = collections.OrderedDict()
        for ts, act in list(self.act_minutes):
            if ts >= now - 86400 and act in ("walking", "active", "light"):
                k = int(ts // 3600) * 3600
                hours[k] = hours.get(k, 0) + 1
        return {"t": "series", "range": rng, "vitals": out, "active_minutes": list(hours.items())}

    def _broadcast(self, obj):
        txt = json.dumps(obj)
        for ws in list(self.clients):
            if ws.closed:
                self.clients.pop(ws, None)
                continue
            asyncio.ensure_future(self._send(ws, txt))

    async def _send(self, ws, txt):
        try:
            await ws.send_str(txt)
        except Exception:
            self.clients.pop(ws, None)

    async def push_loop(self):
        while True:
            await asyncio.sleep(0.5)
            self.tick()
            if not self.clients:
                continue
            if self.dirty:
                self.dirty = False
                self._broadcast(self.snapshot())
            else:
                self._broadcast(self.live())

    # ------------------------------------------------------------------ inbound (from phones)
    async def _reply(self, ws, obj):
        await ws.send_str(json.dumps(obj))

    async def handle(self, ws, msg):
        try:
            m = json.loads(msg)
        except ValueError:
            return
        op = m.get("op_id")
        if op:
            if op in self.seen_ops:
                await self._reply(ws, {"t": "ack_op", "op_id": op})
                return
            self.seen_ops.append(op)
        me = self.clients.get(ws, {})
        who = me.get("name") or "A caregiver"
        t = m.get("t")

        if t == "hello":
            self.clients[ws] = {"id": m.get("id") or _uid(), "name": (m.get("name") or "Caregiver")[:40],
                                "role": (m.get("role") or "family")[:30], "since": time.time()}
            self.dirty = True
            await self._reply(ws, self.snapshot())
        elif t == "ack":
            a = self._find_alert(m.get("alert"))
            if a and a["status"] != "resolved" and not any(r["name"] == who for r in a["responders"]):
                a["responders"].append({"name": who, "role": me.get("role"), "ts": time.time()})
                if a["status"] == "active":
                    a["status"] = "acknowledged"
                self.add_feed("alert", "responding", f"{who} is responding", {"who": who},
                              source="caregiver", by=who, ref=a["id"])
                self._broadcast({"t": "alert", "alert": a})
        elif t == "resolve":
            a = self._find_alert(m.get("alert"))
            if a and a["status"] != "resolved":
                res = m.get("resolution") if m.get("resolution") in ("assisted", "ems", "false_alarm") else "assisted"
                a.update(status="resolved", resolution=res, resolved_by=who, resolved_ts=time.time(),
                         note=(m.get("note") or "")[:300])
                self.hub.send_esp("A0")
                self.add_feed("alert", "resolved", f"Alert resolved by {who}", {"who": who, "res": res},
                              source="caregiver", by=who, ref=a["id"])
                self._broadcast({"t": "alert", "alert": a})
        elif t == "call":
            target = m.get("target") or "someone"
            self.add_feed("call", "called", f"{who} called {target}", {"who": who, "target": target},
                          source="caregiver", by=who, ref=m.get("alert"))
        elif t == "log":
            kind = m.get("kind")
            txt = (m.get("text") or "").strip()[:300]
            if kind == "meal":
                self.add_feed("meal", "meal" if txt else "meal_plain", f"Meal: {txt}" if txt else "Meal eaten",
                              {"text": txt}, source="caregiver", by=who)
            elif kind == "note" and txt:
                self.add_feed("note", "note", txt, {"text": txt}, source="caregiver", by=who)
        elif t == "profile":
            p = m.get("profile") or {}
            for k in ("wearer", "contacts", "home"):
                if k in p:
                    self.profile[k] = p[k]
            self.add_feed("note", "profile_updated", "Profile updated", source="caregiver", by=who)
        elif t == "calibrate":
            if not (self.last_rx and time.time() - self.last_rx < 3):
                await self._reply(ws, {"t": "toast", "key": "toast_no_vest", "text": "The vest isn't connected"})
            else:
                self.start_calibration(who)
                await self._reply(ws, {"t": "toast", "key": "toast_cal_start", "text": "Calibrating"})
        elif t == "series":
            await self._reply(ws, self.series(m.get("range", "24h")))
        elif t == "simulate":
            if m.get("kind") == "stumble":
                self.stumbles.append(time.time())
                self.add_feed("stumble", "stumble", "Stumble (demo)", {"g": 2.8, "demo": True})
            else:
                self.create_alert(int(m.get("severity", 3)), 5.2, 88, 92, demo=True)
        if op:
            await self._reply(ws, {"t": "ack_op", "op_id": op})
        if t not in ("series", "hello", "calibrate"):
            self.dirty = True

    def disconnect(self, ws):
        if self.clients.pop(ws, None):
            self.dirty = True

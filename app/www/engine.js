/* VestGuard on-phone care engine
   ------------------------------------------------------------------
   The phone-hotspot version of laptop/care.py. The vest does the detection itself and sends
   status (S), heartbeats (H), events (E) and falls (F); this engine turns them into the same
   messages the UI already understands (snapshot / live / alert / toast / series), so the UI
   code is identical whether it talks to the laptop server or straight to the vest.

     REAL (from the vest): falls, stumbles, activity, posture, steps, vest/sensor status
     LOGGED by caregivers: meals, notes, alert responses, calls
     SIMULATED (labelled):  heart rate, SpO2, skin temperature, sleep
*/
(function () {
  'use strict';
  const ESCALATE_AFTER_S = 60;
  const ONLINE_S = 4;
  const IMPORTANT = new Set(['fall_detected', 'escalated', 'responding', 'resolved', 'stumble', 'meal', 'note', 'called',
    'vest_lost', 'sensor_offline', 'sensor_frozen', 'act_feed_lying', 'act_feed_gotup']);
  const ACT_EN = { resting: 'Resting', light: 'Moving lightly', walking: 'Walking', active: 'Very active', lying: 'Lying down' };
  const DEFAULT_PROFILE = {
    wearer: { name: 'Kamla Devi', age: 76, phone: '', notes: 'Uses a walking stick. Hearing aid in left ear.' },
    contacts: [],
    home: { label: 'Home, Bandla, Bilaspur', lat: 31.3260, lon: 76.7590 },
  };
  const now = () => Date.now() / 1000;
  const uid = () => Math.random().toString(36).slice(2, 12);
  const today = () => { const d = new Date(); return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`; };
  const load = (k, d) => { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } };
  const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };

  // small seeded RNG so the demo vitals history looks the same every time
  function rng(seed) { let s = seed >>> 0; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
  function gauss(r) { let u = 0, v = 0; while (!u) u = r(); while (!v) v = r(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }

  class Engine {
    constructor(io) {
      this.io = io;                       // { sendVest(cmd), native: {...} | null }
      this.client = null;                 // function(msg) -> UI
      this.me = null;
      const st = load('vge_state', {});
      this.profile = Object.assign(JSON.parse(JSON.stringify(DEFAULT_PROFILE)), st.profile || {});
      this.alerts = (st.alerts || []).slice(-200);
      this.feed = (st.feed || []).slice(-400);
      this.steps = st.steps && st.steps.date === today() ? st.steps : { date: today(), count: 0, fw: null, up: null };
      this.stumbles = (st.stumbles || []).filter(t => now() - t < 86400);
      this.seenEv = st.seenEv || [];
      this.actMin = load('vge_actmin', []);
      this.calib = !!st.calib;
      this.vestWifi = st.vestWifi || null;
      this.fw = st.fw || null;
      this.autoSms = !!st.autoSms;

      this.lastRx = 0; this.everConnected = !!st.ever; this.wasOnline = false;
      this.sensorOk = [false, false]; this.hbSeen = false; this.rssi = 0; this.frozenUntil = 0;
      this.act = null; this.actSince = now(); this.posture = null; this.tilt = null; this.calibrating = false;
      this.lyingSince = null; this.vestIp = null; this.fallBoost = 0;
      this.minuteAcc = null;
      this.vit = { hr: 72, spo2: 97, temp: 34.2 };
      this.vhist = this._backfill();
      this.sleep = this._sleepHistory();
      this.dirty = true; this.persistDirty = false; this.lastSave = 0;
      setInterval(() => this._loop(), 500);
    }

    // ------------------------------------------------------------ plumbing
    attach(fn) { this.client = fn; }
    emit(m) { if (this.client) this.client(m); }
    toast(key) { this.emit({ t: 'toast', key }); }
    _changed() { this.dirty = true; this.persistDirty = true; }
    _save() {
      save('vge_state', { profile: this.profile, alerts: this.alerts.slice(-200), feed: this.feed.slice(-400), steps: this.steps,
        stumbles: this.stumbles, seenEv: this.seenEv.slice(-300), calib: this.calib, vestWifi: this.vestWifi, fw: this.fw,
        ever: this.everConnected, autoSms: this.autoSms });
      save('vge_actmin', this.actMin.slice(-7 * 24 * 60));
      this.persistDirty = false; this.lastSave = now();
    }
    online() { return !!(this.lastRx && now() - this.lastRx < ONLINE_S); }

    addFeed(kind, key, text, p = {}, source = 'vest', by = null, ref = null, ts = null) {
      const e = { id: uid(), ts: ts || now(), kind, key, p, text, source, imp: IMPORTANT.has(key) };
      if (by) e.by = by;
      if (ref) e.ref = ref;
      this.feed.push(e);
      this.feed.sort((a, b) => a.ts - b.ts);
      if (this.feed.length > 400) this.feed.splice(0, this.feed.length - 400);
      this._changed();
      return e;
    }

    // ------------------------------------------------------------ vest input
    /** one text line from the vest (live, or drained from the native store with its real receive time) */
    onLine(line, tsMs, extra = {}) {
      const ts = tsMs ? tsMs / 1000 : now();
      const p = line.split(',');
      const live = now() - ts < 10;
      if (p[0] !== 'ACK' && live) {
        if (!this.everConnected) { this.everConnected = true; this.addFeed('device', 'vest_connected', 'Vest connected'); }
        else if (this.lastRx && ts - this.lastRx > 10) this.addFeed('device', 'vest_reconnected', 'Vest reconnected');
        this.lastRx = Math.max(this.lastRx, ts);
      }
      switch (p[0]) {
        case 'S': return this._status(p, ts);
        case 'H': return this._heartbeat(p);
        case 'I': return this._info(p);
        case 'E': return this._event(p, ts);
        case 'F': return this._fall(p, ts, extra);
        case 'ACK': return this._ackFromNotification(p[1], ts);
      }
    }

    _status(p, ts) {
      // S,posture,tilt,act,steps,cal,uptime_s,calibrating
      const act = p[3] && p[3] !== '-' ? p[3] : null;
      if (act !== this.act) { this.act = act; this.actSince = ts; this.dirty = true; }
      this.posture = p[1] && p[1] !== '-' ? p[1] : null;
      const tl = parseInt(p[2], 10);
      this.tilt = tl >= 0 ? tl : null;
      const cal = p[5] === '1';
      if (cal !== this.calib) { this.calib = cal; this._changed(); }
      const was = this.calibrating;
      this.calibrating = p[7] === '1';
      if (was !== this.calibrating) this.dirty = true;
      // steps: the vest counts since it was switched on; add up the increases per day
      const fw = parseInt(p[4], 10), up = parseInt(p[6], 10);
      if (this.steps.date !== today()) this.steps = { date: today(), count: 0, fw: null, up: null };
      if (Number.isFinite(fw)) {
        const rebooted = this.steps.up != null && up < this.steps.up;
        if (this.steps.fw != null && !rebooted && fw >= this.steps.fw) this.steps.count += fw - this.steps.fw;
        else if (rebooted) this.steps.count += fw;
        this.steps.fw = fw; this.steps.up = up; this.persistDirty = true;
      }
      this.lyingSince = this.act === 'lying' ? (this.lyingSince || ts) : null;
      const m = Math.floor(ts / 60);
      if (!this.minuteAcc || this.minuteAcc[0] !== m) {
        if (this.minuteAcc) this.actMin.push([this.minuteAcc[0] * 60, this.minuteAcc[1]]);
        this.minuteAcc = [m, this.act];
      }
    }

    _heartbeat(p) {
      const ok = [p[1] === '1', p[2] === '1'];
      this.rssi = parseInt(p[5], 10) || 0;
      if (this.hbSeen) for (let i = 0; i < 2; i++) {
        if (ok[i] !== this.sensorOk[i]) this.addFeed('device', ok[i] ? 'sensor_online' : 'sensor_offline', ok[i] ? 'Sensor back online' : 'Sensor offline', { s: i ? 'lower' : 'upper' });
      }
      this.hbSeen = true; this.sensorOk = ok;
    }

    _info(p) {
      if (p[1] === 'FW') { this.fw = p[3]; this.persistDirty = true; }
      else if (p[1] === 'WIFICFG') { this.vestWifi = p[2]; this.persistDirty = true; this.dirty = true; }
      else if (p[1] === 'WIFI' && p[2] === 'SAVED') { this.vestWifi = p[3]; this._changed(); this.emit({ t: 'toast', key: 'toast_wifi_saved', p: { ssid: p[3] } }); }
      else if (p[1] === 'WIFI' && p[2] === 'REJECTED') this.toast('wifi_bad');
      else if (p[1] === 'WIFI' && p[2] === 'RESET') { this.vestWifi = p[3]; this._changed(); }
      else if (p[1] === 'ERR' && /calibrate/.test(p.slice(2).join(','))) this.toast('toast_cal_fail');
    }

    _seen(id) {
      if (this.seenEv.includes(id)) return true;
      this.seenEv.push(id);
      if (this.seenEv.length > 400) this.seenEv.splice(0, 100);
      this.persistDirty = true;
      return false;
    }

    _event(p, ts) {
      if (p.length < 3 || this._seen(p[1])) return;
      const kind = p[2];
      if (kind === 'STUMBLE') {
        const g = Math.round(parseFloat(p[3]) * 10) / 10;
        this.stumbles.push(ts);
        this.addFeed('stumble', 'stumble', `Stumble — impact ${g} g, stayed upright`, { g }, 'vest', null, null, ts);
      } else if (kind === 'ACT') {
        const from = p[3], to = p[4];
        if (!ACT_EN[to]) return;
        if (from === 'lying' && to !== 'lying') this.addFeed('activity', 'act_feed_gotup', 'Got up', { state: to }, 'vest', null, null, ts);
        else this.addFeed('activity', 'act_feed_' + to, ACT_EN[to], {}, 'vest', null, null, ts);
      } else if (kind === 'CAL') {
        if (p[3] === 'OK') {
          this.calib = true;
          this.addFeed('device', 'calibrated', 'Vest calibrated', {}, 'caregiver', this.me ? this.me.name : null, null, ts);
          this.toast('toast_cal_ok');
        } else this.toast('toast_cal_fail');
      } else if (kind === 'FROZEN') {
        this.frozenUntil = now() + 10;
        this.addFeed('device', 'sensor_frozen', 'Sensor stopped updating — restarting it', { s: p[3] === 'lower' ? 'lower' : 'upper' }, 'vest', null, null, ts);
      }
    }

    _fall(p, ts, extra) {
      // F,id,sev,peak,tilt_change,tilt_after,lying
      if (p.length < 6 || this._seen(p[1])) return;
      const sev = Math.min(3, Math.max(1, parseInt(p[2], 10) || 2));
      const ta = parseFloat(p[5]);
      let loc = Object.assign({}, this.profile.home, { source: 'home' });
      if (extra && extra.lat != null && extra.lon != null) {
        loc = { label: this.profile.home.label, lat: +extra.lat.toFixed(5), lon: +extra.lon.toFixed(5), source: 'phone', acc: extra.acc };
      }
      this.createAlert(sev, parseFloat(p[3]), parseFloat(p[4]), ta >= 0 ? ta : null, false, p[1], ts, loc);
    }

    createAlert(severity, peak, tiltChange, tiltAfter, demo, id, ts, loc) {
      const a = {
        id: id || uid(), ts: ts || now(), kind: 'fall', severity, peak_g: Math.round(peak * 100) / 100,
        tilt_change: Math.round(tiltChange), tilt_after: tiltAfter == null || isNaN(tiltAfter) ? null : Math.round(tiltAfter),
        status: 'active', responders: [], escalated: false, resolution: null, resolved_by: null, resolved_ts: null, note: '',
        location: loc || Object.assign({}, this.profile.home, { source: 'home' }), demo: !!demo,
      };
      if (now() - a.ts > ESCALATE_AFTER_S) a.escalated = true;
      this.alerts.push(a);
      this.alerts.sort((x, y) => x.ts - y.ts);
      this.addFeed('alert', 'fall_detected', `Fall detected — impact ${peak.toFixed(1)} g` + (demo ? ' (demo)' : ''),
        { sev: severity, g: Math.round(peak * 10) / 10, demo: !!demo }, 'vest', null, a.id, a.ts);
      this.fallBoost = now();
      this.emit({ t: 'alert', alert: a });
      this._changed();
      return a;
    }

    _ackFromNotification(id, ts) {
      const a = this.alerts.find(x => x.id === id);
      if (!a || a.status === 'resolved' || !this.me) return;
      this._respond(a, this.me, ts);
    }
    _respond(a, who, ts) {
      if (a.responders.some(r => r.name === who.name)) return;
      a.responders.push({ name: who.name, role: who.role, ts: ts || now() });
      if (a.status === 'active') a.status = 'acknowledged';
      this.addFeed('alert', 'responding', `${who.name} is responding`, { who: who.name }, 'caregiver', who.name, a.id);
      this.emit({ t: 'alert', alert: a });
    }

    // ------------------------------------------------------------ periodic
    _loop() {
      const t = now(), online = this.online();
      if (this.everConnected && !online && this.wasOnline) this.addFeed('device', 'vest_lost', 'Vest connection lost');
      this.wasOnline = online;
      this._vitals(t);
      for (const a of this.alerts) {
        if (a.status === 'active' && !a.escalated && t - a.ts > ESCALATE_AFTER_S) {
          a.escalated = true;
          this.addFeed('alert', 'escalated', 'No caregiver responded within 60 s', {}, 'vest', null, a.id);
          this.emit({ t: 'alert', alert: a });
        }
      }
      while (this.stumbles.length && t - this.stumbles[0] > 86400) this.stumbles.shift();
      if (this.persistDirty && t - this.lastSave > 5) this._save();
      if (!this.client) return;
      if (this.dirty) { this.dirty = false; this.emit(this.snapshot()); }
      else this.emit(this.live());
    }

    _vitals(t) {
      const hour = new Date().getHours();
      const base = 70 + (hour < 6 || hour >= 22 ? -6 : 0);
      const bump = { resting: 0, lying: -4, light: 8, walking: 18, active: 32 }[this.act] || 0;
      const el = t - this.fallBoost, boost = this.fallBoost && el < 600 ? 26 * Math.exp(-el / 180) : 0;
      const v = this.vit, r = Math.random;
      v.hr += (base + bump + boost - v.hr) * 0.08 + (r() - 0.5);
      v.spo2 += ((97.3 - (this.act === 'active' ? 0.8 : 0)) - v.spo2) * 0.05 + (r() - 0.5) * 0.16;
      v.spo2 = Math.max(94, Math.min(99, v.spo2));
      v.temp += (34.3 - v.temp) * 0.01 + (r() - 0.5) * 0.02;
      const m = Math.floor(t / 60) * 60;
      if (!this.vhist.length || this.vhist[this.vhist.length - 1][0] !== m) {
        this.vhist.push([m, Math.round(v.hr), Math.round(v.spo2 * 10) / 10, Math.round(v.temp * 100) / 100]);
        while (this.vhist.length && this.vhist[0][0] < t - 7 * 86400) this.vhist.shift();
      }
    }
    _backfill() {
      const r = rng(42), out = [], end = Math.floor(now() / 60) * 60;
      let hr = 72, sp = 97.2, tp = 34.2;
      for (let m = end - 7 * 86400; m < end; m += 60) {
        const h = new Date(m * 1000).getHours(), night = h < 6 || h >= 22;
        const act = night ? 0 : ([7, 9, 17, 18].includes(h) && r() < 0.35 ? 14 : r() < 0.4 ? 4 : 0);
        hr += ((night ? 64 : 72) + act - hr) * 0.15 + gauss(r) * 0.8;
        sp += ((night ? 96.6 : 97.3) - sp) * 0.1 + gauss(r) * 0.1;
        tp += ((night ? 34.0 : 34.4) - tp) * 0.05 + gauss(r) * 0.02;
        out.push([m, Math.round(hr), Math.round(Math.max(94, Math.min(99, sp)) * 10) / 10, Math.round(tp * 100) / 100]);
      }
      return out;
    }
    _sleepHistory() {
      const out = [], d0 = new Date(); d0.setHours(12, 0, 0, 0);
      for (let i = 6; i >= 0; i--) {
        const d = new Date(d0.getTime() - i * 86400000), r = rng(Math.floor(d.getTime() / 86400000));
        const hours = Math.round((5.6 + r() * 2.2) * 10) / 10, wakes = Math.floor(r() * 5);
        const score = Math.round(Math.max(40, Math.min(96, 50 + (hours - 5.5) * 14 - wakes * 4 + (r() * 8 - 4))));
        out.push({ date: d.toISOString().slice(0, 10), hours, wakeups: wakes, score });
      }
      return out;
    }

    // ------------------------------------------------------------ derived status (same as care.py)
    fallRisk() {
      const t = now();
      const real = this.alerts.filter(a => !a.demo && a.resolution !== 'false_alarm');
      const f24 = real.filter(a => t - a.ts < 86400).length, f7 = real.filter(a => t - a.ts < 7 * 86400).length;
      const st = this.stumbles.length, reasons = [];
      if (f24) reasons.push({ key: 'fr_falls_24', p: { n: f24 } });
      else if (f7) reasons.push({ key: 'fr_falls_7d', p: { n: f7 } });
      if (st) reasons.push({ key: 'fr_stumbles', p: { n: st } });
      let level;
      if (f24 || st >= 3) level = 'high';
      else if (f7 || st >= 1) level = 'moderate';
      else { level = 'low'; reasons.push({ key: 'fr_none', p: {} }); }
      return { level, reasons };
    }
    overall() {
      const t = now(), open = this.alerts.filter(a => a.status !== 'resolved');
      if (open.length) {
        const a = open[open.length - 1], who = a.responders[0] ? a.responders[0].name : null;
        return { level: 'emergency', title: 'st_emergency', detail: who ? { key: 'st_responding', p: { who } } : { key: 'st_no_response', p: {} }, reasons: [], alert: a.id };
      }
      const reasons = [], online = this.online();
      if (!this.everConnected) reasons.push({ key: 'r_not_connected', p: {} });
      else if (!online) { const mins = Math.floor((t - this.lastRx) / 60); reasons.push(mins ? { key: 'r_offline_min', p: { n: mins } } : { key: 'r_offline', p: {} }); }
      else if (!this.sensorOk.every(Boolean) || t < this.frozenUntil) reasons.push({ key: 'r_sensor', p: {} });
      const hour = new Date().getHours();
      if (this.lyingSince && hour >= 6 && hour < 22 && t - this.lyingSince > 20 * 60) reasons.push({ key: 'r_lying_long', p: { n: Math.floor((t - this.lyingSince) / 60) } });
      if (this.fallRisk().level === 'high') reasons.push({ key: 'r_risk_high', p: {} });
      if (reasons.length) return { level: 'attention', title: 'st_attention', detail: reasons[0], reasons };
      return { level: 'clear', title: 'st_clear', detail: { key: 'st_clear_detail', p: { name: this.profile.wearer.name.split(' ')[0], act: this.act } }, reasons: [] };
    }
    live() {
      const t = now(), online = this.online();
      return {
        t: 'live', ts: t, status: this.overall(),
        activity: { state: online ? this.act : null, since: this.actSince, posture: online ? this.posture : null, tilt: this.tilt,
          steps: this.steps.date === today() ? this.steps.count : 0, calibrated: this.calib, calibrating: this.calibrating },
        risk: this.fallRisk(),
        vitals: { hr: Math.round(this.vit.hr), spo2: Math.round(this.vit.spo2), temp: Math.round(this.vit.temp * 10) / 10, simulated: true },
        device: { online, last_rx: this.lastRx || null, sensors: this.sensorOk, rssi: this.rssi, ever: this.everConnected,
          ip: this.vestIp, fw: this.fw, wifi: this.vestWifi, mode: 'vest' },
      };
    }
    snapshot() {
      return {
        t: 'snapshot', profile: this.profile, alerts: this.alerts.slice(-100), feed: this.feed.slice(-150), sleep: this.sleep,
        calib: this.calib, caregivers: this.me ? [{ id: this.me.id, name: this.me.name, role: this.me.role }] : [],
        autoSms: this.autoSms, live: this.live(),
      };
    }
    series(range) {
      const t = now(), span = { '1h': 3600, '24h': 86400, '7d': 7 * 86400 }[range] || 86400;
      const pts = this.vhist.filter(p => p[0] >= t - span), step = Math.max(1, Math.floor(pts.length / 120)), out = [];
      for (let i = 0; i < pts.length; i += step) {
        const c = pts.slice(i, i + step), avg = k => c.reduce((s, x) => s + x[k], 0) / c.length;
        out.push([c[c.length - 1][0], Math.round(avg(1)), Math.round(avg(2) * 10) / 10, Math.round(avg(3) * 100) / 100]);
      }
      const hours = new Map();
      const mins = this.minuteAcc ? this.actMin.concat([[this.minuteAcc[0] * 60, this.minuteAcc[1]]]) : this.actMin;
      for (const [ts, act] of mins) {
        if (ts >= t - 86400 && ['walking', 'active', 'light'].includes(act)) { const k = Math.floor(ts / 3600) * 3600; hours.set(k, (hours.get(k) || 0) + 1); }
      }
      return { t: 'series', range, vitals: out, active_minutes: [...hours.entries()] };
    }

    // ------------------------------------------------------------ from the UI (same messages as the laptop server)
    handle(m) {
      const who = this.me ? this.me.name : 'A caregiver', nat = this.io.native;
      switch (m.t) {
        case 'hello':
          this.me = { id: m.id || uid(), name: (m.name || 'Caregiver').slice(0, 40), role: (m.role || 'family').slice(0, 30) };
          this.emit(this.snapshot());
          this.pushConfig();
          break;
        case 'ack': {
          const a = this.alerts.find(x => x.id === m.alert);
          if (a && a.status !== 'resolved' && this.me) this._respond(a, this.me);
          if (nat) nat.ackAlarm({ id: m.alert }).catch(() => {});
          break;
        }
        case 'resolve': {
          const a = this.alerts.find(x => x.id === m.alert);
          if (a && a.status !== 'resolved') {
            const res = ['assisted', 'ems', 'false_alarm'].includes(m.resolution) ? m.resolution : 'assisted';
            Object.assign(a, { status: 'resolved', resolution: res, resolved_by: who, resolved_ts: now(), note: (m.note || '').slice(0, 300) });
            this.io.sendVest('A0');
            this.addFeed('alert', 'resolved', `Alert resolved by ${who}`, { who, res }, 'caregiver', who, a.id);
            this.emit({ t: 'alert', alert: a });
          }
          if (nat) nat.clearAlarm({ id: m.alert }).catch(() => {});
          break;
        }
        case 'call':
          this.addFeed('call', 'called', `${who} called ${m.target || 'someone'}`, { who, target: m.target || 'someone' }, 'caregiver', who, m.alert);
          break;
        case 'log': {
          const txt = (m.text || '').trim().slice(0, 300);
          if (m.kind === 'meal') this.addFeed('meal', txt ? 'meal' : 'meal_plain', txt ? `Meal: ${txt}` : 'Meal eaten', { text: txt }, 'caregiver', who);
          else if (m.kind === 'note' && txt) this.addFeed('note', 'note', txt, { text: txt }, 'caregiver', who);
          break;
        }
        case 'profile':
          for (const k of ['wearer', 'contacts', 'home']) if (m.profile && k in m.profile) this.profile[k] = m.profile[k];
          this.addFeed('note', 'profile_updated', 'Profile updated', {}, 'caregiver', who);
          this.pushConfig();
          break;
        case 'calibrate':
          if (!this.online()) this.toast('toast_no_vest');
          else { this.io.sendVest('C'); this.toast('toast_cal_start'); this.calibrating = true; this.dirty = true; }
          return;
        case 'wifi':
          if (!this.online()) { this.toast('wifi_need_vest'); return; }
          if (m.reset) this.io.sendVest('X');
          else this.io.sendVest('W' + m.ssid + '\t' + (m.pass || ''));
          return;
        case 'settings':
          if ('autoSms' in m) { this.autoSms = !!m.autoSms; this.pushConfig(); }
          break;
        case 'series':
          this.emit(this.series(m.range || '24h'));
          return;
        case 'simulate':
          if (m.kind === 'stumble') {
            this.stumbles.push(now());
            this.addFeed('stumble', 'stumble', 'Stumble (demo)', { g: 2.8, demo: true });
          } else {
            const a = this.createAlert(+m.severity || 3, 5.2, 88, 92, true);
            if (nat) nat.raiseAlarm({ id: a.id, sev: a.severity, demo: true }).catch(() => {});
          }
          break;
      }
      if (m.op_id) this.emit({ t: 'ack_op', op_id: m.op_id });
      this._changed();
    }

    pushConfig() {
      const nat = this.io.native;
      if (!nat) return;
      const lang = (() => { try { return JSON.parse(localStorage.getItem('vgc_lang')) || 'en'; } catch { return 'en'; } })();
      nat.setConfig({
        lang, wearer: this.profile.wearer.name, contacts: this.profile.contacts.map(c => ({ name: c.name, phone: c.phone })),
        autoSms: this.autoSms, lat: +this.profile.home.lat || 0, lon: +this.profile.home.lon || 0, homeLabel: this.profile.home.label,
        me: this.me ? this.me.name : '',
      }).catch(() => {});
    }
  }

  window.VGEngine = Engine;
})();

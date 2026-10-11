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
  const LOST_S = 30;          // a short Wi-Fi hiccup shows "reconnecting"; only after 30 s is the vest "offline"
  const CHECK_S = 30;         // with a Home Hub: first ask the wearer, ring the family after 30 s
  const SENSOR_GRACE_S = 4;   // a sensor must stay down this long before we report it
  const IMPORTANT = new Set(['fall_detected', 'escalated', 'responding', 'resolved', 'stumble', 'meal', 'note', 'called',
    'vest_lost', 'sensor_offline', 'sensor_frozen', 'act_feed_lying', 'act_feed_gotup', 'recovered', 'vest_power', 'vest_crash']);
  const ACT_EN = { resting: 'Resting', light: 'Moving lightly', walking: 'Walking', active: 'Very active', lying: 'Lying down' };
  const DEFAULT_PROFILE = {
    wearer: { name: 'Kamla Devi', age: 76, phone: '', notes: 'Uses a walking stick. Hearing aid in left ear.' },
    contacts: [],
    home: { label: 'Home, Bandla, Bilaspur', lat: 31.3260, lon: 76.7590 },
  };
  const now = () => Date.now() / 1000;
  const unb64 = t => { try { return JSON.parse(decodeURIComponent(escape(atob(t)))); } catch { return null; } };
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
      this.lastBoot = st.lastBoot || null;
      this.autoSms = !!st.autoSms;
      this.autoCall = st.autoCall !== false;
      this.alarmSound = st.alarmSound === 'phone' ? 'phone' : 'chime';
      this.peers = {};                   // other phones of this family: device -> {name, role, kind, ts, vest}

      this.lastRx = 0; this.everConnected = !!st.ever; this.wasOnline = false;
      this.sensorOk = [false, false]; this.sensorDownAt = [0, 0]; this.sensorReported = [true, true]; this.hbSeen = false; this.rssi = 0; this.frozenUntil = 0;
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
        ever: this.everConnected, autoSms: this.autoSms, autoCall: this.autoCall, lastBoot: this.lastBoot, alarmSound: this.alarmSound });
      save('vge_actmin', this.actMin.slice(-7 * 24 * 60));
      this.persistDirty = false; this.lastSave = now();
    }
    online() { return !!(this.lastRx && now() - this.lastRx < ONLINE_S); }
    /** 'live' | 'reconnecting' (short gap, nothing to worry about) | 'offline' | 'never' */
    link() {
      if (!this.everConnected || !this.lastRx) return 'never';
      const gap = now() - this.lastRx;
      return gap < ONLINE_S ? 'live' : gap < LOST_S ? 'reconnecting' : 'offline';
    }
    /** a Home Hub phone of this family is around (it asks the wearer first after a fall) */
    hubPresent() {
      if (this.me && this.me.kind === 'hub') return true;
      const t = now();
      for (const k in this.peers) if (this.peers[k].kind === 'hub' && t - this.peers[k].ts < 15) return true;
      return false;
    }
    /** where an open alert is in its life: check (asking the wearer) -> alarm (family ringing) -> calling */
    phase(a) {
      if (!a || a.status === 'resolved') return 'done';
      if (a.kind === 'sos') return 'alarm';
      const age = now() - a.ts;
      if (a.responders.length) return 'responding';
      if (a.hub && age < CHECK_S) return 'check';
      return 'alarm';
    }

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
      if (!['ACK', 'CG', 'CS', 'CALL'].includes(p[0]) && live) {
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
        case 'CG': return this._presence(line, ts);
        case 'CS': return this._sync(line, ts);
        case 'CALL': return this._autoCalled(p, ts);
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
      const t = now();
      for (let i = 0; i < 2; i++) {
        if (!ok[i] && this.sensorOk[i]) this.sensorDownAt[i] = t;
        if (ok[i] && !this.sensorOk[i] && this.hbSeen && this.sensorReported[i] === false) this.sensorReported[i] = true;
        if (ok[i] && !this.sensorOk[i] && this.hbSeen && this.sensorReported[i] === 'down') {
          this.addFeed('device', 'sensor_online', 'Sensor back online', { s: i ? 'lower' : 'upper' });
          this.sensorReported[i] = true;
        }
        // only report a sensor that stays down (a one-second blip is not worth a notice)
        if (!ok[i] && this.hbSeen && this.sensorReported[i] === true && t - this.sensorDownAt[i] >= SENSOR_GRACE_S) {
          this.addFeed('device', 'sensor_offline', 'Sensor offline', { s: i ? 'lower' : 'upper' });
          this.sensorReported[i] = 'down';
        }
      }
      if (!this.hbSeen) for (let i = 0; i < 2; i++) if (!ok[i]) this.sensorDownAt[i] = t;
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
      const num = i => { const v = parseFloat(p[i]); return Number.isFinite(v) ? Math.round(v * 10) / 10 : null; };
      if (kind === 'STUMBLE') {
        const g = num(3);
        this.stumbles.push(ts);
        this.addFeed('stumble', 'stumble', `Stumble — impact ${g} g, stayed upright`, { g, turn: num(4) }, 'vest', null, null, ts);
      } else if (kind === 'NOFALL') {
        // 3.5: an impact the vest checked and decided was NOT a fall, with the reason
        const why = p[3] === 'one_sensor' ? 'one_sensor' : 'upright_active';
        this.addFeed('activity', 'nofall_' + why, 'Checked an impact: not a fall', { g: num(4), turn: Math.round(num(5) || 0), g2: num(6) }, 'vest', null, null, ts);
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
      } else if (kind === 'RISE') {
        // 3.4 vest: a quick get-up that older firmware would have called a fall
        this.addFeed('activity', 'rise', 'Got up quickly (not a fall)', { g: num(3), from: Math.round(num(4) || 0), to: Math.round(num(5) || 0) }, 'vest', null, null, ts);
      } else if (kind === 'RECOVER') {
        const a = this.alerts.filter(x => x.kind === 'fall' && x.status !== 'resolved' && ts >= x.ts).at(-1);
        if (a && !a.recovered) { a.recovered = ts; this.emit({ t: 'alert', alert: a }); }
        this.addFeed('activity', 'recovered', 'Back on their feet after the fall', {}, 'vest', null, a ? a.id : null, ts);
      } else if (kind === 'BOOT') {
        const why = p[3] || 'other';
        if (why === 'brownout') this.addFeed('device', 'vest_power', 'Vest restarted — the battery voltage dipped', { why }, 'vest', null, null, ts);
        else if (why === 'crash' || why === 'watchdog') this.addFeed('device', 'vest_crash', 'Vest restarted after an error', { why }, 'vest', null, null, ts);
        else this.addFeed('device', 'vest_restart', 'Vest switched on', { why }, 'vest', null, null, ts);
        this.lastBoot = { why, ts }; this.persistDirty = true;
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
        loc = { label: '', lat: +extra.lat.toFixed(5), lon: +extra.lon.toFixed(5), source: 'phone', acc: extra.acc };
      }
      const a = this.createAlert(sev, parseFloat(p[3]), parseFloat(p[4]), ta >= 0 ? ta : null, false, p[1], ts, loc);
      // firmware 3.5+: the numbers behind the decision (F,id,sev,peak,turn,ta,lying,tb,p1,p2,turnLower,still)
      if (a && p.length >= 12 && !a.ev) {
        const n = i => { const v = parseFloat(p[i]); return Number.isFinite(v) && v >= 0 ? v : null; };
        a.ev = { peak: n(3), turn: n(4), ta: n(5), lying: p[6] === '1', tb: n(7), p1: n(8), p2: n(9), turnLower: n(10), still: n(11) };
        this._changed();
      }
    }

    createAlert(severity, peak, tiltChange, tiltAfter, demo, id, ts, loc, kind = 'fall', from = '') {
      if (id && this.alerts.some(x => x.id === id)) return this.alerts.find(x => x.id === id);
      const a = {
        id: id || uid(), ts: ts || now(), kind, from, severity, peak_g: Math.round(peak * 100) / 100,
        tilt_change: Math.round(tiltChange), tilt_after: tiltAfter == null || isNaN(tiltAfter) ? null : Math.round(tiltAfter),
        status: 'active', responders: [], escalated: false, resolution: null, resolved_by: null, resolved_ts: null, note: '',
        location: loc || Object.assign({}, this.profile.home, { source: 'home' }), demo: !!demo,
        hub: kind === 'fall' && this.hubPresent(), recovered: null,
      };
      if (now() - a.ts > ESCALATE_AFTER_S) a.escalated = true;
      this.alerts.push(a);
      this.alerts.sort((x, y) => x.ts - y.ts);
      if (kind === 'sos') this.addFeed('alert', 'sos_raised', `SOS from ${from}`, { who: from }, 'caregiver', from, a.id, a.ts);
      else this.addFeed('alert', 'fall_detected', `Fall detected — impact ${peak.toFixed(1)} g` + (demo ? ' (demo)' : ''),
        { sev: severity, g: Math.round(peak * 10) / 10, demo: !!demo }, 'vest', null, a.id, a.ts);
      this.fallBoost = now();
      this.emit({ t: 'alert', alert: a });
      this._changed();
      return a;
    }

    // ------------------------------------------------------------ family (other phones on the same network)
    _presence(line, ts) {
      const p = line.split(','), b = unb64(p[3]);
      if (!b) return;
      const had = this.peers[p[2]];
      this.peers[p[2]] = { id: p[2], name: b.name || '?', role: b.role || 'family', kind: b.kind || 'caregiver', ts, vest: !!b.vest };
      if (!had) {
        this.dirty = true;
        // a phone just joined: the home hub shares the wearer's profile and contacts with it
        if (this.me && this.me.kind === 'hub') this._share({ t: 'profile', profile: { wearer: this.profile.wearer, contacts: this.profile.contacts, home: this.profile.home } });
      }
    }
    caregivers() {
      const t = now(), out = [];
      if (this.me) out.push({ id: this.me.id, name: this.me.name, role: this.me.role, kind: this.me.kind || 'caregiver', me: true });
      for (const k in this.peers) if (t - this.peers[k].ts < 15) out.push(this.peers[k]);
      return out;
    }
    _sync(line, ts) {
      const p = line.split(','), b = unb64(p[3]);
      if (!b || (b.uid && this._seen('s:' + b.uid))) return;     // live + drained copies arrive twice
      const a = b.alert ? this.alerts.find(x => x.id === b.alert) : null;
      switch (b.t) {
        case 'resp':
          if (a && a.status !== 'resolved') this._respond(a, { name: b.who, role: b.role }, b.ts || ts);
          break;
        case 'res':
          if (a && a.status !== 'resolved') {
            Object.assign(a, { status: 'resolved', resolution: b.res || 'assisted', resolved_by: b.who, resolved_ts: b.ts || ts, note: b.note || '' });
            this.addFeed('alert', 'resolved', `Alert resolved by ${b.who}`, { who: b.who, res: a.resolution }, 'caregiver', b.who, a.id);
            this.emit({ t: 'alert', alert: a });
          }
          break;
        case 'sos':
          this.createAlert(3, 0, 0, null, false, b.alert, b.ts || ts, null, 'sos', b.who || '?');
          break;
        case 'fall':
          if (!a) {
            if (b.line && b.line.startsWith('F,')) this._fall(b.line.split(','), b.ts || ts, b.lat != null ? { lat: b.lat, lon: b.lon } : {});
            else this.createAlert(+b.sev || 3, 5.2, 88, 92, !!b.demo, b.alert, b.ts || ts);
          }
          break;
        case 'note':
          if (b.entry && !this.feed.some(e => e.id === b.entry.id)) { this.feed.push(b.entry); this.feed.sort((x, y) => x.ts - y.ts); this._changed(); }
          break;
        case 'profile':
          if (b.profile) { for (const k of ['wearer', 'contacts', 'home']) if (k in b.profile) this.profile[k] = b.profile[k]; this._changed(); this.pushConfig(); }
          break;
        case 'call':
          this.addFeed('call', 'auto_called', `${b.by || 'Home hub'} called ${b.who}`, { who: b.who, by: b.by || '' }, 'caregiver', b.by || null, b.alert || null);
          break;
      }
    }
    _autoCalled(p, ts) {
      // CALL,alertId,name,number  (this phone placed an automatic call)
      if (this._seen('call:' + p[1] + ':' + p[3] + ':' + Math.floor(ts / 30))) return;
      this.addFeed('call', 'auto_called', `Called ${p[2]}`, { who: p[2], by: this.me ? this.me.name : '' }, 'caregiver', this.me ? this.me.name : null, p[1] || null, ts);
    }
    _share(body) {
      if (!this.io.native || !this.io.native.syncSend) return;
      body.uid = body.uid || uid(); body.ts = body.ts || now();
      this.io.native.syncSend({ body }).catch(() => {});
    }

    _ackFromNotification(id, ts) {
      const a = this.alerts.find(x => x.id === id);
      const me = this.me || load('vgc_me', null);     // the app may not have said hello yet on a cold start
      if (!a || a.status === 'resolved' || !me) return;
      this._respond(a, me, ts);
      this._share({ t: 'resp', alert: a.id, who: me.name, role: me.role });
    }
    _respond(a, who, ts) {
      if (a.responders.some(r => r.name === who.name)) return;
      a.responders.push({ name: who.name, role: who.role, ts: ts || now() });
      if (a.status === 'active') a.status = 'acknowledged';
      this.addFeed('alert', 'responding', `${who.name} is responding`, { who: who.name }, 'caregiver', who.name, a.id, ts || null);
      this.emit({ t: 'alert', alert: a });
    }

    // ------------------------------------------------------------ periodic
    _loop() {
      const t = now(), online = this.link() !== 'offline';
      if (this.everConnected && !online && this.wasOnline) this.addFeed('device', 'vest_lost', 'Vest connection lost', {}, 'vest', null, null, this.lastRx);
      this.wasOnline = online;
      // an open alert crossing from "asking the wearer" to "ringing the family" needs a fresh render
      for (const a of this.alerts) if (a.status === 'active' && a.hub && !a.phased && t - a.ts >= CHECK_S) { a.phased = true; this.emit({ t: 'alert', alert: a }); }
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
      else if (this.link() === 'offline') { const mins = Math.floor((t - this.lastRx) / 60); reasons.push(mins ? { key: 'r_offline_min', p: { n: mins } } : { key: 'r_offline', p: {} }); }
      else if (online) {
        const down = [0, 1].filter(i => !this.sensorOk[i] && t - this.sensorDownAt[i] >= SENSOR_GRACE_S);
        if (down.length === 2) reasons.push({ key: 'r_sensor_both', p: {} });
        else if (down.length) reasons.push({ key: down[0] ? 'r_sensor_lower' : 'r_sensor_upper', p: {} });
        else if (t < this.frozenUntil) reasons.push({ key: 'r_sensor', p: {} });
      }
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
        activity: { state: this.link() === 'live' || this.link() === 'reconnecting' ? this.act : null, since: this.actSince, posture: this.link() === 'live' || this.link() === 'reconnecting' ? this.posture : null, tilt: this.tilt,
          steps: this.steps.date === today() ? this.steps.count : 0, calibrated: this.calib, calibrating: this.calibrating },
        risk: this.fallRisk(),
        vitals: { hr: Math.round(this.vit.hr), spo2: Math.round(this.vit.spo2), temp: Math.round(this.vit.temp * 10) / 10, simulated: true },
        device: { online, link: this.link(), last_rx: this.lastRx || null, sensors: this.sensorOk, rssi: this.rssi, ever: this.everConnected, boot: this.lastBoot || null,
          ip: this.vestIp, fw: this.fw, wifi: this.vestWifi, mode: 'vest' },
        peers: this.caregivers().length - 1, hub: this.hubPresent(),
      };
    }
    snapshot() {
      return {
        t: 'snapshot', profile: this.profile, alerts: this.alerts.slice(-100), feed: this.feed.slice(-150), sleep: this.sleep,
        calib: this.calib, caregivers: this.caregivers(),
        autoSms: this.autoSms, autoCall: this.autoCall, alarmSound: this.alarmSound, live: this.live(),
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
          this.me = { id: m.id || uid(), name: (m.name || 'Caregiver').slice(0, 40), role: (m.role || 'family').slice(0, 30), kind: m.kind || 'caregiver', family: m.family || '', phone: m.phone || '' };
          this.emit(this.snapshot());
          this.pushConfig();
          break;
        case 'ack': {
          const a = this.alerts.find(x => x.id === m.alert);
          if (a && a.status !== 'resolved' && this.me) { this._respond(a, this.me); this._share({ t: 'resp', alert: a.id, who: this.me.name, role: this.me.role }); }
          if (nat) nat.ackAlarm({ id: m.alert }).catch(() => {});
          break;
        }
        case 'resolve': {
          const a = this.alerts.find(x => x.id === m.alert);
          if (a && a.status !== 'resolved') {
            const res = ['assisted', 'ems', 'false_alarm', 'wearer_ok'].includes(m.resolution) ? m.resolution : 'assisted';
            Object.assign(a, { status: 'resolved', resolution: res, resolved_by: who, resolved_ts: now(), note: (m.note || '').slice(0, 300) });
            this.io.sendVest('A0');
            this.addFeed('alert', 'resolved', `Alert resolved by ${who}`, { who, res }, 'caregiver', who, a.id);
            this.emit({ t: 'alert', alert: a });
            this._share({ t: 'res', alert: a.id, res, who, note: a.note });
          }
          if (nat) nat.clearAlarm({ id: m.alert }).catch(() => {});
          break;
        }
        case 'call':
          this.addFeed('call', 'called', `${who} called ${m.target || 'someone'}`, { who, target: m.target || 'someone' }, 'caregiver', who, m.alert);
          break;
        case 'log': {
          const txt = (m.text || '').trim().slice(0, 300);
          let e = null;
          if (m.kind === 'meal') e = this.addFeed('meal', txt ? 'meal' : 'meal_plain', txt ? `Meal: ${txt}` : 'Meal eaten', { text: txt }, 'caregiver', who);
          else if (m.kind === 'note' && txt) e = this.addFeed('note', 'note', txt, { text: txt }, 'caregiver', who);
          if (e) this._share({ t: 'note', entry: e });
          break;
        }
        case 'profile':
          for (const k of ['wearer', 'contacts', 'home']) if (m.profile && k in m.profile) this.profile[k] = m.profile[k];
          this.addFeed('note', 'profile_updated', 'Profile updated', {}, 'caregiver', who);
          this.pushConfig();
          this._share({ t: 'profile', profile: { wearer: this.profile.wearer, contacts: this.profile.contacts, home: this.profile.home } });
          break;
        case 'sos': {
          const a = this.createAlert(3, 0, 0, null, false, 'sos' + Date.now().toString(36), now(), null, 'sos', who);
          if (nat) nat.sos({ id: a.id, who }).catch(() => {});
          break;
        }
        case 'callnow': {
          const c = this.profile.contacts[m.idx || 0];
          if (c && nat) nat.callNow({ number: c.phone, name: c.name, alert: m.alert || '' }).catch(() => {});
          else if (c) this.emit({ t: 'dial', number: c.phone });
          return;
        }
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
          if ('autoCall' in m) { this.autoCall = !!m.autoCall; this.pushConfig(); }
          if ('alarmSound' in m) { this.alarmSound = m.alarmSound === 'phone' ? 'phone' : 'chime'; this.pushConfig(); }
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
        me: this.me ? this.me.name : '', meRole: this.me ? this.me.role : 'family',
        kind: this.me ? (this.me.kind || 'caregiver') : 'caregiver', family: this.me ? (this.me.family || '') : '',
        device: this.me ? this.me.id : '', autoCall: this.autoCall, alarmSound: this.alarmSound,
      }).catch(() => {});
    }
  }

  window.VGEngine = Engine;
})();

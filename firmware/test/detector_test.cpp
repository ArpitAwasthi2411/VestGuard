// PC test: runs the vest's detector.h on a recorded dataset CSV.
//   g++ -O2 -std=c++17 -I../vestguard_vest detector_test.cpp -o detector_test && ./detector_test data.csv
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>
#include <sstream>
#include <fstream>
#include "detector.h"

struct S { int trial; std::string activity, label; uint32_t t; float v[12]; };

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "usage: %s data.csv [--reverse]\n", argv[0]); return 1; }
  // --reverse plays every trial backwards in time: "lying down" becomes "getting up fast",
  // a fall becomes "getting up off the floor". None of these may raise a FALL.
  bool reverse = argc > 2 && !strcmp(argv[2], "--reverse");
  // --lower: pretend the upper sensor is dead; the vest must still work on the lower sensor alone
  bool lowerOnly = argc > 2 && !strcmp(argv[2], "--lower");
  // --events: one CSV line per checked impact with all its evidence (for the explainer)
  bool evCsv = argc > 2 && !strcmp(argv[2], "--events");
  if (evCsv) printf("trial,label,activity,kind,why,sev,p1,p2,turn_upper,turn_lower,tilt_before,tilt_after,still\n");
  std::ifstream f(argv[1]); std::string line; std::getline(f, line);
  std::vector<S> rows;
  while (std::getline(f, line)) {
    std::vector<std::string> c; std::stringstream ss(line); std::string x;
    while (std::getline(ss, x, ',')) c.push_back(x);
    if (c.size() < 20) continue;
    S s; s.trial = atoi(c[0].c_str()); s.activity = c[2]; s.label = c[3]; s.t = strtoul(c[6].c_str(), 0, 10);
    for (int k = 0; k < 12; k++) s.v[k] = strtof(c[8 + k].c_str(), 0);
    rows.push_back(s);
  }
  if (reverse) {
    std::vector<S> out; size_t a = 0;
    while (a < rows.size()) {
      size_t b = a; while (b < rows.size() && rows[b].trial == rows[a].trial) b++;
      uint32_t t0 = rows[a].t, t1 = rows[b - 1].t;
      for (size_t k = b; k-- > a;) { S r = rows[k]; r.t = t0 + (t1 - rows[k].t); r.label = "adl"; r.activity = "reversed " + r.activity; out.push_back(r); }
      a = b;
    }
    rows.swap(out);
  }
  // calibration = mean of the first second of trial 1 (standing still)
  float u1[3] = {0}, u2[3] = {0}; int n = 0;
  for (auto& r : rows) { if (r.trial != rows[0].trial || r.t - rows[0].t > 1000) break; for (int k = 0; k < 3; k++) { u1[k] += r.v[k]; u2[k] += r.v[6 + k]; } n++; }
  for (int k = 0; k < 3; k++) { u1[k] /= n; u2[k] /= n; }

  int tp = 0, fn = 0, fp = 0, tn = 0;
  size_t i = 0;
  while (i < rows.size()) {
    int trial = rows[i].trial; std::string act = rows[i].activity, label = rows[i].label;
    vg::Detector* d = new vg::Detector(); d->setCalibration(u1, u2);
    int falls = 0, stumbles = 0; std::string detail;
    uint32_t lastTick = rows[i].t;
    for (; i < rows.size() && rows[i].trial == trial; i++) {
      auto& r = rows[i];
      if (std::isnan(r.v[0])) continue;
      bool s2 = !std::isnan(r.v[6]);
      if (lowerOnly) { if (!s2) continue; d->setLowerPrimary(true); d->feed(r.t, r.v + 6, r.v + 9, r.v + 6, false); }
      else d->feed(r.t, r.v, r.v + 3, r.v + 6, s2);
      if (r.t - lastTick >= 500) { d->tick(r.t); lastTick = r.t; }
      vg::Event e;
      while (d->pop(e)) {
        if (evCsv && (e.kind == vg::Event::FALL || e.kind == vg::Event::STUMBLE || e.kind == vg::Event::RISE || e.kind == vg::Event::NOFALL)) {
          const char* k = e.kind == vg::Event::FALL ? "FALL" : e.kind == vg::Event::STUMBLE ? "STUMBLE" : e.kind == vg::Event::RISE ? "RISE" : "NOFALL";
          printf("%d,%s,%s,%s,%s,%u,%.2f,%.2f,%.0f,%.0f,%.0f,%.0f,%.3f\n", r.trial, label.c_str(), act.c_str(), k, e.why, e.severity,
                 e.p1, e.p2, e.tiltChange, e.a2, e.tiltBefore, e.tiltAfter, e.still);
        }
        char b[96];
        if (e.kind == vg::Event::FALL) { falls++; snprintf(b, sizeof b, " FALL(sev%u %.1fg %.0fdeg after%.0f)", e.severity, e.peak, e.tiltChange, e.tiltAfter); detail += b; }
        if (e.kind == vg::Event::RISE) { snprintf(b, sizeof b, " rise(%.1fg %.0f->%.0f)", e.peak, e.tiltBefore, e.tiltAfter); detail += b; }
        if (e.kind == vg::Event::NOFALL) { snprintf(b, sizeof b, " nofall:%s(%.1fg)", e.why, e.peak); detail += b; }
        if (e.kind == vg::Event::RECOVER) { detail += " recovered"; }
        if (e.kind == vg::Event::STUMBLE) { stumbles++; snprintf(b, sizeof b, " stumble(%.1fg)", e.peak); detail += b; }
      }
    }
    // flush pending analysis window with no-op (trial ended)
    bool isFall = label == "fall";
    if (isFall) { if (falls) tp++; else fn++; } else { if (falls) fp++; else tn++; }
    if (!evCsv) printf("%3d %-5s %-28s falls=%d stumbles=%d%s\n", trial, label.c_str(), act.c_str(), falls, stumbles, detail.c_str());
    delete d;
  }
  if (!evCsv) printf("\nfall trials detected: %d/%d   non-fall trials with false alarm: %d/%d\n", tp, tp + fn, fp, fp + tn);
}

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
  if (argc < 2) { fprintf(stderr, "usage: %s data.csv\n", argv[0]); return 1; }
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
      d->feed(r.t, r.v, r.v + 3, r.v + 6, s2);
      if (r.t - lastTick >= 500) { d->tick(r.t); lastTick = r.t; }
      vg::Event e;
      while (d->pop(e)) {
        char b[96];
        if (e.kind == vg::Event::FALL) { falls++; snprintf(b, sizeof b, " FALL(sev%u %.1fg %.0fdeg after%.0f)", e.severity, e.peak, e.tiltChange, e.tiltAfter); detail += b; }
        if (e.kind == vg::Event::STUMBLE) { stumbles++; snprintf(b, sizeof b, " stumble(%.1fg)", e.peak); detail += b; }
      }
    }
    // flush pending analysis window with no-op (trial ended)
    bool isFall = label == "fall";
    if (isFall) { if (falls) tp++; else fn++; } else { if (falls) fp++; else tn++; }
    printf("%3d %-5s %-28s falls=%d stumbles=%d%s\n", trial, label.c_str(), act.c_str(), falls, stumbles, detail.c_str());
    delete d;
  }
  printf("\nfall trials detected: %d/%d   non-fall trials with false alarm: %d/%d\n", tp, tp + fn, fp, fp + tn);
}

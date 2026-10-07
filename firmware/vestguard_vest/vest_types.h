// Types used in function signatures must live in a header (Arduino generates prototypes
// at the top of the sketch, before any struct defined in the .ino).
#pragma once
#include <Arduino.h>
#include <IPAddress.h>

struct Peer { IPAddress ip; uint32_t lastHeard; bool phone; bool used; };   // a phone or laptop we talk to
struct Note { uint16_t freq; uint16_t ms; };                               // buzzer note

// Astratto Engine - an example gameplay module in C++: the shape a team copies to write its game logic in C++ rather
// than Java. It links against nothing of the engine; everything it does goes through the astratto_module_host it is
// handed, on the thread that called astratto_frame.
//
// What it does (the starter game uses it): a radio that makes a noise every few seconds at a place given in its
// config, which agents hear (and a policy may send them to investigate); and a tally of what the frame's events say,
// logged back to whoever drives the engine. Config: "radio <x> <y> <z> <every seconds> <loudness>" (all optional).
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>

#include "astratto/astratto_module.h"

namespace {

struct Radio {
    float x = 0, y = 0, z = 0, every = 4.0f, loudness = 1.0f, radius = 40.0f;
    double next = 0;
    unsigned noises = 0, arrivals = 0, sightings = 0, frames = 0;
};

void* init(const astratto_module_host* host, const char* config) {
    Radio* r = new (std::nothrow) Radio();
    if (r == nullptr) return nullptr;
    if (config != nullptr && std::strncmp(config, "radio", 5) == 0) {
        float v[5] = {r->x, r->y, r->z, r->every, r->loudness};
        const char* at = config + 5;
        for (float& f : v) {
            char* end = nullptr;
            const float parsed = std::strtof(at, &end);
            if (end == at) break;
            f = parsed;
            at = end;
        }
        r->x = v[0]; r->y = v[1]; r->z = v[2]; r->every = v[3] > 0.05f ? v[3] : 0.05f; r->loudness = v[4];
    }
    r->next = host->time(host->ctx) + r->every;
    char line[160];
    std::snprintf(line, sizeof line, "example module: radio at (%.1f, %.1f, %.1f) every %.2f s", r->x, r->y, r->z, r->every);
    host->log(host->ctx, line);
    return r;
}

void update(void* self, const astratto_module_host* host, float) {
    Radio* r = static_cast<Radio*>(self);
    if (r == nullptr) return;
    r->frames++;
    if (host->time(host->ctx) < r->next) return;
    r->next += r->every;
    // a command written here runs exactly as if the caller had written it at this point of the frame
    astratto_cmd_noise n{};
    n.head.type = ASTRATTO_CMD_NOISE;
    n.head.bytes = sizeof n;
    n.x = r->x; n.y = r->y; n.z = r->z; n.radius = r->radius; n.loudness = r->loudness; n.team = 0xFFFFFFFFu;
    if (host->command(host->ctx, &n, sizeof n) == ASTRATTO_OK) r->noises++;
}

void event(void* self, const astratto_module_host* host, const uint8_t* events, uint32_t bytes, uint32_t) {
    Radio* r = static_cast<Radio*>(self);
    if (r == nullptr) return;
    // records are read by copying: the stream promises four-byte sizes, not alignment
    uint32_t at = 0;
    while (at + sizeof(astratto_record) <= bytes) {
        astratto_record head;
        std::memcpy(&head, events + at, sizeof head);
        if (head.bytes < sizeof head || head.bytes > bytes - at) break;
        if (head.type == ASTRATTO_EVT_AGENT_ARRIVED) r->arrivals++;
        else if (head.type == ASTRATTO_EVT_AGENT_SEES) {
            r->sightings++;
            char line[96];
            std::snprintf(line, sizeof line, "example module: sighting %u", r->sightings);
            host->log(host->ctx, line);
        }
        at += head.bytes;
    }
}

void shutdown(void* self, const astratto_module_host* host) {
    Radio* r = static_cast<Radio*>(self);
    if (r == nullptr) return;
    char line[160];
    std::snprintf(line, sizeof line, "example module: %u frames, %u noises, %u arrivals, %u sightings", r->frames, r->noises, r->arrivals, r->sightings);
    host->log(host->ctx, line);
    delete r;
}

}  // namespace

extern "C" ASTRATTO_MODULE_EXPORT int32_t astratto_module_entry(uint32_t api_version, astratto_module* out) {
    if ((api_version >> 16) != ASTRATTO_API_MAJOR) return ASTRATTO_ERR_VERSION;
    if (out == nullptr || out->struct_size < sizeof(astratto_module)) return ASTRATTO_ERR_ARGUMENT;
    out->abi = ASTRATTO_MODULE_ABI;
    out->name = "example";
    out->init = init;
    out->update = update;
    out->event = event;
    out->shutdown = shutdown;
    return ASTRATTO_OK;
}

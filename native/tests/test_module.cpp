// Astratto Engine - a gameplay module for the self-test only (it is not shipped with a game): it releases itself from
// inside its own code, at the point its config names, so the self-test can show that the engine waits until the call
// returns before it unloads the library. Config: "init", "update <frames>" or "event <frames>".
#include <cstdlib>
#include <cstring>
#include <new>

#include "astratto/astratto_module.h"

namespace {

struct State { int when = 0, frames = 0, after = 0; };   // when: 0 init, 1 update, 2 event
enum { AT_INIT = 0, AT_UPDATE = 1, AT_EVENT = 2 };

void releaseSelf(const astratto_module_host* host) {
    astratto_cmd_handle c{};
    c.head.type = ASTRATTO_CMD_RELEASE;
    c.head.bytes = sizeof c;
    c.handle = host->self;
    host->command(host->ctx, &c, sizeof c);
    // still running after the release: the library is still mapped and the host still good to use
    host->log(host->ctx, "test module: still here after releasing itself");
}

void* init(const astratto_module_host* host, const char* config) {
    State* s = new (std::nothrow) State();
    if (s == nullptr) return nullptr;
    if (config != nullptr && std::strncmp(config, "update", 6) == 0) { s->when = AT_UPDATE; s->after = std::atoi(config + 6); }
    else if (config != nullptr && std::strncmp(config, "event", 5) == 0) { s->when = AT_EVENT; s->after = std::atoi(config + 5); }
    if (s->when == AT_INIT) releaseSelf(host);
    return s;
}

void update(void* self, const astratto_module_host* host, float) {
    State* s = static_cast<State*>(self);
    if (s && s->when == AT_UPDATE && ++s->frames == s->after) releaseSelf(host);
}

void event(void* self, const astratto_module_host* host, const uint8_t*, uint32_t, uint32_t) {
    State* s = static_cast<State*>(self);
    if (s && s->when == AT_EVENT && ++s->frames == s->after) releaseSelf(host);
}

void shutdown(void* self, const astratto_module_host* host) {
    host->log(host->ctx, "test module: shut down");
    // asking again from inside its own shutdown is refused, not a second shutdown
    astratto_cmd_handle c{};
    c.head.type = ASTRATTO_CMD_RELEASE;
    c.head.bytes = sizeof c;
    c.handle = host->self;
    host->command(host->ctx, &c, sizeof c);
    delete static_cast<State*>(self);
}

}  // namespace

extern "C" ASTRATTO_MODULE_EXPORT int32_t astratto_module_entry(uint32_t api_version, astratto_module* out) {
    if ((api_version >> 16) != ASTRATTO_API_MAJOR) return ASTRATTO_ERR_VERSION;
    if (out == nullptr || out->struct_size < sizeof(astratto_module)) return ASTRATTO_ERR_ARGUMENT;
    out->abi = ASTRATTO_MODULE_ABI;
    out->name = "selftest";
    out->init = init;
    out->update = update;
    out->event = event;
    out->shutdown = shutdown;
    return ASTRATTO_OK;
}

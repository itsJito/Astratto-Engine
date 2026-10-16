#include "modules/module_host.h"

#include <cstring>
#include <exception>
#include <new>

#if defined(_WIN32)
#  define WIN32_LEAN_AND_MEAN
#  define NOMINMAX
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

namespace astratto {

namespace {

void* openLibrary(const char* path) {
#if defined(_WIN32)
    const int n = MultiByteToWideChar(CP_UTF8, 0, path, -1, nullptr, 0);
    std::wstring w(static_cast<size_t>(n > 0 ? n : 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path, -1, &w[0], n);
    return reinterpret_cast<void*>(LoadLibraryW(w.c_str()));
#else
    return dlopen(path, RTLD_NOW | RTLD_LOCAL);
#endif
}

void* symbol(void* lib, const char* name) {
#if defined(_WIN32)
    return reinterpret_cast<void*>(GetProcAddress(reinterpret_cast<HMODULE>(lib), name));
#else
    return dlsym(lib, name);
#endif
}

void closeLibrary(void* lib) {
#if defined(_WIN32)
    FreeLibrary(reinterpret_cast<HMODULE>(lib));
#else
    dlclose(lib);
#endif
}

// what a module may do with the engine: the host functions, on the engine's side
Engine& engineOf(void* ctx) { return *static_cast<ModuleHost::Loaded*>(ctx)->engine; }

/** A host function's body: nothing it throws may unwind through the module's frames (they are C, and not ours). */
template <typename F> int32_t hostGuarded(void* ctx, F&& body) {
    try { return body(engineOf(ctx)); }
    catch (const std::bad_alloc&) { engineOf(ctx).fail("out of memory"); return ASTRATTO_ERR_MEMORY; }
    catch (const std::exception& x) { engineOf(ctx).fail(x.what()); return ASTRATTO_ERR_INTERNAL; }
    catch (...) { engineOf(ctx).fail("unknown failure"); return ASTRATTO_ERR_INTERNAL; }
}

int32_t hostCommand(void* ctx, const void* record, uint32_t bytes) {
    if (record == nullptr || bytes < sizeof(astratto_record) || (bytes & 3u) != 0) return ASTRATTO_ERR_ARGUMENT;
    astratto_record head;
    std::memcpy(&head, record, sizeof(head));
    if (head.bytes != bytes) return ASTRATTO_ERR_STREAM;
    return hostGuarded(ctx, [&](Engine& e) { return e.command(static_cast<const uint8_t*>(record), bytes); });
}

int32_t hostReserve(void* ctx, uint32_t kind, uint32_t count, astratto_handle* out) {
    return hostGuarded(ctx, [&](Engine& e) { return e.reserve(kind, count, out); });
}

int32_t hostSnapshot(void* ctx, uint32_t kind, const void** data, uint64_t* bytes) {
    if (data == nullptr || bytes == nullptr) return ASTRATTO_ERR_ARGUMENT;
    return hostGuarded(ctx, [&](Engine& e) { return e.snapshot(kind, data, bytes); });
}

int32_t hostWorldView(void* ctx, astratto_world_view* out) {
    Engine& e = engineOf(ctx);
    const VoxelWorld* w = e.active();
    if (out == nullptr) return ASTRATTO_ERR_ARGUMENT;
    if (w == nullptr) return ASTRATTO_ERR_STATE;
    out->struct_size = sizeof(*out);
    out->size_x = static_cast<uint32_t>(w->sizeX()); out->size_y = static_cast<uint32_t>(w->sizeY()); out->size_z = static_cast<uint32_t>(w->sizeZ());
    out->blocks = w->blocks(); out->light = w->light(); out->pristine = w->pristine();
    out->cells = static_cast<uint64_t>(w->sizeX()) * w->sizeY() * w->sizeZ();
    out->edits = w->edits(); out->nav_version = w->navVersion(); out->light_version = w->lightVersion();
    return ASTRATTO_OK;
}

double hostTime(void* ctx) { return engineOf(ctx).time(); }

void hostLog(void* ctx, const char* text) {
    if (text == nullptr) return;
    ModuleHost::Loaded* m = static_cast<ModuleHost::Loaded*>(ctx);
    astratto_evt_module_log ev{};
    ev.module = m->host.self;
    ev.length = static_cast<uint32_t>(std::strlen(text));
    hostGuarded(ctx, [&](Engine& e) { e.emit(ev, ASTRATTO_EVT_MODULE_LOG, text, ev.length); return ASTRATTO_OK; });
}

}  // namespace

ModuleHost::~ModuleHost() { unloadAll(); }

void ModuleHost::unloadAll() {
    // the engine is going: every module is shut down, last loaded first
    order_.clear();
    modules_.each([&](astratto_handle h, const Loaded&) { order_.push_back(h); });
    for (size_t i = order_.size(); i-- > 0;) { if (Loaded* m = modules_.get(order_[i])) unload(*m); }
}

int32_t ModuleHost::load(Engine& e, const char* path, const char* config, astratto_handle* out) {
    if (path == nullptr || out == nullptr) { e.fail("no module path"); return ASTRATTO_ERR_ARGUMENT; }
    void* lib = openLibrary(path);
    if (lib == nullptr) { e.fail(std::string("module library not found or not loadable: ") + path); return ASTRATTO_ERR_ARGUMENT; }
    auto entry = reinterpret_cast<astratto_module_entry_fn>(symbol(lib, ASTRATTO_MODULE_ENTRY));
    if (entry == nullptr) { closeLibrary(lib); e.fail(std::string("not a module (no astratto_module_entry): ") + path); return ASTRATTO_ERR_ARGUMENT; }
    std::unique_ptr<Loaded> m(new Loaded());
    m->api.struct_size = sizeof(m->api);
    const int32_t st = entry(ASTRATTO_API_VERSION, &m->api);
    if (st != ASTRATTO_OK || m->api.abi != ASTRATTO_MODULE_ABI || m->api.init == nullptr) {
        closeLibrary(lib);
        e.fail(std::string("module refused this engine (built for another interface?): ") + path);
        return st == ASTRATTO_OK ? ASTRATTO_ERR_VERSION : st;
    }
    const astratto_handle h = modules_.reserve();
    m->library = lib;
    m->config = config ? config : "";
    m->engine = &e;
    m->host.struct_size = sizeof(m->host);
    m->host.api_version = ASTRATTO_API_VERSION;
    m->host.ctx = m.get();
    m->host.self = h;
    m->host.command = hostCommand; m->host.reserve = hostReserve; m->host.snapshot = hostSnapshot;
    m->host.world_view = hostWorldView; m->host.time = hostTime; m->host.log = hostLog;
    Loaded* raw = m.get();
    modules_.put(h, std::move(m));
    *out = h;
    calling([&] { raw->state = raw->api.init(&raw->host, raw->config.c_str()); });
    return ASTRATTO_OK;
}

void ModuleHost::unload(Loaded& m) {
    if (m.library == nullptr) return;
    m.released = true;          // a release asked from inside its own shutdown is refused, not run twice
    if (m.api.shutdown) { calling_++; m.api.shutdown(m.state, &m.host); calling_--; }
    closeLibrary(m.library);
    m.library = nullptr;
}

bool ModuleHost::release(Engine&, astratto_handle h) {
    Loaded* m = modules_.get(h);
    if (m == nullptr || m->released) return false;
    if (calling_ > 0) {
        // module code is running - perhaps the module's own, asking to be released: unloading its library now would
        // pull its code and its host out from under it. It is called no more, and goes when the call returns.
        m->released = true;
        released_.push_back(h);
        return true;
    }
    unload(*m);
    return modules_.release(h);
}

void ModuleHost::reap() {
    // (a module's shutdown may release others in turn: they join the list and go too)
    std::vector<astratto_handle> going;
    while (!released_.empty()) {
        going.clear();
        going.swap(released_);
        for (astratto_handle h : going) {
            if (Loaded* m = modules_.get(h)) unload(*m);
            modules_.release(h);
        }
    }
}

void ModuleHost::step(Engine&, float dt) {
    order_.clear();
    modules_.each([&](astratto_handle h, const Loaded&) { order_.push_back(h); });
    calling([&] {
        for (astratto_handle h : order_) {
            Loaded* m = modules_.get(h);
            if (m && m->library && !m->released && m->api.update) m->api.update(m->state, &m->host, dt);
        }
    });
}

void ModuleHost::frameEnd(Engine& e) {
    if (order_.empty()) return;
    // the modules see the frame's events as they stood when it ended: what they log or cause from here on is added to
    // the caller's events after them (and questions they ask now are answered in the next frame)
    seen_ = e.events();
    const uint32_t count = e.eventCount();
    calling([&] {
        for (astratto_handle h : order_) {
            Loaded* m = modules_.get(h);
            if (m && m->library && !m->released && m->api.event) m->api.event(m->state, &m->host, seen_.empty() ? nullptr : seen_.data(), static_cast<uint32_t>(seen_.size()), count);
        }
    });
}

}  // namespace astratto

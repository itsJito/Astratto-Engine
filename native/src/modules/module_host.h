// Astratto Engine - the host for gameplay modules written in C++: loads them, runs them inside the frame on the
// caller's thread, shows them the frame's events, and shuts them down.
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "astratto/astratto_module.h"
#include "core/engine.h"

namespace astratto {

class ModuleHost : public Service {
public:
    ~ModuleHost() override;

    int32_t load(Engine& e, const char* path, const char* config, astratto_handle* out);
    /** Shuts every module down, last loaded first (the engine does this first when it goes, while all else still stands). */
    void unloadAll();

    int command(Engine&, uint32_t, const uint8_t*, uint32_t) override { return -1; }
    void step(Engine& e, float dt) override;
    void frameEnd(Engine& e) override;
    bool owns(uint32_t kind) const override { return kind == ASTRATTO_KIND_MODULE; }
    bool release(Engine& e, astratto_handle h) override;
    uint32_t live(uint32_t kind) const override { return kind == ASTRATTO_KIND_MODULE ? modules_.live() : 0; }

    struct Loaded {
        void* library = nullptr;
        astratto_module api{};
        astratto_module_host host{};
        void* state = nullptr;
        std::string config;
        Engine* engine = nullptr;
        /** Released (or shutting down): it is called no more, and goes when no module code is running. */
        bool released = false;
    };

private:
    void unload(Loaded& m);
    /** Runs module code. A module released meanwhile - even by itself - is unloaded only once no module code is running. */
    template <typename F> void calling(F&& f) {
        calling_++;
        f();
        if (--calling_ == 0 && !released_.empty()) reap();
    }
    void reap();
    HandleTable<Loaded> modules_{ASTRATTO_KIND_MODULE};
    std::vector<astratto_handle> order_, released_;
    std::vector<uint8_t> seen_;
    int calling_ = 0;
};

}  // namespace astratto

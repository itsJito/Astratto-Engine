// Astratto Engine - the physics service: every body of the engine, stepped in the tick.
#pragma once

#include <memory>
#include <vector>

#include "core/engine.h"
#include "physics/bodies.h"
#include "physics/ragdoll.h"

namespace astratto {

class PhysicsService : public Service {
public:
    PhysicsService();

    int command(Engine& e, uint32_t type, const uint8_t* rec, uint32_t bytes) override;
    void step(Engine& e, float dt) override;
    bool owns(uint32_t kind) const override;
    astratto_handle reserve(uint32_t kind) override;
    bool release(Engine& e, astratto_handle h) override;
    void worldChanged(Engine& e) override;
    bool snapshot(Engine& e, uint32_t kind, std::vector<uint8_t>& out) override;
    uint32_t live(uint32_t kind) const override;

    // what the other services read (AI walks movers and looks at bodies)
    Mover* mover(astratto_handle h) const { return movers_.get(h); }
    /** Where a body is (its middle) and how tall it stands; false when the handle is no body. */
    bool bodyPosition(astratto_handle h, float* pos, float* height) const;
    template <typename F> void eachMover(F&& f) const { movers_.each([&](astratto_handle h, const Mover& m) { f(h, m); }); }

    struct RagdollBody {
        std::unique_ptr<Ragdoll> body;
        std::unique_ptr<SydneyCharacter> sydney;
        int lastState = -1, lastOutcome = 0;
        bool wasSettled = false;
    };
    struct SoftBodyRec { SoftBody body; bool wasSettled = false; };
    struct VehicleRec { Vehicle car; Vehicle::Report report; };

private:
    HandleTable<Mover> movers_{ASTRATTO_KIND_MOVER};
    HandleTable<RagdollBody> ragdolls_{ASTRATTO_KIND_RAGDOLL};
    HandleTable<SoftBodyRec> softs_{ASTRATTO_KIND_SOFTBODY};
    HandleTable<VehicleRec> vehicles_{ASTRATTO_KIND_VEHICLE};
    Surface surfaces_[64];
    SydneyConfig profiles_[16];
    float gravity_ = 22.0f, ragdollGravity_ = 22.0f;
    std::vector<astratto_handle> order_;
    std::vector<float> landed_;
};

}  // namespace astratto

// Astratto Engine - NeuralAI, native: agents that perceive, decide by the game's policies, and carry out what they
// decided with the engine's own route search and walkability checks.
#pragma once

#include <memory>
#include <vector>

#include "core/engine.h"

namespace astratto {

class PhysicsService;

class AiService : public Service {
public:
    int command(Engine& e, uint32_t type, const uint8_t* rec, uint32_t bytes) override;
    void step(Engine& e, float dt) override;
    bool owns(uint32_t kind) const override { return kind == ASTRATTO_KIND_AGENT || kind == ASTRATTO_KIND_POLICY; }
    astratto_handle reserve(uint32_t kind) override;
    bool release(Engine& e, astratto_handle h) override;
    void worldChanged(Engine& e) override;
    bool snapshot(Engine& e, uint32_t kind, std::vector<uint8_t>& out) override;
    uint32_t live(uint32_t kind) const override;

    struct Policy {
        std::vector<astratto_action> actions;
        std::vector<astratto_consideration> considerations;
        float inertia = 0.1f, think = 0.2f;
    };

    struct Agent {
        astratto_handle self = ASTRATTO_NO_HANDLE, policy = ASTRATTO_NO_HANDLE, body = ASTRATTO_NO_HANDLE, target = ASTRATTO_NO_HANDLE;
        float x = 0, y = 0, z = 0, yaw = 0;
        float homeX = 0, homeY = 0, homeZ = 0;
        float eye = 1.6f, viewRange = 40, viewCos = 0.5f, hearRange = 30;
        uint32_t team = 0, flags = 0, rng = 1;
        float facts[16] = {};
        // perception
        bool sees = false, hasTargetPlace = false;
        float targetX = 0, targetY = 0, targetZ = 0;       // where the target is, as far as the agent knows
        double lastSeen = -1e9, lastHeard = -1e9;
        float heardX = 0, heardY = 0, heardZ = 0;
        float lookX = 0, lookY = 0, lookZ = 0;           // the target's real middle this frame (for the sight line)
        bool lookValid = false, sawThisFrame = false;
        // decision
        int current = -1, forced = -2;                   // indices into the policy's actions; forced -2 = not forced
        float actionTime = 0, thinkTimer = 0, random = 0.5f;
        // movement
        bool hasGoal = false;
        float goalX = 0, goalY = 0, goalZ = 0;
        std::vector<int32_t> route;
        size_t routeAt = 0;
        bool wantPath = false, moving = false, stuck = false, arrived = false;
        float destX = 0, destY = 0, destZ = 0;           // where the current plan is taking it
        float progressBest = 1e9f, progressTimer = 0, repathTimer = 0;
        int fails = 0;
        uint32_t navSeen = 0;
        float speed = 0;
        float pause = 0;                                 // a wanderer stands a moment where it got to
        bool waiting = false;
    };

private:
    float input(const Engine& e, const Agent& a, uint32_t which) const;
    float score(const Engine& e, const Agent& a, const Policy& p, size_t action) const;
    void decide(Engine& e, Agent& a);
    void plan(Engine& e, Agent& a, const VoxelWorld* w);
    void follow(Engine& e, Agent& a, const VoxelWorld* w, float dt, PhysicsService* phys);
    bool targetPosition(Engine& e, const Agent& a, float* pos) const;
    uint32_t next(Agent& a) { a.rng = a.rng * 1664525u + 1013904223u; return a.rng >> 8; }
    float rand01(Agent& a) { return static_cast<float>(next(a) & 0xFFFF) / 65535.0f; }

    HandleTable<Agent> agents_{ASTRATTO_KIND_AGENT};
    HandleTable<Policy> policies_{ASTRATTO_KIND_POLICY};
    std::vector<astratto_cmd_noise> noises_;
    std::vector<astratto_handle> order_;
    std::vector<std::unique_ptr<class Pathfinder>> finders_;
    std::vector<uint8_t> clear_;
};

}  // namespace astratto

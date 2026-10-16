// Astratto Engine - handles: what the caller holds instead of a pointer.
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "astratto/astratto.h"

namespace astratto {

inline uint32_t handleKind(astratto_handle h) { return static_cast<uint32_t>(h >> 56); }
inline uint32_t handleGen(astratto_handle h) { return static_cast<uint32_t>(h >> 32) & 0xFFFFFFu; }
inline uint32_t handleIndex(astratto_handle h) { return static_cast<uint32_t>(h); }
inline astratto_handle makeHandle(uint32_t kind, uint32_t gen, uint32_t index) {
    return (static_cast<uint64_t>(kind) << 56) | (static_cast<uint64_t>(gen & 0xFFFFFFu) << 32) | index;
}

/**
 * A table of things of one kind. A slot is reserved (a handle exists, nothing is in it yet), filled, or free; every
 * time a slot is freed its generation moves on, so a stale handle never finds what has since taken its place.
 */
template <typename T> class HandleTable {
public:
    explicit HandleTable(uint32_t kind) : kind_(kind) {}

    astratto_handle reserve() {
        uint32_t i;
        if (!free_.empty()) { i = free_.back(); free_.pop_back(); }
        else { i = static_cast<uint32_t>(slots_.size()); slots_.emplace_back(); }
        Slot& s = slots_[i];
        s.reserved = true;
        s.gen = (s.gen + 1) & 0xFFFFFFu;
        if (s.gen == 0) s.gen = 1;
        return makeHandle(kind_, s.gen, i);
    }

    /** The slot a handle names: null when the handle is not of this kind, or is stale. */
    bool valid(astratto_handle h) const {
        if (handleKind(h) != kind_) return false;
        const uint32_t i = handleIndex(h);
        return i < slots_.size() && slots_[i].reserved && slots_[i].gen == handleGen(h);
    }

    T* get(astratto_handle h) const { return valid(h) ? slots_[handleIndex(h)].value.get() : nullptr; }

    bool put(astratto_handle h, std::unique_ptr<T> v) {
        if (!valid(h)) return false;
        Slot& s = slots_[handleIndex(h)];
        if (s.value) live_--;
        s.value = std::move(v);
        if (s.value) live_++;
        return true;
    }

    bool release(astratto_handle h) {
        if (!valid(h)) return false;
        Slot& s = slots_[handleIndex(h)];
        if (s.value) live_--;
        s.value.reset();
        s.reserved = false;
        free_.push_back(handleIndex(h));
        return true;
    }

    uint32_t live() const { return live_; }

    /** Every thing held, in slot order (the order of a frame's work over them: stable, whatever the threads). */
    template <typename F> void each(F&& f) const {
        for (uint32_t i = 0; i < slots_.size(); i++)
            if (slots_[i].reserved && slots_[i].value) f(makeHandle(kind_, slots_[i].gen, i), *slots_[i].value);
    }

private:
    struct Slot { std::unique_ptr<T> value; uint32_t gen = 0; bool reserved = false; };
    uint32_t kind_;
    std::vector<Slot> slots_;
    std::vector<uint32_t> free_;
    uint32_t live_ = 0;
};

}  // namespace astratto

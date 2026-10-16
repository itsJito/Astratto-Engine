// Astratto Engine - UltraEngine, native: sounds, voices, the mixer and its effects, and the device it plays on.
#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include "core/engine.h"

namespace astratto {

/** A decoded sound at the mixer's rate, mono or stereo. Shared with the mixer thread, never changed once made. */
struct Sound {
    std::vector<float> data;   // frames * channels
    uint32_t channels = 1;
    uint32_t frames = 0;
};

/**
 * The shared late field (the game's gun bus LateReverb): a feedback delay network of four mutually prime lines through
 * a lossless Householder matrix, damped per pass, behind a pre-delay and two all-passes; its parameters glide.
 */
class LateReverb {
public:
    explicit LateReverb(int rate);
    void target(float decay, float wet, float damp, float predelay);
    void process(const float* in, float* outL, float* outR, int n);
    float decay() const { return decay_; }
    float wet() const { return wet_; }

private:
    void gains();
    int rate_;
    std::vector<float> line_[4];
    int len_[4] = {1447, 1877, 2251, 2789}, at_[4] = {};
    float lp_[4] = {}, g_[4] = {};
    std::vector<float> pre_, ap1_, ap2_;
    int preAt_ = 0, a1_ = 0, a2_ = 0;
    float decay_ = 1.1f, wet_ = 0.22f, damp_ = 0.45f, predelay_ = 0.02f, tDecay_ = 1.1f, tWet_ = 0.22f, tDamp_ = 0.45f, tPre_ = 0.02f;
};

/** Everything the audio thread touches. The frame thread hands it changes under its lock; it mixes without it. */
class Mixer {
public:
    explicit Mixer(int rate);

    struct Voice {
        uint64_t id = 0;
        std::shared_ptr<const Sound> sound;
        double pos = 0;
        float pitch = 1;
        float gl = 0, gr = 0, tgl = 0, tgr = 0;         // gains now and wanted (smoothed per block)
        float lp = 0, tlp = 0, lpL = 0, lpR = 0;        // low-pass coefficient now and wanted, and its state
        float send = 0, tsend = 0;                       // into the reverb
        float fade = 1, fadeTarget = 1, fadeSpeed = 0;
        uint32_t bus = 0;
        bool loop = false, stopped = false, ended = false, primed = false;
    };
    struct Change {
        enum Kind { PLAY, SET, STOP } kind;
        Voice v;                                         // PLAY: the voice; SET: new targets (gains, lp, send, pitch); STOP: id and fade in fadeSpeed
        uint32_t what = 0;
        float fadeTo = 1, fadeTime = 0;
    };

    void post(const Change& c);
    void levels(float master, bool muted, const float* bus, uint32_t busMuted);
    void room(float decay, float wet, float damp, float predelay);
    /** Mixes frames of stereo (interleaved) into out. Audio thread (or the caller's, when there is no device). */
    void mix(float* out, int frames);
    /** Voices that ended since last asked (frame thread). */
    void takeEnded(std::vector<uint64_t>& out);

    std::atomic<uint64_t> framesMixed{0};
    std::atomic<uint32_t> voiceCount{0}, limited{0};
    std::atomic<float> outPeak{0}, mixMs{0};
    float busPeak[ASTRATTO_BUSES] = {};

private:
    int rate_;
    std::mutex lock_;
    std::vector<Change> inbox_;
    std::vector<uint64_t> ended_;
    std::vector<Voice> voices_;
    float master_ = 0.8f, bus_[ASTRATTO_BUSES] = {1, 0.55f, 1, 0.7f, 0.8f};
    bool muted_ = false;
    uint32_t busMuted_ = 0;
    float roomDecay_ = 1.1f, roomWet_ = 0.22f, roomDamp_ = 0.45f, roomPre_ = 0.02f;
    LateReverb reverb_;
    std::vector<float> left_, right_, send_, wetL_, wetR_;
    float comp_ = 1, env_ = 0, lim_ = 1;
};

/** The system's audio output: a thread that keeps a few blocks queued on the device, mixing each as one comes back. */
class AudioDevice {
public:
    static std::unique_ptr<AudioDevice> open(Mixer* mixer, int rate, int block);
    virtual ~AudioDevice() = default;
    std::atomic<uint32_t> underruns{0};
};

class AudioService : public Service {
public:
    explicit AudioService(const astratto_config& cfg);
    ~AudioService() override;

    int command(Engine& e, uint32_t type, const uint8_t* rec, uint32_t bytes) override;
    void step(Engine& e, float dt) override;
    bool owns(uint32_t kind) const override { return kind == ASTRATTO_KIND_SOUND || kind == ASTRATTO_KIND_VOICE; }
    astratto_handle reserve(uint32_t kind) override;
    bool release(Engine& e, astratto_handle h) override;
    bool snapshot(Engine& e, uint32_t kind, std::vector<uint8_t>& out) override;
    uint32_t live(uint32_t kind) const override;

    int32_t createSound(Engine& e, const astratto_sound_desc& d, astratto_handle* out);
    /** Renders frames when there is no device. False when a device is open (it is the device's to mix). */
    bool render(float* out, uint32_t frames);
    bool hasDevice() const { return device_ != nullptr; }

    struct VoiceRec {
        uint64_t id = 0;
        astratto_handle sound = ASTRATTO_NO_HANDLE;
        uint32_t bus = 0, flags = 0;
        float gain = 1, pitch = 1, pan = 0, x = 0, y = 0, z = 0, range = 30;
        float hear = 1;                     // how much of it gets through what stands between (smoothed)
        bool playing = false;
    };

private:
    void place(Engine& e, VoiceRec& v, Mixer::Change& c, float hear);
    float openness(const VoxelWorld& w, float x, float y, float z) const;
    void listenerRoom(Engine& e);

    int rate_, block_;
    Mixer mixer_;
    std::unique_ptr<AudioDevice> device_;
    HandleTable<std::shared_ptr<const Sound>> sounds_{ASTRATTO_KIND_SOUND};
    HandleTable<VoiceRec> voices_{ASTRATTO_KIND_VOICE};
    std::unordered_map<uint64_t, astratto_handle> ids_;   // the mixer's voice ids -> the caller's handles
    uint64_t nextId_ = 1;
    float lx_ = 0, ly_ = 0, lz_ = 0, lyaw_ = 0;
    float roomTimer_ = 0;
    float roomDecay_ = 1.1f, roomWet_ = 0.22f, listenerIndoor_ = 0.3f;
    std::vector<uint64_t> ended_;
    std::vector<astratto_handle> order_;
    std::vector<float> hear_;
};

}  // namespace astratto

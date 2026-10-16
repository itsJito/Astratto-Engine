// Astratto Engine - UltraEngine, native. The mixing law, the attenuation, the late reverb and the dynamics are the
// Java engine's (astratto.audio.UltraEngine and astratto.audio.gun.AudioMixer); what is new is that the world they
// listen through is the engine's own.
#include "audio/audio_service.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <unordered_map>

#include "perception/sight.h"
#include "world/raycast.h"

namespace astratto {

namespace {
constexpr float PI = 3.14159265358979f;
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
float knee(float x) {
    const float a = std::fabs(x);
    if (a <= 0.8f) return x;
    const float y = 0.8f + 0.2f * std::tanh((a - 0.8f) / 0.2f);
    return x < 0 ? -y : y;
}
constexpr float HEADROOM = 0.85f, THRESHOLD = 0.62f, RATIO = 3.2f, CEILING = 0.92f;
constexpr size_t MAX_VOICES = 128;
}  // namespace

// ------------------------------------------------------------------ the late reverb
LateReverb::LateReverb(int rate) : rate_(rate) {
    for (int i = 0; i < 4; i++) line_[i].assign(static_cast<size_t>(len_[i]), 0);
    pre_.assign(static_cast<size_t>(rate / 8), 0);
    ap1_.assign(441, 0);
    ap2_.assign(149, 0);
    gains();
}

void LateReverb::target(float decay, float wet, float damp, float predelay) {
    tDecay_ = clampf(decay, 0.15f, 4); tWet_ = clampf(wet, 0, 0.8f); tDamp_ = clampf(damp, 0.05f, 0.9f); tPre_ = clampf(predelay, 0.001f, 0.11f);
}

void LateReverb::gains() { for (int i = 0; i < 4; i++) g_[i] = std::pow(10.0f, -3.0f * static_cast<float>(len_[i]) / (decay_ * static_cast<float>(rate_))); }

void LateReverb::process(const float* in, float* outL, float* outR, int n) {
    const float k = std::min(1.0f, static_cast<float>(n) / (static_cast<float>(rate_) * 0.25f));
    decay_ += (tDecay_ - decay_) * k; wet_ += (tWet_ - wet_) * k; damp_ += (tDamp_ - damp_) * k; predelay_ += (tPre_ - predelay_) * k;
    gains();
    const int plen = static_cast<int>(pre_.size());
    const int pd = std::max(1, std::min(plen - 1, static_cast<int>(predelay_ * static_cast<float>(rate_))));
    for (int f = 0; f < n; f++) {
        pre_[static_cast<size_t>(preAt_)] = in[f];
        int rp = preAt_ - pd;
        if (rp < 0) rp += plen;
        const float x = pre_[static_cast<size_t>(rp)];
        if (++preAt_ == plen) preAt_ = 0;
        const float d1 = ap1_[static_cast<size_t>(a1_)], y1 = d1 - 0.6f * x; ap1_[static_cast<size_t>(a1_)] = x + 0.6f * y1; if (++a1_ == static_cast<int>(ap1_.size())) a1_ = 0;
        const float d2 = ap2_[static_cast<size_t>(a2_)]; float y2 = d2 - 0.6f * y1; ap2_[static_cast<size_t>(a2_)] = y1 + 0.6f * y2; if (++a2_ == static_cast<int>(ap2_.size())) a2_ = 0;
        const float s0 = line_[0][static_cast<size_t>(at_[0])], s1 = line_[1][static_cast<size_t>(at_[1])], s2 = line_[2][static_cast<size_t>(at_[2])], s3 = line_[3][static_cast<size_t>(at_[3])];
        const float sum = (s0 + s1 + s2 + s3) * 0.5f;
        const float f0 = s0 - sum, f1 = s1 - sum, f2 = s2 - sum, f3 = s3 - sum;
        lp_[0] += (f0 - lp_[0]) * (1 - damp_); lp_[1] += (f1 - lp_[1]) * (1 - damp_); lp_[2] += (f2 - lp_[2]) * (1 - damp_); lp_[3] += (f3 - lp_[3]) * (1 - damp_);
        for (float& v : lp_) if (std::fabs(v) < 1e-18f) v = 0;
        if (std::fabs(y2) < 1e-18f) y2 = 0;
        for (int i = 0; i < 4; i++) { line_[i][static_cast<size_t>(at_[i])] = y2 + lp_[i] * g_[i]; if (++at_[i] == len_[i]) at_[i] = 0; }
        outL[f] = (s0 - s2 + s1 * 0.5f) * wet_;
        outR[f] = (s1 - s3 + s0 * 0.5f) * wet_;
    }
}

// ------------------------------------------------------------------ the mixer
Mixer::Mixer(int rate) : rate_(rate), reverb_(rate) {}

void Mixer::post(const Change& c) { std::lock_guard<std::mutex> g(lock_); inbox_.push_back(c); }

void Mixer::levels(float master, bool muted, const float* bus, uint32_t busMuted) {
    std::lock_guard<std::mutex> g(lock_);
    master_ = master; muted_ = muted; busMuted_ = busMuted;
    for (int i = 0; i < ASTRATTO_BUSES; i++) bus_[i] = bus[i];
}

void Mixer::room(float decay, float wet, float damp, float predelay) {
    std::lock_guard<std::mutex> g(lock_);
    roomDecay_ = decay; roomWet_ = wet; roomDamp_ = damp; roomPre_ = predelay;
}

void Mixer::takeEnded(std::vector<uint64_t>& out) {
    std::lock_guard<std::mutex> g(lock_);
    out.insert(out.end(), ended_.begin(), ended_.end());
    ended_.clear();
}

void Mixer::mix(float* out, int frames) {
    const auto t0 = std::chrono::steady_clock::now();
    std::vector<Change> changes;
    float level[ASTRATTO_BUSES];
    {
        std::lock_guard<std::mutex> g(lock_);
        changes.swap(inbox_);
        for (int b = 0; b < ASTRATTO_BUSES; b++) level[b] = muted_ || (busMuted_ >> b & 1u) ? 0.0f : master_ * bus_[b];
        reverb_.target(roomDecay_, roomWet_, roomDamp_, roomPre_);
    }
    for (Change& c : changes) {
        if (c.kind == Change::PLAY) {
            if (voices_.size() >= MAX_VOICES) {
                // the quietest voice that is not looping makes room
                size_t q = voices_.size();
                float ql = 1e9f;
                for (size_t i = 0; i < voices_.size(); i++) { const float l = voices_[i].tgl + voices_[i].tgr; if (!voices_[i].loop && l < ql) { ql = l; q = i; } }
                if (q == voices_.size()) { ended_.push_back(c.v.id); continue; }
                ended_.push_back(voices_[q].id);
                voices_.erase(voices_.begin() + static_cast<std::ptrdiff_t>(q));
            }
            voices_.push_back(c.v);
            continue;
        }
        for (Voice& v : voices_) {
            if (v.id != c.v.id) continue;
            if (c.kind == Change::STOP) {
                if (c.fadeTime <= 0) v.stopped = true;
                else { v.fadeTarget = 0; v.fadeSpeed = 1.0f / (c.fadeTime * static_cast<float>(rate_)); }
            } else {
                v.tgl = c.v.tgl; v.tgr = c.v.tgr; v.tlp = c.v.tlp; v.tsend = c.v.tsend;
                if (c.what & ASTRATTO_SET_PITCH) v.pitch = c.v.pitch;
                if (c.what & ASTRATTO_SET_FADE) { v.fadeTarget = c.fadeTo; v.fadeSpeed = c.fadeTime <= 0 ? 1.0f : 1.0f / (c.fadeTime * static_cast<float>(rate_)); }
            }
            break;
        }
    }
    const size_t n = static_cast<size_t>(frames);
    left_.assign(n, 0); right_.assign(n, 0); send_.assign(n, 0); wetL_.assign(n, 0); wetR_.assign(n, 0);
    float peak[ASTRATTO_BUSES] = {};
    const float invN = 1.0f / static_cast<float>(frames);
    for (size_t vi = voices_.size(); vi-- > 0;) {
        Voice& v = voices_[vi];
        if (v.stopped) { ended_.push_back(v.id); voices_.erase(voices_.begin() + static_cast<std::ptrdiff_t>(vi)); continue; }
        if (!v.primed) { v.gl = v.tgl; v.gr = v.tgr; v.lp = v.tlp; v.send = v.tsend; v.primed = true; }
        const Sound& s = *v.sound;
        const float* d = s.data.data();
        const bool stereo = s.channels == 2;
        const int64_t len = static_cast<int64_t>(s.frames);
        const float lvl = level[v.bus < ASTRATTO_BUSES ? v.bus : 0];
        const float dgl = (v.tgl - v.gl) * invN, dgr = (v.tgr - v.gr) * invN, dlp = (v.tlp - v.lp) * invN, dsend = (v.tsend - v.send) * invN;
        for (size_t f = 0; f < n; f++) {
            int64_t i0 = static_cast<int64_t>(v.pos);
            if (i0 >= len - 1) {
                if (v.loop && len > 1) { v.pos -= static_cast<double>(len - 1); i0 = static_cast<int64_t>(v.pos); }
                else { v.stopped = true; break; }
            }
            const float frac = static_cast<float>(v.pos - static_cast<double>(i0));
            float sl, sr;
            if (stereo) {
                sl = d[i0 * 2] + (d[(i0 + 1) * 2] - d[i0 * 2]) * frac;
                sr = d[i0 * 2 + 1] + (d[(i0 + 1) * 2 + 1] - d[i0 * 2 + 1]) * frac;
            } else sl = sr = d[i0] + (d[i0 + 1] - d[i0]) * frac;
            v.gl += dgl; v.gr += dgr; v.lp += dlp; v.send += dsend;
            const float keep = v.lp;
            if (keep > 0) { v.lpL += (sl - v.lpL) * (1 - keep); v.lpR += (sr - v.lpR) * (1 - keep); sl = v.lpL; sr = v.lpR; }
            if (v.fade != v.fadeTarget) {
                v.fade = v.fade < v.fadeTarget ? std::min(v.fadeTarget, v.fade + v.fadeSpeed) : std::max(v.fadeTarget, v.fade - v.fadeSpeed);
                if (v.fade <= 0 && v.fadeTarget <= 0) { v.stopped = true; break; }
            }
            const float k = v.fade * lvl;
            const float l = sl * k * v.gl, r = sr * k * v.gr;
            left_[f] += l; right_[f] += r;
            send_[f] += (l + r) * 0.5f * v.send;
            const float a = std::max(std::fabs(l), std::fabs(r));
            if (a > peak[v.bus]) peak[v.bus] = a;
            v.pos += v.pitch;
        }
    }
    reverb_.process(send_.data(), wetL_.data(), wetR_.data(), frames);
    float pk = 0;
    uint32_t lim = 0;
    for (size_t f = 0; f < n; f++) {
        float l = (left_[f] + wetL_[f]) * HEADROOM, r = (right_[f] + wetR_[f]) * HEADROOM;
        // level riding, then a peak limiter, then a soft knee that should never be reached
        const float a = std::max(std::fabs(l), std::fabs(r));
        env_ += (a - env_) * (a > env_ ? 0.0035f : 0.00012f);
        const float want = env_ <= THRESHOLD ? 1.0f : (THRESHOLD + (env_ - THRESHOLD) / RATIO) / env_;
        comp_ += (want - comp_) * (want < comp_ ? 0.004f : 0.00025f);
        l *= comp_; r *= comp_;
        const float raw = std::max(std::fabs(l), std::fabs(r));
        if (raw * lim_ > CEILING) { lim_ = CEILING / raw; lim++; } else lim_ += (1 - lim_) * 0.0004f;
        l *= lim_; r *= lim_;
        l = knee(l); r = knee(r);
        if (l != l) l = 0;
        if (r != r) r = 0;
        out[f * 2] = l; out[f * 2 + 1] = r;
        pk = std::max(pk, std::max(std::fabs(l), std::fabs(r)));
    }
    for (int b = 0; b < ASTRATTO_BUSES; b++) busPeak[b] = peak[b];
    outPeak = pk;
    limited += lim;
    voiceCount = static_cast<uint32_t>(voices_.size());
    framesMixed += static_cast<uint64_t>(frames);
    mixMs = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

// ------------------------------------------------------------------ the service
AudioService::AudioService(const astratto_config& cfg)
    : rate_(cfg.audio_rate ? static_cast<int>(cfg.audio_rate) : 44100), block_(cfg.audio_block ? static_cast<int>(cfg.audio_block) : 1024), mixer_(rate_) {
    if (cfg.flags & ASTRATTO_CONFIG_AUDIO_DEVICE) device_ = AudioDevice::open(&mixer_, rate_, block_);
}

AudioService::~AudioService() { device_.reset(); }

astratto_handle AudioService::reserve(uint32_t kind) {
    if (kind == ASTRATTO_KIND_VOICE) return voices_.reserve();
    return ASTRATTO_NO_HANDLE;   // sounds are made with astratto_sound_create
}

bool AudioService::release(Engine&, astratto_handle h) {
    if (handleKind(h) == ASTRATTO_KIND_SOUND) return sounds_.release(h);      // a voice still playing it keeps its own reference
    if (handleKind(h) == ASTRATTO_KIND_VOICE) {
        VoiceRec* v = voices_.get(h);
        if (v && v->playing) { Mixer::Change c{}; c.kind = Mixer::Change::STOP; c.v.id = v->id; mixer_.post(c); ids_.erase(v->id); }
        return voices_.release(h);
    }
    return false;
}

uint32_t AudioService::live(uint32_t kind) const {
    if (kind == ASTRATTO_KIND_SOUND) return sounds_.live();
    if (kind == ASTRATTO_KIND_VOICE) return voices_.live();
    return 0;
}

int32_t AudioService::createSound(Engine& e, const astratto_sound_desc& d, astratto_handle* out) {
    if (d.samples == nullptr || d.frames < 2 || (d.channels != 1 && d.channels != 2) || d.rate < 1000 || d.rate > 384000) { e.fail("a sound needs 1 or 2 channels, a rate, and at least two frames"); return ASTRATTO_ERR_ARGUMENT; }
    std::shared_ptr<Sound> s(new Sound());
    s->channels = d.channels;
    if (static_cast<int>(d.rate) == rate_) { s->frames = d.frames; s->data.assign(d.samples, d.samples + static_cast<size_t>(d.frames) * d.channels); }
    else {
        // resampled once, linearly, to the mixer's rate
        const double ratio = static_cast<double>(d.rate) / rate_;
        const uint32_t n = static_cast<uint32_t>(static_cast<double>(d.frames - 1) / ratio) + 1;
        s->frames = n;
        s->data.resize(static_cast<size_t>(n) * d.channels);
        for (uint32_t i = 0; i < n; i++) {
            const double p = i * ratio;
            const uint32_t i0 = std::min(static_cast<uint32_t>(p), d.frames - 2);
            const float fr = static_cast<float>(p - i0);
            for (uint32_t c = 0; c < d.channels; c++) {
                const float a = d.samples[i0 * d.channels + c], b = d.samples[(i0 + 1) * d.channels + c];
                s->data[static_cast<size_t>(i) * d.channels + c] = a + (b - a) * fr;
            }
        }
    }
    for (float& v : s->data) if (!(v == v)) v = 0;
    const astratto_handle h = sounds_.reserve();
    sounds_.put(h, std::unique_ptr<std::shared_ptr<const Sound>>(new std::shared_ptr<const Sound>(s)));
    *out = h;
    return ASTRATTO_OK;
}

bool AudioService::render(float* out, uint32_t frames) {
    if (device_) return false;
    const int block = std::max(64, block_);
    uint32_t done = 0;
    while (done < frames) {
        const int n = static_cast<int>(std::min<uint32_t>(static_cast<uint32_t>(block), frames - done));
        mixer_.mix(out + static_cast<size_t>(done) * 2, n);
        done += static_cast<uint32_t>(n);
    }
    return true;
}

float AudioService::openness(const VoxelWorld& w, float x, float y, float z) const {
    // five lines from the source to the ear, a little apart: how many get through is how much of the sound does
    static const float OFF[5][2] = {{0, 0}, {0.45f, 0}, {-0.45f, 0}, {0, 0.45f}, {0, -0.45f}};
    int clear = 0;
    for (const auto& o : OFF) if (sightClear(w, x + o[0], y + o[1], z, lx_, ly_, lz_, false)) clear++;
    return static_cast<float>(clear) / 5.0f;
}

void AudioService::place(Engine&, VoiceRec& v, Mixer::Change& c, float hear) {
    float gl, gr, lp = 0, send = (v.flags & ASTRATTO_VOICE_REVERB) ? 0.6f : 0.0f;
    if (v.flags & ASTRATTO_VOICE_POSITIONAL) {
        const float dx = v.x - lx_, dy = v.y - ly_, dz = v.z - lz_;
        const float d = std::sqrt(dx * dx + dy * dy + dz * dz);
        float att = 0;
        if (d < v.range) {
            att = std::pow(1 - d / v.range, 1.6f) * std::min(1.0f, 6.0f / std::max(1.0f, d * 0.4f + 1));
            att = std::max(att, (1 - d / v.range) * 0.25f);
        }
        att *= hear;
        // listener right for yaw (yaw 0 faces -z): (cos, 0, -sin)
        const float rx = std::cos(lyaw_), rz = -std::sin(lyaw_), l = std::sqrt(dx * dx + dz * dz);
        const float pan = l < 1e-3f ? 0 : (dx * rx + dz * rz) / l * std::min(1.0f, d / 3.0f);
        const float g = v.gain * att, p = clampf(pan, -1, 1);
        gl = g * std::sqrt(0.5f * (1 - p)); gr = g * std::sqrt(0.5f * (1 + p));
        lp = std::max(clampf((d - 12) / std::max(40.0f, v.range * 0.5f), 0, 0.93f), std::min(0.9f, (1 - hear) * 0.95f));
        send = std::max(send, 0.25f + 0.5f * listenerIndoor_);
    } else {
        const float p = clampf(v.pan, -1, 1);
        gl = v.gain * std::sqrt(0.5f * (1 - p)); gr = v.gain * std::sqrt(0.5f * (1 + p));
    }
    c.v.tgl = gl; c.v.tgr = gr; c.v.tlp = lp; c.v.tsend = send;
}

void AudioService::listenerRoom(Engine& e) {
    const VoxelWorld* w = e.active();
    if (w == nullptr) { mixer_.room(1.1f, 0.22f, 0.45f, 0.02f); roomDecay_ = 1.1f; roomWet_ = 0.22f; listenerIndoor_ = 0.3f; return; }
    // fourteen directions: a ring of eight, four raised, straight up and straight down
    float fan[14][3];
    for (int i = 0; i < 8; i++) { const float a = static_cast<float>(i) * PI / 4; fan[i][0] = std::cos(a); fan[i][1] = 0; fan[i][2] = std::sin(a); }
    for (int i = 0; i < 4; i++) { const float a = static_cast<float>(i) * PI / 2 + PI / 4; fan[8 + i][0] = std::cos(a) * 0.707f; fan[8 + i][1] = 0.707f; fan[8 + i][2] = std::sin(a) * 0.707f; }
    fan[12][0] = 0; fan[12][1] = 1; fan[12][2] = 0; fan[13][0] = 0; fan[13][1] = -1; fan[13][2] = 0;
    float close = 0, total = 0, roof = 0, sum = 0, absorb = 0;
    int hits = 0;
    for (int k = 0; k < 14; k++) {
        const RayHit h = castRay(*w, lx_, ly_, lz_, fan[k][0], fan[k][1], fan[k][2], 60);
        const float wt = k == 12 ? 3.0f : 1.0f;
        total += wt;
        if (h.t < 0) continue;
        if (k != 13) { sum += std::min(h.t, 40.0f); hits++; absorb += static_cast<float>(w->defOf(h.block).absorb) / 255.0f; }
        if (h.t < 14) close += wt * (1 - h.t / 28);
        if (k == 12 && h.t < 9) roof = 1;
    }
    const float indoor = clampf(close / std::max(1.0f, total) * 1.25f * (0.45f + 0.55f * roof), 0, 1);
    const float mean = hits == 0 ? 40 : sum / static_cast<float>(hits), soft = hits == 0 ? 0.3f : absorb / static_cast<float>(hits);
    const float inDecay = (0.30f + 0.055f * mean) * (1.25f - soft), outDecay = 1.5f + 0.5f * std::min(1.0f, static_cast<float>(hits) / 10.0f);
    roomDecay_ = inDecay * indoor + outDecay * (1 - indoor);
    roomWet_ = 0.42f * indoor + (0.07f + 0.09f * std::min(1.0f, static_cast<float>(hits) / 10.0f)) * (1 - indoor);
    const float damp = 0.30f + 0.4f * soft + 0.2f * (1 - indoor);
    const float pre = std::min(0.09f, 0.006f + mean / 343.0f);
    listenerIndoor_ = indoor;
    mixer_.room(roomDecay_, roomWet_, damp, pre);
}

int AudioService::command(Engine& e, uint32_t type, const uint8_t* rec, uint32_t bytes) {
    auto& ids = ids_;
    switch (type) {
        case ASTRATTO_CMD_LISTENER: {
            astratto_cmd_listener c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            lx_ = c.x; ly_ = c.y; lz_ = c.z; lyaw_ = c.yaw;
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_VOICE_PLAY: {
            astratto_cmd_voice_play c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (!voices_.valid(c.voice)) { e.error(type, ASTRATTO_ERR_HANDLE, c.voice); return ASTRATTO_OK; }
            std::shared_ptr<const Sound>* s = sounds_.get(c.sound);
            if (!s) { e.error(type, ASTRATTO_ERR_HANDLE, c.sound); return ASTRATTO_OK; }
            std::unique_ptr<VoiceRec> v(new VoiceRec());
            v->id = nextId_++;
            v->sound = c.sound; v->bus = c.bus < ASTRATTO_BUSES ? c.bus : 0; v->flags = c.flags;
            v->gain = c.gain; v->pitch = clampf(c.pitch > 0 ? c.pitch : 1, 0.05f, 4); v->pan = c.pan;
            v->x = c.x; v->y = c.y; v->z = c.z; v->range = c.range > 0 ? c.range : 30;
            v->hear = (v->flags & ASTRATTO_VOICE_OCCLUDE) && e.active() ? 0.15f + 0.85f * openness(*e.active(), v->x, v->y, v->z) : 1.0f;
            v->playing = true;
            Mixer::Change ch{};
            ch.kind = Mixer::Change::PLAY;
            ch.v.id = v->id; ch.v.sound = *s; ch.v.pitch = v->pitch; ch.v.bus = v->bus; ch.v.loop = (c.flags & ASTRATTO_VOICE_LOOP) != 0;
            ch.v.fade = c.fade_in > 0 ? 0.0f : 1.0f; ch.v.fadeTarget = 1; ch.v.fadeSpeed = c.fade_in > 0 ? 1.0f / (c.fade_in * static_cast<float>(rate_)) : 0;
            place(e, *v, ch, v->hear);
            // a positional one-shot nobody would hear is not played at all (the game's rule)
            if ((v->flags & ASTRATTO_VOICE_POSITIONAL) && !ch.v.loop && ch.v.tgl + ch.v.tgr < 0.012f) {
                voices_.put(c.voice, std::move(v));
                astratto_evt_body ev{}; ev.handle = c.voice; e.emit(ev, ASTRATTO_EVT_VOICE_ENDED);
                voices_.release(c.voice);
                return ASTRATTO_OK;
            }
            ids[v->id] = c.voice;
            mixer_.post(ch);
            voices_.put(c.voice, std::move(v));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_VOICE_SET: {
            astratto_cmd_voice_set c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            VoiceRec* v = voices_.get(c.voice);
            if (!v) { e.error(type, ASTRATTO_ERR_HANDLE, c.voice); return ASTRATTO_OK; }
            if (c.what & ASTRATTO_SET_GAIN) v->gain = c.gain;
            if (c.what & ASTRATTO_SET_PITCH) v->pitch = clampf(c.pitch, 0.05f, 4);
            if (c.what & ASTRATTO_SET_POSITION) { v->x = c.x; v->y = c.y; v->z = c.z; }
            Mixer::Change ch{};
            ch.kind = Mixer::Change::SET; ch.v.id = v->id; ch.what = c.what; ch.v.pitch = v->pitch; ch.fadeTo = c.fade_to; ch.fadeTime = c.fade_time;
            place(e, *v, ch, v->hear);
            mixer_.post(ch);
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_VOICE_STOP: {
            astratto_cmd_voice_stop c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            VoiceRec* v = voices_.get(c.voice);
            if (!v) { e.error(type, ASTRATTO_ERR_HANDLE, c.voice); return ASTRATTO_OK; }
            Mixer::Change ch{};
            ch.kind = Mixer::Change::STOP; ch.v.id = v->id; ch.fadeTime = c.fade;
            mixer_.post(ch);
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_AUDIO_MIX: {
            astratto_cmd_audio_mix c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            mixer_.levels(clampf(c.master, 0, 2), c.muted != 0, c.bus, c.bus_muted);
            return ASTRATTO_OK;
        }
        default: return -1;
    }
}

void AudioService::step(Engine& e, float dt) {
    auto& ids = ids_;
    const VoxelWorld* w = e.active();
    // what stands between each positional sound and the ear, worked out across the workers, then glided toward
    order_.clear();
    voices_.each([&](astratto_handle h, const VoiceRec& v) { if (v.playing && (v.flags & ASTRATTO_VOICE_POSITIONAL)) order_.push_back(h); });
    hear_.assign(order_.size(), 1.0f);
    if (w != nullptr) e.pool().parallelFor(order_.size(), [&](size_t i, unsigned) {
        const VoiceRec& v = *voices_.get(order_[i]);
        if (v.flags & ASTRATTO_VOICE_OCCLUDE) hear_[i] = 0.15f + 0.85f * openness(*w, v.x, v.y, v.z);
    });
    const float k = std::min(1.0f, dt * 6);
    for (size_t i = 0; i < order_.size(); i++) {
        VoiceRec& v = *voices_.get(order_[i]);
        v.hear += (hear_[i] - v.hear) * k;
        Mixer::Change ch{};
        ch.kind = Mixer::Change::SET; ch.v.id = v.id; ch.v.pitch = v.pitch;
        place(e, v, ch, v.hear);
        mixer_.post(ch);
    }
    roomTimer_ -= dt;
    if (roomTimer_ <= 0) { roomTimer_ = 0.25f; listenerRoom(e); }
    // voices that ended are released by the engine, after their event
    ended_.clear();
    mixer_.takeEnded(ended_);
    for (uint64_t id : ended_) {
        auto it = ids.find(id);
        if (it == ids.end()) continue;
        const astratto_handle h = it->second;
        ids.erase(it);
        if (!voices_.valid(h)) continue;
        astratto_evt_body ev{}; ev.handle = h;
        e.emit(ev, ASTRATTO_EVT_VOICE_ENDED);
        voices_.release(h);
    }
}

bool AudioService::snapshot(Engine&, uint32_t kind, std::vector<uint8_t>& out) {
    if (kind != ASTRATTO_SNAPSHOT_AUDIO) return false;
    astratto_audio_stats s{};
    s.struct_size = sizeof(s);
    s.device = device_ ? 1u : 0u;
    s.voices = mixer_.voiceCount.load(); s.sounds = sounds_.live();
    s.frames_mixed = mixer_.framesMixed.load();
    for (int b = 0; b < ASTRATTO_BUSES; b++) s.peak[b] = mixer_.busPeak[b];
    s.out_peak = mixer_.outPeak.load();
    s.room_decay = roomDecay_; s.room_wet = roomWet_; s.listener_indoor = listenerIndoor_;
    s.limited = mixer_.limited.load(); s.underruns = device_ ? device_->underruns.load() : 0u;
    s.mix_ms = mixer_.mixMs.load();
    out.resize(sizeof(s));
    std::memcpy(out.data(), &s, sizeof(s));
    return true;
}

}  // namespace astratto

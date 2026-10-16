// Astratto Engine - the audio output device. Windows: WinMM (waveOut), four blocks queued, a thread of the engine's own
// that mixes each block as the device hands one back. Elsewhere there is no device yet: the engine renders on request
// (astratto_audio_render) and whoever owns the output plays it.
#include "audio/audio_service.h"

#if defined(_WIN32)
#  define WIN32_LEAN_AND_MEAN
#  define NOMINMAX
#  include <windows.h>
#  include <mmsystem.h>
#endif

#include <cmath>
#include <vector>

namespace astratto {

#if defined(_WIN32)
namespace {

class WinDevice : public AudioDevice {
public:
    WinDevice(Mixer* m, int rate, int block) : mixer_(m), block_(block) {
        event_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        WAVEFORMATEX f{};
        f.wFormatTag = WAVE_FORMAT_PCM; f.nChannels = 2; f.nSamplesPerSec = static_cast<DWORD>(rate); f.wBitsPerSample = 16;
        f.nBlockAlign = 4; f.nAvgBytesPerSec = static_cast<DWORD>(rate) * 4;
        if (waveOutOpen(&out_, WAVE_MAPPER, &f, reinterpret_cast<DWORD_PTR>(event_), 0, CALLBACK_EVENT) != MMSYSERR_NOERROR) { out_ = nullptr; return; }
        for (int i = 0; i < BUFFERS; i++) {
            pcm_[i].assign(static_cast<size_t>(block) * 2, 0);
            hdr_[i] = WAVEHDR{};
            hdr_[i].lpData = reinterpret_cast<LPSTR>(pcm_[i].data());
            hdr_[i].dwBufferLength = static_cast<DWORD>(block * 4);
            waveOutPrepareHeader(out_, &hdr_[i], sizeof(WAVEHDR));
        }
        mix_.assign(static_cast<size_t>(block) * 2, 0);
        running_ = true;
        thread_ = std::thread([this] { run(); });
    }

    ~WinDevice() override {
        running_ = false;
        if (event_) SetEvent(event_);
        if (thread_.joinable()) thread_.join();
        if (out_) {
            waveOutReset(out_);
            for (int i = 0; i < BUFFERS; i++) waveOutUnprepareHeader(out_, &hdr_[i], sizeof(WAVEHDR));
            waveOutClose(out_);
        }
        if (event_) CloseHandle(event_);
    }

    bool ok() const { return out_ != nullptr; }

private:
    static constexpr int BUFFERS = 4;

    void fill(int i) {
        mixer_->mix(mix_.data(), block_);
        for (size_t k = 0; k < mix_.size(); k++) {
            const float s = mix_[k];
            pcm_[i][k] = static_cast<int16_t>(std::lround((s > 1 ? 1 : s < -1 ? -1 : s) * 32000.0f));
        }
        waveOutWrite(out_, &hdr_[i], sizeof(WAVEHDR));
    }

    void run() {
        SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
        for (int i = 0; i < BUFFERS; i++) fill(i);
        while (running_) {
            WaitForSingleObject(event_, 100);
            if (!running_) break;
            int done = 0;
            for (int i = 0; i < BUFFERS; i++) if (hdr_[i].dwFlags & WHDR_DONE) { hdr_[i].dwFlags &= ~static_cast<DWORD>(WHDR_DONE); fill(i); done++; }
            if (done == BUFFERS) underruns++;        // every block had run dry: the device waited on the mixer
        }
    }

    Mixer* mixer_;
    int block_;
    HWAVEOUT out_ = nullptr;
    HANDLE event_ = nullptr;
    WAVEHDR hdr_[BUFFERS];
    std::vector<int16_t> pcm_[BUFFERS];
    std::vector<float> mix_;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

}  // namespace

std::unique_ptr<AudioDevice> AudioDevice::open(Mixer* mixer, int rate, int block) {
    std::unique_ptr<WinDevice> d(new WinDevice(mixer, rate, block));
    if (!d->ok()) return nullptr;
    return std::unique_ptr<AudioDevice>(d.release());
}
#else
std::unique_ptr<AudioDevice> AudioDevice::open(Mixer*, int, int) { return nullptr; }
#endif

}  // namespace astratto

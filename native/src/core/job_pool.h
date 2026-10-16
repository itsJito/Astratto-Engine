// Astratto Engine - worker threads for work that splits into independent pieces.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <exception>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace astratto {

/**
 * A fixed set of threads that sleep until there is a batch to share out. parallelFor hands every index of a batch
 * to exactly one worker and returns when all of them are done; the calling thread is worker 0 and works too.
 * Which worker takes which index is not fixed - so a piece of work must depend on its index only, never on its
 * worker - and that is what keeps a batch's results the same however many threads there are.
 */
class JobPool {
public:
    explicit JobPool(unsigned extraThreads) {
        for (unsigned i = 0; i < extraThreads; i++) threads_.emplace_back([this, i] { run(i + 1); });
    }

    ~JobPool() {
        { std::lock_guard<std::mutex> lock(mutex_); quit_ = true; batch_++; }
        wake_.notify_all();
        for (std::thread& t : threads_) t.join();
    }

    JobPool(const JobPool&) = delete;
    JobPool& operator=(const JobPool&) = delete;

    /** Workers, counting the caller's own thread. */
    unsigned workers() const { return static_cast<unsigned>(threads_.size()) + 1; }

    void parallelFor(size_t count, const std::function<void(size_t index, unsigned worker)>& fn) {
        if (count == 0) return;
        if (threads_.empty() || count == 1) { for (size_t i = 0; i < count; i++) fn(i, 0); return; }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            fn_ = &fn; count_ = count; next_.store(0); active_ = static_cast<unsigned>(threads_.size());
            batch_++;
        }
        wake_.notify_all();
        drain(0);
        std::exception_ptr failure;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            done_.wait(lock, [this] { return active_ == 0; });
            fn_ = nullptr;
            failure = failure_;
            failure_ = nullptr;
        }
        // every worker has left the batch before anything it threw goes on up the caller's thread (out of memory, say)
        if (failure) std::rethrow_exception(failure);
    }

private:
    void drain(unsigned worker) {
        for (;;) {
            const size_t i = next_.fetch_add(1);
            if (i >= count_) return;
            try { (*fn_)(i, worker); }
            catch (...) {
                // a throw must not leave a worker's thread (that ends the process): the first is kept for the caller,
                // and the rest of the batch is abandoned
                std::lock_guard<std::mutex> lock(mutex_);
                if (!failure_) failure_ = std::current_exception();
                next_.store(count_);
                return;
            }
        }
    }

    void run(unsigned worker) {
        unsigned long long seen = 0;
        for (;;) {
            {
                std::unique_lock<std::mutex> lock(mutex_);
                wake_.wait(lock, [&] { return batch_ != seen; });
                seen = batch_;
                if (quit_) return;
            }
            drain(worker);
            {
                std::lock_guard<std::mutex> lock(mutex_);
                active_--;
            }
            done_.notify_one();
        }
    }

    std::vector<std::thread> threads_;
    std::mutex mutex_;
    std::condition_variable wake_, done_;
    const std::function<void(size_t, unsigned)>* fn_ = nullptr;
    size_t count_ = 0;
    std::atomic<size_t> next_{0};
    unsigned active_ = 0;
    unsigned long long batch_ = 0;
    bool quit_ = false;
    std::exception_ptr failure_;
};

}  // namespace astratto

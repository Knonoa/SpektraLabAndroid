// parallel.hpp -- a tiny persistent worker pool for row-parallel loops.
//
// The pipeline issues ~100 parallel loops per render; spawning fresh
// std::threads for each one cost more than some of the loops themselves on
// a phone. One pool, created on first use, sized to the big cores.
#pragma once

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace spk {

class ThreadPool {
public:
    static ThreadPool& instance() {
        static ThreadPool pool;
        return pool;
    }

    int size() const { return (int)workers_.size() + 1; }

    // Runs job(begin, end) over [0, n) split into contiguous chunks; the
    // calling thread takes a chunk too. Blocks until all chunks finish.
    // Nested calls from inside a job run serially (no deadlock).
    void run(int n, const std::function<void(int, int)>& job, int min_chunk = 16) {
        if (n <= 0) return;
        const int threads = size();
        if (in_job_ || threads <= 1 || n < 2 * min_chunk) {
            job(0, n);
            return;
        }
        std::unique_lock<std::mutex> serial(run_mutex_);
        const int chunks = std::min(threads, std::max(1, n / min_chunk));
        {
            std::lock_guard<std::mutex> lk(m_);
            job_ = &job;
            n_ = n;
            chunks_ = chunks;
            next_.store(0);
            pending_ = chunks;
            generation_++;
        }
        cv_.notify_all();
        work();
        std::unique_lock<std::mutex> lk(m_);
        done_cv_.wait(lk, [&] { return pending_ == 0; });
        job_ = nullptr;
    }

private:
    ThreadPool() {
        int hw = (int)std::thread::hardware_concurrency();
        int n = std::max(1, std::min(hw, 8)) - 1;
        for (int i = 0; i < n; i++) workers_.emplace_back([this] { loop(); });
    }
    ~ThreadPool() {
        {
            std::lock_guard<std::mutex> lk(m_);
            stop_ = true;
            generation_++;
        }
        cv_.notify_all();
        for (auto& t : workers_) t.join();
    }

    void work() {
        in_job_ = true;
        for (;;) {
            int c = next_.fetch_add(1);
            if (c >= chunks_) break;
            int b = (int)((long long)n_ * c / chunks_);
            int e = (int)((long long)n_ * (c + 1) / chunks_);
            (*job_)(b, e);
            std::lock_guard<std::mutex> lk(m_);
            if (--pending_ == 0) done_cv_.notify_all();
        }
        in_job_ = false;
    }

    void loop() {
        unsigned seen = 0;
        for (;;) {
            {
                std::unique_lock<std::mutex> lk(m_);
                cv_.wait(lk, [&] { return stop_ || generation_ != seen; });
                if (stop_) return;
                seen = generation_;
                if (!job_) continue;
            }
            work();
        }
    }

    std::vector<std::thread> workers_;
    std::mutex m_, run_mutex_;
    std::condition_variable cv_, done_cv_;
    const std::function<void(int, int)>* job_ = nullptr;
    int n_ = 0, chunks_ = 0, pending_ = 0;
    std::atomic<int> next_{0};
    unsigned generation_ = 0;
    bool stop_ = false;
    static thread_local bool in_job_;
};

inline thread_local bool ThreadPool::in_job_ = false;

inline void parallel_for(int n, const std::function<void(int, int)>& job, int min_chunk = 16) {
    ThreadPool::instance().run(n, job, min_chunk);
}

}  // namespace spk

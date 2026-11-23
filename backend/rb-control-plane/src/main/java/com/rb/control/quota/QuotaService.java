package com.rb.control.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class QuotaService {

    private final int maxConcurrent;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public QuotaService(@Value("${rb.quota.max-concurrent-per-user}") int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public boolean tryAcquire(String userId) {
        AtomicInteger c = counters.computeIfAbsent(userId, k -> new AtomicInteger(0));
        int v = c.incrementAndGet();
        if (v > maxConcurrent) {
            c.decrementAndGet();
            return false;
        }
        return true;
    }

    public void release(String userId) {
        AtomicInteger c = counters.get(userId);
        if (c == null) return;
        int v = c.decrementAndGet();
        if (v <= 0) counters.remove(userId);
    }

    public int current(String userId) {
        AtomicInteger c = counters.get(userId);
        return c == null ? 0 : c.get();
    }

    public void forceReset(String userId) {
        counters.remove(userId);
        System.out.println("[quota] 用户 " + userId + " 配额已清零");
    }
}
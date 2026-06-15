package com.example.FieldFinder.service.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OversellWithoutLockTest {

    private boolean buyOneNoLock(AtomicInteger stock) throws InterruptedException {
        int avail = stock.get();        // ĐỌC
        Thread.sleep(15);               // cửa sổ race
        if (avail < 1) return false;    // tồn không đủ -> từ chối
        stock.set(avail - 1);           // GHI
        return true;
    }

    @Test
    void oversell_happens_when_no_lock() throws InterruptedException {
        int threads = 20;
        AtomicInteger stock = new AtomicInteger(1);     // tồn chỉ còn 1

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (buyOneNoLock(stock)) success.incrementAndGet();
                    else failure.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        int initialStock = 1;
        System.out.printf(
                "[KHONG KHOA] %d thread mua unit cuoi (ton=%d) -> %d don thanh cong / %d truot | ban vuot %d cai (OVERSELL!)%n",
                threads, initialStock, success.get(), failure.get(), success.get() - initialStock);

        assertThat(finished).isTrue();
        // Thiếu khóa -> nhiều thread cùng đọc tồn=1 -> bán vượt số tồn.
        assertThat(success.get())
                .as("oversell: số đơn thắng vượt quá tồn (%d)", initialStock)
                .isGreaterThan(initialStock);
    }
}

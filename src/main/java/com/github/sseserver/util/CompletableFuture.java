package com.github.sseserver.util;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class CompletableFuture<T> extends java.util.concurrent.CompletableFuture<T> {
    private long startTimestamp = System.currentTimeMillis();
    private long endTimestamp;

    public static <R> void join(List<? extends java.util.concurrent.CompletableFuture> list, java.util.concurrent.CompletableFuture<R> end, Supplier<R> callback) {
        int size = list.size();
        AtomicInteger count = new AtomicInteger();
        for (java.util.concurrent.CompletableFuture f : list) {
            f.handle((r, t) -> {
                if (count.incrementAndGet() == size) {
                    end.complete(callback.get());
                }
                return null;
            });
        }
    }

    @Override
    public boolean complete(T value) {
        boolean complete = super.complete(value);
        endTimestamp = System.currentTimeMillis();
        return complete;
    }

    public long getCostMs() {
        return endTimestamp - startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

}

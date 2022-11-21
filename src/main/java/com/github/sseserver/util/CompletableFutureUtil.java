package com.github.sseserver.util;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class CompletableFutureUtil {

    public static <R> void join(List<? extends CompletableFuture> list, CompletableFuture<R> end, Supplier<R> callback) {
        int size = list.size();
        AtomicInteger count = new AtomicInteger();
        for (CompletableFuture f : list) {
            f.handle((r, t) -> {
                if (count.incrementAndGet() == size) {
                    end.complete(callback.get());
                }
                return null;
            });
        }
    }
}

package com.github.sseserver.util;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class CompletableFuture<T> extends java.util.concurrent.CompletableFuture<T> {
    private final long startTimestamp = System.currentTimeMillis();
    private volatile long endTimestamp;
    private volatile String exceptionallyPrefix;

    public static <R> void join(List<? extends java.util.concurrent.CompletableFuture> list, java.util.concurrent.CompletableFuture<R> end, Supplier<R> callback) {
        int size = list.size();
        if (size == 0) {
            end.complete(callback.get());
        } else {
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
    }

    @Override
    public boolean complete(T value) {
        boolean complete = super.complete(value);
        this.endTimestamp = System.currentTimeMillis();
        return complete;
    }

    @Override
    public boolean completeExceptionally(Throwable ex) {
        boolean b = super.completeExceptionally(ex);
        if (b) {
            this.endTimestamp = System.currentTimeMillis();
        }
        return b;
    }

    public void setExceptionallyPrefix(String exceptionallyPrefix) {
        this.exceptionallyPrefix = exceptionallyPrefix;
    }

    public String getExceptionallyPrefix() {
        return exceptionallyPrefix;
    }

    public T block() {
        try {
            return super.get();
        } catch (InterruptedException e) {
            LambdaUtil.sneakyThrows(e);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                LambdaUtil.sneakyThrows(e);
            } else {
                ExecutionException exception;
                String exceptionallyPrefix = this.exceptionallyPrefix;
                if (exceptionallyPrefix != null) {
                    exception = new ExecutionException(
                            exceptionallyPrefix + "\n" + cause,
                            cause);
                } else {
                    exception = new ExecutionException(cause);
                }
                LambdaUtil.sneakyThrows(exception);
            }
            return null;
        }
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

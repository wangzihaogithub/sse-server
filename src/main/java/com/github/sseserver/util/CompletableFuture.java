package com.github.sseserver.util;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class CompletableFuture<T> extends java.util.concurrent.CompletableFuture<T> {
    private final long startTimestamp = System.currentTimeMillis();
    private volatile long endTimestamp;
    private volatile String exceptionallyPrefix;

    public static <U> CompletableFuture<U> completedFuture(U value) {
        CompletableFuture<U> future = new CompletableFuture<>();
        future.complete(value);
        return future;
    }

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
        if (complete) {
            this.endTimestamp = System.currentTimeMillis();
        }
        return complete;
    }

    @Override
    public boolean completeExceptionally(Throwable ex) {
        boolean complete = super.completeExceptionally(ex);
        if (complete) {
            this.endTimestamp = System.currentTimeMillis();
        }
        return complete;
    }

    public String getExceptionallyPrefix() {
        return exceptionallyPrefix;
    }

    public void setExceptionallyPrefix(String exceptionallyPrefix) {
        this.exceptionallyPrefix = exceptionallyPrefix;
    }

    public T block() {
        try {
            return super.get();
        } catch (InterruptedException e) {
            LambdaUtil.sneakyThrows(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            ExecutionException throwsException;
            if (cause != null) {
                String exceptionallyPrefix = this.exceptionallyPrefix;
                if (exceptionallyPrefix != null) {
                    throwsException = new ExecutionException(exceptionallyPrefix + "\n" + cause, cause);
                } else {
                    throwsException = new ExecutionException(cause);
                }
            } else {
                throwsException = e;
            }
            LambdaUtil.sneakyThrows(throwsException);
        }
        return null;
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

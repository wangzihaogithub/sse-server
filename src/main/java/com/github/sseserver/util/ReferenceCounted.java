package com.github.sseserver.util;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

public class ReferenceCounted<T> implements AutoCloseable {
    private final T value;
    private final List<Consumer<T>> destroyFinishCallbackList = new LinkedList<>();

    private volatile int refCnt;
    private volatile boolean destroy;

    public ReferenceCounted(T value) {
        this.value = value;
        this.refCnt = 0;
    }

    public ReferenceCounted<T> open() {
        synchronized (this) {
            if (this.destroy) {
                throw new IllegalStateException("destroy");
            }
            refCnt++;
            return this;
        }
    }

    public void destroy(Consumer<T> destroyFinishCallback) {
        synchronized (this) {
            this.destroy = true;
            if (refCnt == 0) {
                destroyFinishCallback.accept(value);
            } else {
                this.destroyFinishCallbackList.add(destroyFinishCallback);
            }
        }
    }

    public int refCnt() {
        return refCnt;
    }

    public T get() {
        return value;
    }

    @Override
    public void close() {
        synchronized (this) {
            int refCnt = --this.refCnt;
            if (this.destroy && refCnt == 0) {
                for (Consumer<T> consumer : destroyFinishCallbackList) {
                    consumer.accept(value);
                }
            }
        }
    }
}
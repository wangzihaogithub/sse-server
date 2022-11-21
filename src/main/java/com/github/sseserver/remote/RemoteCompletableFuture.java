package com.github.sseserver.remote;

import java.util.concurrent.CompletableFuture;

public class RemoteCompletableFuture<T> extends CompletableFuture<T> {
    private RemoteConnectionService service;
    private long startTimestamp = System.currentTimeMillis();
    private long endTimestamp;

    public long getCostMs() {
        return endTimestamp - startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public RemoteConnectionService getService() {
        return service;
    }

    public void setService(RemoteConnectionService service) {
        this.service = service;
    }
}

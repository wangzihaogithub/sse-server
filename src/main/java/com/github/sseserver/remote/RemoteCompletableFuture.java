package com.github.sseserver.remote;

import com.github.sseserver.util.CompletableFuture;

public class RemoteCompletableFuture<T> extends CompletableFuture<T> {
    private RemoteConnectionService service;

    public RemoteConnectionService getService() {
        return service;
    }

    public void setService(RemoteConnectionService service) {
        this.service = service;
    }
}

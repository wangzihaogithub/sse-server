package com.github.sseserver.remote;

import com.github.sseserver.util.CompletableFuture;

public class RemoteCompletableFuture<T, CLIENT> extends CompletableFuture<T> {
    private CLIENT client;

    public CLIENT getClient() {
        return client;
    }

    public void setClient(CLIENT client) {
        this.client = client;
    }
}

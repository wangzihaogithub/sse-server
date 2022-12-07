package com.github.sseserver.remote;

import com.github.sseserver.util.CompletableFuture;

import java.net.URL;
import java.util.List;

public class ClusterCompletableFuture<T, CLIENT> extends CompletableFuture<T> {
    private final List<URL> fromRemoteUrlList;
    private final CLIENT client;

    public ClusterCompletableFuture(List<URL> fromRemoteUrlList, CLIENT client) {
        this.fromRemoteUrlList = fromRemoteUrlList;
        this.client = client;
    }

    public List<URL> getFromRemoteUrlList() {
        return fromRemoteUrlList;
    }

    public CLIENT getClient() {
        return client;
    }
}

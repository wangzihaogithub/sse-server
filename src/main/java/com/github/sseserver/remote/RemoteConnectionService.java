package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;

import java.io.Closeable;
import java.io.Serializable;
import java.net.URL;

public interface RemoteConnectionService extends ConnectionQueryService, SendService<RemoteCompletableFuture<Integer, RemoteConnectionService>>, Closeable {

    URL getRemoteUrl();

    @Override
    void close();

    /* disconnect */

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByUserId(Serializable userId);

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByAccessToken(String accessToken);

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByConnectionId(Long connectionId);

}

package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;

public interface RemoteConnectionService extends ConnectionQueryService, SendService<RemoteCompletableFuture<Integer>>, Closeable {

    URL getRemoteUrl();

    @Override
    void close() ;

    /* disconnect */

    RemoteCompletableFuture<Integer> disconnectByUserId(Serializable userId);

    RemoteCompletableFuture<Integer> disconnectByAccessToken(String accessToken);

    RemoteCompletableFuture<Integer> disconnectByConnectionId(Long connectionId);

}

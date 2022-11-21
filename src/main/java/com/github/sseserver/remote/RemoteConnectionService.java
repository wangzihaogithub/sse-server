package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;

import java.io.Serializable;

public interface RemoteConnectionService extends ConnectionQueryService, SendService<RemoteCompletableFuture<Integer>> {

    /* disconnect */

    RemoteCompletableFuture<Integer> disconnectByUserId(Serializable userId);

    RemoteCompletableFuture<Integer> disconnectByAccessToken(String accessToken);

    RemoteCompletableFuture<Integer> disconnectByConnectionId(Long connectionId);

}

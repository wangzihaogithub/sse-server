package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.util.ReferenceCounted;

import java.io.Serializable;
import java.util.List;
import java.util.function.Supplier;

public interface ClusterConnectionService extends ConnectionQueryService, SendService<ClusterCompletableFuture<Integer, ClusterConnectionService>> {

    static ClusterConnectionService newInstance(Supplier<LocalConnectionService> localSupplier,
                                                Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier) {
        return new ClusterConnectionServiceImpl(localSupplier, remoteSupplier);
    }

    /* disconnect */

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByUserId(Serializable userId);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByAccessToken(String accessToken);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionId(Long connectionId);

}

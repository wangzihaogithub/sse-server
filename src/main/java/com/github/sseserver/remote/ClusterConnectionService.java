package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.ReferenceCounted;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface ClusterConnectionService extends ConnectionQueryService, SendService<ClusterCompletableFuture<Integer, ClusterConnectionService>> {

    static ClusterConnectionService newInstance(Supplier<Optional<LocalConnectionService>> localSupplier,
                                                Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier) {
        return new ClusterConnectionServiceImpl(localSupplier, remoteSupplier);
    }

    /* getUsers */

    <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersAsync(SseServerProperties.AutoType autoType);

    /* getConnection */

    <ACCESS_USER> ClusterCompletableFuture<List<ConnectionDTO<ACCESS_USER>>, ClusterConnectionService> getConnectionDTOAllAsync(SseServerProperties.AutoType autoType);

    /* disconnect */

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByUserId(Serializable userId);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByAccessToken(String accessToken);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionId(Long connectionId);

}

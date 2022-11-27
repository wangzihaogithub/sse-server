package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;

import java.io.Serializable;
import java.util.List;
import java.util.function.Supplier;

public interface DistributedConnectionService extends ConnectionQueryService, SendService<DistributedCompletableFuture<Integer>> {

    static DistributedConnectionService newInstance(Supplier<ServiceDiscoveryService> discoverySupplier,
                                                    Supplier<LocalConnectionService> provider) {
        return new DistributedConnectionServiceImpl(provider, discoverySupplier);
    }

    /* disconnect */

    List<RemoteConnectionService> getRemoteConnectionServiceList();

    DistributedCompletableFuture<Integer> disconnectByUserId(Serializable userId);

    DistributedCompletableFuture<Integer> disconnectByAccessToken(String accessToken);

    DistributedCompletableFuture<Integer> disconnectByConnectionId(Long connectionId);

}

package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.qos.QosCompletableFuture;
import com.github.sseserver.util.ReferenceCounted;
import io.netty.buffer.ByteBuf;

import java.io.Serializable;
import java.util.List;
import java.util.function.Supplier;

public interface DistributedConnectionService extends ConnectionQueryService, SendService<DistributedCompletableFuture<Integer>> {

    static DistributedConnectionService newInstance(Supplier<ServiceDiscoveryService> discoverySupplier,
                                                    Supplier<LocalConnectionService> provider) {
        return new DistributedConnectionServiceImpl(provider, discoverySupplier);
    }

    /* QOS */

    <ACCESS_USER> SendService<QosCompletableFuture<ACCESS_USER>> atLeastOnce();

    /* disconnect */

    ReferenceCounted<List<RemoteConnectionService>> getRemoteServiceListRef();

    DistributedCompletableFuture<Integer> disconnectByUserId(Serializable userId);

    DistributedCompletableFuture<Integer> disconnectByAccessToken(String accessToken);

    DistributedCompletableFuture<Integer> disconnectByConnectionId(Long connectionId);

}

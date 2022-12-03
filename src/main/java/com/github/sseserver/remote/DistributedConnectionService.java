package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.util.ReferenceCounted;

import java.io.Serializable;
import java.util.List;
import java.util.function.Supplier;

public interface DistributedConnectionService extends ConnectionQueryService, SendService<DistributedCompletableFuture<Integer>> {

    static DistributedConnectionService newInstance(Supplier<LocalConnectionService> localSupplier,
                                                    Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier) {
        return new DistributedConnectionServiceImpl(localSupplier, remoteSupplier);
    }

    /* disconnect */

    DistributedCompletableFuture<Integer> disconnectByUserId(Serializable userId);

    DistributedCompletableFuture<Integer> disconnectByAccessToken(String accessToken);

    DistributedCompletableFuture<Integer> disconnectByConnectionId(Long connectionId);

}

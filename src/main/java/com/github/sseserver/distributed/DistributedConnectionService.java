package com.github.sseserver.distributed;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;

import java.io.Serializable;

public interface DistributedConnectionService extends ConnectionQueryService, SendService<DistributedCompletableFuture<Integer>> {

    /* disconnect */

    DistributedCompletableFuture<Integer> disconnectByUserId(Serializable userId);

    DistributedCompletableFuture<Integer> disconnectByAccessToken(String accessToken);

    DistributedCompletableFuture<Integer> disconnectByConnectionId(Long connectionId);

}

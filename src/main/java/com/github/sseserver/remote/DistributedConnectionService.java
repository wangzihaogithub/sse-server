package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;

import java.io.Serializable;
import java.util.List;

public interface DistributedConnectionService extends ConnectionQueryService, SendService<DistributedCompletableFuture<Integer>> {

    static DistributedConnectionService newInstance(String localConnectionServiceBeanName,
                                                    BeanFactory beanFactory, Environment environment) {
        ServiceDiscoveryService discoveryService = ServiceDiscoveryService.newInstance(localConnectionServiceBeanName, beanFactory, environment);
        return new DistributedConnectionServiceImpl(
                () -> beanFactory.getBean(localConnectionServiceBeanName, LocalConnectionService.class),
                discoveryService);
    }

    /* disconnect */

    List<RemoteConnectionService> getRemoteConnectionServiceList();

    DistributedCompletableFuture<Integer> disconnectByUserId(Serializable userId);

    DistributedCompletableFuture<Integer> disconnectByAccessToken(String accessToken);

    DistributedCompletableFuture<Integer> disconnectByConnectionId(Long connectionId);

}

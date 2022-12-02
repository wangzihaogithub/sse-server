package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.ReferenceCounted;

import java.util.List;

public interface ServiceDiscoveryService extends ServiceAuthenticator {

    static NacosServiceDiscoveryService newInstance(String groupName,
                                                    SseServerProperties.Remote remote) {
        SseServerProperties.Remote.Nacos nacos = remote.getNacos();
        if (nacos != null) {
            return new NacosServiceDiscoveryService(
                    groupName,
                    nacos.getServiceName(),
                    nacos.getClusterName(),
                    nacos.buildProperties());
        } else {
            return null;
        }
    }

    void registerInstance(String ip, int port);

    ReferenceCounted<List<RemoteConnectionService>> getConnectionServiceListRef();

    ReferenceCounted<List<RemoteMessageRepository>> getMessageRepositoryListRef();
}

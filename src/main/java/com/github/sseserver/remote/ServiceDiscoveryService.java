package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.ReferenceCounted;
import com.sun.net.httpserver.HttpPrincipal;

import java.util.List;

public interface ServiceDiscoveryService {

    static NacosServiceDiscoveryService newInstance(String groupName, String applicationName,
                                                    SseServerProperties.Remote remote) {
        SseServerProperties.Remote.Nacos nacos = remote.getNacos();
        if (nacos != null) {
            return new NacosServiceDiscoveryService(
                    groupName,
                    applicationName,
                    nacos.getServiceName(),
                    nacos.getClusterName(),
                    nacos.buildProperties());
        } else {
            return null;
        }
    }

    HttpPrincipal login(String authorization);

    void registerInstance(String ip, int port);

    ReferenceCounted<List<RemoteConnectionService>> getConnectionServiceListRef();

    ReferenceCounted<List<RemoteMessageRepository>> getMessageRepositoryListRef();
}

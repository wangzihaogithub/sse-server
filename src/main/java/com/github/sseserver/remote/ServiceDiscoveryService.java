package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.ReferenceCounted;
import com.sun.net.httpserver.HttpPrincipal;

import java.util.List;
import java.util.Objects;

public interface ServiceDiscoveryService {

    static ServiceDiscoveryService newInstance(String groupName,
                                               SseServerProperties.Remote remote) {
        SseServerProperties.Remote.Nacos nacos = remote.getNacos();
        if (Objects.toString(nacos.getServerAddr(), "").length() > 0) {
            return new NacosServiceDiscoveryService(
                    groupName,
                    nacos.getServiceName(),
                    nacos.getClusterName(),
                    nacos.buildProperties(),
                    remote.getAutoType());
        } else {
            throw new IllegalArgumentException("ServiceDiscoveryService newInstance fail! remote discovery url is empty!");
        }
    }

    HttpPrincipal login(String authorization);

    void registerInstance(String ip, int port);

    ReferenceCounted<List<RemoteConnectionService>> getConnectionServiceListRef();

    ReferenceCounted<List<RemoteMessageRepository>> getMessageRepositoryListRef();
}

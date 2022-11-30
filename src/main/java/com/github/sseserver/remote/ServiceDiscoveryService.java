package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.ReferenceCounted;
import com.sun.net.httpserver.HttpPrincipal;

import java.util.List;

public interface ServiceDiscoveryService {

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

    ReferenceCounted<List<RemoteConnectionService>> rebuild();

    void registerInstance(String ip, int port);

    HttpPrincipal login(String authorization);

    ReferenceCounted<List<RemoteConnectionService>> getServiceListRef();
}

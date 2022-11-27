package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import com.sun.net.httpserver.HttpPrincipal;

import java.util.List;

public interface ServiceDiscoveryService {

    static NacosServiceDiscoveryService newInstance(String groupName,
                                                    SseServerProperties.Remote remote) {
        SseServerProperties.Remote.Nacos nacos = remote.getNacos();
        if (nacos != null) {
            return new NacosServiceDiscoveryService(groupName, nacos);
        } else {
            return null;
        }
    }

    List<RemoteConnectionService> rebuild();

    void registerInstance(String ip, int port) ;

    HttpPrincipal login(String authorization);

    List<RemoteConnectionService> getServiceList();
}

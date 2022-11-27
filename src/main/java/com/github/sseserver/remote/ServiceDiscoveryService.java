package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;

import java.util.List;

public interface ServiceDiscoveryService {

    static NacosServiceDiscoveryService newInstance(String localConnectionServiceBeanName,
                                                    BeanFactory beanFactory, Environment environment) {
        SseServerProperties properties = beanFactory.getBean(SseServerProperties.class);
        SseServerProperties.Remote.Nacos nacos = properties.getRemote().getNacos();
        if (nacos != null) {
            return new NacosServiceDiscoveryService(localConnectionServiceBeanName, nacos, environment);
        } else {
            return null;
        }
    }

    List<RemoteConnectionService> rebuild();

    List<RemoteConnectionService> getServiceList();
}

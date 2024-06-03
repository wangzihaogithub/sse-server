package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.PlatformDependentUtil;
import com.github.sseserver.util.ReferenceCounted;
import com.sun.net.httpserver.HttpPrincipal;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.List;
import java.util.Objects;

public interface ServiceDiscoveryService {

    static ServiceDiscoveryService newInstance(String groupName,
                                               SseServerProperties.ClusterConfig config,
                                               ListableBeanFactory beanFactory) {
        SseServerProperties.DiscoveryEnum discoveryEnum = config.getDiscovery();
        if (discoveryEnum == SseServerProperties.DiscoveryEnum.AUTO) {
            if (!Objects.toString(config.getNacos().getServerAddr(), "").isEmpty()) {
                discoveryEnum = SseServerProperties.DiscoveryEnum.NACOS;
            } else if (PlatformDependentUtil.isSupportSpringframeworkRedis() && beanFactory.getBeanNamesForType(PlatformDependentUtil.REDIS_CONNECTION_FACTORY_CLASS).length > 0) {
                discoveryEnum = SseServerProperties.DiscoveryEnum.REDIS;
            }
        }

        switch (discoveryEnum) {
            case NACOS: {
                SseServerProperties.ClusterConfig.Nacos nacos = config.getNacos();
                return new NacosServiceDiscoveryService(
                        groupName,
                        nacos.getServiceName(),
                        nacos.getClusterName(),
                        nacos.buildProperties(),
                        config);
            }
            case REDIS: {
                SseServerProperties.ClusterConfig.Redis redis = config.getRedis();
                Object redisConnectionFactory;
                try {
                    redisConnectionFactory = beanFactory.getBean(redis.getRedisConnectionFactoryBeanName());
                } catch (BeansException e) {
                    redisConnectionFactory = beanFactory.getBean(PlatformDependentUtil.REDIS_CONNECTION_FACTORY_CLASS);
                }
                return new RedisServiceDiscoveryService(
                        redisConnectionFactory,
                        groupName,
                        redis.getRedisKeyRootPrefix(),
                        redis.getRedisInstanceExpireSec(),
                        config);
            }
            default: {
                throw new IllegalArgumentException("ServiceDiscoveryService newInstance fail! remote discovery config is empty!");
            }
        }
    }

    boolean isPrimary();

    HttpPrincipal login(String authorization);

    void registerInstance(String ip, int port);

    ReferenceCounted<List<RemoteConnectionService>> getConnectionServiceListRef();

    ReferenceCounted<List<RemoteMessageRepository>> getMessageRepositoryListRef();
}

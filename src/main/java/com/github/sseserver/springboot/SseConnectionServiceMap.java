package com.github.sseserver.springboot;

import com.github.sseserver.DistributedConnectionService;
import com.github.sseserver.local.LocalConnectionService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.Map;

/**
 * 多集群连接获取
 *
 * @since 1.2.16
 */
public class SseConnectionServiceMap {
    private Map<String, DistributedConnectionService> distributedServiceMap = Collections.emptyMap();
    private Map<String, LocalConnectionService> localServiceMap = Collections.emptyMap();

    public LocalConnectionService getLocal(String beanName) {
        return localServiceMap.get(beanName);
    }

    public DistributedConnectionService getDistributed(String beanName) {
        return distributedServiceMap.get(beanName);
    }

    public Map<String, DistributedConnectionService> getDistributedServiceMap() {
        return distributedServiceMap;
    }

    @Autowired(required = false)
    public void setDistributedServiceMap(Map<String, DistributedConnectionService> distributedServiceMap) {
        this.distributedServiceMap = distributedServiceMap;
    }

    public Map<String, LocalConnectionService> getLocalServiceMap() {
        return localServiceMap;
    }

    @Autowired(required = false)
    public void setLocalServiceMap(Map<String, LocalConnectionService> localServiceMap) {
        this.localServiceMap = localServiceMap;
    }

    @Override
    public String toString() {
        return "SseConnectionServiceMap{" +
                "distributedServiceMap=" + distributedServiceMap +
                ", localServiceMap=" + localServiceMap +
                '}';
    }
}

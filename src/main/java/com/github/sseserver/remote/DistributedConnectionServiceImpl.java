package com.github.sseserver.remote;

import com.github.sseserver.DistributedConnectionService;
import com.github.sseserver.SendService;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.qos.QosCompletableFuture;
import com.github.sseserver.springboot.SseServerBeanDefinitionRegistrar;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;

public class DistributedConnectionServiceImpl implements DistributedConnectionService, BeanNameAware, BeanFactoryAware {
    private BeanFactory beanFactory;
    private String beanName = getClass().getSimpleName();

    @Override
    public <ACCESS_USER> SendService<QosCompletableFuture<ACCESS_USER>> qos() {
        String beanName = SseServerBeanDefinitionRegistrar.getAtLeastOnceBeanName(this.beanName);
        return beanFactory.getBean(beanName, SendService.class);
    }

    @Override
    public ClusterConnectionService getCluster() {
        String beanName = SseServerBeanDefinitionRegistrar.getClusterConnectionServiceBeanName(this.beanName);
        return beanFactory.getBean(beanName, ClusterConnectionService.class);
    }

    @Override
    public ServiceDiscoveryService getDiscovery() {
        String beanName = SseServerBeanDefinitionRegistrar.getServiceDiscoveryServiceBeanName(this.beanName);
        return beanFactory.getBean(beanName, ServiceDiscoveryService.class);
    }

    @Override
    public MessageRepository getLocalMessageRepository() {
        String beanName = SseServerBeanDefinitionRegistrar.getLocalMessageRepositoryBeanName(this.beanName);
        return beanFactory.getBean(beanName, MessageRepository.class);
    }

    @Override
    public boolean isEnableCluster() {
        String beanName = SseServerBeanDefinitionRegistrar.getClusterConnectionServiceBeanName(this.beanName);
        try {
            return beanFactory.containsBean(beanName);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ClusterMessageRepository getClusterMessageRepository() {
        String beanName = SseServerBeanDefinitionRegistrar.getClusterMessageRepositoryBeanName(this.beanName);
        return beanFactory.getBean(beanName, ClusterMessageRepository.class);
    }

    @Override
    public String getBeanName() {
        return beanName;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

}

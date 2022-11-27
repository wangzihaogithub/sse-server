package com.github.sseserver.springboot;

import com.github.sseserver.remote.DistributedConnectionService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Map;

public class SseServerCommandLineRunner implements CommandLineRunner, BeanFactoryAware, ApplicationContextAware {
    private ListableBeanFactory beanFactory;
    private ApplicationContext applicationContext;

    @Override
    public void run(String... args) throws Exception {
        Map<String, DistributedConnectionService> distributedConnectionServiceMap = applicationContext != null
                ? applicationContext.getBeansOfType(DistributedConnectionService.class)
                : beanFactory.getBeansOfType(DistributedConnectionService.class);
        for (DistributedConnectionService value : distributedConnectionServiceMap.values()) {
            value.getRemoteConnectionServiceList();
        }
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory instanceof ListableBeanFactory ? (ListableBeanFactory) beanFactory : null;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}

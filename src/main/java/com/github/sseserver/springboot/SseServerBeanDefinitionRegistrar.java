package com.github.sseserver.springboot;

import com.github.sseserver.local.LocalConnectionController;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.LocalConnectionServiceImpl;
import com.github.sseserver.remote.DistributedConnectionService;
import com.github.sseserver.remote.ServiceDiscoveryService;
import com.github.sseserver.util.WebUtil;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.GithubSseEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Registrar if not exist
 * 1.GithubSseEmitterReturnValueHandler.class
 * 2.LocalConnectionService.class
 * 3.DistributedConnectionService.class
 * wangzihaogithub 2022-11-17
 */
public class SseServerBeanDefinitionRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware, EnvironmentAware {
    public static final String DEFAULT_BEAN_NAME_GITHUB_SSE_EMITTER_RETURN_VALUE_HANDLER = "githubSseEmitterReturnValueHandler";
    public static final String DEFAULT_BEAN_NAME_LOCAL_CONNECTION_SERVICE = "localConnectionService";

    private ListableBeanFactory beanFactory;
    private Environment environment;
    private BeanDefinitionRegistry definitionRegistry;

    public static String getServiceDiscoveryServiceBeanName(String localConnectionServiceBeanName) {
        return localConnectionServiceBeanName + "ServiceDiscoveryService";
    }

    public static String getLocalConnectionControllerBeanName(String localConnectionServiceBeanName) {
        return localConnectionServiceBeanName + "LocalConnectionController";
    }

    public static String getDistributedConnectionServiceBeanName(String localConnectionServiceBeanName) {
        return localConnectionServiceBeanName + "DistributedConnectionService";
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry definitionRegistry) {
        if (beanFactory == null && definitionRegistry instanceof ListableBeanFactory) {
            beanFactory = (ListableBeanFactory) definitionRegistry;
        }
        this.definitionRegistry = definitionRegistry;
        Objects.requireNonNull(beanFactory);
        WebUtil.port = environment.getProperty("server.port", Integer.class, 8080);

        // 1.GithubSseEmitterReturnValueHandler.class (if not exist)
        if (beanFactory.getBeanNamesForType(GithubSseEmitterReturnValueHandler.class).length == 0) {
            registerBeanDefinitionsGithubSseEmitterReturnValueHandler();
        }

        // 2.LocalConnectionService.class  (if not exist)
        if (beanFactory.getBeanNamesForType(LocalConnectionService.class).length == 0) {
            registerBeanDefinitionsLocalConnectionService();
        }

        // 3.DistributedConnectionService.class  (if enabled)
        Boolean remoteEnabled = environment.getProperty("spring.sse-server.remote.enabled", Boolean.class, false);
        if (remoteEnabled) {
            String[] localConnectionServiceBeanNames = beanFactory.getBeanNamesForType(LocalConnectionService.class);
            registerBeanDefinitionsServiceDiscoveryService(localConnectionServiceBeanNames);
            registerBeanDefinitionsLocalConnectionController(localConnectionServiceBeanNames);
            registerBeanDefinitionsDistributedConnectionService(localConnectionServiceBeanNames);
        }
    }

    protected void registerBeanDefinitionsGithubSseEmitterReturnValueHandler() {
        BeanDefinition beanDefinition = BeanDefinitionBuilder
                .rootBeanDefinition(GithubSseEmitterReturnValueHandler.class, () -> {
                    RequestMappingHandlerAdapter requestMappingHandler = beanFactory.getBean(RequestMappingHandlerAdapter.class);
                    GithubSseEmitterReturnValueHandler sseHandler = new GithubSseEmitterReturnValueHandler(requestMappingHandler::getMessageConverters);

                    List<HandlerMethodReturnValueHandler> newHandlers = new ArrayList<>();
                    newHandlers.add(sseHandler);
                    List<HandlerMethodReturnValueHandler> oldHandlers = requestMappingHandler.getReturnValueHandlers();
                    if (oldHandlers != null) {
                        newHandlers.addAll(oldHandlers);
                    }
                    requestMappingHandler.setReturnValueHandlers(newHandlers);
                    return sseHandler;
                })
                .setLazyInit(false)
                .getBeanDefinition();
        definitionRegistry.registerBeanDefinition(DEFAULT_BEAN_NAME_GITHUB_SSE_EMITTER_RETURN_VALUE_HANDLER, beanDefinition);
    }

    protected void registerBeanDefinitionsLocalConnectionService() {
        BeanDefinition beanDefinition = BeanDefinitionBuilder
                .rootBeanDefinition(LocalConnectionService.class, LocalConnectionServiceImpl::new)
                .getBeanDefinition();
        definitionRegistry.registerBeanDefinition(DEFAULT_BEAN_NAME_LOCAL_CONNECTION_SERVICE, beanDefinition);
    }

    protected void registerBeanDefinitionsDistributedConnectionService(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinition beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(DistributedConnectionService.class,
                    () -> {
                        Supplier<LocalConnectionService> provider = () -> beanFactory.getBean(localConnectionServiceBeanName, LocalConnectionService.class);
                        Supplier<ServiceDiscoveryService> discoverySupplier = () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(localConnectionServiceBeanName), ServiceDiscoveryService.class);

                        return DistributedConnectionService.newInstance(discoverySupplier, provider);
                    }).getBeanDefinition();

            String beanName = getDistributedConnectionServiceBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, beanDefinition);
        }
    }

    protected void registerBeanDefinitionsServiceDiscoveryService(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinition beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(ServiceDiscoveryService.class,
                    () -> {
                        SseServerProperties properties = beanFactory.getBean(SseServerProperties.class);
                        return ServiceDiscoveryService.newInstance(localConnectionServiceBeanName, properties.getRemote());
                    }).getBeanDefinition();

            String beanName = getServiceDiscoveryServiceBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, beanDefinition);
        }
    }

    protected void registerBeanDefinitionsLocalConnectionController(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinition beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(LocalConnectionController.class,
                    () -> {
                        Supplier<LocalConnectionService> provider = () -> beanFactory.getBean(localConnectionServiceBeanName, LocalConnectionService.class);
                        Supplier<ServiceDiscoveryService> discoverySupplier = () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(localConnectionServiceBeanName), ServiceDiscoveryService.class);

                        return new LocalConnectionController(provider, discoverySupplier);
                    }).getBeanDefinition();

            String beanName = getLocalConnectionControllerBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, beanDefinition);
        }
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory instanceof ListableBeanFactory ? (ListableBeanFactory) beanFactory : null;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

}

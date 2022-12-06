package com.github.sseserver.springboot;

import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.LocalConnectionServiceImpl;
import com.github.sseserver.local.LocalController;
import com.github.sseserver.qos.AtLeastOnceSendService;
import com.github.sseserver.qos.MemoryMessageRepository;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.remote.*;
import com.github.sseserver.util.ReferenceCounted;
import com.github.sseserver.util.WebUtil;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
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
 * 1.GithubSseEmitterReturnValueHandler.class (if not exist)
 * 2.LocalConnectionService.class  (if not exist)
 * 3.MessageRepository (for support Qos used)
 * 4.ClusterConnectionService.class  (if enabled)
 * 5.Qos (qos atLeastOnce send)
 *
 * @author wangzihaogithub 2022-11-17
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

    public static String getClusterConnectionServiceBeanName(String localConnectionServiceBeanName) {
        return localConnectionServiceBeanName + "ClusterConnectionService";
    }

    public static String getLocalMessageRepositoryBeanName(String localConnectionServiceBeanName) {
        return localConnectionServiceBeanName + "LocalMessageRepository";
    }

    public static String getClusterMessageRepositoryBeanName(String localConnectionServiceBeanName) {
        return localConnectionServiceBeanName + "ClusterMessageRepository";
    }

    public static String getAtLeastOnceBeanName(String localConnectionServiceBeanName) {
        return localConnectionServiceBeanName + "AtLeastOnce";
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

        // 3.Qos used MessageRepository
        String[] localConnectionServiceBeanNames = beanFactory.getBeanNamesForType(LocalConnectionService.class);
        registerBeanDefinitionsLocalMessageRepository(localConnectionServiceBeanNames);

        // 4.ClusterConnectionService.class  (if enabled)
        Boolean remoteEnabled = environment.getProperty("spring.sse-server.remote.enabled", Boolean.class, false);
        if (remoteEnabled) {
            registerBeanDefinitionsClusterMessageRepository(localConnectionServiceBeanNames);
            registerBeanDefinitionsServiceDiscoveryService(localConnectionServiceBeanNames);
            registerBeanDefinitionsLocalConnectionController(localConnectionServiceBeanNames);
            registerBeanDefinitionsClusterConnectionService(localConnectionServiceBeanNames);
        }

        // 5.Qos
        registerBeanDefinitionsAtLeastOnce(localConnectionServiceBeanNames, remoteEnabled);
    }

    protected void registerBeanDefinitionsGithubSseEmitterReturnValueHandler() {
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(GithubSseEmitterReturnValueHandler.class, () -> {
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
                .setLazyInit(false);

        definitionRegistry.registerBeanDefinition(DEFAULT_BEAN_NAME_GITHUB_SSE_EMITTER_RETURN_VALUE_HANDLER, builder.getBeanDefinition());
    }

    protected void registerBeanDefinitionsLocalConnectionService() {
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(LocalConnectionService.class, LocalConnectionServiceImpl::new);
        definitionRegistry.registerBeanDefinition(DEFAULT_BEAN_NAME_LOCAL_CONNECTION_SERVICE, builder.getBeanDefinition());
    }

    protected void registerBeanDefinitionsClusterConnectionService(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ClusterConnectionService.class,
                    () -> {
                        Supplier<LocalConnectionService> localSupplier = () -> beanFactory.getBean(localConnectionServiceBeanName, LocalConnectionService.class);
                        Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier =
                                () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(localConnectionServiceBeanName), ServiceDiscoveryService.class)
                                        .getConnectionServiceListRef();
                        return ClusterConnectionService.newInstance(localSupplier, remoteSupplier);
                    });

            String beanName = getClusterConnectionServiceBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsLocalMessageRepository(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
                    MessageRepository.class,
                    MemoryMessageRepository::new);
            String beanName = getLocalMessageRepositoryBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsClusterMessageRepository(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ClusterMessageRepository.class,
                    () -> {
                        Supplier<MessageRepository> localSupplier =
                                () -> beanFactory.getBean(getLocalMessageRepositoryBeanName(localConnectionServiceBeanName), MessageRepository.class);
                        Supplier<ReferenceCounted<List<RemoteMessageRepository>>> remoteSupplier =
                                () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(localConnectionServiceBeanName), ServiceDiscoveryService.class)
                                        .getMessageRepositoryListRef();
                        return new ClusterMessageRepository(localSupplier, remoteSupplier);
                    });
            String beanName = getClusterMessageRepositoryBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsAtLeastOnce(String[] localConnectionServiceBeanNames, Boolean remoteEnabled) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SendService.class,
                    () -> {
                        String repositoryBeanName;
                        if (remoteEnabled) {
                            repositoryBeanName = getClusterMessageRepositoryBeanName(localConnectionServiceBeanName);
                        } else {
                            repositoryBeanName = getLocalMessageRepositoryBeanName(localConnectionServiceBeanName);
                        }
                        MessageRepository repository = beanFactory.getBean(repositoryBeanName, MessageRepository.class);
                        LocalConnectionService localService = beanFactory.getBean(localConnectionServiceBeanName, LocalConnectionService.class);
                        return new AtLeastOnceSendService(localService, repository);
                    });
            String beanName = getAtLeastOnceBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsServiceDiscoveryService(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ServiceDiscoveryService.class,
                    () -> {
                        SseServerProperties properties = beanFactory.getBean(SseServerProperties.class);
                        return ServiceDiscoveryService.newInstance(localConnectionServiceBeanName, properties.getRemote());
                    });

            String beanName = getServiceDiscoveryServiceBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsLocalConnectionController(String[] localConnectionServiceBeanNames) {
        for (String localConnectionServiceBeanName : localConnectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(LocalController.class,
                    () -> {
                        Supplier<LocalConnectionService> localConnectionServiceSupplier = () -> beanFactory.getBean(localConnectionServiceBeanName, LocalConnectionService.class);
                        Supplier<MessageRepository> localMessageRepositorySupplier = () -> beanFactory.getBean(getLocalMessageRepositoryBeanName(localConnectionServiceBeanName), MessageRepository.class);
                        Supplier<ServiceDiscoveryService> discoverySupplier = () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(localConnectionServiceBeanName), ServiceDiscoveryService.class);
                        return new LocalController(localConnectionServiceSupplier, localMessageRepositorySupplier, discoverySupplier);
                    });

            String beanName = getLocalConnectionControllerBeanName(localConnectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
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

package com.github.sseserver.springboot;

import com.github.sseserver.DistributedConnectionService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.LocalConnectionServiceImpl;
import com.github.sseserver.local.LocalController;
import com.github.sseserver.qos.AtLeastOnceSendService;
import com.github.sseserver.qos.MemoryMessageRepository;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.remote.*;
import com.github.sseserver.util.PlatformDependentUtil;
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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    public static final String DEFAULT_BEAN_NAME_CONNECTION_SERVICE = "defaultConnectionService";

    private ListableBeanFactory beanFactory;
    private Environment environment;
    private BeanDefinitionRegistry definitionRegistry;

    public static String getServiceDiscoveryServiceBeanName(String connectionServiceBeanName) {
        return connectionServiceBeanName + "ServiceDiscoveryService";
    }

    public static String getLocalConnectionControllerBeanName(String connectionServiceBeanName) {
        return connectionServiceBeanName + "LocalConnectionController";
    }

    public static String getClusterConnectionServiceBeanName(String connectionServiceBeanName) {
        return connectionServiceBeanName + "ClusterConnectionService";
    }

    public static String getLocalMessageRepositoryBeanName(String connectionServiceBeanName) {
        return connectionServiceBeanName + "LocalMessageRepository";
    }

    public static String getClusterMessageRepositoryBeanName(String connectionServiceBeanName) {
        return connectionServiceBeanName + "ClusterMessageRepository";
    }

    public static String getAtLeastOnceBeanName(String connectionServiceBeanName) {
        return connectionServiceBeanName + "AtLeastOnce";
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry definitionRegistry) {
        if (beanFactory == null && definitionRegistry instanceof ListableBeanFactory) {
            beanFactory = (ListableBeanFactory) definitionRegistry;
        }
        this.definitionRegistry = definitionRegistry;
        Objects.requireNonNull(beanFactory);

        boolean enableLocalConnectionService = PlatformDependentUtil.isSupportSpringframeworkWeb();

        // 1.GithubSseEmitterReturnValueHandler.class (if not exist)
        if (enableLocalConnectionService) {
            WebUtil.port = environment.getProperty("server.port", Integer.class, 8080);
            SpringWebMvcRegistrar.registerBeanDefinitionsGithubSseEmitterReturnValueHandler(beanFactory, definitionRegistry, DEFAULT_BEAN_NAME_GITHUB_SSE_EMITTER_RETURN_VALUE_HANDLER);
        }

        // 2.LocalConnectionService.class  (if not exist)
        String[] connectionServiceBeanNames = getConnectionServiceNames();
        if (connectionServiceBeanNames.length == 0) {
            BeanDefinitionBuilder builder;
            if (enableLocalConnectionService) {
                builder = BeanDefinitionBuilder.genericBeanDefinition(LocalConnectionService.class, LocalConnectionServiceImpl::new);
            } else {
                builder = BeanDefinitionBuilder.genericBeanDefinition(DistributedConnectionService.class, DistributedConnectionServiceImpl::new);
            }
            definitionRegistry.registerBeanDefinition(DEFAULT_BEAN_NAME_CONNECTION_SERVICE, builder.getBeanDefinition());
            connectionServiceBeanNames = new String[]{DEFAULT_BEAN_NAME_CONNECTION_SERVICE};
        }

        // 3.Qos used MessageRepository
        registerBeanDefinitionsLocalMessageRepository(connectionServiceBeanNames);

        // 4.ClusterConnectionService.class  (if enabled)
        Boolean remoteEnabled = environment.getProperty("spring.sse-server.remote.enabled", Boolean.class, false);
        if (remoteEnabled) {
            registerBeanDefinitionsClusterMessageRepository(connectionServiceBeanNames);
            registerBeanDefinitionsServiceDiscoveryService(connectionServiceBeanNames);
            registerBeanDefinitionsLocalConnectionController(connectionServiceBeanNames);
            registerBeanDefinitionsClusterConnectionService(connectionServiceBeanNames);
        }

        // 5.Qos
        registerBeanDefinitionsAtLeastOnce(connectionServiceBeanNames, remoteEnabled);
    }

    protected String[] getConnectionServiceNames() {
        String[] names = beanFactory.getBeanNamesForType(LocalConnectionService.class);
        if (names.length == 0) {
            names = beanFactory.getBeanNamesForType(DistributedConnectionService.class);
        }
        return names;
    }

    protected void registerBeanDefinitionsClusterConnectionService(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ClusterConnectionService.class,
                    () -> {
                        Supplier<Optional<LocalConnectionService>> localSupplier = () -> getLocalConnectionService(connectionServiceBeanName, beanFactory);
                        Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier =
                                () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(connectionServiceBeanName), ServiceDiscoveryService.class)
                                        .getConnectionServiceListRef();
                        return ClusterConnectionService.newInstance(localSupplier, remoteSupplier);
                    });

            String beanName = getClusterConnectionServiceBeanName(connectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsLocalMessageRepository(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
                    MessageRepository.class,
                    MemoryMessageRepository::new);
            String beanName = getLocalMessageRepositoryBeanName(connectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsClusterMessageRepository(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ClusterMessageRepository.class,
                    () -> {
                        Supplier<MessageRepository> localSupplier =
                                () -> beanFactory.getBean(getLocalMessageRepositoryBeanName(connectionServiceBeanName), MessageRepository.class);
                        Supplier<ReferenceCounted<List<RemoteMessageRepository>>> remoteSupplier =
                                () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(connectionServiceBeanName), ServiceDiscoveryService.class)
                                        .getMessageRepositoryListRef();
                        return new ClusterMessageRepository(localSupplier, remoteSupplier);
                    });
            String beanName = getClusterMessageRepositoryBeanName(connectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsAtLeastOnce(String[] connectionServiceBeanNames, Boolean remoteEnabled) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SendService.class,
                    () -> {
                        String repositoryBeanName;
                        if (remoteEnabled) {
                            repositoryBeanName = getClusterMessageRepositoryBeanName(connectionServiceBeanName);
                        } else {
                            repositoryBeanName = getLocalMessageRepositoryBeanName(connectionServiceBeanName);
                        }
                        MessageRepository repository = beanFactory.getBean(repositoryBeanName, MessageRepository.class);
                        DistributedConnectionService distributedConnectionService = getDistributedConnectionService(connectionServiceBeanName, beanFactory);
                        Optional<LocalConnectionService> localConnectionService = getLocalConnectionService(connectionServiceBeanName, beanFactory);
                        return new AtLeastOnceSendService(localConnectionService, distributedConnectionService, repository);
                    });
            String beanName = getAtLeastOnceBeanName(connectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsServiceDiscoveryService(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ServiceDiscoveryService.class,
                    () -> {
                        SseServerProperties properties = beanFactory.getBean(SseServerProperties.class);
                        return ServiceDiscoveryService.newInstance(connectionServiceBeanName, properties.getRemote());
                    });

            String beanName = getServiceDiscoveryServiceBeanName(connectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerBeanDefinitionsLocalConnectionController(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(LocalController.class,
                    () -> {
                        Supplier<Optional<LocalConnectionService>> localConnectionServiceSupplier = () -> getLocalConnectionService(connectionServiceBeanName, beanFactory);
                        Supplier<MessageRepository> localMessageRepositorySupplier = () -> beanFactory.getBean(getLocalMessageRepositoryBeanName(connectionServiceBeanName), MessageRepository.class);
                        Supplier<ServiceDiscoveryService> discoverySupplier = () -> beanFactory.getBean(getServiceDiscoveryServiceBeanName(connectionServiceBeanName), ServiceDiscoveryService.class);
                        return new LocalController(localConnectionServiceSupplier, localMessageRepositorySupplier, discoverySupplier);
                    });

            String beanName = getLocalConnectionControllerBeanName(connectionServiceBeanName);
            definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    public static DistributedConnectionService getDistributedConnectionService(String connectionServiceBeanName, BeanFactory beanFactory) {
        return beanFactory.getBean(connectionServiceBeanName, DistributedConnectionService.class);
    }

    public static Optional<LocalConnectionService> getLocalConnectionService(String connectionServiceBeanName, BeanFactory beanFactory) {
        Object bean = beanFactory.getBean(connectionServiceBeanName);
        return bean instanceof LocalConnectionService ? Optional.of((LocalConnectionService) bean) : Optional.empty();
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

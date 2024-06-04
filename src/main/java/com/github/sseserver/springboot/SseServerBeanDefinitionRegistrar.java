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
import com.github.sseserver.util.SpringUtil;
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.type.AnnotationMetadata;

import java.util.*;
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
    public static final String registerLocalConnectionService = "LocalConnectionService()";
    public static final String registerLocalConnectionController = "LocalController(?LocalConnectionService, ?LocalMessageRepository, ?ServiceDiscoveryService)";
    public static final String registerClusterConnectionService = "ClusterConnectionService(?LocalConnectionService, ?ServiceDiscoveryService)";
    public static final String registerClusterMessageRepository = "ClusterMessageRepository(?LocalMessageRepository, ?ServiceDiscoveryService)";
    public static final String registerLocalMessageRepository = "LocalMessageRepository()";
    public static final String registerServiceDiscoveryService = "ServiceDiscoveryService(SseServerProperties)";
    public static final String registerAtLeastOnce = "AtLeastOnceSendService(?LocalConnectionService, ?DistributedConnectionService, ?MessageRepository)";

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
        return connectionServiceBeanName + "AtLeastOnceSendService";
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry definitionRegistry) {
        if (beanFactory == null && definitionRegistry instanceof ListableBeanFactory) {
            beanFactory = (ListableBeanFactory) definitionRegistry;
        }
        this.definitionRegistry = definitionRegistry;
        Objects.requireNonNull(beanFactory);

        boolean enableLocalConnectionService = PlatformDependentUtil.isSupportSpringframeworkWeb();
        Boolean remoteEnabled = environment.getProperty(SseServerProperties.PREFIX_REMOTE_ENABLED, Boolean.class, false);

        // 1.GithubSseEmitterReturnValueHandler.class (if not exist)
        if (enableLocalConnectionService) {
            WebUtil.port = environment.getProperty("server.port", Integer.class, 8080);
            SpringWebMvcRegistrar.registerBeanDefinitionsGithubSseEmitterReturnValueHandler(beanFactory, definitionRegistry, DEFAULT_BEAN_NAME_GITHUB_SSE_EMITTER_RETURN_VALUE_HANDLER);
        }
        // 2.LocalConnectionService.class  (if not exist)
        String[] beanNames = getConnectionServiceBeanNames(enableLocalConnectionService);
        // 3.Qos used MessageRepository
        registerLocalMessageRepository(beanNames);
        // 4.Qos
        registerAtLeastOnce(beanNames, remoteEnabled);
        // 5.Cluster (if enabled)
        if (remoteEnabled) {
            registerClusterMessageRepository(beanNames);
            registerClusterConnectionService(beanNames);
            registerServiceDiscoveryService(beanNames);
            registerLocalConnectionController(beanNames);
        }
    }

    protected String[] getClusterConnectionServiceNames(String prefixCluster) {
        Set<String> names = new LinkedHashSet<>(3);
        Environment environment = this.environment;
        if (!(environment instanceof ConfigurableEnvironment)) {
            return new String[0];
        }
        MutablePropertySources propertySources = ((ConfigurableEnvironment) environment).getPropertySources();
        for (PropertySource<?> propertySource : propertySources) {
            Object source = propertySource.getSource();
            if (!(source instanceof Map)) {
                continue;
            }
            Map soureMap = (Map) source;
            for (Object key : soureMap.keySet()) {
                String keyString = Objects.toString(key);
                if (!keyString.startsWith(prefixCluster)) {
                    continue;
                }
                String substring = keyString.substring(prefixCluster.length());
                String split = substring.split("\\.", 2)[0];
                names.add(split);
            }
        }
        return names.toArray(new String[0]);
    }

    protected String[] getConnectionServiceBeanNames(boolean enableLocalConnectionService) {
        String[] names = beanFactory.getBeanNamesForType(LocalConnectionService.class);
        if (names.length == 0) {
            names = beanFactory.getBeanNamesForType(DistributedConnectionService.class);
        }
        if (names.length == 0) {
            names = getClusterConnectionServiceNames(SseServerProperties.PREFIX_CLUSTER);
            registerConnectionService(names, enableLocalConnectionService);
        }
        if (names.length == 0) {
            names = new String[]{SseServerProperties.DEFAULT_BEAN_NAME};
            registerConnectionService(names, enableLocalConnectionService);
        }
        return names;
    }

    protected void registerConnectionService(String[] beanNames, boolean enableLocalConnectionService) {
        for (String beanName : beanNames) {
            boolean primary = isPrimary(beanName);
            BeanDefinitionBuilder builder;
            if (enableLocalConnectionService && containsRegister(beanName, registerLocalConnectionService)) {
                builder = BeanDefinitionBuilder.genericBeanDefinition(LocalConnectionService.class, () -> new LocalConnectionServiceImpl(primary));
            } else {
                builder = BeanDefinitionBuilder.genericBeanDefinition(DistributedConnectionService.class, () -> new DistributedConnectionServiceImpl(primary));
            }
            builder.setPrimary(primary);
            registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerClusterConnectionService(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            if (!containsRegister(connectionServiceBeanName, registerClusterConnectionService)) {
                continue;
            }
            boolean primary = isPrimary(connectionServiceBeanName);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ClusterConnectionService.class,
                    () -> {
                        Supplier<LocalConnectionService> localSupplier = () -> getBean(connectionServiceBeanName, LocalConnectionService.class);
                        Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier =
                                () -> getBean(getServiceDiscoveryServiceBeanName(connectionServiceBeanName), ServiceDiscoveryService.class)
                                        .getConnectionServiceListRef();
                        return ClusterConnectionService.newInstance(localSupplier, remoteSupplier, primary);
                    });
            builder.setPrimary(primary);
            String beanName = getClusterConnectionServiceBeanName(connectionServiceBeanName);
            registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerLocalMessageRepository(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            if (!containsRegister(connectionServiceBeanName, registerLocalMessageRepository)) {
                continue;
            }
            boolean primary = isPrimary(connectionServiceBeanName);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
                    MessageRepository.class,
                    () -> new MemoryMessageRepository(primary));
            builder.setPrimary(primary);
            String beanName = getLocalMessageRepositoryBeanName(connectionServiceBeanName);
            registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerClusterMessageRepository(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            if (!containsRegister(connectionServiceBeanName, registerClusterMessageRepository)) {
                continue;
            }
            boolean primary = isPrimary(connectionServiceBeanName);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ClusterMessageRepository.class,
                    () -> {
                        Supplier<MessageRepository> localSupplier =
                                () -> getBean(getLocalMessageRepositoryBeanName(connectionServiceBeanName), MessageRepository.class);
                        Supplier<ReferenceCounted<List<RemoteMessageRepository>>> remoteSupplier =
                                () -> getBean(getServiceDiscoveryServiceBeanName(connectionServiceBeanName), ServiceDiscoveryService.class)
                                        .getMessageRepositoryListRef();
                        return new ClusterMessageRepository(localSupplier, remoteSupplier, primary);
                    });
            builder.setPrimary(primary);
            String beanName = getClusterMessageRepositoryBeanName(connectionServiceBeanName);
            registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerAtLeastOnce(String[] connectionServiceBeanNames, Boolean remoteEnabled) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            if (!containsRegister(connectionServiceBeanName, registerAtLeastOnce)) {
                continue;
            }
            boolean primary = isPrimary(connectionServiceBeanName);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SendService.class,
                    () -> {
                        String repositoryBeanName;
                        if (remoteEnabled) {
                            repositoryBeanName = getClusterMessageRepositoryBeanName(connectionServiceBeanName);
                        } else {
                            repositoryBeanName = getLocalMessageRepositoryBeanName(connectionServiceBeanName);
                        }
                        MessageRepository repository = getBean(repositoryBeanName, MessageRepository.class);
                        DistributedConnectionService distributedConnectionService = getBean(connectionServiceBeanName, DistributedConnectionService.class);
                        LocalConnectionService localConnectionService = getBean(connectionServiceBeanName, LocalConnectionService.class);
                        return new AtLeastOnceSendService(localConnectionService, distributedConnectionService, repository, primary);
                    });
            builder.setPrimary(primary);
            String beanName = getAtLeastOnceBeanName(connectionServiceBeanName);
            registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerServiceDiscoveryService(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            if (!containsRegister(connectionServiceBeanName, registerServiceDiscoveryService)) {
                continue;
            }
            boolean primary = isPrimary(connectionServiceBeanName);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ServiceDiscoveryService.class,
                    () -> {
                        SseServerProperties properties = beanFactory.getBean(SseServerProperties.class);
                        SseServerProperties.ClusterConfig config = properties.getCluster().get(connectionServiceBeanName);
                        if (config == null) {
                            config = new SseServerProperties.ClusterConfig();
                        }
                        SseServerAutoConfiguration.bindNacos(config.getNacos(), environment);
                        String groupName = config.getGroupName();
                        if (groupName == null || groupName.isEmpty()) {
                            groupName = SseServerProperties.DEFAULT_GROUP_NAME;
                        }
                        return ServiceDiscoveryService.newInstance(groupName, config, beanFactory);
                    });

            builder.setPrimary(primary);
            String beanName = getServiceDiscoveryServiceBeanName(connectionServiceBeanName);
            registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected void registerLocalConnectionController(String[] connectionServiceBeanNames) {
        for (String connectionServiceBeanName : connectionServiceBeanNames) {
            if (!containsRegister(connectionServiceBeanName, registerLocalConnectionController)) {
                continue;
            }
            boolean primary = isPrimary(connectionServiceBeanName);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(LocalController.class,
                    () -> {
                        Supplier<LocalConnectionService> localConnectionServiceSupplier = () -> getBean(connectionServiceBeanName, LocalConnectionService.class);
                        Supplier<MessageRepository> localMessageRepositorySupplier = () -> getBean(getLocalMessageRepositoryBeanName(connectionServiceBeanName), MessageRepository.class);
                        Supplier<ServiceDiscoveryService> discoverySupplier = () -> getBean(getServiceDiscoveryServiceBeanName(connectionServiceBeanName), ServiceDiscoveryService.class);
                        return new LocalController(localConnectionServiceSupplier, localMessageRepositorySupplier, discoverySupplier, primary);
                    });

            builder.setPrimary(primary);
            String beanName = getLocalConnectionControllerBeanName(connectionServiceBeanName);
            registerBeanDefinition(beanName, builder.getBeanDefinition());
        }
    }

    protected <T> T getBean(String name, Class<T> type) {
        return SpringUtil.getBean(name, type, beanFactory);
    }

    protected boolean containsRegister(String connectionServiceBeanName, String registerName) {
        SseServerProperties.ClusterRoleEnum roleEnum = environment.getProperty(String.format(SseServerProperties.PREFIX_CLUSTER_ROLE, connectionServiceBeanName), SseServerProperties.ClusterRoleEnum.class);
        return roleEnum == null || roleEnum.containsRegister(registerName);
    }

    protected boolean isPrimary(String connectionServiceBeanName) {
        Boolean primary = environment.getProperty(String.format(SseServerProperties.PREFIX_CLUSTER_PRIMARY, connectionServiceBeanName), Boolean.class);
        return primary != null && primary;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory instanceof ListableBeanFactory ? (ListableBeanFactory) beanFactory : null;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    protected void registerBeanDefinition(String beanName, BeanDefinition definition) {
        if (!definitionRegistry.containsBeanDefinition(beanName)) {
            definitionRegistry.registerBeanDefinition(beanName, definition);
        }
    }

}

package com.github.sseserver.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

@ConfigurationProperties(prefix = SseServerProperties.PREFIX, ignoreUnknownFields = true)
public class SseServerProperties {
    public static final String PREFIX = "spring.sse-server";
    public static final String PREFIX_REMOTE_ENABLED = PREFIX + ".remote.enabled";
    public static final String PREFIX_CLUSTER = PREFIX + ".cluster.";
    public static final String PREFIX_CLUSTER_ROLE = PREFIX + ".cluster.%s.role";
    public static final String PREFIX_CLUSTER_PRIMARY = PREFIX + ".cluster.%s.primary";
    public static final String DEFAULT_BEAN_NAME_CONNECTION_SERVICE = "defaultConnectionService";
    private final Remote remote = new Remote();
    private final Map<String, ClusterConfig> cluster = new LinkedHashMap<>();

    public Map<String, ClusterConfig> getCluster() {
        return cluster;
    }

    public Remote getRemote() {
        return remote;
    }

    public enum AutoType {
        DISABLED,
        CLASS_NOT_FOUND_USE_MAP,
        CLASS_NOT_FOUND_THROWS,
    }

    public enum ClusterRoleEnum {
        SERVER(Arrays.asList(
                SseServerBeanDefinitionRegistrar.registerLocalConnectionService,
                SseServerBeanDefinitionRegistrar.registerLocalConnectionController,
                SseServerBeanDefinitionRegistrar.registerLocalMessageRepository,
                SseServerBeanDefinitionRegistrar.registerClusterConnectionService,
                SseServerBeanDefinitionRegistrar.registerClusterMessageRepository,
                SseServerBeanDefinitionRegistrar.registerServiceDiscoveryService,
                SseServerBeanDefinitionRegistrar.registerAtLeastOnce
        )),
        CLIENT(Arrays.asList(
                SseServerBeanDefinitionRegistrar.registerLocalMessageRepository,
                SseServerBeanDefinitionRegistrar.registerClusterConnectionService,
                SseServerBeanDefinitionRegistrar.registerClusterMessageRepository,
                SseServerBeanDefinitionRegistrar.registerServiceDiscoveryService,
                SseServerBeanDefinitionRegistrar.registerAtLeastOnce
        ));

        private final Set<String> registers;

        ClusterRoleEnum(Collection<String> registers) {
            this.registers = new LinkedHashSet<>(registers);
        }

        public boolean containsRegister(String registerName) {
            return registers.contains(registerName);
        }
    }

    public enum DiscoveryEnum {
        AUTO,
        REDIS,
        NACOS
    }

    public static class Remote {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ClusterConfig {
        private final Nacos nacos = new Nacos();
        private final Redis redis = new Redis();
        private final MessageRepository messageRepository = new MessageRepository();
        private final ConnectionService connectionService = new ConnectionService();
        private String groupName;
        private DiscoveryEnum discovery = DiscoveryEnum.AUTO;
        private ClusterRoleEnum role = ClusterRoleEnum.SERVER;
        /**
         * 是否默认集群，多个ClusterConfig时，默认注入。实现spring的 @Primary注解
         */
        private boolean primary = false;

        public boolean isPrimary() {
            return primary;
        }

        public void setPrimary(boolean primary) {
            this.primary = primary;
        }

        public ClusterRoleEnum getRole() {
            return role;
        }

        public void setRole(ClusterRoleEnum role) {
            this.role = role;
        }

        public String getGroupName() {
            return groupName;
        }

        public void setGroupName(String groupName) {
            this.groupName = groupName;
        }

        public DiscoveryEnum getDiscovery() {
            return discovery;
        }

        public void setDiscovery(DiscoveryEnum discovery) {
            this.discovery = discovery;
        }

        public MessageRepository getMessageRepository() {
            return messageRepository;
        }

        public ConnectionService getConnectionService() {
            return connectionService;
        }

        public Redis getRedis() {
            return redis;
        }

        public Nacos getNacos() {
            return nacos;
        }

        public static class MessageRepository {
            private AutoType autoType = AutoType.CLASS_NOT_FOUND_USE_MAP;

            public AutoType getAutoType() {
                return autoType;
            }

            public void setAutoType(AutoType autoType) {
                this.autoType = autoType;
            }
        }

        public static class ConnectionService {
            private AutoType autoType = AutoType.CLASS_NOT_FOUND_THROWS;

            public AutoType getAutoType() {
                return autoType;
            }

            public void setAutoType(AutoType autoType) {
                this.autoType = autoType;
            }
        }

        public static class Nacos {
            private String serverAddr = "${nacos.discovery.server-addr:${nacos.config.server-addr:${spring.cloud.nacos.server-addr:${spring.cloud.nacos.discovery.server-addr:${spring.cloud.nacos.config.server-addr:}}}}}";
            private String namespace = "${nacos.discovery.namespace:${nacos.config.namespace:${spring.cloud.nacos.namespace:${spring.cloud.nacos.discovery.namespace:${spring.cloud.nacos.config.namespace:}}}}}";
            private String serviceName = "${spring.application.name:sse-server}";
            private String clusterName = "${nacos.discovery.clusterName:${nacos.config.clusterName:${spring.cloud.nacos.clusterName:${spring.cloud.nacos.discovery.clusterName:${spring.cloud.nacos.config.clusterName:DEFAULT}}}}}";
            private Properties properties = new Properties();

            public Properties buildProperties() {
                Properties properties = new Properties();
                properties.putAll(this.properties);
                if (serverAddr != null && !serverAddr.isEmpty()) {
                    properties.put("serverAddr", serverAddr);
                }
                if (namespace != null && !namespace.isEmpty()) {
                    properties.put("namespace", namespace);
                }
                return properties;
            }

            public String getServerAddr() {
                return serverAddr;
            }

            public void setServerAddr(String serverAddr) {
                this.serverAddr = serverAddr;
            }

            public String getNamespace() {
                return namespace;
            }

            public void setNamespace(String namespace) {
                this.namespace = namespace;
            }

            public String getServiceName() {
                return serviceName;
            }

            public void setServiceName(String serviceName) {
                this.serviceName = serviceName;
            }

            public String getClusterName() {
                return clusterName;
            }

            public void setClusterName(String clusterName) {
                this.clusterName = clusterName;
            }

            public Properties getProperties() {
                return properties;
            }

            public void setProperties(Properties properties) {
                this.properties = properties;
            }
        }

        public static class Redis {
            private String redisConnectionFactoryBeanName = "redisConnectionFactory";
            private String redisKeyRootPrefix = "sse:";
            private int redisInstanceExpireSec = 10;

            public String getRedisConnectionFactoryBeanName() {
                return redisConnectionFactoryBeanName;
            }

            public void setRedisConnectionFactoryBeanName(String redisConnectionFactoryBeanName) {
                this.redisConnectionFactoryBeanName = redisConnectionFactoryBeanName;
            }

            public String getRedisKeyRootPrefix() {
                return redisKeyRootPrefix;
            }

            public void setRedisKeyRootPrefix(String redisKeyRootPrefix) {
                this.redisKeyRootPrefix = redisKeyRootPrefix;
            }

            public int getRedisInstanceExpireSec() {
                return redisInstanceExpireSec;
            }

            public void setRedisInstanceExpireSec(int redisInstanceExpireSec) {
                this.redisInstanceExpireSec = redisInstanceExpireSec;
            }

        }
    }
}

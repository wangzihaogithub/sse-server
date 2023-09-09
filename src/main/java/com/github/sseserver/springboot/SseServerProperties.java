package com.github.sseserver.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Properties;

@ConfigurationProperties(prefix = "spring.sse-server", ignoreUnknownFields = true)
public class SseServerProperties {
    private final Qos qos = new Qos();
    private final Local local = new Local();
    private final Remote remote = new Remote();

    public Qos getQos() {
        return qos;
    }

    public Local getLocal() {
        return local;
    }

    public Remote getRemote() {
        return remote;
    }

    public enum AutoType {
        DISABLED,
        CLASS_NOT_FOUND_USE_MAP,
        CLASS_NOT_FOUND_THROWS,
    }

    public static class Local {

    }

    public static class Qos {

    }

    public enum DiscoveryEnum {
        AUTO,
        REDIS,
        NACOS
    }

    public static class Remote {
        private boolean enabled = false;
        private DiscoveryEnum discovery = DiscoveryEnum.AUTO;
        private final Nacos nacos = new Nacos();
        private final Redis redis = new Redis();
        private final MessageRepository messageRepository = new MessageRepository();
        private final ConnectionService connectionService = new ConnectionService();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
                if (serverAddr != null && serverAddr.length() > 0) {
                    properties.put("serverAddr", serverAddr);
                }
                if (namespace != null && namespace.length() > 0) {
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

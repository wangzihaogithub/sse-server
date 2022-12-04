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

    public static class Local {

    }

    public static class Qos {

    }

    public static class Remote {
        private boolean enabled = false;
        private Nacos nacos;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Nacos getNacos() {
            return nacos;
        }

        public void setNacos(Nacos nacos) {
            this.nacos = nacos;
        }

        public static class Nacos {
            private String serverAddr;
            private String namespace;
            private String serviceName;
            private String clusterName = "DEFAULT";

            private Properties properties = new Properties();

            public Properties buildProperties() {
                Properties properties = new Properties(this.properties);
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
    }
}

package com.github.sseserver.springboot;

import com.github.sseserver.local.LocalController;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/**
 * 自动配置
 *
 * @author wangzihao
 */
@AutoConfigureOrder(Integer.MAX_VALUE - 9)
@Import(SseServerBeanDefinitionRegistrar.class)
@Configuration
public class SseServerAutoConfiguration {
    public static void bindNacos(SseServerProperties.ClusterConfig.Nacos config, Environment environment) {
        for (SseServerProperties.ClusterConfig.Nacos nacos : Arrays.asList(config)) {
            nacos.setClusterName(environment.resolveRequiredPlaceholders(nacos.getClusterName()));
            nacos.setNamespace(environment.resolveRequiredPlaceholders(nacos.getNamespace()));
            nacos.setServerAddr(environment.resolveRequiredPlaceholders(nacos.getServerAddr()));
            nacos.setServiceName(environment.resolveRequiredPlaceholders(nacos.getServiceName()));

            String serverAddr = nacos.getServerAddr();
            if (serverAddr != null && serverAddr.length() > 0) {
                try {
                    URI url = new URI(serverAddr);
                    String scheme = url.getScheme();
                    if ("nacos".equalsIgnoreCase(scheme) || "spring-cloud".equalsIgnoreCase(scheme)) {
                        String rawSchemeSpecificPart = url.getRawSchemeSpecificPart();
                        while (rawSchemeSpecificPart.startsWith("/")) {
                            rawSchemeSpecificPart = rawSchemeSpecificPart.substring(1);
                        }
                        nacos.setServerAddr(rawSchemeSpecificPart);
                    }
                } catch (Exception ignored) {

                }
            }

            String[] props = {
                    "contextPath",
                    "clusterName",
                    "endpoint",
                    "autoRegister",
                    "accessKey",
                    "secretKey",
                    "username",
                    "password"
            };
            for (String prop : props) {
                String placeholder = "${nacos.discovery." + prop + ":${nacos.config." + prop + ":${spring.cloud.nacos." + prop + ":${spring.cloud.nacos.discovery." + prop + ":${spring.cloud.nacos.config." + prop + ":}}}}}";
                try {
                    String placeholderValue = environment.resolvePlaceholders(placeholder);
                    if (placeholderValue.length() > 0 && !Objects.equals(placeholderValue, placeholder)) {
                        nacos.getProperties().put(prop, placeholderValue);
                    }
                } catch (Exception ignored) {

                }
            }
        }
    }

    @Bean
    public SseConnectionServiceMap connectionServiceBeanMap() {
        return new SseConnectionServiceMap();
    }

    @Bean
    public SseServerProperties sseServerProperties() {
        return new SseServerProperties();
    }

    @Bean
    public CommandLineRunner sseCommandLineRunner() {
        return new CommandLineRunner() {
            @Autowired
            private ListableBeanFactory beanFactory;

            @Override
            public void run(String... args) {
                String[] names = beanFactory.getBeanNamesForType(LocalController.class, false, true);
                for (String name : names) {
                    LocalController bean = beanFactory.getBean(name, LocalController.class);
                    bean.registerInstance();
                }
            }
        };
    }
}

package com.github.sseserver.springboot;

import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * 自动配置
 *
 * @author wangzihao
 */
@AutoConfigureOrder(Integer.MAX_VALUE - 9)
@Import(SseServerBeanDefinitionRegistrar.class)
@Configuration(proxyBeanMethods = false)
public class SseServerAutoConfiguration {

    public static void bindNacos(SseServerProperties.Remote.Nacos nacos, Environment environment) throws URISyntaxException {
        nacos.setClusterName(environment.resolveRequiredPlaceholders(nacos.getClusterName()));
        nacos.setNamespace(environment.resolveRequiredPlaceholders(nacos.getNamespace()));
        nacos.setServerAddr(environment.resolveRequiredPlaceholders(nacos.getServerAddr()));
        nacos.setServiceName(environment.resolveRequiredPlaceholders(nacos.getServiceName()));

        String serverAddr = nacos.getServerAddr();
        if (serverAddr != null && serverAddr.length() > 0) {
            URI url = new URI(serverAddr);
            String scheme = url.getScheme();
            if ("nacos".equalsIgnoreCase(scheme) || "spring-cloud".equalsIgnoreCase(scheme)) {
                String rawSchemeSpecificPart = url.getRawSchemeSpecificPart();
                while (rawSchemeSpecificPart.startsWith("/")) {
                    rawSchemeSpecificPart = rawSchemeSpecificPart.substring(1);
                }
                nacos.setServerAddr(rawSchemeSpecificPart);
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
            String placeholderValue = environment.resolvePlaceholders(placeholder);
            if (placeholderValue.length() > 0 && !Objects.equals(placeholderValue, placeholder)) {
                nacos.getProperties().put(prop, placeholderValue);
            }
        }
    }

    @Bean
    public SseServerProperties sseServerProperties(Environment environment) throws URISyntaxException {
        SseServerProperties properties = new SseServerProperties();
        bindNacos(properties.getRemote().getNacos(), environment);
        return properties;
    }
}

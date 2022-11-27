package com.github.sseserver.springboot;

import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 自动配置
 *
 * @author wangzihao
 */
@AutoConfigureOrder(Integer.MAX_VALUE - 9)
@EnableConfigurationProperties(SseServerProperties.class)
@Import(value = {SseServerBeanDefinitionRegistrar.class, SseServerCommandLineRunner.class})
@Configuration(proxyBeanMethods = false)
public class SseServerAutoConfiguration {

}

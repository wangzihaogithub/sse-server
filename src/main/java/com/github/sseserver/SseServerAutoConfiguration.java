package com.github.sseserver;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.GithubSseEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动配置
 *
 * @author wangzihao
 */
@Configuration(proxyBeanMethods = false)
public class SseServerAutoConfiguration {

    @ConditionalOnBean(RequestMappingHandlerAdapter.class)
    @ConditionalOnMissingBean
    @Bean
    public GithubSseEmitterReturnValueHandler githubSseEmitterReturnValueHandler(
            RequestMappingHandlerAdapter requestMappingHandler,
            ListableBeanFactory beanFactory) {
        GithubSseEmitterReturnValueHandler sseHandler = new GithubSseEmitterReturnValueHandler(
                () -> beanFactory.getBeansOfType(HttpMessageConverter.class).values());

        List<HandlerMethodReturnValueHandler> newHandlers = new ArrayList<>();
        newHandlers.add(sseHandler);
        List<HandlerMethodReturnValueHandler> oldHandlers = requestMappingHandler.getReturnValueHandlers();
        if (oldHandlers != null) {
            newHandlers.addAll(oldHandlers);
        }
        requestMappingHandler.setReturnValueHandlers(newHandlers);
        return sseHandler;
    }
}

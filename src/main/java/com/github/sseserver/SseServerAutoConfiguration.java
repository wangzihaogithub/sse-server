package com.github.sseserver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean("githubSseEmitterReturnValueHandler")
    @ConditionalOnMissingBean(GithubSseEmitterReturnValueHandler.class)
    public GithubSseEmitterReturnValueHandler githubSseEmitterReturnValueHandler(
            RequestMappingHandlerAdapter requestMappingHandler) {
        GithubSseEmitterReturnValueHandler sseHandler = new GithubSseEmitterReturnValueHandler(requestMappingHandler::getMessageConverters);

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

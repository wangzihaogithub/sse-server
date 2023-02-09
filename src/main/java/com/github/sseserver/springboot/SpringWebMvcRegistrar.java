package com.github.sseserver.springboot;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.GithubSseEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.ArrayList;
import java.util.List;

public class SpringWebMvcRegistrar {

    public static void registerBeanDefinitionsGithubSseEmitterReturnValueHandler(ListableBeanFactory beanFactory,
                                                                                 BeanDefinitionRegistry definitionRegistry,
                                                                                 String beanName) {
        // 1.GithubSseEmitterReturnValueHandler.class (if not exist)
        if (beanFactory.getBeanNamesForType(GithubSseEmitterReturnValueHandler.class).length != 0) {
            return;
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(GithubSseEmitterReturnValueHandler.class, () -> {
                    RequestMappingHandlerAdapter requestMappingHandler;
                    try {
                        requestMappingHandler = beanFactory.getBean(RequestMappingHandlerAdapter.class);
                    } catch (BeansException e) {
                        return new GithubSseEmitterReturnValueHandler(ArrayList::new);
                    }

                    GithubSseEmitterReturnValueHandler sseHandler = new GithubSseEmitterReturnValueHandler(requestMappingHandler::getMessageConverters);

                    List<HandlerMethodReturnValueHandler> newHandlers = new ArrayList<>();
                    newHandlers.add(sseHandler);
                    List<HandlerMethodReturnValueHandler> oldHandlers = requestMappingHandler.getReturnValueHandlers();
                    if (oldHandlers != null) {
                        newHandlers.addAll(oldHandlers);
                    }
                    requestMappingHandler.setReturnValueHandlers(newHandlers);
                    return sseHandler;
                })
                .setLazyInit(false);
        definitionRegistry.registerBeanDefinition(beanName, builder.getBeanDefinition());
    }

}

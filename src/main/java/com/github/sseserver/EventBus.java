package com.github.sseserver;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 事件总线
 *
 * @author wangzihaogithub 2022-11-12
 */
public interface EventBus {

    /* ConnectListener */

    <ACCESS_USER> void addConnectListener(String accessToken, String channel, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER> void addConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER> void addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    /* DisConnectListener */

    <ACCESS_USER> void addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER> void addDisConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    /* ListeningChangeWatch */

    <ACCESS_USER> void addListeningChangeWatch(Consumer<ChangeEvent<ACCESS_USER, Set<String>>> watch);

}

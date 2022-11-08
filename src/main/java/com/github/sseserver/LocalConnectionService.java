package com.github.sseserver;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 单机长连接(非分布式)
 * 1. 如果用nginx代理, 要加下面的配置
 * # 长连接配置
 * proxy_buffering off;
 * proxy_read_timeout 7200s;
 * proxy_pass http://xx.xx.xx.xx:xxx;
 * proxy_http_version 1.1; #nginx默认是http1.0, 改为1.1 支持长连接, 和后端保持长连接,复用,防止出现文件句柄打开数量过多的错误
 * proxy_set_header Connection ""; # 去掉Connection的close字段
 *
 * @author hao 2021年12月7日19:27:41
 */
public interface LocalConnectionService {

    /* connect */

    ScheduledExecutorService getScheduled();

    /**
     * 创建用户连接并返回 SseEmitter
     *
     * @param accessUser    用户令牌
     * @param keepaliveTime 链接最大保持时间 ，0表示不过期。默认30秒，超过时间未完成会抛出异常：AsyncRequestTimeoutException
     * @return SseEmitter
     */
    <ACCESS_USER extends AccessUser & AccessToken> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime);

    <ACCESS_USER extends AccessUser & AccessToken> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime, Map<String, Object> attributeMap);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> disconnectByUserId(Object userId);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> disconnectByAccessToken(String accessToken);

    <ACCESS_USER extends AccessUser & AccessToken> SseEmitter<ACCESS_USER> disconnectByConnectionId(Long connectionId);

    /* listener*/

    <ACCESS_USER extends AccessUser & AccessToken> void addConnectListener(String accessToken, String channel, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser & AccessToken> void addConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser & AccessToken> void addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser & AccessToken> void addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser & AccessToken> void addDisConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    /* getConnection */

    <ACCESS_USER extends AccessUser & AccessToken> SseEmitter<ACCESS_USER> getConnectionById(Long connectionId);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> getConnectionByListener(String sseListenerName);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> getConnectionByChannel(String channel);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> getConnectionByAccessToken(String accessToken);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> getConnectionByUserId(Object userId);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> getConnectionByTenantId(Object tenantId);

    <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> getConnectionAll();

    /* send */

    int send(Collection<SseEmitter> sseEmitterList, SseEventBuilder message);

    int sendAll(SseEventBuilder message);

    int sendAllByClientListener(SseEventBuilder message, String sseListenerName);

    default int sendAllByClientListener(Object data, String sseListenerName) {
        return sendAllByClientListener(SseEmitter.event(sseListenerName, data), sseListenerName);
    }

    int sendByConnectionId(Collection<Long> connectionIds, SseEventBuilder message);

    default int sendByConnectionId(Long connectionId, SseEventBuilder message) {
        return sendByConnectionId(Collections.singletonList(connectionId), message);
    }

    int sendByChannel(Collection<String> channels, SseEventBuilder message);

    default int sendByChannel(String channel, SseEventBuilder message) {
        return sendByChannel(Collections.singletonList(channel), message);
    }

    int sendByAccessToken(Collection<String> accessTokens, SseEventBuilder message);

    default int sendByAccessToken(String accessToken, SseEventBuilder message) {
        return sendByAccessToken(Collections.singletonList(accessToken), message);
    }

    int sendByUserId(Collection<?> userIds, SseEventBuilder message);

    default int sendByUserId(Object userId, SseEventBuilder message) {
        return sendByUserId(Collections.singletonList(userId), message);
    }

    int sendByTenantId(Collection<?> tenantIds, SseEventBuilder message);

    default int sendByTenantId(Object tenantId, SseEventBuilder message) {
        return sendByTenantId(Collections.singletonList(tenantId), message);
    }

    int sendByTenantIdClientListener(Object tenantId, SseEventBuilder message, String sseListenerName);

    default int sendByTenantIdClientListener(Object tenantId, Object message, String sseListenerName) {
        return sendByTenantIdClientListener(tenantId, SseEmitter.event(sseListenerName, message), sseListenerName);
    }

    /* getUser */

    <ACCESS_USER extends AccessUser & AccessToken> List<ACCESS_USER> getUsers();

    default <ACCESS_USER extends AccessUser & AccessToken> List<ACCESS_USER> getUsersByListener(String sseListenerName) {
        return getConnectionAll().stream()
                .filter(e -> e.existListener(sseListenerName))
                .map(e -> (ACCESS_USER) e.getAccessUser())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    default <ACCESS_USER extends AccessUser & AccessToken> List<ACCESS_USER> getUsersByTenantIdListener(Object tenantId, String sseListenerName) {
        return getConnectionByTenantId(tenantId).stream()
                .filter(e -> e.existListener(sseListenerName))
                .map(e -> (ACCESS_USER) e.getAccessUser())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    <ACCESS_USER extends AccessUser & AccessToken> ACCESS_USER getUser(Object userId);

    boolean isOnline(Object userId);

    List<Long> getConnectionIds();

    List<String> getAccessTokens();

    List<String> getUserIds();

    List<String> getUserIdsByListener(String sseListenerName);

    default List<Integer> getUserIdsIntByTenantIdListener(Object tenantId, String sseListenerName) {
        return getUsersByTenantIdListener(tenantId, sseListenerName).stream()
                .map(e -> Objects.toString(e.getId(), null))
                .filter(e -> e != null && e.length() > 0)
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }

    default List<Integer> getUserIdsIntByListener(String sseListenerName) {
        return getUserIdsByListener(sseListenerName).stream()
                .filter(e -> e != null && e.length() > 0)
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }

    default List<Integer> getUserIdsInt() {
        return getUserIds().stream()
                .filter(e -> e != null && e.length() > 0)
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }

    List<String> getTenantIds();

    default List<Integer> getTenantIdsInt() {
        return getTenantIds().stream()
                .filter(e -> e != null && e.length() > 0)
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }

    List<String> getChannels();

    /* count */

    /**
     * 获取当前登录端数量
     */
    int getAccessTokenCount();

    /**
     * 获取当前用户数量
     */
    int getUserCount();

    /**
     * 获取当前连接数量
     */
    int getConnectionCount();

    /**
     * 可以在spring里多实例 （例如：HR系统的用户链接，猎头系统的用户链接）
     *
     * @return spring的bean名称
     */
    String getBeanName();
}
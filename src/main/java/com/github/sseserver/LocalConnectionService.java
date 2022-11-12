package com.github.sseserver;

import com.github.sseserver.qos.QosCompletableFuture;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

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
public interface LocalConnectionService extends Sender<Integer>, EventBus {

    ScheduledExecutorService getScheduled();

    /* QOS */

    <ACCESS_USER> Sender<QosCompletableFuture<ACCESS_USER>> atLeastOnce();

    /* connect */

    /**
     * 创建用户连接并返回 SseEmitter
     *
     * @param accessUser    用户令牌
     * @param keepaliveTime 链接最大保持时间 ，0表示不过期。默认30秒，超过时间未完成会抛出异常：AsyncRequestTimeoutException
     * @return SseEmitter
     */
    <ACCESS_USER> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime);

    <ACCESS_USER> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime, Map<String, Object> attributeMap);

    /* disconnect */

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByUserId(Serializable userId);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByAccessToken(String accessToken);

    <ACCESS_USER> SseEmitter<ACCESS_USER> disconnectByConnectionId(Long connectionId);


    /* getConnection */

    <ACCESS_USER> Collection<SseEmitter<ACCESS_USER>> getConnectionAll();

    <ACCESS_USER> SseEmitter<ACCESS_USER> getConnectionById(Long connectionId);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByListening(String sseListenerName);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByChannel(String channel);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByAccessToken(String accessToken);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByUserId(Serializable userId);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByTenantId(Serializable tenantId);

    /* getUser */

    boolean isOnline(Serializable userId);

    <ACCESS_USER> ACCESS_USER getUser(Serializable userId);

    <ACCESS_USER> List<ACCESS_USER> getUsers();

    <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName);

    <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName);

    /* getUserIds */

    <T> Collection<T> getUserIds(Class<T> type);

    <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type);

    <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type);

    /* getConnectionId */

    Collection<Long> getConnectionIds();

    /* getAccessToken */

    Collection<String> getAccessTokens();

    /* getTenantId */

    <T> List<T> getTenantIds(Class<T> type);

    /* getChannels */

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
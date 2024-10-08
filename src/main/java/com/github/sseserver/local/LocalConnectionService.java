package com.github.sseserver.local;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.DistributedConnectionService;
import com.github.sseserver.SendService;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * 长连接
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
public interface LocalConnectionService extends DistributedConnectionService, ConnectionQueryService, SendService<Integer> {

    ScheduledExecutorService getScheduled();

    /* connect */

    /**
     * 创建用户连接并返回 SseEmitter
     *
     * @param accessUser    用户令牌
     * @param keepaliveTime 链接最大保持时间 ，0表示不过期。默认30秒，超过时间未完成会抛出异常：AsyncRequestTimeoutException
     * @param attributeMap  {@link SseEmitter#getAttributeMap()}
     * @return SseEmitter
     */
    <ACCESS_USER> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime, Map<String, Object> attributeMap);

    /* disconnect */

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByUserId(Serializable userId);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByAccessToken(String accessToken);

    <ACCESS_USER> SseEmitter<ACCESS_USER> disconnectByConnectionId(Long connectionId);

    <ACCESS_USER> SseEmitter<ACCESS_USER> disconnectByConnectionId(Long connectionId, Long duration, Long sessionDuration);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByConnectionIds(Collection<Long> connectionIds);

    /**
     * 清零已统计的在线时长
     *
     * @param userId        用户ID
     * @param <ACCESS_USER> 用户
     * @return 清空的链接
     */
    default <ACCESS_USER> List<SseEmitter<ACCESS_USER>> clearDurationByUserId(Serializable userId) {
        return setDurationByUserId(userId, 0L);
    }

    default <ACCESS_USER> List<SseEmitter<ACCESS_USER>> clearDurationByAccessToken(String accessToken) {
        return setDurationByAccessToken(accessToken, 0L);
    }

    default <ACCESS_USER> SseEmitter<ACCESS_USER> clearDurationByConnectionId(Long connectionId) {
        return setDurationByConnectionId(connectionId, 0L);
    }

    /**
     * 修改已统计的在线时长
     *
     * @param userId         用户ID
     * @param durationSecond 在线时长（秒）
     * @param <ACCESS_USER>  用户
     * @return 清空的链接
     */
    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> setDurationByUserId(Serializable userId, long durationSecond);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> setDurationByAccessToken(String accessToken, long durationSecond);

    <ACCESS_USER> SseEmitter<ACCESS_USER> setDurationByConnectionId(Long connectionId, long durationSecond);

    /* getConnectionId */

    Collection<Long> getConnectionIds();

    /* getConnection */

    <ACCESS_USER> Collection<SseEmitter<ACCESS_USER>> getConnectionAll();

    <ACCESS_USER> SseEmitter<ACCESS_USER> getConnectionById(Long connectionId);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByListening(String sseListenerName);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByChannel(String channel);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByAccessToken(String accessToken);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByUserId(Serializable userId);

    <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByTenantId(Serializable tenantId);

    /* ConnectListener */

    <ACCESS_USER> void addConnectListener(String accessToken, String channel, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER> void addConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER> void addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    /* DisConnectListener */

    <ACCESS_USER> void addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER> void addDisConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    /* ListeningChangeWatch */

    <ACCESS_USER> void addListeningChangeWatch(Consumer<SseChangeEvent<ACCESS_USER, Set<String>>> watch);

}
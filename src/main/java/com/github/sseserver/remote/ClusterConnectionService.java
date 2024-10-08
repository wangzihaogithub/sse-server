package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.ReferenceCounted;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public interface ClusterConnectionService extends ConnectionQueryService, SendService<ClusterCompletableFuture<Integer, ClusterConnectionService>> {

    static ClusterConnectionService newInstance(Supplier<LocalConnectionService> localSupplier,
                                                Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier,
                                                boolean primary) {
        return new ClusterConnectionServiceImpl(localSupplier, remoteSupplier, primary);
    }

    boolean isPrimary();

    /* getUsers */

    <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersAsync(SseServerProperties.AutoType autoType);

    <ACCESS_USER> ClusterCompletableFuture<ACCESS_USER, ClusterConnectionService> getUserAsync(Serializable userId);

    <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersAsync();

    <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersByListeningAsync(String sseListenerName);

    <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersByTenantIdListeningAsync(Serializable tenantId, String sseListenerName);

    /* getUserIds */

    <T> ClusterCompletableFuture<List<T>, ClusterConnectionService> getUserIdsAsync(Class<T> type);

    <T> ClusterCompletableFuture<List<T>, ClusterConnectionService> getUserIdsByListeningAsync(String sseListenerName, Class<T> type);

    <T> ClusterCompletableFuture<List<T>, ClusterConnectionService> getUserIdsByTenantIdListeningAsync(Serializable tenantId, String sseListenerName, Class<T> type);

    /* getConnection */

    <ACCESS_USER> ClusterCompletableFuture<List<ConnectionDTO<ACCESS_USER>>, ClusterConnectionService> getConnectionDTOAllAsync(SseServerProperties.AutoType autoType);

    ClusterCompletableFuture<List<ConnectionByUserIdDTO>, ClusterConnectionService> getConnectionDTOByUserIdAsync(Serializable userId);

    /* disconnect */

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByUserId(Serializable userId);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByAccessToken(String accessToken);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionId(Long connectionId);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionId(Long connectionId, Long duration, Long sessionDuration);

    ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionIds(Collection<Long> connectionIds);

    /**
     * 清零已统计的在线时长
     *
     * @param userId 用户ID
     * @return 清空的链接
     */
    default ClusterCompletableFuture<Integer, ClusterConnectionService> clearDurationByUserId(Serializable userId) {
        return setDurationByUserId(userId, 0L);
    }

    default ClusterCompletableFuture<Integer, ClusterConnectionService> clearDurationByAccessToken(String accessToken) {
        return setDurationByAccessToken(accessToken, 0L);
    }

    /**
     * 修改已统计的在线时长
     *
     * @param userId         用户ID
     * @param durationSecond 在线时长（秒）
     * @return 修改的链接
     */
    ClusterCompletableFuture<Integer, ClusterConnectionService> setDurationByUserId(Serializable userId, long durationSecond);

    ClusterCompletableFuture<Integer, ClusterConnectionService> setDurationByAccessToken(String accessToken, long durationSecond);

}

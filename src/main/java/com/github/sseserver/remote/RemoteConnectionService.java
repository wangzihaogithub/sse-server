package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.springboot.SseServerProperties;

import java.io.Closeable;
import java.io.Serializable;
import java.net.URL;
import java.util.Collection;
import java.util.List;

public interface RemoteConnectionService extends ConnectionQueryService, SendService<RemoteCompletableFuture<Integer, RemoteConnectionService>>, Closeable {

    URL getRemoteUrl();

    @Override
    void close();

    String getId();

    RemoteCompletableFuture<Boolean, RemoteConnectionService> isOnlineAsync(Serializable userId);

    <ACCESS_USER> RemoteCompletableFuture<ACCESS_USER, RemoteConnectionService> getUserAsync(Serializable userId);

    <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersAsync();

    <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersAsync(SseServerProperties.AutoType autoType);

    <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersByListeningAsync(String sseListenerName);

    <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersByTenantIdListeningAsync(Serializable tenantId, String sseListenerName);

    /* getConnection */

    <ACCESS_USER> RemoteCompletableFuture<List<ConnectionDTO<ACCESS_USER>>, RemoteConnectionService> getConnectionDTOAllAsync(SseServerProperties.AutoType autoTypeEnum);

    RemoteCompletableFuture<List<ConnectionByUserIdDTO>, RemoteConnectionService> getConnectionDTOByUserIdAsync(Serializable userId);

    /* getUserIds */

    <T> RemoteCompletableFuture<Collection<T>, RemoteConnectionService> getUserIdsAsync(Class<T> type);

    <T> RemoteCompletableFuture<List<T>, RemoteConnectionService> getUserIdsByListeningAsync(String sseListenerName, Class<T> type);

    <T> RemoteCompletableFuture<List<T>, RemoteConnectionService> getUserIdsByTenantIdListeningAsync(Serializable tenantId, String sseListenerName, Class<T> type);

    /* getAccessToken */

    RemoteCompletableFuture<Collection<String>, RemoteConnectionService> getAccessTokensAsync();

    /* getTenantId */

    <T> RemoteCompletableFuture<List<T>, RemoteConnectionService> getTenantIdsAsync(Class<T> type);

    /* getChannels */

    RemoteCompletableFuture<List<String>, RemoteConnectionService> getChannelsAsync();

    /**
     * 获取当前连接数量
     */
    RemoteCompletableFuture<Integer, RemoteConnectionService> getConnectionCountAsync();

    /* disconnect */

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByUserId(Serializable userId);

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByAccessToken(String accessToken);

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByConnectionId(Long connectionId);

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByConnectionId(Long connectionId, Long duration, Long sessionDuration);

    RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByConnectionIds(Collection<Long> connectionIds);
}

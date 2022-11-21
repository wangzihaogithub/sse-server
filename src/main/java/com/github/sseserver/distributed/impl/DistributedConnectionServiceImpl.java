package com.github.sseserver.distributed.impl;

import com.github.sseserver.distributed.DistributedCompletableFuture;
import com.github.sseserver.distributed.DistributedConnectionService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.SseEmitter;
import com.github.sseserver.remote.RemoteCompletableFuture;
import com.github.sseserver.remote.RemoteConnectionService;
import com.github.sseserver.util.CompletableFutureUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DistributedConnectionServiceImpl implements DistributedConnectionService {
    private LocalConnectionService localConnectionService;
    private List<RemoteConnectionService> remoteConnectionServiceList;

    public void onStateUpdate() {
        for (RemoteConnectionService remote : remoteConnectionServiceList) {

        }
    }

    @Override
    public boolean isOnline(Serializable userId) {
        boolean online = localConnectionService.isOnline(userId);
        if (online) {
            return true;
        }
        for (RemoteConnectionService remote : remoteConnectionServiceList) {
            if (remote.isOnline(userId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        ACCESS_USER user = localConnectionService.getUser(userId);
        if (user != null) {
            return user;
        }
        for (RemoteConnectionService remote : remoteConnectionServiceList) {
            user = remote.getUser(userId);
            if (user != null) {
                return user;
            }
        }
        return null;
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        return null;
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        return null;
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        return null;
    }

    @Override
    public <T> Collection<T> getUserIds(Class<T> type) {
        return null;
    }

    @Override
    public <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type) {
        return null;
    }

    @Override
    public <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type) {
        return null;
    }

    @Override
    public Collection<String> getAccessTokens() {
        return null;
    }

    @Override
    public <T> List<T> getTenantIds(Class<T> type) {
        return null;
    }

    @Override
    public List<String> getChannels() {
        return null;
    }

    @Override
    public int getAccessTokenCount() {
        return 0;
    }

    @Override
    public int getUserCount() {
        return 0;
    }

    @Override
    public int getConnectionCount() {
        return 0;
    }

    private void handleSendError(RemoteCompletableFuture<Integer> remoteFuture, Throwable throwable) {

    }

    private void handleDisconnectError(RemoteCompletableFuture<Integer> remoteFuture, Throwable throwable) {

    }

    @Override
    public DistributedCompletableFuture<Integer> sendAll(String eventName, Serializable body) {
        List<RemoteCompletableFuture<Integer>> remoteFutureList = new ArrayList<>(remoteConnectionServiceList.size());
        for (RemoteConnectionService remote : remoteConnectionServiceList) {
            RemoteCompletableFuture<Integer> future = remote.sendAll(eventName, body);
            remoteFutureList.add(future);
        }

        int localCount = localConnectionService.sendAll(eventName, body);

        DistributedCompletableFuture<Integer> future = new DistributedCompletableFuture<>();
        CompletableFutureUtil.join(remoteFutureList, future, () -> {
            int remoteCount = 0;
            for (RemoteCompletableFuture<Integer> remoteFuture : remoteFutureList) {
                try {
                    Integer count = remoteFuture.get();
                    if (count != null) {
                        remoteCount += count;
                    }
                } catch (Throwable throwable) {
                    handleSendError(remoteFuture, throwable);
                }
            }
            return localCount + remoteCount;
        });
        return future;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendAllListening(String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByChannel(Collection<String> channels, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByChannelListening(Collection<String> channels, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> disconnectByUserId(Serializable userId) {
        List<RemoteCompletableFuture<Integer>> remoteFutureList = new ArrayList<>(remoteConnectionServiceList.size());
        for (RemoteConnectionService remote : remoteConnectionServiceList) {
            RemoteCompletableFuture<Integer> future = remote.disconnectByUserId(userId);
            remoteFutureList.add(future);
        }

        List<SseEmitter<Object>> localList = localConnectionService.disconnectByUserId(userId);

        DistributedCompletableFuture<Integer> future = new DistributedCompletableFuture<>();
        CompletableFutureUtil.join(remoteFutureList, future, () -> {
            int remoteCount = 0;
            for (RemoteCompletableFuture<Integer> remoteFuture : remoteFutureList) {
                try {
                    Integer count = remoteFuture.get();
                    if (count != null) {
                        remoteCount += count;
                    }
                } catch (Throwable throwable) {
                    handleDisconnectError(remoteFuture, throwable);
                }
            }
            return localList.size() + remoteCount;
        });
        return future;
    }

    @Override
    public DistributedCompletableFuture<Integer> disconnectByAccessToken(String accessToken) {
        return null;
    }

    @Override
    public DistributedCompletableFuture<Integer> disconnectByConnectionId(Long connectionId) {
        return null;
    }
}

package com.github.sseserver.remote;

import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.qos.QosCompletableFuture;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.ReferenceCounted;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

public class DistributedConnectionServiceImpl implements DistributedConnectionService {
    private final Supplier<LocalConnectionService> localSupplier;
    private final Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier;

    public DistributedConnectionServiceImpl(Supplier<LocalConnectionService> localSupplier,
                                            Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier) {
        this.localSupplier = localSupplier;
        this.remoteSupplier = remoteSupplier;
    }

    public LocalConnectionService getLocalService() {
        return localSupplier.get();
    }

    @Override
    public <ACCESS_USER> SendService<QosCompletableFuture<ACCESS_USER>> atLeastOnce() {
        return null;
    }

    public ReferenceCounted<List<RemoteConnectionService>> getRemoteServiceRef() {
        if (remoteSupplier == null) {
            return new ReferenceCounted<>(Collections.emptyList());
        }
        return remoteSupplier.get();
    }

    @Override
    public boolean isOnline(Serializable userId) {
        boolean online = getLocalService().isOnline(userId);
        if (online) {
            return true;
        }
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                if (remote.isOnline(userId)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        ACCESS_USER user = getLocalService().getUser(userId);
        if (user != null) {
            return user;
        }
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                user = remote.getUser(userId);
                if (user != null) {
                    return user;
                }
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

    @Override
    public DistributedCompletableFuture<Integer> sendAll(String eventName, Serializable body) {
        return broadcast(
                e -> e.sendAll(eventName, body),
                e -> e.sendAll(eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendAllListening(String eventName, Serializable body) {
        return broadcast(
                e -> e.sendAllListening(eventName, body),
                e -> e.sendAllListening(eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByChannel(Collection<String> channels, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByChannel(channels, eventName, body),
                e -> e.sendByChannel(channels, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByChannelListening(Collection<String> channels, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByChannelListening(channels, eventName, body),
                e -> e.sendByChannelListening(channels, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByAccessToken(accessTokens, eventName, body),
                e -> e.sendByAccessToken(accessTokens, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByAccessTokenListening(accessTokens, eventName, body),
                e -> e.sendByAccessTokenListening(accessTokens, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByUserId(userIds, eventName, body),
                e -> e.sendByUserId(userIds, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByUserIdListening(userIds, eventName, body),
                e -> e.sendByUserIdListening(userIds, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByTenantId(tenantIds, eventName, body),
                e -> e.sendByTenantId(tenantIds, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return broadcast(
                e -> e.sendByTenantIdListening(tenantIds, eventName, body),
                e -> e.sendByTenantIdListening(tenantIds, eventName, body)
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> disconnectByUserId(Serializable userId) {
        return broadcast(
                e -> e.disconnectByUserId(userId),
                e -> e.disconnectByUserId(userId).size()
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> disconnectByAccessToken(String accessToken) {
        return broadcast(
                e -> e.disconnectByAccessToken(accessToken),
                e -> e.disconnectByAccessToken(accessToken).size()
        );
    }

    @Override
    public DistributedCompletableFuture<Integer> disconnectByConnectionId(Long connectionId) {
        return broadcast(
                e -> e.disconnectByConnectionId(connectionId),
                e -> e.disconnectByConnectionId(connectionId) != null ? 1 : 0
        );
    }

    protected DistributedCompletableFuture<Integer> broadcast(
            Function<RemoteConnectionService, RemoteCompletableFuture<Integer, RemoteConnectionService>> remoteFunction,
            Function<LocalConnectionService, Integer> localFunction) {
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            List<RemoteConnectionService> serviceList = ref.get();

            List<RemoteCompletableFuture<Integer, RemoteConnectionService>> remoteFutureList = new ArrayList<>(serviceList.size());
            for (RemoteConnectionService remote : serviceList) {
                RemoteCompletableFuture<Integer, RemoteConnectionService> future = remoteFunction.apply(remote);
                remoteFutureList.add(future);
            }

            Integer localCount = localFunction.apply(getLocalService());

            DistributedCompletableFuture<Integer> future = new DistributedCompletableFuture<>();
            CompletableFuture.join(remoteFutureList, future, () -> {
                int remoteCount = 0;
                InterruptedException interruptedException = null;
                for (RemoteCompletableFuture<Integer, RemoteConnectionService> remoteFuture : remoteFutureList) {
                    try {
                        Integer count;
                        if (interruptedException != null) {
                            if (remoteFuture.isDone()) {
                                count = remoteFuture.get();
                            } else {
                                continue;
                            }
                        } else {
                            count = remoteFuture.get();
                        }
                        if (count != null) {
                            remoteCount += count;
                        }
                    } catch (InterruptedException exception) {
                        interruptedException = exception;
                    } catch (ExecutionException exception) {
                        handleRemoteException(remoteFuture, exception);
                    }
                }
                return localCount + remoteCount;
            });
            return future;
        }
    }

    protected void handleRemoteException(RemoteCompletableFuture<Integer, RemoteConnectionService> remoteFuture, ExecutionException exception) {

    }

}

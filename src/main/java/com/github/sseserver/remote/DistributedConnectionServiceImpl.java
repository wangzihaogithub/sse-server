package com.github.sseserver.remote;

import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.SseEmitter;
import com.github.sseserver.util.CompletableFuture;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public class DistributedConnectionServiceImpl implements DistributedConnectionService {
    private final Supplier<LocalConnectionService> localConnectionServiceSupplier;
    private final Supplier<ServiceDiscoveryService> serviceDiscoveryServiceSupplier;

    public DistributedConnectionServiceImpl(Supplier<LocalConnectionService> localConnectionServiceSupplier,
                                            Supplier<ServiceDiscoveryService> serviceDiscoveryServiceSupplier) {
        this.localConnectionServiceSupplier = localConnectionServiceSupplier;
        this.serviceDiscoveryServiceSupplier = serviceDiscoveryServiceSupplier;
    }

    public LocalConnectionService getLocalConnectionService() {
        return localConnectionServiceSupplier.get();
    }

    @Override
    public List<RemoteConnectionService> getRemoteConnectionServiceList() {
        if (serviceDiscoveryServiceSupplier == null) {
            return Collections.emptyList();
        }
        try {
            return serviceDiscoveryServiceSupplier.get().getServiceList();
        } catch (Exception e) {
            throw e;
//            return Collections.emptyList();
        }
    }

    @Override
    public boolean isOnline(Serializable userId) {
        boolean online = getLocalConnectionService().isOnline(userId);
        if (online) {
            return true;
        }
        for (RemoteConnectionService remote : getRemoteConnectionServiceList()) {
            if (remote.isOnline(userId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        ACCESS_USER user = getLocalConnectionService().getUser(userId);
        if (user != null) {
            return user;
        }
        for (RemoteConnectionService remote : getRemoteConnectionServiceList()) {
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

    private void handleSendError(RemoteCompletableFuture<Integer> remoteFuture, Exception exception) {

    }

    private void handleDisconnectError(RemoteCompletableFuture<Integer> remoteFuture, Exception exception) {

    }

    @Override
    public DistributedCompletableFuture<Integer> sendAll(String eventName, Serializable body) {
        List<RemoteCompletableFuture<Integer>> remoteFutureList = new ArrayList<>(getRemoteConnectionServiceList().size());
        for (RemoteConnectionService remote : getRemoteConnectionServiceList()) {
            RemoteCompletableFuture<Integer> future = remote.sendAll(eventName, body);
            remoteFutureList.add(future);
        }

        int localCount = getLocalConnectionService().sendAll(eventName, body);

        DistributedCompletableFuture<Integer> future = new DistributedCompletableFuture<>();
        CompletableFuture.join(remoteFutureList, future, () -> {
            int remoteCount = 0;
            InterruptedException interruptedException = null;
            for (RemoteCompletableFuture<Integer> remoteFuture : remoteFutureList) {
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
                    handleSendError(remoteFuture, exception);
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
        List<RemoteCompletableFuture<Integer>> remoteFutureList = new ArrayList<>(getRemoteConnectionServiceList().size());
        for (RemoteConnectionService remote : getRemoteConnectionServiceList()) {
            RemoteCompletableFuture<Integer> future = remote.disconnectByUserId(userId);
            remoteFutureList.add(future);
        }

        List<SseEmitter<Object>> localList = getLocalConnectionService().disconnectByUserId(userId);

        DistributedCompletableFuture<Integer> future = new DistributedCompletableFuture<>();
        CompletableFuture.join(remoteFutureList, future, () -> {
            int remoteCount = 0;
            InterruptedException interruptedException = null;
            for (RemoteCompletableFuture<Integer> remoteFuture : remoteFutureList) {
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
                    handleDisconnectError(remoteFuture, exception);
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

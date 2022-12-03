package com.github.sseserver.remote;

import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

public class DistributedConnectionServiceImpl implements DistributedConnectionService {
    private final static Logger log = LoggerFactory.getLogger(DistributedConnectionServiceImpl.class);
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

    public ReferenceCounted<List<RemoteConnectionService>> getRemoteServiceRef() {
        if (remoteSupplier == null) {
            return new ReferenceCounted<>(Collections.emptyList());
        }
        return remoteSupplier.get();
    }

    @Override
    public boolean isOnline(Serializable userId) {
        if (getLocalService().isOnline(userId)) {
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
        ACCESS_USER result = getLocalService().getUser(userId);
        if (result != null) {
            return result;
        }
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result = remote.getUser(userId);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        Set<ACCESS_USER> result = new LinkedHashSet<>(getLocalService().getUsers());
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getUsers());
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        Set<ACCESS_USER> result = new LinkedHashSet<>(getLocalService().getUsersByListening(sseListenerName));
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getUsersByListening(sseListenerName));
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        Set<ACCESS_USER> result = new LinkedHashSet<>(getLocalService().getUsersByTenantIdListening(tenantId, sseListenerName));
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getUsersByTenantIdListening(tenantId, sseListenerName));
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public <T> Collection<T> getUserIds(Class<T> type) {
        Set<T> result = new LinkedHashSet<>(getLocalService().getUserIds(type));
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getUserIds(type));
            }
        }
        return result;
    }

    @Override
    public <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type) {
        Set<T> result = new LinkedHashSet<>(getLocalService().getUserIdsByListening(sseListenerName, type));
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getUserIdsByListening(sseListenerName, type));
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type) {
        Set<T> result = new LinkedHashSet<>(getLocalService().getUserIdsByTenantIdListening(tenantId, sseListenerName, type));
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getUserIdsByTenantIdListening(tenantId, sseListenerName, type));
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public Collection<String> getAccessTokens() {
        Set<String> result = new LinkedHashSet<>(getLocalService().getAccessTokens());
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getAccessTokens());
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public <T> List<T> getTenantIds(Class<T> type) {
        Set<T> result = new LinkedHashSet<>(getLocalService().getTenantIds(type));
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getTenantIds(type));
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public List<String> getChannels() {
        Set<String> result = new LinkedHashSet<>(getLocalService().getChannels());
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result.addAll(remote.getChannels());
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public int getAccessTokenCount() {
        return getAccessTokens().size();
    }

    @Override
    public int getUserCount() {
        return getUserIds(String.class).size();
    }

    @Override
    public int getConnectionCount() {
        int result = getLocalService().getConnectionCount();
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            for (RemoteConnectionService remote : ref.get()) {
                result += remote.getConnectionCount();
            }
        }
        return result;
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
        log.debug("RemoteConnectionService {} , RemoteException {}",
                remoteFuture.getClient(),exception, exception);
    }

}

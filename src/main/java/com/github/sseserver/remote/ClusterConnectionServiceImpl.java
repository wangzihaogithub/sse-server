package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.LambdaUtil;
import com.github.sseserver.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class ClusterConnectionServiceImpl implements ClusterConnectionService {
    private final static Logger log = LoggerFactory.getLogger(ClusterConnectionServiceImpl.class);
    private final Supplier<LocalConnectionService> localSupplier;
    private final Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier;

    public ClusterConnectionServiceImpl(Supplier<LocalConnectionService> localSupplier,
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
        ClusterCompletableFuture<Boolean, ClusterConnectionService> future = mapReduce(
                e -> e.isOnlineAsync(userId),
                e -> false,
                Boolean::logicalOr,
                LambdaUtil.defaultFalse());
        return future.block();
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        ACCESS_USER result = getLocalService().getUser(userId);
        if (result != null) {
            return result;
        }
        ClusterCompletableFuture<ACCESS_USER, ClusterConnectionService> future = mapReduce(
                e -> e.getUserAsync(userId),
                e -> null,
                LambdaUtil.filterNull(),
                LambdaUtil.defaultNull());
        return future.block();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> future = mapReduce(
                RemoteConnectionService::getUsersAsync,
                ConnectionQueryService::getUsers,
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
        return future.block();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> future = mapReduce(
                e -> e.getUsersByListeningAsync(sseListenerName),
                e -> e.getUsersByListening(sseListenerName),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
        return future.block();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> future = mapReduce(
                e -> e.getUsersByTenantIdListeningAsync(tenantId, sseListenerName),
                e -> e.getUsersByTenantIdListening(tenantId, sseListenerName),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
        return future.block();
    }

    @Override
    public <T> Collection<T> getUserIds(Class<T> type) {
        ClusterCompletableFuture<Collection<T>, ClusterConnectionService> future = mapReduce(
                e -> e.getUserIdsAsync(type),
                e -> e.getUserIds(type),
                LambdaUtil.reduceList(),
                LambdaUtil.noop(),
                LinkedHashSet::new);
        return future.block();
    }

    @Override
    public <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type) {
        ClusterCompletableFuture<List<T>, ClusterConnectionService> future = mapReduce(
                e -> e.getUserIdsByListeningAsync(sseListenerName, type),
                e -> e.getUserIdsByListening(sseListenerName, type),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
        return future.block();
    }

    @Override
    public <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type) {
        ClusterCompletableFuture<List<T>, ClusterConnectionService> future = mapReduce(
                e -> e.getUserIdsByTenantIdListeningAsync(tenantId, sseListenerName, type),
                e -> e.getUserIdsByTenantIdListening(tenantId, sseListenerName, type),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
        return future.block();
    }

    @Override
    public Collection<String> getAccessTokens() {
        ClusterCompletableFuture<Collection<String>, ClusterConnectionService> future = mapReduce(
                RemoteConnectionService::getAccessTokensAsync,
                ConnectionQueryService::getAccessTokens,
                LambdaUtil.reduceList(),
                LambdaUtil.noop(),
                LinkedHashSet::new);
        return future.block();
    }

    @Override
    public <T> List<T> getTenantIds(Class<T> type) {
        ClusterCompletableFuture<List<T>, ClusterConnectionService> future = mapReduce(
                e -> e.getTenantIdsAsync(type),
                e -> e.getTenantIds(type),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
        return future.block();
    }

    @Override
    public List<String> getChannels() {
        ClusterCompletableFuture<List<String>, ClusterConnectionService> future = mapReduce(
                RemoteConnectionService::getChannelsAsync,
                ConnectionQueryService::getChannels,
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
        return future.block();
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
        ClusterCompletableFuture<Integer, ClusterConnectionService> future = mapReduce(
                RemoteConnectionService::getConnectionCountAsync,
                ConnectionQueryService::getConnectionCount,
                Integer::sum,
                LambdaUtil.defaultZero());
        return future.block();
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendAll(String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendAll(eventName, body),
                e -> e.sendAll(eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendAllListening(String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendAllListening(eventName, body),
                e -> e.sendAllListening(eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByChannel(Collection<String> channels, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByChannel(channels, eventName, body),
                e -> e.sendByChannel(channels, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByChannelListening(Collection<String> channels, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByChannelListening(channels, eventName, body),
                e -> e.sendByChannelListening(channels, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByAccessToken(accessTokens, eventName, body),
                e -> e.sendByAccessToken(accessTokens, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByAccessTokenListening(accessTokens, eventName, body),
                e -> e.sendByAccessTokenListening(accessTokens, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByUserId(userIds, eventName, body),
                e -> e.sendByUserId(userIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByUserIdListening(userIds, eventName, body),
                e -> e.sendByUserIdListening(userIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByTenantId(tenantIds, eventName, body),
                e -> e.sendByTenantId(tenantIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return mapReduce(
                e -> e.sendByTenantIdListening(tenantIds, eventName, body),
                e -> e.sendByTenantIdListening(tenantIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByUserId(Serializable userId) {
        return mapReduce(
                e -> e.disconnectByUserId(userId),
                e -> e.disconnectByUserId(userId).size(),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByAccessToken(String accessToken) {
        return mapReduce(
                e -> e.disconnectByAccessToken(accessToken),
                e -> e.disconnectByAccessToken(accessToken).size(),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionId(Long connectionId) {
        return mapReduce(
                e -> e.disconnectByConnectionId(connectionId),
                e -> e.disconnectByConnectionId(connectionId) != null ? 1 : 0,
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    protected <T> ClusterCompletableFuture<T, ClusterConnectionService> mapReduce(
            Function<RemoteConnectionService, RemoteCompletableFuture<T, RemoteConnectionService>> remoteFunction,
            Function<LocalConnectionService, T> localFunction,
            BiFunction<T, T, T> reduce,
            Supplier<T> supplier) {
        return mapReduce(remoteFunction, localFunction, reduce, LambdaUtil.noop(), supplier);
    }

    protected <T, R> ClusterCompletableFuture<R, ClusterConnectionService> mapReduce(
            Function<RemoteConnectionService, RemoteCompletableFuture<T, RemoteConnectionService>> remoteFunction,
            Function<LocalConnectionService, T> localFunction,
            BiFunction<T, T, T> reduce,
            Function<T, R> finisher,
            Supplier<T> supplier) {
        try (ReferenceCounted<List<RemoteConnectionService>> ref = getRemoteServiceRef()) {
            List<RemoteConnectionService> serviceList = ref.get();

            List<URL> remoteUrlList = new ArrayList<>(serviceList.size());
            List<RemoteCompletableFuture<T, RemoteConnectionService>> remoteFutureList = new ArrayList<>(serviceList.size());
            for (RemoteConnectionService remote : serviceList) {
                remoteUrlList.add(remote.getRemoteUrl());
                // rpc async method call
                remoteFutureList.add(remoteFunction.apply(remote));
            }

            // local method call
            T localPart = localFunction.apply(getLocalService());

            ClusterCompletableFuture<R, ClusterConnectionService> future = new ClusterCompletableFuture<>(remoteUrlList, this);
            CompletableFuture.join(remoteFutureList, future, () -> {
                T remotePart = supplier.get();
                InterruptedException interruptedException = null;
                for (RemoteCompletableFuture<T, RemoteConnectionService> remoteFuture : remoteFutureList) {
                    try {
                        T part;
                        if (interruptedException != null) {
                            if (remoteFuture.isDone()) {
                                part = remoteFuture.get();
                            } else {
                                continue;
                            }
                        } else {
                            part = remoteFuture.get();
                        }
                        remotePart = reduce.apply(remotePart, part);
                    } catch (InterruptedException exception) {
                        interruptedException = exception;
                    } catch (ExecutionException exception) {
                        handleRemoteException(remoteFuture, exception);
                    }
                }
                T end = reduce.apply(remotePart, localPart);
                return finisher.apply(end);
            });
            return future;
        }
    }

    protected void handleRemoteException(RemoteCompletableFuture<?, RemoteConnectionService> remoteFuture, ExecutionException exception) {
        if (log.isDebugEnabled()) {
            log.debug("RemoteConnectionService {} , RemoteException {}",
                    remoteFuture.getClient(), exception, exception);
        }
    }

}

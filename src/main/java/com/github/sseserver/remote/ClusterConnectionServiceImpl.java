package com.github.sseserver.remote;

import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.LambdaUtil;
import com.github.sseserver.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class ClusterConnectionServiceImpl implements ClusterConnectionService {
    private final static Logger log = LoggerFactory.getLogger(ClusterConnectionServiceImpl.class);
    private final Supplier<LocalConnectionService> localSupplier;
    private final Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier;
    private final ThreadLocal<Boolean> scopeOnWriteableThreadLocal = new ThreadLocal<>();
    private final boolean primary;

    /**
     * @param localSupplier  非必填
     * @param remoteSupplier 非必填
     * @param primary        是否主要
     */
    public ClusterConnectionServiceImpl(Supplier<LocalConnectionService> localSupplier,
                                        Supplier<ReferenceCounted<List<RemoteConnectionService>>> remoteSupplier,
                                        boolean primary) {
        this.localSupplier = localSupplier;
        this.remoteSupplier = remoteSupplier;
        this.primary = primary;
    }

    public Optional<LocalConnectionService> getLocalService() {
        return localSupplier != null ? Optional.ofNullable(localSupplier.get()) : Optional.empty();
    }

    public ReferenceCounted<List<RemoteConnectionService>> getRemoteServiceRef() {
        if (remoteSupplier == null) {
            return new ReferenceCounted<>(Collections.emptyList());
        }
        return remoteSupplier.get();
    }

    @Override
    public boolean isOnline(Serializable userId) {
        if (getLocalService().map(e -> e.isOnline(userId)).orElse(false)) {
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
    public <ACCESS_USER> List<ConnectionDTO<ACCESS_USER>> getConnectionDTOAll() {
        ClusterCompletableFuture<List<ConnectionDTO<ACCESS_USER>>, ClusterConnectionService> future
                = getConnectionDTOAllAsync(null);
        return future.block();
    }

    @Override
    public List<ConnectionByUserIdDTO> getConnectionDTOByUserId(Serializable userId) {
        ClusterCompletableFuture<List<ConnectionByUserIdDTO>, ClusterConnectionService> future
                = getConnectionDTOByUserIdAsync(userId);
        return future.block();
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        Optional<ACCESS_USER> result = getLocalService().map(e -> e.getUser(userId));
        if (result.isPresent()) {
            return result.get();
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
        ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> future = getUsersAsync(null);
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
    public <T> T scopeOnWriteable(Callable<T> runnable) {
        scopeOnWriteableThreadLocal.set(true);
        try {
            return runnable.call();
        } catch (Exception e) {
            LambdaUtil.sneakyThrows(e);
            return null;
        } finally {
            scopeOnWriteableThreadLocal.remove();
        }
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendAll(String eventName, Object body) {
        return mapReduce(
                e -> e.sendAll(eventName, body),
                e -> e.sendAll(eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendAllListening(String eventName, Object body) {
        return mapReduce(
                e -> e.sendAllListening(eventName, body),
                e -> e.sendAllListening(eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByChannel(Collection<String> channels, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByChannel(channels, eventName, body),
                e -> e.sendByChannel(channels, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByChannelListening(Collection<String> channels, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByChannelListening(channels, eventName, body),
                e -> e.sendByChannelListening(channels, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByAccessToken(Collection<String> accessTokens, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByAccessToken(accessTokens, eventName, body),
                e -> e.sendByAccessToken(accessTokens, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByAccessTokenListening(accessTokens, eventName, body),
                e -> e.sendByAccessTokenListening(accessTokens, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByUserId(userIds, eventName, body),
                e -> e.sendByUserId(userIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByUserIdListening(userIds, eventName, body),
                e -> e.sendByUserIdListening(userIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByTenantId(tenantIds, eventName, body),
                e -> e.sendByTenantId(tenantIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
        return mapReduce(
                e -> e.sendByTenantIdListening(tenantIds, eventName, body),
                e -> e.sendByTenantIdListening(tenantIds, eventName, body),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public boolean isPrimary() {
        return primary;
    }

    @Override
    public <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersAsync(SseServerProperties.AutoType autoType) {
        return mapReduce(
                e -> e.getUsersAsync(autoType),
                ConnectionQueryService::getUsers,
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
    }

    @Override
    public <ACCESS_USER> ClusterCompletableFuture<ACCESS_USER, ClusterConnectionService> getUserAsync(Serializable userId) {
        Optional<ACCESS_USER> result = getLocalService().map(e -> e.getUser(userId));
        if (result.isPresent()) {
            ClusterCompletableFuture<ACCESS_USER, ClusterConnectionService> future = new ClusterCompletableFuture<>(Collections.emptyList(), this);
            future.complete(result.get());
            return future;
        }
        ClusterCompletableFuture<ACCESS_USER, ClusterConnectionService> future = mapReduce(
                e -> e.getUserAsync(userId),
                e -> null,
                LambdaUtil.filterNull(),
                LambdaUtil.defaultNull());
        return future;
    }

    @Override
    public <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersAsync() {
        return mapReduce(
                RemoteConnectionService::getUsersAsync,
                ConnectionQueryService::getUsers,
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
    }

    @Override
    public <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersByListeningAsync(String sseListenerName) {
        return mapReduce(
                e -> e.getUsersByListeningAsync(sseListenerName),
                e -> e.getUsersByListening(sseListenerName),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
    }

    @Override
    public <ACCESS_USER> ClusterCompletableFuture<List<ACCESS_USER>, ClusterConnectionService> getUsersByTenantIdListeningAsync(Serializable tenantId, String sseListenerName) {
        return mapReduce(
                e -> e.getUsersByTenantIdListeningAsync(tenantId, sseListenerName),
                e -> e.getUsersByTenantIdListening(tenantId, sseListenerName),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
    }

    @Override
    public <T> ClusterCompletableFuture<List<T>, ClusterConnectionService> getUserIdsAsync(Class<T> type) {
        return mapReduce(
                e -> e.getUserIdsAsync(type),
                e -> e.getUserIds(type),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
    }

    @Override
    public <T> ClusterCompletableFuture<List<T>, ClusterConnectionService> getUserIdsByListeningAsync(String sseListenerName, Class<T> type) {
        return mapReduce(
                e -> e.getUserIdsByListeningAsync(sseListenerName, type),
                e -> e.getUserIdsByListening(sseListenerName, type),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
    }

    @Override
    public <T> ClusterCompletableFuture<List<T>, ClusterConnectionService> getUserIdsByTenantIdListeningAsync(Serializable tenantId, String sseListenerName, Class<T> type) {
        return mapReduce(
                e -> e.getUserIdsByTenantIdListeningAsync(tenantId, sseListenerName, type),
                e -> e.getUserIdsByTenantIdListening(tenantId, sseListenerName, type),
                LambdaUtil.reduceList(),
                LambdaUtil.distinct(),
                ArrayList::new);
    }

    @Override
    public <ACCESS_USER> ClusterCompletableFuture<List<ConnectionDTO<ACCESS_USER>>, ClusterConnectionService> getConnectionDTOAllAsync(SseServerProperties.AutoType autoType) {
        return mapReduce(
                e -> e.getConnectionDTOAllAsync(autoType),
                ConnectionQueryService::getConnectionDTOAll,
                LambdaUtil.reduceList(),
                LambdaUtil.noop(),
                ArrayList::new);
    }

    @Override
    public ClusterCompletableFuture<List<ConnectionByUserIdDTO>, ClusterConnectionService> getConnectionDTOByUserIdAsync(Serializable userId) {
        return mapReduce(
                e -> e.getConnectionDTOByUserIdAsync(userId),
                e -> e.getConnectionDTOByUserId(userId),
                LambdaUtil.reduceList(),
                LambdaUtil.noop(),
                ArrayList::new);
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

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionId(Long connectionId, Long duration, Long sessionDuration) {
        return mapReduce(
                e -> e.disconnectByConnectionId(connectionId, duration, sessionDuration),
                e -> e.disconnectByConnectionId(connectionId, duration, sessionDuration) != null ? 1 : 0,
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> disconnectByConnectionIds(Collection<Long> connectionIds) {
        return mapReduce(
                e -> e.disconnectByConnectionIds(connectionIds),
                e -> e.disconnectByConnectionIds(connectionIds).size(),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> setDurationByUserId(Serializable userId, long durationSecond) {
        return mapReduce(
                e -> e.setDurationByUserId(userId, durationSecond),
                e -> e.setDurationByUserId(userId, durationSecond).size(),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    @Override
    public ClusterCompletableFuture<Integer, ClusterConnectionService> setDurationByAccessToken(String accessToken, long durationSecond) {
        return mapReduce(
                e -> e.setDurationByAccessToken(accessToken, durationSecond),
                e -> e.setDurationByAccessToken(accessToken, durationSecond).size(),
                Integer::sum,
                LambdaUtil.defaultZero());
    }

    public ClusterCompletableFuture<Integer, ClusterConnectionService> active(List<Map<String, Object>> activeList) {
        return mapReduce(
                e -> e.active(activeList),
                e -> 0,
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

            Boolean scopeOnWriteable = scopeOnWriteableThreadLocal.get();

            List<URL> remoteUrlList = new ArrayList<>(serviceList.size());
            List<RemoteCompletableFuture<T, RemoteConnectionService>> remoteFutureList = new ArrayList<>(serviceList.size());
            for (RemoteConnectionService remote : serviceList) {
                remoteUrlList.add(remote.getRemoteUrl());
                // rpc async method call
                if (scopeOnWriteable != null && scopeOnWriteable) {
                    remoteFutureList.add(remote.scopeOnWriteable(() -> remoteFunction.apply(remote)));
                } else {
                    remoteFutureList.add(remoteFunction.apply(remote));
                }
            }

            // local method call
            Optional<LocalConnectionService> localService = getLocalService();
            T localPart;
            if (localService.isPresent()) {
                LocalConnectionService local = localService.get();
                if (scopeOnWriteable != null && scopeOnWriteable) {
                    localPart = local.scopeOnWriteable(() -> localFunction.apply(local));
                } else {
                    localPart = localFunction.apply(local);
                }
            } else {
                localPart = null;
            }

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
                        handleRemoteException(remoteFuture, exception, future);
                    }
                }
                T end;
                if (localPart != null) {
                    end = reduce.apply(remotePart, localPart);
                } else {
                    end = remotePart;
                }
                return finisher.apply(end);
            });
            return future;
        }
    }

    protected <R> void handleRemoteException(RemoteCompletableFuture<?, RemoteConnectionService> remoteFuture,
                                             ExecutionException exception,
                                             ClusterCompletableFuture<R, ClusterConnectionService> doneFuture) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            cause = exception;
        }
        if (cause instanceof IOException) {
            if (log.isDebugEnabled()) {
                log.debug("RemoteException: RemoteConnectionService {} , RemoteException {}",
                        remoteFuture.getClient(), exception, exception);
            }
        } else {
            if (!doneFuture.isDone()) {
                doneFuture.setExceptionallyPrefix("ClusterConnectionServiceImpl at remoteFuture " + remoteFuture.getClient().getId());
            }
            boolean completeExceptionally = doneFuture.completeExceptionally(cause);
            if (log.isDebugEnabled()) {
                log.debug("RemoteException: RemoteConnectionService {} , RemoteException {}, completeExceptionally {}",
                        remoteFuture.getClient(), exception, completeExceptionally, exception);
            }
        }
    }

}

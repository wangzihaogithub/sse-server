package com.github.sseserver.remote;

import com.github.sseserver.local.LocalController.Response;
import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.*;
import com.github.sseserver.util.SpringUtil.AsyncRestTemplate;
import com.github.sseserver.util.SpringUtil.HttpEntity;

import java.io.Serializable;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class RemoteConnectionServiceImpl implements RemoteConnectionService {
    public static int connectTimeout = Integer.getInteger("sseserver.RemoteConnectionServiceImpl.connectTimeout",
            500);
    public static int readTimeout = Integer.getInteger("sseserver.RemoteConnectionServiceImpl.readTimeout",
            1000);
    public static int threadsIfAsyncRequest = Integer.getInteger("sseserver.RemoteConnectionServiceImpl.threadsIfAsyncRequest",
            1);
    public static int threadsIfBlockRequest = Integer.getInteger("sseserver.RemoteConnectionServiceImpl.threadsIfBlockRequest",
            Math.max(16, Runtime.getRuntime().availableProcessors() * 2));

    private final ThreadLocal<Boolean> scopeOnWriteableThreadLocal = new ThreadLocal<>();
    private final AsyncRestTemplate restTemplate;
    private final URL url;
    private final String urlConnectionQueryService;
    private final String urlSendService;
    private final String urlRemoteConnectionService;
    private final SseServerProperties.ClusterConfig.ConnectionService config;
    private final Set<String> classNotFoundSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final String id;
    private boolean closeFlag = false;

    public RemoteConnectionServiceImpl(URL url, String account, String password,
                                       SseServerProperties.ClusterConfig.ConnectionService config) {
        this.url = url;
        this.id = account;
        this.urlConnectionQueryService = url + "/ConnectionQueryService";
        this.urlSendService = url + "/SendService";
        this.urlRemoteConnectionService = url + "/RemoteConnectionService";
        this.config = config;
        this.restTemplate = SpringUtil.newAsyncRestTemplate(
                connectTimeout, readTimeout,
                threadsIfAsyncRequest, threadsIfBlockRequest,
                account + "RemoteConnectionService", account, password);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public RemoteCompletableFuture<Boolean, RemoteConnectionService> isOnlineAsync(Serializable userId) {
        return asyncGetConnectionQueryService("/isOnline?userId={userId}", this::extract,
                userId);
    }

    @Override
    public <ACCESS_USER> RemoteCompletableFuture<ACCESS_USER, RemoteConnectionService> getUserAsync(Serializable userId) {
        return asyncGetConnectionQueryService("/getUser?userId={userId}", this::extract,
                userId);
    }

    @Override
    public <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersAsync() {
        return getUsersAsync(null);
    }

    @Override
    public <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersAsync(SseServerProperties.AutoType autoType) {
        return asyncGetConnectionQueryService("/getUsers", (response) -> extract(response, autoType));
    }

    @Override
    public <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersByListeningAsync(String sseListenerName) {
        return asyncGetConnectionQueryService("/getUsersByListening?sseListenerName={sseListenerName}", this::extract,
                sseListenerName);
    }

    @Override
    public <ACCESS_USER> RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> getUsersByTenantIdListeningAsync(Serializable tenantId, String sseListenerName) {
        return asyncGetConnectionQueryService("/getUsersByTenantIdListening?tenantId={tenantId}&sseListenerName={sseListenerName}", this::extract,
                tenantId, sseListenerName);
    }

    @Override
    public <ACCESS_USER> RemoteCompletableFuture<List<ConnectionDTO<ACCESS_USER>>, RemoteConnectionService> getConnectionDTOAllAsync(SseServerProperties.AutoType autoTypeEnum) {
        return asyncGetConnectionQueryService("/getConnectionDTOAll", (response) -> {
            List<ConnectionDTO<ACCESS_USER>> list = extract(response, autoTypeEnum);
            SseServerProperties.AutoType autoType = autoTypeEnum == null ? config.getAutoType() : autoTypeEnum;
            for (ConnectionDTO<ACCESS_USER> item : list) {
                try {
                    ACCESS_USER cast = AutoTypeBean.cast(item.getAccessUser(),
                            item.getArrayClassName(), item.getObjectClassName(),
                            autoType, classNotFoundSet);
                    item.setAccessUser(cast);
                } catch (ClassNotFoundException e) {
                    LambdaUtil.sneakyThrows(e);
                }
            }
            return list;
        });
    }

    @Override
    public RemoteCompletableFuture<List<ConnectionByUserIdDTO>, RemoteConnectionService> getConnectionDTOByUserIdAsync(Serializable userId) {
        return asyncGetConnectionQueryService("/getConnectionDTOByUserId?userId={userId}", this::extract, userId);
    }

    @Override
    public <T> RemoteCompletableFuture<Collection<T>, RemoteConnectionService> getUserIdsAsync(Class<T> type) {
        return asyncGetConnectionQueryService("/getUserIds", entity -> {
            Collection<?> result = extract(entity);
            return castBasic(result, type);
        });
    }

    @Override
    public <T> RemoteCompletableFuture<List<T>, RemoteConnectionService> getUserIdsByListeningAsync(String sseListenerName, Class<T> type) {
        return asyncGetConnectionQueryService("/getUserIdsByListening?sseListenerName={sseListenerName}", entity -> {
            Collection<?> result = extract(entity);
            return castBasic(result, type);
        }, sseListenerName);
    }

    @Override
    public <T> RemoteCompletableFuture<List<T>, RemoteConnectionService> getUserIdsByTenantIdListeningAsync(Serializable tenantId, String sseListenerName, Class<T> type) {
        return asyncGetConnectionQueryService("/getUserIdsByTenantIdListening?tenantId={tenantId}&sseListenerName={sseListenerName}", entity -> {
            Collection<?> result = extract(entity);
            return castBasic(result, type);
        }, tenantId, sseListenerName);
    }

    @Override
    public RemoteCompletableFuture<Collection<String>, RemoteConnectionService> getAccessTokensAsync() {
        return asyncGetConnectionQueryService("/getAccessTokens", this::extract);
    }

    @Override
    public <T> RemoteCompletableFuture<List<T>, RemoteConnectionService> getTenantIdsAsync(Class<T> type) {
        return asyncGetConnectionQueryService("/getTenantIds", entity -> {
            Collection<?> result = extract(entity);
            return castBasic(result, type);
        });
    }

    @Override
    public RemoteCompletableFuture<List<String>, RemoteConnectionService> getChannelsAsync() {
        return asyncGetConnectionQueryService("/getChannels", this::extract);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> getConnectionCountAsync() {
        return asyncGetConnectionQueryService("/getConnectionCount", this::extract);
    }

    @Override
    public boolean isOnline(Serializable userId) {
        RemoteCompletableFuture<Boolean, RemoteConnectionService> future = isOnlineAsync(userId);
        Boolean result = future.block();
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public boolean isOnline(userId) result is Null");
        return result;
    }

    @Override
    public <ACCESS_USER> List<ConnectionDTO<ACCESS_USER>> getConnectionDTOAll() {
        RemoteCompletableFuture<List<ConnectionDTO<ACCESS_USER>>, RemoteConnectionService> future
                = getConnectionDTOAllAsync(null);
        return future.block();
    }

    @Override
    public List<ConnectionByUserIdDTO> getConnectionDTOByUserId(Serializable userId) {
        RemoteCompletableFuture<List<ConnectionByUserIdDTO>, RemoteConnectionService> future
                = getConnectionDTOByUserIdAsync(userId);
        return future.block();
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        RemoteCompletableFuture<ACCESS_USER, RemoteConnectionService> future = getUserAsync(userId);
        return future.block();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> future = getUsersAsync();
        return future.block();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> future = getUsersByListeningAsync(sseListenerName);
        return future.block();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        RemoteCompletableFuture<List<ACCESS_USER>, RemoteConnectionService> future = getUsersByTenantIdListeningAsync(tenantId, sseListenerName);
        return future.block();
    }

    @Override
    public <T> Collection<T> getUserIds(Class<T> type) {
        RemoteCompletableFuture<Collection<T>, RemoteConnectionService> future = getUserIdsAsync(type);
        return future.block();
    }

    @Override
    public <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type) {
        RemoteCompletableFuture<List<T>, RemoteConnectionService> future = getUserIdsByListeningAsync(sseListenerName, type);
        return future.block();
    }

    @Override
    public <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type) {
        RemoteCompletableFuture<List<T>, RemoteConnectionService> future = getUserIdsByTenantIdListeningAsync(tenantId, sseListenerName, type);
        return future.block();
    }

    @Override
    public Collection<String> getAccessTokens() {
        RemoteCompletableFuture<Collection<String>, RemoteConnectionService> future = getAccessTokensAsync();
        return future.block();
    }

    @Override
    public <T> List<T> getTenantIds(Class<T> type) {
        RemoteCompletableFuture<List<T>, RemoteConnectionService> future = getTenantIdsAsync(type);
        return future.block();
    }

    @Override
    public List<String> getChannels() {
        RemoteCompletableFuture<List<String>, RemoteConnectionService> future = getChannelsAsync();
        return future.block();
    }

    @Override
    public int getAccessTokenCount() {
        RemoteCompletableFuture<Integer, RemoteConnectionService> future = asyncGetConnectionQueryService("/getAccessTokenCount", this::extract);
        Integer result = future.block();
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public int getAccessTokenCount() result is Null");
        return result;
    }

    @Override
    public int getUserCount() {
        RemoteCompletableFuture<Integer, RemoteConnectionService> future = asyncGetConnectionQueryService("/getUserCount", this::extract);
        Integer result = future.block();
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public int getUserCount() result is Null");
        return result;
    }

    @Override
    public int getConnectionCount() {
        RemoteCompletableFuture<Integer, RemoteConnectionService> future = asyncGetConnectionQueryService("/getConnectionCount", this::extract);
        Integer result = future.block();
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public boolean getConnectionCount() result is Null");
        return result;
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
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendAll(String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(2);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendAll", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendAllListening(String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(2);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendAllListening", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByChannel(Collection<String> channels, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("channels", channels);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByChannel", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByChannelListening(Collection<String> channels, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("channels", channels);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByChannelListening", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByAccessToken(Collection<String> accessTokens, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("accessTokens", accessTokens);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByAccessToken", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("accessTokens", accessTokens);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByAccessTokenListening", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("userIds", userIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByUserId", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("userIds", userIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByUserIdListening", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("tenantIds", tenantIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByTenantId", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("tenantIds", tenantIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByTenantIdListening", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByUserId(Serializable userId) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("userId", userId);
        return asyncPostRemoteConnectionService("/disconnectByUserId", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByAccessToken(String accessToken) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("accessToken", accessToken);
        return asyncPostRemoteConnectionService("/disconnectByAccessToken", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByConnectionId(Long connectionId) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("connectionId", connectionId);
        return asyncPostRemoteConnectionService("/disconnectByConnectionId", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByConnectionId(Long connectionId, Long duration, Long sessionDuration) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("connectionId", connectionId);
        request.put("duration", duration);
        request.put("sessionDuration", sessionDuration);
        return asyncPostRemoteConnectionService("/disconnectByConnectionId", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> disconnectByConnectionIds(Collection<Long> connectionIds) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("connectionIds", connectionIds);
        return asyncPostRemoteConnectionService("/disconnectByConnectionIds", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> setDurationByUserId(Serializable userId, long durationSecond) {
        Map<String, Object> request = new HashMap<>(2);
        request.put("userId", userId);
        request.put("durationSecond", durationSecond);
        return asyncPostRemoteConnectionService("/setDurationByUserId", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> setDurationByAccessToken(String accessToken, long durationSecond) {
        Map<String, Object> request = new HashMap<>(2);
        request.put("accessToken", accessToken);
        request.put("durationSecond", durationSecond);
        return asyncPostRemoteConnectionService("/setDurationByAccessToken", this::extract, request);
    }

    @Override
    public RemoteCompletableFuture<Integer, RemoteConnectionService> active(Serializable userId, String accessToken) {
        Map<String, Object> request = new HashMap<>(2);
        request.put("userId", userId);
        request.put("accessToken", accessToken);
        return asyncPostRemoteConnectionService("/active", this::extract, request);
    }

    protected <T> RemoteCompletableFuture<T, RemoteConnectionService> asyncGetConnectionQueryService(String uri, Function<HttpEntity<Response>, T> extract, Object... uriVariables) {
        return asyncGet(urlConnectionQueryService + uri, extract, uriVariables);
    }

    protected <T> RemoteCompletableFuture<T, RemoteConnectionService> asyncPostSendService(String uri, Function<HttpEntity<Response>, T> extract, Map<String, Object> request) {
        Boolean scopeOnWriteable = scopeOnWriteableThreadLocal.get();
        if (scopeOnWriteable != null && scopeOnWriteable) {
            request.put("scopeOnWriteable", true);
        }
        return asyncPost(urlSendService + uri, extract, request);
    }

    protected <T> RemoteCompletableFuture<T, RemoteConnectionService> asyncPostRemoteConnectionService(String uri, Function<HttpEntity<Response>, T> extract, Map<String, Object> request) {
        return asyncPost(urlRemoteConnectionService + uri, extract, request);
    }

    protected <T> RemoteCompletableFuture<T, RemoteConnectionService> asyncGet(String url, Function<HttpEntity<Response>, T> extract, Object... uriVariables) {
        checkClose();
        CompletableFuture<HttpEntity<Response>> future = restTemplate.getForEntity(url, Response.class, uriVariables);
        return completable(future, extract);
    }

    protected <T> RemoteCompletableFuture<T, RemoteConnectionService> asyncPost(String url, Function<HttpEntity<Response>, T> extract, Map<String, Object> request) {
        checkClose();
        CompletableFuture<HttpEntity<Response>> future = restTemplate.postForEntity(
                url, request, Response.class);
        return completable(future, extract);
    }

    protected <T> RemoteCompletableFuture<T, RemoteConnectionService> completable(CompletableFuture<HttpEntity<Response>> future, Function<HttpEntity<Response>, T> extract) {
        RemoteCompletableFuture<T, RemoteConnectionService> result = new RemoteCompletableFuture<>();
        result.setClient(this);
        future.whenComplete((response, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(throwable);
            } else {
                T data;
                try {
                    data = extract.apply(response);
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                    return;
                }
                result.complete(data);
            }
        });
        return result;
    }

    protected <T> T extract(HttpEntity<Response> response) {
        return extract(response, null);
    }

    protected <T> T extract(HttpEntity<Response> response, SseServerProperties.AutoType autoTypeEnum) {
        if (autoTypeEnum == null) {
            autoTypeEnum = config.getAutoType();
        }
        Response body = response.getBody();
        try {
            return AutoTypeBean.cast(body.getData(),
                    body.getArrayClassName(), body.getObjectClassName(),
                    autoTypeEnum, classNotFoundSet);
        } catch (ClassNotFoundException e) {
            LambdaUtil.sneakyThrows(e);
            return null;
        }
    }

    protected <SOURCE extends Collection<?>, T> List<T> castBasic(SOURCE source, Class<T> type) {
        return TypeUtil.castBasic(source, type);
    }

    @Override
    public URL getRemoteUrl() {
        return url;
    }

    @Override
    public String toString() {
        return url == null ? "null" : url.toString();
    }

    @Override
    public void close() {
        restTemplate.close();
        this.closeFlag = true;
    }

    protected void checkClose() {
        if (closeFlag) {
            LambdaUtil.sneakyThrows(new ClosedChannelException());
        }
    }

}

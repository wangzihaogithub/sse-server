package com.github.sseserver.remote;

import com.github.sseserver.local.LocalConnectionController.Response;
import com.github.sseserver.util.NettyUtil;
import com.github.sseserver.util.OkhttpUtil;
import com.github.sseserver.util.TypeUtil;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.client.AsyncRestTemplate;

import java.io.Serializable;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class RemoteConnectionServiceImpl implements RemoteConnectionService {
    public static int connectTimeout = Integer.getInteger("RemoteConnectionServiceImpl.connectTimeout",
            2000);
    public static int readTimeout = Integer.getInteger("RemoteConnectionServiceImpl.readTimeout",
            10000);
    public static int threadsIfAsyncRequest = Integer.getInteger("RemoteConnectionServiceImpl.threadsIfAsyncRequest",
            1);
    public static int threadsIfBlockRequest = Integer.getInteger("RemoteConnectionServiceImpl.threadsIfBlockRequest",
            Runtime.getRuntime().availableProcessors() * 2);

    private static final HttpHeaders EMPTY_HEADERS = new HttpHeaders();

    private static final boolean SUPPORT_NETTY4;
    private static final boolean SUPPORT_OKHTTP3;

    private final AsyncRestTemplate restTemplate;
    private final URL url;
    private final String urlConnectionQueryService;
    private final String urlSendService;
    private final String urlRemoteConnectionService;
    private final String authorization;
    private boolean closeFlag = false;

    static {
        boolean supportNetty4;
        try {
            Class.forName("io.netty.channel.ChannelHandler");
            supportNetty4 = true;
        } catch (Throwable e) {
            supportNetty4 = false;
        }
        SUPPORT_NETTY4 = supportNetty4;

        boolean supportOkhttp3;
        try {
            Class.forName("okhttp3.OkHttpClient");
            supportOkhttp3 = true;
        } catch (Throwable e) {
            supportOkhttp3 = false;
        }
        SUPPORT_OKHTTP3 = supportOkhttp3;
    }

    public RemoteConnectionServiceImpl(URL url, String account, String password) {
        this.url = url;
        this.authorization = "Basic " + HttpHeaders.encodeBasicAuth(account, password, Charset.forName("ISO-8859-1"));
        this.urlConnectionQueryService = url + "/ConnectionQueryService";
        this.urlSendService = url + "/SendService";
        this.urlRemoteConnectionService = url + "/RemoteConnectionService";
        this.restTemplate = newAsyncRestTemplate(connectTimeout, readTimeout,
                threadsIfAsyncRequest, threadsIfBlockRequest, account);
        this.restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("Authorization", authorization);
            return execution.executeAsync(request, body);
        });
    }

    protected AsyncRestTemplate newAsyncRestTemplate(int connectTimeout, int readTimeout,
                                                     int threadsIfAsyncRequest, int threadsIfBlockRequest,
                                                     String threadName) {
        if (SUPPORT_NETTY4) {
            return new AsyncRestTemplate(NettyUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName));
        } else if (SUPPORT_OKHTTP3) {
            return new AsyncRestTemplate(OkhttpUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName));
        } else {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setDaemon(true);
            executor.setThreadNamePrefix(threadName + "-");
            executor.setCorePoolSize(0);
            executor.setKeepAliveSeconds(60);
            executor.setMaxPoolSize(threadsIfBlockRequest);
            executor.setWaitForTasksToCompleteOnShutdown(true);
            ClientHttpRequestFactory factory = new ClientHttpRequestFactory(executor);
            factory.setConnectTimeout(connectTimeout);
            factory.setReadTimeout(readTimeout);
            return new AsyncRestTemplate(factory);
        }
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
        AsyncClientHttpRequestFactory factory = restTemplate.getAsyncRequestFactory();
        if (factory instanceof DisposableBean) {
            try {
                ((DisposableBean) factory).destroy();
            } catch (Exception ignored) {
            }
        }
        this.closeFlag = true;
    }

    protected void checkClose() {
        if (closeFlag) {
            sneakyThrows(new ClosedChannelException());
        }
    }

    @Override
    public boolean isOnline(Serializable userId) {
        Boolean result = syncGetConnectionQueryService("/isOnline?userId={userId}",
                userId);
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public boolean isOnline(userId) result is NullPointerException");
        return result;
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        Map result = syncGetConnectionQueryService("/getUser?userId={userId}",
                userId);
        return castBean(result);
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        List<Map> result = syncGetConnectionQueryService("/getUsers");
        return castBean(result);
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        List<Map> result = syncGetConnectionQueryService("/getUsersByListening?sseListenerName={sseListenerName}",
                sseListenerName);
        return castBean(result);
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        List<Map> result = syncGetConnectionQueryService("/getUsersByTenantIdListening?tenantId={tenantId}&sseListenerName={sseListenerName}",
                tenantId, sseListenerName);
        return castBean(result);
    }

    @Override
    public <T> Collection<T> getUserIds(Class<T> type) {
        List<?> result = syncGetConnectionQueryService("/getUserIds");
        return castBasic(result, type);
    }

    @Override
    public <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type) {
        List<?> result = syncGetConnectionQueryService("/getUserIdsByListening?sseListenerName={sseListenerName}",
                sseListenerName);
        return castBasic(result, type);
    }

    @Override
    public <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type) {
        List<?> result = syncGetConnectionQueryService("/getUserIdsByTenantIdListening?tenantId={tenantId}&sseListenerName={sseListenerName}",
                tenantId, sseListenerName);
        return castBasic(result, type);
    }

    @Override
    public Collection<String> getAccessTokens() {
        Collection<String> result = syncGetConnectionQueryService("/getAccessTokens");
        return result;
    }

    @Override
    public <T> List<T> getTenantIds(Class<T> type) {
        List<?> result = syncGetConnectionQueryService("/getTenantIds");
        return castBasic(result, type);
    }

    @Override
    public List<String> getChannels() {
        List<String> result = syncGetConnectionQueryService("/getChannels");
        return result;
    }

    @Override
    public int getAccessTokenCount() {
        Integer result = syncGetConnectionQueryService("/getAccessTokenCount");
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public int getAccessTokenCount() result is NullPointerException");
        return result;
    }

    @Override
    public int getUserCount() {
        Integer result = syncGetConnectionQueryService("/getUserCount");
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public int getUserCount() result is NullPointerException");
        return result;
    }

    @Override
    public int getConnectionCount() {
        Integer result = syncGetConnectionQueryService("/getConnectionCount");
        Objects.requireNonNull(result,
                "RemoteConnectionServiceImpl -> public boolean getConnectionCount() result is NullPointerException");
        return result;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendAll(String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(2);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendAll", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendAllListening(String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(2);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendAllListening", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByChannel(Collection<String> channels, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("channels", channels);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByChannel", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByChannelListening(Collection<String> channels, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("channels", channels);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByChannelListening", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("accessTokens", accessTokens);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByAccessToken", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("accessTokens", accessTokens);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByAccessTokenListening", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("userIds", userIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByUserId", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("userIds", userIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByUserIdListening", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("tenantIds", tenantIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByTenantId", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        Map<String, Object> request = new HashMap<>(3);
        request.put("tenantIds", tenantIds);
        request.put("eventName", eventName);
        request.put("body", body);
        return asyncPostSendService("/sendByTenantIdListening", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> disconnectByUserId(Serializable userId) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("userId", userId);
        return asyncPostRemoteConnectionService("/disconnectByUserId", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> disconnectByAccessToken(String accessToken) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("accessToken", accessToken);
        return asyncPostRemoteConnectionService("/disconnectByAccessToken", request);
    }

    @Override
    public RemoteCompletableFuture<Integer> disconnectByConnectionId(Long connectionId) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("connectionId", connectionId);
        return asyncPostRemoteConnectionService("/disconnectByConnectionId", request);
    }

    protected <T> T syncGetConnectionQueryService(String uri, Object... uriVariables) {
        return syncGet(urlConnectionQueryService + uri, uriVariables);
    }

    protected <T> RemoteCompletableFuture<T> asyncPostSendService(String uri, Map<String, Object> request) {
        return asyncPost(urlSendService + uri, request);
    }

    protected <T> RemoteCompletableFuture<T> asyncPostRemoteConnectionService(String uri, Map<String, Object> request) {
        return asyncPost(urlRemoteConnectionService + uri, request);
    }

    protected <T> T syncGet(String url, Object... uriVariables) {
        checkClose();
        ListenableFuture<ResponseEntity<Response>> future = restTemplate.getForEntity(url, Response.class, uriVariables);
        try {
            ResponseEntity<Response> responseEntity = future.get();
            return extract(responseEntity);
        } catch (InterruptedException | ExecutionException e) {
            sneakyThrows(e);
            return null;
        }
    }

    protected <T> RemoteCompletableFuture<T> asyncPost(String url, Map<String, Object> request) {
        checkClose();
        ListenableFuture<ResponseEntity<Response>> future = restTemplate.postForEntity(
                url, new HttpEntity(request, EMPTY_HEADERS), Response.class);
        return completable(future);
    }

    protected <T> RemoteCompletableFuture<T> completable(ListenableFuture<ResponseEntity<Response>> future) {
        RemoteCompletableFuture<T> result = new RemoteCompletableFuture<>();
        result.setService(this);
        future.addCallback(response -> result.complete(extract(response)), result::completeExceptionally);
        return result;
    }

    protected <T> T extract(ResponseEntity<Response> response) {
        Object data = response.getBody().getData();
        return (T) data;
    }

    protected <SOURCE extends Collection<Map>, T> List<T> castBean(SOURCE list) {
        return list.stream()
                .map(e -> (T) castBean(e))
                .collect(Collectors.toList());
    }

    protected <T> T castBean(Map<?, ?> map) {
        return TypeUtil.castBean(map);
    }

    protected <SOURCE extends Collection<?>, T> List<T> castBasic(SOURCE source, Class<T> type) {
        return TypeUtil.castBasic(source, type);
    }

    private static <E extends Throwable> void sneakyThrows(Throwable t) throws E {
        throw (E) t;
    }

    public static class ClientHttpRequestFactory extends SimpleClientHttpRequestFactory implements DisposableBean {
        private final ThreadPoolTaskExecutor threadPool;

        public ClientHttpRequestFactory(ThreadPoolTaskExecutor threadPool) {
            this.threadPool = threadPool;
            threadPool.afterPropertiesSet();
            setTaskExecutor(threadPool);
        }

        @Override
        public void destroy() {
            threadPool.shutdown();
        }
    }

}

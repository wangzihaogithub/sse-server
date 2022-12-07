package com.github.sseserver.remote;

import com.github.sseserver.local.LocalController;
import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.util.LambdaUtil;
import com.github.sseserver.util.SpringUtil;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.client.AsyncRestTemplate;

import java.io.Serializable;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RemoteMessageRepository implements MessageRepository {
    public static int connectTimeout = Integer.getInteger("RemoteMessageRepository.connectTimeout",
            2000);
    public static int readTimeout = Integer.getInteger("RemoteMessageRepository.readTimeout",
            10000);
    public static int threadsIfAsyncRequest = Integer.getInteger("RemoteMessageRepository.threadsIfAsyncRequest",
            1);
    public static int threadsIfBlockRequest = Integer.getInteger("RemoteMessageRepository.threadsIfBlockRequest",
            Math.max(16, Runtime.getRuntime().availableProcessors() * 2));

    private final AsyncRestTemplate restTemplate;
    private final URL url;
    private final String urlMessageRepository;
    private final String id;
    private boolean closeFlag = false;

    public RemoteMessageRepository(URL url, String account, String password) {
        this.url = url;
        this.urlMessageRepository = url + "/MessageRepository";
        this.id = account;
        this.restTemplate = SpringUtil.newAsyncRestTemplate(
                connectTimeout, readTimeout,
                threadsIfAsyncRequest, threadsIfBlockRequest,
                account, account, password);
    }

    @Override
    public String insert(Message message) {
        return insertAsync(message).block();
    }

    @Override
    public List<Message> select(Query query) {
        return selectAsync(query).block();
    }

    @Override
    public Message delete(String id) {
        return deleteAsync(id).block();
    }

    public RemoteCompletableFuture<String, RemoteMessageRepository> insertAsync(Message message) {
        Map<String, Object> request = new HashMap<>(8);
        request.put("filters", message.getFilters());

        request.put("id", message.getId());
        request.put("body", message.getBody());
        request.put("eventName", message.getEventName());
        request.put("listenerName", message.getListenerName());

        request.put("userIdList", message.getUserIdList());
        request.put("tenantIdList", message.getTenantIdList());
        request.put("accessTokenList", message.getAccessTokenList());
        request.put("channelList", message.getChannelList());
        return asyncPost("/insert", request, this::extract);
    }

    public RemoteCompletableFuture<List<Message>, RemoteMessageRepository> selectAsync(Query query) {
        Map<String, Object> request = new HashMap<>(6);
        request.put("tenantId", query.getTenantId());
        request.put("channel", query.getChannel());
        request.put("accessToken", query.getAccessToken());
        request.put("userId", query.getUserId());
        request.put("listeners", query.getListeners());
        return asyncPost("/select", request, this::extractListMessage);
    }

    public RemoteCompletableFuture<Message, RemoteMessageRepository> deleteAsync(String id) {
        Map<String, Object> request = new HashMap<>(1);
        request.put("id", id);
        return asyncPost("/delete", request, entity -> {
            Map data = (Map) entity.getBody().getData();
            return buildMessage(data);
        });
    }

    @Override
    public void close() {
        SpringUtil.close(restTemplate);
        this.closeFlag = true;
    }

    @Override
    public void addDeleteListener(Consumer<Message> listener) {
        throw new UnsupportedOperationException("public void addDeleteListener(Consumer<Message> listener)");
    }

    protected <T> RemoteCompletableFuture<T, RemoteMessageRepository> asyncPost(String url,
                                                                                Object request,
                                                                                Function<ResponseEntity<LocalController.Response>, T> extract) {
        checkClose();
        ListenableFuture<ResponseEntity<LocalController.Response>> future = restTemplate.postForEntity(
                urlMessageRepository + url, new HttpEntity(request, SpringUtil.EMPTY_HEADERS), LocalController.Response.class);
        return completable(future, extract);
    }

    protected <T> RemoteCompletableFuture<T, RemoteMessageRepository> completable(
            ListenableFuture<ResponseEntity<LocalController.Response>> future,
            Function<ResponseEntity<LocalController.Response>, T> extract) {
        RemoteCompletableFuture<T, RemoteMessageRepository> result = new RemoteCompletableFuture<>();
        result.setClient(this);
        future.addCallback(response -> result.complete(extract.apply(response)), result::completeExceptionally);
        return result;
    }

    protected <T> T extract(ResponseEntity<LocalController.Response> response) {
        LocalController.Response body = response.getBody();
        body.autoCastClassName(false);
        Object data = body.getData();
        return (T) data;
    }

    protected List<Message> extractListMessage(ResponseEntity<LocalController.Response> response) {
        List<Map> data = (List<Map>) response.getBody().getData();
        return data.stream()
                .map(this::buildMessage)
                .collect(Collectors.toList());
    }

    protected RemoteResponseMessage buildMessage(Map source) {
        if (source == null) {
            return null;
        }
        RemoteResponseMessage target = new RemoteResponseMessage();
        target.setRemoteMessageRepositoryId(id);
        target.setFilters((Integer) source.get("filters"));

        target.setId((String) source.get("id"));
        target.setBody(source.get("body"));
        target.setEventName((String) source.get("eventName"));
        target.setListenerName((String) source.get("listenerName"));

        target.setTenantIdList((Collection<? extends Serializable>) source.get("tenantIdList"));
        target.setUserIdList((Collection<? extends Serializable>) source.get("userIdList"));
        target.setAccessTokenList((Collection<String>) source.get("accessTokenList"));
        target.setChannelList((Collection<String>) source.get("channelList"));
        return target;
    }

    public URL getRemoteUrl() {
        return url;
    }

    public String getId() {
        return id;
    }

    @Override
    public String toString() {
        return url == null ? "null" : url.toString();
    }

    protected void checkClose() {
        if (closeFlag) {
            LambdaUtil.sneakyThrows(new ClosedChannelException());
        }
    }

}

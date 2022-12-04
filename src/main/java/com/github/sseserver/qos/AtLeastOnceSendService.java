package com.github.sseserver.qos;

import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.SseChangeEvent;
import com.github.sseserver.local.SseEmitter;
import com.github.sseserver.remote.ClusterMessageRepository;
import com.github.sseserver.remote.RemoteResponseMessage;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 推送
 * 保证前端至少收到一次推送
 *
 * @param <ACCESS_USER>
 * @author wangzihaogithub 2022-11-12
 */
public class AtLeastOnceSendService<ACCESS_USER> implements SendService<QosCompletableFuture<List<SseEmitter<ACCESS_USER>>>> {
    protected final LocalConnectionService localConnectionService;
    protected final MessageRepository messageRepository;
    protected final Map<String, QosCompletableFuture<List<SseEmitter<ACCESS_USER>>>> futureMap = new ConcurrentHashMap<>(32);
    protected final Set<String> sendingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public AtLeastOnceSendService(LocalConnectionService localConnectionService, MessageRepository messageRepository) {
        this.localConnectionService = localConnectionService;
        this.messageRepository = messageRepository;
        localConnectionService.<ACCESS_USER>addConnectListener(this::resend);
        localConnectionService.addListeningChangeWatch((Consumer<SseChangeEvent<ACCESS_USER, Set<String>>>) event -> {
            if (SseEmitter.EVENT_ADD_LISTENER.equals(event.getEventName())) {
                resend(event.getInstance());
            }
        });
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendAll(String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionAll()) {
            if (connection.isActive() && connection.isWriteable()) {
                try {
                    connection.send(eventName, body);
                    succeedList.add(connection);
                } catch (IOException ignored) {
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    0);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendAllListening(String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionAll()) {
            if (connection.isActive() && connection.isWriteable() && connection.existListener(eventName)) {
                try {
                    connection.send(eventName, body);
                    succeedList.add(connection);
                } catch (IOException ignored) {
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_LISTENER_NAME);
            message.setListenerName(eventName);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByChannel(Collection<String> channels, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (String channel : channels) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByChannel(channel)) {
                if (connection.isActive() && connection.isWriteable()) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_CHANNEL);
            message.setChannelList(channels);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByChannelListening(Collection<String> channels, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (String channel : channels) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByChannel(channel)) {
                if (connection.isActive() && connection.isWriteable() && connection.existListener(eventName)) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_CHANNEL | Message.FILTER_LISTENER_NAME);
            message.setChannelList(channels);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (String accessToken : accessTokens) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByAccessToken(accessToken)) {
                if (connection.isActive() && connection.isWriteable()) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_ACCESS_TOKEN);
            message.setAccessTokenList(accessTokens);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (String accessToken : accessTokens) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByAccessToken(accessToken)) {
                if (connection.isActive() && connection.isWriteable() && connection.existListener(eventName)) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_ACCESS_TOKEN | Message.FILTER_LISTENER_NAME);
            message.setAccessTokenList(accessTokens);
            message.setListenerName(eventName);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (Serializable userId : userIds) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByUserId(userId)) {
                if (connection.isActive() && connection.isWriteable()) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_USER_ID);
            message.setUserIdList(userIds);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(2);
        for (Serializable userId : userIds) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByUserId(userId)) {
                if (connection.isActive() && connection.isWriteable() && connection.existListener(eventName)) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_USER_ID | Message.FILTER_LISTENER_NAME);
            message.setUserIdList(userIds);
            message.setListenerName(eventName);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(10);
        for (Serializable tenantId : tenantIds) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByTenantId(tenantId)) {
                if (connection.isActive() && connection.isWriteable()) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_TENANT_ID);
            message.setTenantIdList(tenantIds);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    @Override
    public QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        List<SseEmitter<ACCESS_USER>> succeedList = new ArrayList<>(10);
        for (Serializable tenantId : tenantIds) {
            for (SseEmitter<ACCESS_USER> connection : localConnectionService.<ACCESS_USER>getConnectionByTenantId(tenantId)) {
                if (connection.isActive() && connection.isWriteable() && connection.existListener(eventName)) {
                    try {
                        connection.send(eventName, body);
                        succeedList.add(connection);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = new QosCompletableFuture<>();
        if (succeedList.isEmpty()) {
            AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                    Message.FILTER_TENANT_ID | Message.FILTER_LISTENER_NAME);
            message.setTenantIdList(tenantIds);
            message.setListenerName(eventName);
            enqueue(message, future);
        } else {
            complete(future, succeedList);
        }
        return future;
    }

    protected void complete(QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future, List<SseEmitter<ACCESS_USER>> succeedList) {
        if (future.getMessageId() == null) {
            future.setMessageId(Message.newId());
        }
        future.complete(succeedList);
    }

    protected void enqueue(Message message, QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future) {
        String id = messageRepository.insert(message);
        future.setMessageId(id);
        futureMap.put(id, future);
    }

    protected void markSending(List<Message> messageList) {
        int i = 0;
        for (Message message : messageList) {
            if (!sendingSet.add(message.getId())) {
                messageList.set(i, null);
            }
            i++;
        }
    }

    protected void resend(SseEmitter<ACCESS_USER> connection) {
        if (messageRepository instanceof ClusterMessageRepository) {
            ((ClusterMessageRepository) messageRepository).selectAsync(connection)
                    .thenAccept(e -> resend(e, connection));
        } else {
            resend(messageRepository.select(connection), connection);
        }
    }

    protected void resend(List<Message> messageList, SseEmitter<ACCESS_USER> connection) {
        if (messageList.isEmpty()) {
            return;
        }
        markSending(messageList);

        List<SseEmitter<ACCESS_USER>> succeedList = Collections.singletonList(connection);
        IOException error = null;
        for (Message message : messageList) {
            if (message == null) {
                // other sending
                continue;
            }
            String id = message.getId();
            try {
                if (!connection.isActive() || !connection.isWriteable() || error != null) {
                    continue;
                }

                connection.send(SseEmitter.event()
                        .id(id)
                        .name(message.getEventName())
                        .comment("resend")
                        .data(message.getBody()));

                if (messageRepository instanceof ClusterMessageRepository) {
                    ClusterMessageRepository repository = ((ClusterMessageRepository) messageRepository);
                    String repositoryId;
                    if (message instanceof RemoteResponseMessage) {
                        repositoryId = ((RemoteResponseMessage) message).getRemoteMessageRepositoryId();
                    } else {
                        repositoryId = null;
                    }
                    repository.deleteAsync(id, repositoryId)
                            .thenAccept(e -> removeAndCompleteFuture(id, succeedList));
                } else {
                    messageRepository.delete(id);
                    removeAndCompleteFuture(id, succeedList);
                }
            } catch (IOException e) {
                error = e;
            } finally {
                sendingSet.remove(id);
            }
        }
    }

    protected void removeAndCompleteFuture(String messageId, List<SseEmitter<ACCESS_USER>> succeedList) {
        QosCompletableFuture<List<SseEmitter<ACCESS_USER>>> future = futureMap.remove(messageId);
        if (future != null) {
            complete(future, succeedList);
        }
    }
}

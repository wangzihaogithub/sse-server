package com.github.sseserver.qos;

import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.SseChangeEvent;
import com.github.sseserver.local.SseEmitter;
import com.github.sseserver.remote.ClusterCompletableFuture;
import com.github.sseserver.remote.ClusterConnectionService;
import com.github.sseserver.remote.ClusterMessageRepository;
import com.github.sseserver.remote.RemoteResponseMessage;
import com.github.sseserver.util.LambdaUtil;
import com.github.sseserver.util.SpringUtil;
import com.github.sseserver.util.WebUtil;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 推送
 * 保证前端至少收到一次推送
 *
 * @param <ACCESS_USER>
 * @author wangzihaogithub 2022-11-12
 */
public class AtLeastOnceSendService<ACCESS_USER> implements SendService<QosCompletableFuture<Integer>> {
    protected final LocalConnectionService localConnectionService;
    protected final MessageRepository messageRepository;
    protected final Map<String, QosCompletableFuture<Integer>> futureMap = new ConcurrentHashMap<>(32);
    protected final Set<String> sendingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    protected final String serverId = SpringUtil.filterNonAscii(WebUtil.getIPAddress(WebUtil.port));

    public AtLeastOnceSendService(LocalConnectionService localConnectionService, MessageRepository messageRepository) {
        this.localConnectionService = localConnectionService;
        this.messageRepository = messageRepository;
        this.messageRepository.addDeleteListener(message -> {
            QosCompletableFuture<Integer> future = futureMap.remove(message.getId());
            if (future != null) {
                complete(future, 1);
            }
        });
        localConnectionService.<ACCESS_USER>addConnectListener(this::resend);
        localConnectionService.addListeningChangeWatch((Consumer<SseChangeEvent<ACCESS_USER, Set<String>>>) event -> {
            if (SseEmitter.EVENT_ADD_LISTENER.equals(event.getEventName())) {
                resend(event.getInstance());
            }
        });
    }

    public QosCompletableFuture<Integer> qosSend(Function<SendService, ?> sendFunction, Supplier<AtLeastOnceMessage> messageSupplier) {
        QosCompletableFuture<Integer> future = new QosCompletableFuture<>(Message.newId("qos", serverId));
        if (localConnectionService.isEnableCluster()) {
            ClusterConnectionService cluster = localConnectionService.getCluster();

            ClusterCompletableFuture<Integer, ClusterConnectionService> clusterFuture = cluster.scopeOnWriteable(
                    () -> (ClusterCompletableFuture<Integer, ClusterConnectionService>) sendFunction.apply(cluster));
            clusterFuture.whenComplete((succeedCount, throwable) -> {
                if (succeedCount != null && succeedCount > 0) {
                    complete(future, succeedCount);
                } else {
                    AtLeastOnceMessage message = messageSupplier.get();
                    enqueue(message, future);
                }
            });
        } else {
            Integer succeedCount = localConnectionService.scopeOnWriteable(
                    () -> (Integer) sendFunction.apply(localConnectionService));
            if (succeedCount != null && succeedCount > 0) {
                complete(future, succeedCount);
            } else {
                AtLeastOnceMessage message = messageSupplier.get();
                enqueue(message, future);
            }
        }
        return future;
    }

    @Override
    public <T> T scopeOnWriteable(Callable<T> runnable) {
        try {
            return runnable.call();
        } catch (Exception e) {
            LambdaUtil.sneakyThrows(e);
            return null;
        }
    }

    @Override
    public QosCompletableFuture<Integer> sendAll(String eventName, Object body) {
        return qosSend(
                e -> e.sendAll(eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            0);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendAllListening(String eventName, Object body) {
        return qosSend(
                e -> e.sendAllListening(eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_LISTENER_NAME);
                    message.setListenerName(eventName);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByChannel(Collection<String> channels, String eventName, Object body) {
        return qosSend(
                e -> e.sendByChannel(channels, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_CHANNEL);
                    message.setChannelList(channels);
                    message.setListenerName(eventName);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByChannelListening(Collection<String> channels, String eventName, Object body) {
        return qosSend(
                e -> e.sendByChannelListening(channels, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_CHANNEL | Message.FILTER_LISTENER_NAME);
                    message.setChannelList(channels);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByAccessToken(Collection<String> accessTokens, String eventName, Object body) {
        return qosSend(
                e -> e.sendByAccessToken(accessTokens, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_ACCESS_TOKEN);
                    message.setAccessTokenList(accessTokens);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Object body) {
        return qosSend(
                e -> e.sendByAccessTokenListening(accessTokens, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_ACCESS_TOKEN | Message.FILTER_LISTENER_NAME);
                    message.setAccessTokenList(accessTokens);
                    message.setListenerName(eventName);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Object body) {
        return qosSend(
                e -> e.sendByUserId(userIds, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_USER_ID);
                    message.setUserIdList(userIds);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Object body) {
        return qosSend(
                e -> e.sendByUserIdListening(userIds, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_USER_ID | Message.FILTER_LISTENER_NAME);
                    message.setUserIdList(userIds);
                    message.setListenerName(eventName);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
        return qosSend(
                e -> e.sendByTenantId(tenantIds, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_TENANT_ID);
                    message.setTenantIdList(tenantIds);
                    return message;
                });
    }

    @Override
    public QosCompletableFuture<Integer> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
        return qosSend(
                e -> e.sendByTenantIdListening(tenantIds, eventName, body),
                () -> {
                    AtLeastOnceMessage message = new AtLeastOnceMessage(eventName, body,
                            Message.FILTER_TENANT_ID | Message.FILTER_LISTENER_NAME);
                    message.setTenantIdList(tenantIds);
                    message.setListenerName(eventName);
                    return message;
                });
    }

    protected void complete(QosCompletableFuture<Integer> future, Integer succeedCount) {
        future.complete(succeedCount);
    }

    protected void enqueue(Message message, QosCompletableFuture<Integer> future) {
        String messageId = future.getMessageId();
        message.setId(messageId);
        messageRepository.insert(message);
        futureMap.put(messageId, future);
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
                    repository.deleteAsync(id, repositoryId);
                } else {
                    messageRepository.delete(id);
                }
            } catch (IOException e) {
                error = e;
            } finally {
                sendingSet.remove(id);
            }
        }
    }

}

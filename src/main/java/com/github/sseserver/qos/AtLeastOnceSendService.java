package com.github.sseserver.qos;

import com.github.sseserver.DistributedConnectionService;
import com.github.sseserver.SendService;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.SseChangeEvent;
import com.github.sseserver.remote.ClusterCompletableFuture;
import com.github.sseserver.remote.ClusterConnectionService;
import com.github.sseserver.util.LambdaUtil;
import com.github.sseserver.util.SpringUtil;
import com.github.sseserver.util.WebUtil;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
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
    protected final DistributedConnectionService distributedConnectionService;
    protected final MessageRepository messageRepository;
    protected final Map<String, QosCompletableFuture<Integer>> futureMap = new ConcurrentHashMap<>(32);
    protected final String serverId = SpringUtil.filterNonAscii(WebUtil.getIPAddress(WebUtil.port));
    private final boolean primary;
    /**
     * @param localConnectionService       非必填
     * @param distributedConnectionService 非必填
     * @param messageRepository            非必填
     * @param primary 是否主要
     */
    public AtLeastOnceSendService(LocalConnectionService localConnectionService, DistributedConnectionService distributedConnectionService, MessageRepository messageRepository, boolean primary) {
        this.localConnectionService = localConnectionService;
        this.distributedConnectionService = distributedConnectionService;
        this.messageRepository = messageRepository;
        this.primary = primary;
        if (messageRepository != null) {
            messageRepository.addDeleteListener(message -> {
                QosCompletableFuture<Integer> future = futureMap.remove(message.getId());
                if (future != null) {
                    complete(future, 1);
                }
            });
        }
        if (localConnectionService != null && messageRepository != null) {
            AtLeastResend<ACCESS_USER> atLeastResend = new AtLeastResend<>(messageRepository);
            localConnectionService.<ACCESS_USER>addConnectListener(atLeastResend::resend);
            localConnectionService.addListeningChangeWatch((Consumer<SseChangeEvent<ACCESS_USER, Set<String>>>) event -> {
                if (SseChangeEvent.EVENT_ADD_LISTENER.equals(event.getEventName())) {
                    atLeastResend.resend(event.getInstance());
                }
            });
        }
    }

    public QosCompletableFuture<Integer> qosSend(Function<SendService, ?> sendFunction, Supplier<AtLeastOnceMessage> messageSupplier) {
        QosCompletableFuture<Integer> future = new QosCompletableFuture<>(Message.newId("qos", serverId));
        if (distributedConnectionService != null && distributedConnectionService.isEnableCluster()) {
            ClusterConnectionService cluster = distributedConnectionService.getCluster();

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
        } else if (localConnectionService != null) {
            Integer succeedCount = localConnectionService.scopeOnWriteable(
                    () -> (Integer) sendFunction.apply(localConnectionService));
            if (succeedCount != null && succeedCount > 0) {
                complete(future, succeedCount);
            } else {
                AtLeastOnceMessage message = messageSupplier.get();
                enqueue(message, future);
            }
        } else {
            future.complete(0);
        }
        return future;
    }

    public boolean isPrimary() {
        return primary;
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

    protected void enqueue(AtLeastOnceMessage message, QosCompletableFuture<Integer> future) {
        if (messageRepository == null) {
            return;
        }
        if (future.isDone()) {
            return;
        }
        String messageId = future.getMessageId();
        message.setId(messageId);
        messageRepository.insert(message);
        futureMap.put(messageId, future);
    }

}

package com.github.sseserver.local;

import com.github.sseserver.SendService;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.qos.QosCompletableFuture;
import com.github.sseserver.qos.AtLeastOnceSendService;
import com.github.sseserver.qos.MemoryMessageRepository;
import com.github.sseserver.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 单机长连接(非分布式)
 * 1. 如果用nginx代理, 要加下面的配置
 * # 长连接配置
 * proxy_buffering off;
 * proxy_read_timeout 7200s;
 * proxy_pass http://xx.xx.xx.xx:xxx;
 * proxy_http_version 1.1; #nginx默认是http1.0, 改为1.1 支持长连接, 和后端保持长连接,复用,防止出现文件句柄打开数量过多的错误
 * proxy_set_header Connection ""; # 去掉Connection的close字段
 *
 * @author hao 2021年12月7日19:27:41
 */
public class LocalConnectionServiceImpl implements LocalConnectionService, BeanNameAware, DisposableBean {
    private final static Logger log = LoggerFactory.getLogger(LocalConnectionServiceImpl.class);
    private final static AtomicInteger SCHEDULED_INDEX = new AtomicInteger();
    /**
     * 业务维度与链接ID的关系表
     */
    protected final Object mutex = new Object();
    protected final Map<String, Set<Long>> accessToken2ConnectionIdMap = new ConcurrentHashMap<>();
    protected final Map<String, Set<Long>> channel2ConnectionIdMap = new ConcurrentHashMap<>();
    protected final Map<String, Set<Long>> tenantId2ConnectionIdMap = new ConcurrentHashMap<>();
    protected final Map<String, Set<Long>> userId2ConnectionIdMap = new ConcurrentHashMap<>();
    /**
     * 链接
     */
    protected final Map<Long, SseEmitter> connectionMap = new ConcurrentHashMap<>();
    /**
     * 永久事件监听。
     * {@link #connectListenerList ,#disconnectListeners}
     */
    protected final List<Consumer<SseEmitter>> connectListenerList = new ArrayList<>();
    protected final List<Consumer<SseEmitter>> disconnectListenerList = new ArrayList<>();
    protected final List<Consumer<ChangeEvent<?, Set<String>>>> listeningChangeWatchList = new ArrayList<>();
    /**
     * 如果 {@link Predicate#test(Object)} 返回true，则是只监听一次事件的一次性listener。 否则永久事件监听。
     * {@link #connectListenerMap,#disconnectListenerMap}
     */
    protected final Map<String, List<Predicate<SseEmitter>>> connectListenerMap = new ConcurrentHashMap<>();
    protected final Map<String, List<Predicate<SseEmitter>>> disconnectListenerMap = new ConcurrentHashMap<>();

    private final ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, getBeanName() + "-" + SCHEDULED_INDEX.incrementAndGet()));
    private String beanName = getClass().getSimpleName();
    private int reconnectTime = 5000;
    private boolean destroyFlag;
    private volatile AtLeastOnceSendService atLeastOnceSender;
    private MessageRepository messageRepository;

    @Override
    public ScheduledExecutorService getScheduled() {
        return scheduled;
    }

    @Override
    public <ACCESS_USER> SendService<QosCompletableFuture<ACCESS_USER>> atLeastOnce() {
        if (atLeastOnceSender == null) {
            synchronized (this) {
                if (atLeastOnceSender == null) {
                    if (messageRepository == null) {
                        messageRepository = new MemoryMessageRepository();
                    }
                    atLeastOnceSender = new AtLeastOnceSendService(this, messageRepository);
                }
            }
        }
        return atLeastOnceSender;
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    public void setMessageRepository(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public <ACCESS_USER> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime, Map<String, Object> attributeMap) {
        if (destroyFlag) {
            throw new IllegalStateException("destroy");
        }
        if (keepaliveTime == null) {
            keepaliveTime = 900_000L;
        }
        // 设置超时时间，0表示不过期。servlet默认30秒，超过时间未完成会抛出异常：AsyncRequestTimeoutException
        SseEmitter<ACCESS_USER> result = new SseEmitter<>(keepaliveTime, accessUser);
        result.onCompletion(completionCallBack(result));
        result.onError(errorCallBack(result));
        result.onTimeout(timeoutCallBack(result));
        if (keepaliveTime > 0) {
            result.setTimeoutCheckFuture(scheduled.schedule(
                    result::disconnectByTimeoutCheck, keepaliveTime, TimeUnit.MILLISECONDS));
        }

        Long id = result.getId();
        String accessToken = wrapStringKey(result.getAccessToken());
        String userId = wrapStringKey(result.getUserId());
        String tenantId = wrapStringKey(result.getTenantId());

        result.addDisConnectListener(e -> {
            log.debug("sse {} connection disconnect : {}", beanName, e);

            String channel = wrapStringKey(e.getChannel());

            notifyListener(e, disconnectListenerList, disconnectListenerMap);
            synchronized (mutex) {
                connectionMap.remove(id);

                Collection<Long> tokenEmitterList = accessToken2ConnectionIdMap.get(accessToken);
                if (tokenEmitterList != null) {
                    tokenEmitterList.remove(id);
                    if (tokenEmitterList.isEmpty()) {
                        accessToken2ConnectionIdMap.remove(accessToken);
                    }
                }

                Collection<Long> userList = userId2ConnectionIdMap.get(userId);
                if (userList != null) {
                    userList.remove(id);
                    if (userList.isEmpty()) {
                        userId2ConnectionIdMap.remove(userId);
                    }
                }

                Collection<Long> tenantList = tenantId2ConnectionIdMap.get(tenantId);
                if (tenantList != null) {
                    tenantList.remove(id);
                    if (tenantList.isEmpty()) {
                        tenantId2ConnectionIdMap.remove(tenantId);
                    }
                }

                Collection<Long> channelList = channel2ConnectionIdMap.get(channel);
                if (channelList != null) {
                    channelList.remove(id);
                    if (channelList.isEmpty()) {
                        channel2ConnectionIdMap.remove(channel);
                    }
                }
            }
        });
        result.addConnectListener(e -> {
            String channel = wrapStringKey(e.getChannel());
            synchronized (mutex) {
                channel2ConnectionIdMap.computeIfAbsent(channel, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                        .add(e.getId());
            }
            log.debug("sse {} connection create : {}", beanName, e);
            notifyListener(e, connectListenerList, connectListenerMap);
        });
        result.addListeningWatch(e -> {
            for (Consumer<ChangeEvent<?, Set<String>>> changeEventConsumer : new ArrayList<>(listeningChangeWatchList)) {
                changeEventConsumer.accept(e);
            }
        });

        synchronized (mutex) {
            connectionMap.put(id, result);
            accessToken2ConnectionIdMap.computeIfAbsent(accessToken, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                    .add(id);
            tenantId2ConnectionIdMap.computeIfAbsent(tenantId, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                    .add(id);
            userId2ConnectionIdMap.computeIfAbsent(userId, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                    .add(id);
        }

        if (attributeMap != null) {
            result.getAttributeMap().putAll(attributeMap);
        }
        try {
            result.send(SseEmitter.event()
                    .reconnectTime(reconnectTime)
                    .name("connect-finish")
                    .data("{\"connectionId\":\"" + id + "\""
                            + ",\"serverTime\":" + System.currentTimeMillis()
                            + ",\"reconnectTime\":" + reconnectTime
                            + ",\"name\":\"" + beanName + "\""
                            + ",\"version\":\"" + SseEmitter.VERSION + "\""
                            + "}"));
            return result;
        } catch (IOException e) {
            log.error("sse {} send {} IOException:{}", beanName, result, e, e);
            return null;
        }
    }

    @Override
    public <ACCESS_USER> SseEmitter<ACCESS_USER> disconnectByConnectionId(Long connectionId) {
        SseEmitter<ACCESS_USER> sseEmitter = getConnectionById(connectionId);
        if (sseEmitter != null && sseEmitter.disconnect()) {
            return sseEmitter;
        } else {
            return null;
        }
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByAccessToken(String accessToken) {
        List<SseEmitter<ACCESS_USER>> sseEmitters = getConnectionByAccessToken(accessToken);
        List<SseEmitter<ACCESS_USER>> result = new ArrayList<>();
        if (sseEmitters != null) {
            for (SseEmitter<ACCESS_USER> next : sseEmitters) {
                if (next.disconnect()) {
                    result.add(next);
                }
            }
        }
        return result;
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByUserId(Serializable userId) {
        List<SseEmitter<ACCESS_USER>> sseEmitters = getConnectionByUserId(userId);
        List<SseEmitter<ACCESS_USER>> result = new ArrayList<>();
        if (sseEmitters != null) {
            for (SseEmitter<ACCESS_USER> next : sseEmitters) {
                if (next.disconnect()) {
                    result.add(next);
                }
            }
        }
        return result;
    }

    @Override
    public <ACCESS_USER> Collection<SseEmitter<ACCESS_USER>> getConnectionAll() {
        return (Collection) connectionMap.values();
    }

    @Override
    public <ACCESS_USER> SseEmitter<ACCESS_USER> getConnectionById(Long connectionId) {
        if (connectionId == null) {
            return null;
        } else {
            return connectionMap.get(connectionId);
        }
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByChannel(String channel) {
        Collection<Long> idList = channel2ConnectionIdMap.get(wrapStringKey(channel));
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        return idList.stream()
                .map(this::<ACCESS_USER>getConnectionById)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByAccessToken(String accessToken) {
        Collection<Long> idList = accessToken2ConnectionIdMap.get(wrapStringKey(accessToken));
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        return idList.stream()
                .map(this::<ACCESS_USER>getConnectionById)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByTenantId(Serializable tenantId) {
        Collection<Long> idList = tenantId2ConnectionIdMap.get(wrapStringKey(tenantId));
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        return idList.stream()
                .map(this::<ACCESS_USER>getConnectionById)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByUserId(Serializable userId) {
        Collection<Long> idList = userId2ConnectionIdMap.get(wrapStringKey(userId));
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        return idList.stream()
                .map(this::<ACCESS_USER>getConnectionById)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> void addConnectListener(String accessToken, String channel, Consumer<SseEmitter<ACCESS_USER>> consumer) {
        List<SseEmitter<ACCESS_USER>> sseEmitters = getConnectionByAccessToken(accessToken);
        if (sseEmitters != null) {
            for (SseEmitter<ACCESS_USER> emitter : sseEmitters) {
                if (emitter.isConnect() && Objects.equals(channel, emitter.getChannel())) {
                    consumer.accept(emitter);
                }
            }
        }
        connectListenerMap.computeIfAbsent(accessToken, e -> new ArrayList<>()).add(e -> {
            if (Objects.equals(channel, e.getChannel())) {
                consumer.accept(e);
                return true;
            }
            return false;
        });
    }

    @Override
    public <ACCESS_USER> void addConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer) {
        List<SseEmitter<ACCESS_USER>> sseEmitters = getConnectionByAccessToken(accessToken);
        if (sseEmitters != null) {
            for (SseEmitter<ACCESS_USER> emitter : sseEmitters) {
                if (emitter.isConnect()) {
                    consumer.accept(emitter);
                }
            }
        }
        connectListenerMap.computeIfAbsent(accessToken, e -> new ArrayList<>()).add(e -> {
            consumer.accept(e);
            return true;
        });
    }

    @Override
    public <ACCESS_USER> void addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer) {
        connectListenerList.add((Consumer) consumer);
    }

    @Override
    public <ACCESS_USER> void addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer) {
        disconnectListenerList.add((Consumer) consumer);
    }

    @Override
    public <ACCESS_USER> void addDisConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer) {
        disconnectListenerMap.computeIfAbsent(accessToken, e -> new ArrayList<>()).add(e -> {
            consumer.accept(e);
            return true;
        });
    }

    @Override
    public <ACCESS_USER> void addListeningChangeWatch(Consumer<ChangeEvent<ACCESS_USER, Set<String>>> watch) {
        listeningChangeWatchList.add((Consumer) watch);
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByListening(String sseListenerName) {
        return (List) connectionMap.values().stream()
                .filter(e -> e.existListener(sseListenerName))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isOnline(Serializable userId) {
        Set<Long> idList = userId2ConnectionIdMap.get(wrapStringKey(userId));
        return idList != null && !idList.isEmpty();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        return getConnectionAll().stream()
                .map(e -> (ACCESS_USER) e.getAccessUser())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        return getConnectionAll().stream()
                .filter(e -> e.existListener(sseListenerName))
                .map(e -> (ACCESS_USER) e.getAccessUser())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        return getConnectionByTenantId(tenantId).stream()
                .filter(e -> e.existListener(sseListenerName))
                .map(e -> (ACCESS_USER) e.getAccessUser())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Long> getConnectionIds() {
        return Collections.unmodifiableCollection(connectionMap.keySet());
    }

    @Override
    public Collection<String> getAccessTokens() {
        return Collections.unmodifiableCollection(accessToken2ConnectionIdMap.keySet());
    }

    @Override
    public <T> List<T> getTenantIds(Class<T> type) {
        return getConnectionAll().stream()
                .map(SseEmitter::getTenantId)
                .filter(Objects::nonNull)
                .map(e -> TypeUtil.cast(e, type))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /* getChannels */

    @Override
    public List<String> getChannels() {
        return getConnectionAll().stream()
                .map(SseEmitter::getChannel)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        List<SseEmitter<ACCESS_USER>> list = getConnectionByUserId(userId);
        return list.isEmpty() ? null : list.get(0).getAccessUser();
    }

    @Override
    public <T> Collection<T> getUserIds(Class<T> type) {
        if (type == String.class) {
            return (Collection<T>) Collections.unmodifiableCollection(userId2ConnectionIdMap.keySet());
        } else {
            return userId2ConnectionIdMap.keySet().stream()
                    .map(e -> TypeUtil.cast(e, type))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    @Override
    public <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type) {
        return getConnectionAll().stream()
                .filter(e -> e.existListener(sseListenerName))
                .map(SseEmitter::getUserId)
                .filter(Objects::nonNull)
                .map(e -> TypeUtil.cast(e, type))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type) {
        return getConnectionByTenantId(tenantId).stream()
                .filter(e -> e.existListener(sseListenerName))
                .map(SseEmitter::getUserId)
                .filter(Objects::nonNull)
                .map(e -> TypeUtil.cast(e, type))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取当前登录端数量
     */
    @Override
    public int getAccessTokenCount() {
        return accessToken2ConnectionIdMap.size();
    }

    /**
     * 获取当前用户数量
     */
    @Override
    public int getUserCount() {
        return userId2ConnectionIdMap.size();
    }

    /**
     * 获取当前连接数量
     */
    @Override
    public int getConnectionCount() {
        return connectionMap.size();
    }

    protected Runnable completionCallBack(SseEmitter sseEmitter) {
        return () -> {
            sseEmitter.disconnect();
            log.debug("sse {} completion 结束连接：{}", beanName, sseEmitter);
        };
    }

    protected Runnable timeoutCallBack(SseEmitter sseEmitter) {
        return () -> {
            sseEmitter.disconnect();
            log.debug("sse {} timeout 超过最大连接时间：{}", beanName, sseEmitter);
        };
    }

    protected Consumer<Throwable> errorCallBack(SseEmitter sseEmitter) {
        return throwable -> {
            sseEmitter.disconnect();
            log.debug("sse {} {} error 发生错误：{}, {}", beanName, sseEmitter, throwable, throwable);
        };
    }

    protected String wrapStringKey(Object key) {
        return key == null ? "" : key.toString();
    }

    protected <ACCESS_USER> void notifyListener(SseEmitter<ACCESS_USER> emitter,
                                                List<Consumer<SseEmitter>> listeners,
                                                Map<String, List<Predicate<SseEmitter>>> listenerMap) {
        for (Consumer<SseEmitter> listener : listeners) {
            try {
                listener.accept(emitter);
            } catch (Exception e) {
                log.error("notifyListener error = {}. listener = {}, emitter = {}", e.toString(), listener, emitter, e);
            }
        }
        List<Predicate<SseEmitter>> consumerList = listenerMap.get(wrapStringKey(emitter.getAccessToken()));
        if (consumerList != null) {
            for (Predicate<SseEmitter> listener : new ArrayList<>(consumerList)) {
                try {
                    if (listener.test(emitter)) {
                        consumerList.remove(listener);
                    }
                } catch (Exception e) {
                    log.error("notifyListener error = {}. predicate = {}, emitter = {}", e.toString(), listener, emitter, e);
                }
            }
        }
    }

    public <ACCESS_USER> boolean send(SseEmitter<ACCESS_USER> emitter, String name, Object body) {
        if (emitter != null && emitter.isActive()) {
            try {
                emitter.send(name, body);
                return true;
            } catch (IOException e) {
                emitter.disconnect();
            }
        }
        return false;
    }

    public int getReconnectTime() {
        return reconnectTime;
    }

    public void setReconnectTime(int reconnectTime) {
        this.reconnectTime = reconnectTime;
    }

    @Override
    public String getBeanName() {
        return beanName;
    }

    @Override
    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    @Override
    public void destroy() {
        destroyFlag = true;
        connectionMap.values().forEach(SseEmitter::disconnect);
        scheduled.shutdown();
        if (messageRepository != null) {
            messageRepository.close();
        }
    }

    @Override
    public Integer sendAll(String eventName, Serializable body) {
        int count = 0;
        for (SseEmitter value : connectionMap.values()) {
            if (send(value, eventName, body)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Integer sendAllListening(String eventName, Serializable body) {
        int count = 0;
        for (SseEmitter value : connectionMap.values()) {
            if (value.existListener(eventName) && send(value, eventName, body)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Integer sendByChannel(Collection<String> channels, String eventName, Serializable body) {
        int count = 0;
        for (String channel : channels) {
            for (SseEmitter value : getConnectionByChannel(channel)) {
                if (send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Integer sendByChannelListening(Collection<String> channels, String eventName, Serializable body) {
        int count = 0;
        for (String channel : channels) {
            for (SseEmitter value : getConnectionByChannel(channel)) {
                if (value.existListener(eventName) && send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Integer sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body) {
        int count = 0;
        for (String accessToken : accessTokens) {
            for (SseEmitter value : getConnectionByAccessToken(accessToken)) {
                if (send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Integer sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body) {
        int count = 0;
        for (String accessToken : accessTokens) {
            for (SseEmitter value : getConnectionByAccessToken(accessToken)) {
                if (value.existListener(eventName) && send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Integer sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        int count = 0;
        for (Serializable userId : userIds) {
            for (SseEmitter value : getConnectionByUserId(userId)) {
                if (send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Integer sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        int count = 0;
        for (Serializable userId : userIds) {
            for (SseEmitter value : getConnectionByUserId(userId)) {
                if (value.existListener(eventName) && send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Integer sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        int count = 0;
        for (Serializable tenantId : tenantIds) {
            for (SseEmitter value : getConnectionByTenantId(tenantId)) {
                if (send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Integer sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        int count = 0;
        for (Serializable tenantId : tenantIds) {
            for (SseEmitter value : getConnectionByTenantId(tenantId)) {
                if (value.existListener(eventName) && send(value, eventName, body)) {
                    count++;
                }
            }
        }
        return count;
    }
}

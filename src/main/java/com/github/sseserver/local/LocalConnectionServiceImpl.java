package com.github.sseserver.local;

import com.github.sseserver.SendService;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.qos.QosCompletableFuture;
import com.github.sseserver.remote.*;
import com.github.sseserver.springboot.SseServerBeanDefinitionRegistrar;
import com.github.sseserver.util.LambdaUtil;
import com.github.sseserver.util.PlatformDependentUtil;
import com.github.sseserver.util.TypeUtil;
import com.github.sseserver.util.WebUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
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
public class LocalConnectionServiceImpl implements LocalConnectionService, BeanNameAware, BeanFactoryAware {
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
    protected final List<Consumer<SseChangeEvent<?, Set<String>>>> listeningChangeWatchList = new ArrayList<>();
    /**
     * 如果 {@link Predicate#test(Object)} 返回true，则是只监听一次事件的一次性listener。 否则永久事件监听。
     * {@link #connectListenerMap,#disconnectListenerMap}
     */
    protected final Map<String, List<Predicate<SseEmitter>>> connectListenerMap = new ConcurrentHashMap<>();
    protected final Map<String, List<Predicate<SseEmitter>>> disconnectListenerMap = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> scopeOnWriteableThreadLocal = new ThreadLocal<>();
    private final boolean primary;
    private final Map<String, Long> setDurationByUserIdMap = new ConcurrentHashMap<>();
    private final Map<String, Long> setDurationByAccessTokenMap = new ConcurrentHashMap<>();
    private BeanFactory beanFactory;
    private String beanName = getClass().getSimpleName();
    private final ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, getBeanName() + "-" + SCHEDULED_INDEX.incrementAndGet()));
    private int reconnectTime = 5000;
    private Integer serverPort;
    private volatile BatchActiveRunnable clusterBatchActiveRunnable;
    private long clusterBatchActiveDelay = 50L;

    public LocalConnectionServiceImpl() {
        this.primary = false;
    }

    public LocalConnectionServiceImpl(boolean primary) {
        this.primary = primary;
    }

    @Override
    public ScheduledExecutorService getScheduled() {
        return scheduled;
    }

    @Override
    public SendService<QosCompletableFuture<Integer>> qos() {
        String beanName = SseServerBeanDefinitionRegistrar.getAtLeastOnceBeanName(this.beanName);
        return beanFactory.getBean(beanName, SendService.class);
    }

    @Override
    public ClusterConnectionService getCluster() {
        String beanName = SseServerBeanDefinitionRegistrar.getClusterConnectionServiceBeanName(this.beanName);
        return beanFactory.getBean(beanName, ClusterConnectionService.class);
    }

    @Override
    public ServiceDiscoveryService getDiscovery() {
        String beanName = SseServerBeanDefinitionRegistrar.getServiceDiscoveryServiceBeanName(this.beanName);
        return beanFactory.getBean(beanName, ServiceDiscoveryService.class);
    }

    @Override
    public MessageRepository getLocalMessageRepository() {
        String beanName = SseServerBeanDefinitionRegistrar.getLocalMessageRepositoryBeanName(this.beanName);
        return beanFactory.getBean(beanName, MessageRepository.class);
    }

    @Override
    public boolean isEnableCluster() {
        String beanName = SseServerBeanDefinitionRegistrar.getClusterConnectionServiceBeanName(this.beanName);
        try {
            return beanFactory.containsBean(beanName);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ClusterMessageRepository getClusterMessageRepository() {
        String beanName = SseServerBeanDefinitionRegistrar.getClusterMessageRepositoryBeanName(this.beanName);
        return beanFactory.getBean(beanName, ClusterMessageRepository.class);
    }

    @Override
    public <ACCESS_USER> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime, Map<String, Object> attributeMap) {
        if (keepaliveTime == null) {
            keepaliveTime = 900_000L;
        }
        // 设置超时时间，0表示不过期。servlet默认30秒，超过时间未完成会抛出异常：AsyncRequestTimeoutException
        SseEmitter<ACCESS_USER> result = new SseEmitter<>(keepaliveTime, accessUser);
        result.setServerId(WebUtil.getIPAddress(serverPort));
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
            if (log.isDebugEnabled()) {
                log.debug("sse {} connection disconnect : {}", beanName, e);
            }

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
            if (log.isDebugEnabled()) {
                log.debug("sse {} connection create : {}", beanName, e);
            }
            notifyListener(e, connectListenerList, connectListenerMap);
            notifyActive(userId, accessToken);
        });
        result.addListeningWatch(e -> {
            for (Consumer<SseChangeEvent<?, Set<String>>> changeEventConsumer : new ArrayList<>(listeningChangeWatchList)) {
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
                    .id(id.toString())
                    .reconnectTime(reconnectTime)
                    .name("connect-finish")
                    .data("{\"connectionId\":\"" + id + "\""
                            + ",\"serverTime\":" + System.currentTimeMillis()
                            + ",\"reconnectTime\":" + reconnectTime
                            + ",\"name\":\"" + beanName + "\""
                            + ",\"enableCluster\":" + isEnableCluster()
                            + ",\"version\":\"" + PlatformDependentUtil.SSE_SERVER_VERSION + "\""
                            + "}"));
            return result;
        } catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("sse {} send {} IOException:{}", beanName, result, e, e);
            }
            return null;
        }
    }

    public long getClusterBatchActiveDelay() {
        return clusterBatchActiveDelay;
    }

    public void setClusterBatchActiveDelay(long clusterBatchActiveDelay) {
        this.clusterBatchActiveDelay = clusterBatchActiveDelay;
    }

    private void notifyActive(String userId, String accessToken) {
        localActive(userId, accessToken);
        if (clusterBatchActiveRunnable != null || isEnableCluster()) {
            if (clusterBatchActiveRunnable == null) {
                synchronized (this) {
                    if (clusterBatchActiveRunnable == null) {
                        clusterBatchActiveRunnable = new BatchActiveRunnable(this);
                    }
                }
            }
            clusterBatchActiveRunnable.active(userId, accessToken);
            getScheduled().schedule(clusterBatchActiveRunnable, clusterBatchActiveDelay, TimeUnit.MILLISECONDS);
        }
    }

    public void localActive(String userId, String accessToken) {
        removeSetDuration(userId, accessToken);
    }

    private void removeSetDuration(String userId, String accessToken) {
        Long setDurationByAccessToken = setDurationByAccessTokenMap.remove(wrapStringKey(accessToken));
        if (setDurationByAccessToken != null) {
            for (SseEmitter<Object> e : getConnectionByAccessToken(accessToken)) {
                sendSetDuration(e, setDurationByAccessToken);
            }
        }
        Long setDurationByUserId = setDurationByUserIdMap.remove(wrapStringKey(userId));
        if (setDurationByUserId != null) {
            for (SseEmitter<Object> e : getConnectionByUserId(accessToken)) {
                sendSetDuration(e, setDurationByUserId);
            }
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
    public <ACCESS_USER> SseEmitter<ACCESS_USER> disconnectByConnectionId(Long connectionId, Long duration, Long sessionDuration) {
        SseEmitter<ACCESS_USER> sseEmitter = getConnectionById(connectionId);
        if (sseEmitter != null) {
            if (duration != null || sessionDuration != null) {
                if (duration == null) {
                    duration = 0L;
                }
                if (sessionDuration == null) {
                    sessionDuration = 0L;
                }
                sseEmitter.setSessionDuration(duration + sessionDuration);
            }
            if (sseEmitter.disconnect()) {
                return sseEmitter;
            }
        }
        return null;
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> disconnectByConnectionIds(Collection<Long> connectionIds) {
        if (connectionIds == null) {
            return Collections.emptyList();
        }
        List<SseEmitter<ACCESS_USER>> disconnectList = new ArrayList<>(connectionIds.size());
        for (Long connectionId : connectionIds) {
            SseEmitter<ACCESS_USER> disconnect = disconnectByConnectionId(connectionId);
            if (disconnect != null) {
                disconnectList.add(disconnect);
            }
        }
        return disconnectList;
    }

    private <ACCESS_USER> boolean sendSetDuration(SseEmitter<ACCESS_USER> result, long durationSecond) {
        try {
            result.setSessionDuration(durationSecond);
            result.send(SseEmitter.event()
                    .name("_set-duration")
                    .data("{\"duration\":\"" + durationSecond + "\"}"));
            return true;
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("sse {} setDuration{} {} IOException:{}", beanName, durationSecond, result, e, e);
            }
            return false;
        }
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> setDurationByUserId(Serializable userId, long durationSecond) {
        List<SseEmitter<ACCESS_USER>> list = getConnectionByUserId(userId);
        List<SseEmitter<ACCESS_USER>> result = new ArrayList<>(list.size());
        for (SseEmitter<ACCESS_USER> emitter : list) {
            if (sendSetDuration(emitter, durationSecond)) {
                result.add(emitter);
            }
        }
        if (result.isEmpty()) {
            setDurationByUserIdMap.put(wrapStringKey(userId), durationSecond);
        }
        return result;
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> setDurationByAccessToken(String accessToken, long durationSecond) {
        List<SseEmitter<ACCESS_USER>> list = getConnectionByAccessToken(accessToken);
        List<SseEmitter<ACCESS_USER>> result = new ArrayList<>(list.size());
        for (SseEmitter<ACCESS_USER> emitter : list) {
            if (sendSetDuration(emitter, durationSecond)) {
                result.add(emitter);
            }
        }
        if (result.isEmpty()) {
            setDurationByAccessTokenMap.put(wrapStringKey(accessToken), durationSecond);
        }
        return result;
    }

    @Override
    public <ACCESS_USER> SseEmitter<ACCESS_USER> setDurationByConnectionId(Long connectionId, long durationSecond) {
        SseEmitter<ACCESS_USER> result = getConnectionById(connectionId);
        if (sendSetDuration(result, durationSecond)) {
            return result;
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
    public <ACCESS_USER> void addListeningChangeWatch(Consumer<SseChangeEvent<ACCESS_USER, Set<String>>> watch) {
        listeningChangeWatchList.add((Consumer) watch);
    }

    @Override
    public <ACCESS_USER> List<SseEmitter<ACCESS_USER>> getConnectionByListening(String sseListenerName) {
        return (List) connectionMap.values().stream()
                .filter(e -> e.existListener(sseListenerName))
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER> List<ConnectionDTO<ACCESS_USER>> getConnectionDTOAll() {
        return this.<ACCESS_USER>getConnectionAll().stream()
                .map(ConnectionDTO::convert)
                .collect(Collectors.toList());
    }

    @Override
    public List<ConnectionByUserIdDTO> getConnectionDTOByUserId(Serializable userId) {
        return this.getConnectionByUserId(userId).stream()
                .map(ConnectionByUserIdDTO::convert)
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

    @Override
    public List<String> getChannels() {
        return getConnectionAll().stream()
                .map(SseEmitter::getChannel)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /* getChannels */

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
            if (log.isDebugEnabled()) {
                log.debug("sse {} completion 结束连接：{}", beanName, sseEmitter);
            }
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
            if (log.isDebugEnabled()) {
                log.debug("sse {} {} error 发生错误：{}", beanName, sseEmitter, throwable, throwable);
            }
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
                if (log.isErrorEnabled()) {
                    log.error("notifyListener error = {}. listener = {}, emitter = {}", e.toString(), listener, emitter, e);
                }
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
                    if (log.isErrorEnabled()) {
                        log.error("notifyListener error = {}. predicate = {}, emitter = {}", e.toString(), listener, emitter, e);
                    }
                }
            }
        }
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

    public <ACCESS_USER> boolean send(SseEmitter<ACCESS_USER> emitter, String name, Object body) {
        if (emitter != null && emitter.isActive()) {
            Boolean sendAtWriteable = scopeOnWriteableThreadLocal.get();
            if (sendAtWriteable != null && sendAtWriteable && !emitter.isWriteable()) {
                return false;
            }
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
    public boolean isPrimary() {
        return primary;
    }

    @Override
    public Integer sendAll(String eventName, Object body) {
        int count = 0;
        for (SseEmitter value : connectionMap.values()) {
            if (send(value, eventName, body)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Integer sendAllListening(String eventName, Object body) {
        int count = 0;
        for (SseEmitter value : connectionMap.values()) {
            if (value.existListener(eventName) && send(value, eventName, body)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Integer sendByChannel(Collection<String> channels, String eventName, Object body) {
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
    public Integer sendByChannelListening(Collection<String> channels, String eventName, Object body) {
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
    public Integer sendByAccessToken(Collection<String> accessTokens, String eventName, Object body) {
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
    public Integer sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Object body) {
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
    public Integer sendByUserId(Collection<? extends Serializable> userIds, String eventName, Object body) {
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
    public Integer sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Object body) {
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
    public Integer sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
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
    public Integer sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Object body) {
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

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Autowired(required = false)
    public void setServerPort(@Value("${server.port:8080}") Integer serverPort) {
        this.serverPort = serverPort;
    }

    @Override
    public String toString() {
        return "LocalConnectionServiceImpl{" +
                beanName + "[" + connectionMap.size() + "]" +
                '}';
    }

    private static class BatchActiveRunnable implements Runnable {
        private final LocalConnectionServiceImpl localConnectionService;
        private final Set<Request> requestSet = new LinkedHashSet<>();

        private BatchActiveRunnable(LocalConnectionServiceImpl localConnectionService) {
            this.localConnectionService = localConnectionService;
        }

        public void active(String userId, String accessToken) {
            synchronized (requestSet) {
                requestSet.add(new Request(userId, accessToken));
            }
        }

        @Override
        public void run() {
            if (requestSet.isEmpty()) {
                return;
            }
            ArrayList<Request> list;
            synchronized (requestSet) {
                list = new ArrayList<>(requestSet);
                requestSet.clear();
            }
            ClusterConnectionService cluster = localConnectionService.getCluster();
            if (cluster instanceof ClusterConnectionServiceImpl) {
                for (Request request : list) {
                    ((ClusterConnectionServiceImpl) cluster).active(request.userId, request.accessToken);
                }
            }
        }

        private static class Request {
            final String userId;
            final String accessToken;

            private Request(String userId, String accessToken) {
                this.userId = userId;
                this.accessToken = accessToken;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Request)) return false;
                Request request = (Request) o;
                return Objects.equals(userId, request.userId) && Objects.equals(accessToken, request.accessToken);
            }

            @Override
            public int hashCode() {
                return Objects.hash(userId, accessToken);
            }
        }
    }

}

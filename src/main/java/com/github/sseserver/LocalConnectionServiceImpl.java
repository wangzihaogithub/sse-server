package com.github.sseserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
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
    protected final Map<String, Set<Long>> accessToken2ConnectionIdMap = new ConcurrentHashMap<>();
    protected final Map<String, Set<Long>> channel2ConnectionIdMap = new ConcurrentHashMap<>();
    protected final Map<String, Set<Long>> tenantId2ConnectionIdMap = new ConcurrentHashMap<>();
    protected final Map<String, Set<String>> userId2AccessTokenMap = new ConcurrentHashMap<>();
    /**
     * 链接
     */
    protected final Map<Long, SseEmitter> connectionMap = new ConcurrentHashMap<>();
    /**
     * 永久事件监听。
     * {@link #connectListeners,#disconnectListeners}
     */
    protected final List<Consumer<SseEmitter>> connectListeners = new ArrayList<>();
    protected final List<Consumer<SseEmitter>> disconnectListeners = new ArrayList<>();
    /**
     * 如果 {@link Predicate#test(Object)} 返回true，则是只监听一次事件的一次性listener。 否则永久事件监听。
     * {@link #connectListenerMap,#disconnectListenerMap}
     */
    protected final Map<String, List<Predicate<SseEmitter>>> connectListenerMap = new ConcurrentHashMap<>();
    protected final Map<String, List<Predicate<SseEmitter>>> disconnectListenerMap = new ConcurrentHashMap<>();
    private String beanName = getClass().getSimpleName();
    private final ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, getBeanName() + "-" + SCHEDULED_INDEX.incrementAndGet()));
    private int reconnectTime = 5000;
    private boolean destroyFlag;

    @Override
    public ScheduledExecutorService getScheduled() {
        return scheduled;
    }

    /**
     * 创建用户连接并返回 SseEmitter
     *
     * @param accessUser 用户accessToken
     * @return SseEmitter
     */
    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime) {
        return connect(accessUser, keepaliveTime, null);
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime, Map<String, Object> attributeMap) {
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

            notifyListener(e, disconnectListeners, disconnectListenerMap);
            synchronized (this) {
                connectionMap.remove(id);

                Collection<Long> tokenEmitterList = accessToken2ConnectionIdMap.get(accessToken);
                if (tokenEmitterList != null) {
                    tokenEmitterList.remove(id);
                    if (tokenEmitterList.isEmpty()) {
                        accessToken2ConnectionIdMap.remove(accessToken);
                    }
                }

                Collection<String> userList = userId2AccessTokenMap.get(userId);
                if (userList != null) {
                    userList.remove(accessToken);
                    if (userList.isEmpty()) {
                        userId2AccessTokenMap.remove(userId);
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
            channel2ConnectionIdMap.computeIfAbsent(channel, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                    .add(e.getId());
            log.debug("sse {} connection create : {}", beanName, e);
            notifyListener(e, connectListeners, connectListenerMap);
        });

        connectionMap.put(id, result);
        accessToken2ConnectionIdMap.computeIfAbsent(accessToken, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                .add(id);
        tenantId2ConnectionIdMap.computeIfAbsent(tenantId, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                .add(id);
        userId2AccessTokenMap.computeIfAbsent(userId, o -> Collections.newSetFromMap(new ConcurrentHashMap<>(3)))
                .add(accessToken);

        if (attributeMap != null) {
            result.getAttributeMap().putAll(attributeMap);
        }
        try {
            result.send(SseEmitter.event()
                    .reconnectTime(reconnectTime)
                    .name("connect-finish")
                    .data("{\"connectionId\":" + id
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
    public SseEmitter disconnectByConnectionId(Long connectionId) {
        SseEmitter sseEmitter = getConnectionById(connectionId);
        if (sseEmitter != null && sseEmitter.disconnect()) {
            return sseEmitter;
        } else {
            return null;
        }
    }

    @Override
    public List<SseEmitter> disconnectByAccessToken(String accessToken) {
        List<SseEmitter> sseEmitters = getConnectionByAccessToken(accessToken);
        List<SseEmitter> result = new ArrayList<>();
        if (sseEmitters != null) {
            for (SseEmitter next : sseEmitters) {
                if (next.disconnect()) {
                    result.add(next);
                }
            }
        }
        return result;
    }

    @Override
    public List<SseEmitter> disconnectByUserId(Object userId) {
        List<SseEmitter> sseEmitters = getConnectionByUserId(userId);
        List<SseEmitter> result = new ArrayList<>();
        if (sseEmitters != null) {
            for (SseEmitter next : sseEmitters) {
                if (next.disconnect()) {
                    result.add(next);
                }
            }
        }
        return result;
    }

    @Override
    public List<SseEmitter> getConnectionAll() {
        return new ArrayList<>(connectionMap.values());
    }

    @Override
    public SseEmitter getConnectionById(Long connectionId) {
        if (connectionId == null) {
            return null;
        } else {
            return connectionMap.get(connectionId);
        }
    }

    @Override
    public List<SseEmitter> getConnectionByChannel(String channel) {
        Collection<Long> idList = channel2ConnectionIdMap.get(wrapStringKey(channel));
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        return idList.stream()
                .map(this::getConnectionById)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<SseEmitter> getConnectionByAccessToken(String accessToken) {
        Collection<Long> idList = accessToken2ConnectionIdMap.get(wrapStringKey(accessToken));
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        return idList.stream()
                .map(this::getConnectionById)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<SseEmitter> getConnectionByTenantId(Object tenantId) {
        Collection<Long> idList = tenantId2ConnectionIdMap.get(wrapStringKey(tenantId));
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        return idList.stream()
                .map(this::getConnectionById)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<SseEmitter> getConnectionByUserId(Object userId) {
        Collection<String> accessTokenList = userId2AccessTokenMap.get(wrapStringKey(userId));
        if (accessTokenList == null || accessTokenList.isEmpty()) {
            return Collections.emptyList();
        }
        return accessTokenList.stream()
                .map(this::getConnectionByAccessToken)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> void addConnectListener(String accessToken, String channel, Consumer<SseEmitter<ACCESS_USER>> consumer) {
        List<SseEmitter> sseEmitters = getConnectionByAccessToken(accessToken);
        if (sseEmitters != null) {
            for (SseEmitter emitter : sseEmitters) {
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
    public <ACCESS_USER extends AccessUser & AccessToken> void addConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer) {
        List<SseEmitter> sseEmitters = getConnectionByAccessToken(accessToken);
        if (sseEmitters != null) {
            for (SseEmitter emitter : sseEmitters) {
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
    public <ACCESS_USER extends AccessUser & AccessToken> void addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer) {
        connectListeners.add((Consumer) consumer);
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> void addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer) {
        disconnectListeners.add((Consumer) consumer);
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> void addDisConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer) {
        disconnectListenerMap.computeIfAbsent(accessToken, e -> new ArrayList<>()).add(e -> {
            consumer.accept(e);
            return true;
        });
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> List<SseEmitter<ACCESS_USER>> getConnectionByListener(String sseListenerName) {
        return (List) connectionMap.values().stream()
                .filter(e -> e.existListener(sseListenerName))
                .collect(Collectors.toList());
    }

    @Override
    public int send(Collection<SseEmitter> sseEmitterList, SseEventBuilder message) {
        int count = 0;
        for (SseEmitter emitter : sseEmitterList) {
            if (send(emitter, message)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int sendAll(SseEventBuilder message) {
        return send(getConnectionAll(), message);
    }

    @Override
    public int sendAllByClientListener(SseEventBuilder message, String sseListenerName) {
        int count = 0;
        for (SseEmitter emitter : connectionMap.values()) {
            if (emitter.existListener(sseListenerName) && send(emitter, message)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int sendByConnectionId(Collection<Long> connectionIds, SseEventBuilder message) {
        int count = 0;
        for (Long connectionId : connectionIds) {
            if (send(getConnectionById(connectionId), message)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int sendByChannel(Collection<String> channels, SseEventBuilder message) {
        int count = 0;
        for (String channel : channels) {
            count += send(getConnectionByChannel(channel), message);
        }
        return count;
    }

    @Override
    public int sendByAccessToken(Collection<String> accessTokens, SseEventBuilder message) {
        int count = 0;
        for (String accessToken : accessTokens) {
            count += send(getConnectionByAccessToken(accessToken), message);
        }
        return count;
    }

    @Override
    public int sendByUserId(Collection<?> userIds, SseEventBuilder message) {
        int count = 0;
        for (Object userId : userIds) {
            count += send(getConnectionByUserId(userId), message);
        }
        return count;
    }

    @Override
    public int sendByTenantId(Collection<?> tenantIds, SseEventBuilder message) {
        int count = 0;
        for (Object tenantId : tenantIds) {
            count += send(getConnectionByTenantId(tenantId), message);
        }
        return count;
    }

    @Override
    public int sendByTenantIdClientListener(Object tenantId, SseEventBuilder message, String sseListenerName) {
        List<SseEmitter> list = getConnectionByTenantId(tenantId);
        int count = 0;
        for (SseEmitter emitter : list) {
            if(emitter.existListener(sseListenerName) && send(emitter, message)){
                count ++;
            }
        }
        return count;
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> List<ACCESS_USER> getUsers() {
        return connectionMap.values().stream()
                .map(e -> (ACCESS_USER) e.getAccessUser())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> ACCESS_USER getUser(Object userId) {
        List<SseEmitter> list = getConnectionByUserId(userId);
        return list.isEmpty() ? null : (ACCESS_USER) list.get(0).getAccessUser();
    }

    @Override
    public boolean isOnline(Object userId) {
        Set<String> tokenSet = userId2AccessTokenMap.get(wrapStringKey(userId));
        return tokenSet != null && !tokenSet.isEmpty();
    }

    @Override
    public List<Long> getConnectionIds() {
        return new ArrayList<>(connectionMap.keySet());
    }

    @Override
    public List<String> getAccessTokens() {
        return new ArrayList<>(accessToken2ConnectionIdMap.keySet());
    }

    @Override
    public List<String> getUserIds() {
        return connectionMap.values().stream()
                .map(SseEmitter::getUserId)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(this::wrapStringKey)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getUserIdsByListener(String sseListenerName) {
        return connectionMap.values().stream()
                .filter(e -> e.existListener(sseListenerName))
                .map(SseEmitter::getUserId)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(this::wrapStringKey)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getTenantIds() {
        return connectionMap.values().stream()
                .map(SseEmitter::getTenantId)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(this::wrapStringKey)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getChannels() {
        return connectionMap.values().stream()
                .map(SseEmitter::getChannel)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(this::wrapStringKey)
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
        return (int) connectionMap.values().stream()
                .map(SseEmitter::getChannel)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(this::wrapStringKey)
                .distinct()
                .count();
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

    protected <ACCESS_USER extends AccessUser & AccessToken> void notifyListener(SseEmitter<ACCESS_USER> emitter,
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

    public <ACCESS_USER extends AccessUser & AccessToken> boolean send(SseEmitter<ACCESS_USER> sseEmitter, SseEventBuilder message) {
        if (sseEmitter != null && !sseEmitter.isDisconnect()) {
            try {
                sseEmitter.send(message);
                return true;
            } catch (IOException e) {
                sseEmitter.disconnect();
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
    }
}

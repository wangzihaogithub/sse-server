package com.github.sseserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
public class LocalConnectionServiceImpl implements LocalConnectionService {
    private final static Logger log = LoggerFactory.getLogger(LocalConnectionServiceImpl.class);
    /**
     * 使用map对象，便于根据access来获取对应的SseEmitter
     */
    private final Map<String, List<SseEmitter>> connectionMap = new ConcurrentHashMap<>();
    private final Map<Long, SseEmitter> connectionIdMap = new ConcurrentHashMap<>();
    private final List<Consumer<SseEmitter>> connectListeners = new ArrayList<>();
    private final List<Consumer<SseEmitter>> disconnectListeners = new ArrayList<>();
    private final Map<String, List<Predicate<SseEmitter>>> connectListenerMap = new ConcurrentHashMap<>();
    private final Map<String, List<Predicate<SseEmitter>>> disconnectListenerMap = new ConcurrentHashMap<>();

    /**
     * 创建用户连接并返回 SseEmitter
     *
     * @param accessUser 用户accessToken
     * @return SseEmitter
     */
    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime) {
        if (keepaliveTime == null) {
            keepaliveTime = 0L;
        }
        // 设置超时时间，0表示不过期。tomcat默认30秒，超过时间未完成会抛出异常：AsyncRequestTimeoutException
        SseEmitter<ACCESS_USER> sseEmitter = new SseEmitter<>(keepaliveTime, connectionMap, accessUser);
        sseEmitter.onCompletion(completionCallBack(sseEmitter));
        sseEmitter.onError(errorCallBack(sseEmitter));
        sseEmitter.onTimeout(timeoutCallBack(sseEmitter));
        sseEmitter.addDisConnectListener(e -> {
            notifyListener(e, disconnectListeners, disconnectListenerMap);
            connectionIdMap.remove(e.getId());
        });
        sseEmitter.addConnectListener(e -> {
            connectionIdMap.put(e.getId(), e);
            notifyListener(e, connectListeners, connectListenerMap);
        });
        try {
            sseEmitter.send(SseEmitter.event()
                    .reconnectTime(5000L)
                    .name("connect-finish")
                    .data("{\"connectionId\":" + sseEmitter.getId() + "}"));
            return sseEmitter;
        } catch (IOException e) {
            log.error("sse send {} IOException:{}", sseEmitter, e.toString(), e);
            return null;
        }
    }

    private <ACCESS_USER extends AccessUser & AccessToken> void notifyListener(SseEmitter<ACCESS_USER> sseEmitter,
                                                                               List<Consumer<SseEmitter>> listeners,
                                                                               Map<String, List<Predicate<SseEmitter>>> listenerMap) {
        for (Consumer<SseEmitter> listener : listeners) {
            listener.accept(sseEmitter);
        }
        List<Predicate<SseEmitter>> consumerList = listenerMap.get(sseEmitter.getAccessToken());
        if (consumerList != null) {
            for (Predicate<SseEmitter> listener : new ArrayList<>(consumerList)) {
                if (listener.test(sseEmitter)) {
                    consumerList.remove(listener);
                }
            }
        }
    }

    @Override
    public <ACCESS_USER extends AccessUser & AccessToken> void addConnectListener(String accessToken, String channel, Consumer<SseEmitter<ACCESS_USER>> consumer) {
        List<SseEmitter> sseEmitters = connectionMap.get(accessToken);
        if (sseEmitters != null) {
            for (SseEmitter sseEmitter : sseEmitters) {
                if (Objects.equals(channel, sseEmitter.getChannel())) {
                    consumer.accept(sseEmitter);
                    return;
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
        List<SseEmitter> sseEmitters = connectionMap.get(accessToken);
        if (sseEmitters != null) {
            for (SseEmitter sseEmitter : sseEmitters) {
                consumer.accept(sseEmitter);
            }
        } else {
            connectListenerMap.computeIfAbsent(accessToken, e -> new ArrayList<>()).add(e -> {
                consumer.accept(e);
                return true;
            });
        }
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
    public <ACCESS_USER extends AccessUser & AccessToken> int send(SseEmitter<ACCESS_USER> sseEmitter, SseEventBuilder message) {
        int count = 0;
        if (sseEmitter != null) {
            if (sseEmitter.isDisconnect()) {
                return 0;
            }
            if (sseEmitter.getChannel() != null) {
                return 0;
            }
            try {
                sseEmitter.send(message);
                count++;
            } catch (IOException e) {
                if (isSkipException(e)) {

                } else {
                    log.warn("sse send {} io exception = {}", sseEmitter, e.toString(), e);
                    sseEmitter.disconnect();
                }
            }
        }
        return count;
    }

    @Override
    public int send(long connectionId, SseEventBuilder message) {
        SseEmitter next = connectionIdMap.get(connectionId);
        return send(next, message);
    }

    /**
     * 给指定管道发送信息
     */
    @Override
    public int sendToChannel(String channel, SseEventBuilder message) {
        Collection<SseEmitter> sseEmitters = connectionIdMap.values();
        int count = 0;
        for (SseEmitter next : new ArrayList<>(sseEmitters)) {
            if (next.isDisconnect()) {
                continue;
            }
            if (Objects.equals(next.getChannel(), channel)) {
                try {
                    next.send(message);
                    count++;
                } catch (IOException e) {
                    if (isSkipException(e)) {

                    } else {
                        log.warn("sse send {} io exception = {}", next, e.toString(), e);
                        next.disconnect();
                    }
                }
            }
        }
        return count;
    }

    /**
     * 给指定用户发送信息
     */
    @Override
    public int send(String accessToken, SseEventBuilder message) {
        Collection<SseEmitter> sseEmitters = connectionMap.get(accessToken);
        int count = 0;
        if (sseEmitters != null) {
            for (SseEmitter next : new ArrayList<>(sseEmitters)) {
                count += send(next, message);
            }
        }
        return count;
    }

    /**
     * 群发消息
     */
    @Override
    public int send(Collection<String> accessTokens, SseEventBuilder message) {
        if (accessTokens == null) {
            return 0;
        }
        int totalSuccessCount = 0;
        for (String accessToken : accessTokens) {
            int sendCount = send(accessToken, message);
            if (sendCount > 0) {
                totalSuccessCount++;
            }
        }
        return totalSuccessCount;
    }

    /**
     * 群发所有人
     */
    @Override
    public int sendAll(SseEventBuilder message) {
        return send(new ArrayList<>(connectionMap.keySet()), message);
    }

    /**
     * 移除用户连接
     */
    @Override
    public List<SseEmitter> disconnect(String accessToken) {
        List<SseEmitter> sseEmitters = connectionMap.remove(accessToken);
        List<SseEmitter> result = new ArrayList<>();
        if (sseEmitters != null) {
            for (SseEmitter next : new ArrayList<>(sseEmitters)) {
                if (next.disconnect()) {
                    result.add(next);
                }
            }
        }
        return result;
    }

    @Override
    public SseEmitter disconnect(String accessToken, Long connectionId) {
        if (connectionId == null) {
            return null;
        }
        Collection<SseEmitter> sseEmitters = connectionMap.get(accessToken);
        if (sseEmitters != null) {
            for (SseEmitter next : new ArrayList<>(sseEmitters)) {
                if (Objects.equals(next.getId(), connectionId)) {
                    if (next.disconnect()) {
                        return next;
                    } else {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获取当前连接信息
     */
    @Override
    public List<String> getAccessTokens() {
        return new ArrayList<>(connectionMap.keySet());
    }

    /**
     * 获取当前用户数量
     */
    @Override
    public int getUserCount() {
        return connectionMap.size();
    }

    /**
     * 获取当前连接数量
     */
    @Override
    public int getConnectionCount() {
        int count = 0;
        for (List<SseEmitter> value : connectionMap.values()) {
            if (value != null) {
                count += value.size();
            }
        }
        return count;
    }

    private Runnable completionCallBack(SseEmitter sseEmitter) {
        return () -> {
            log.debug("sse completion 结束连接：{}", sseEmitter);
            sseEmitter.disconnect();
        };
    }

    private Runnable timeoutCallBack(SseEmitter sseEmitter) {
        return () -> {
            log.debug("sse timeout 超过最大连接时间：{}", sseEmitter);
            sseEmitter.disconnect();
        };
    }

    private Consumer<Throwable> errorCallBack(SseEmitter sseEmitter) {
        return throwable -> {
            log.debug("sse error 发生错误：{}, {}", sseEmitter, throwable.toString(), throwable);
            sseEmitter.disconnect();
        };
    }

    protected boolean isSkipException(IOException e) {
        String exceptionMessage = e.getMessage();
        return exceptionMessage != null && exceptionMessage.contains("Broken pipe");
    }

}

package com.github.sseserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class SseEmitter<ACCESS_USER extends AccessUser> extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
    private final static Logger log = LoggerFactory.getLogger(SseEmitter.class);
    private static final AtomicLong ID_INCR = new AtomicLong();
    private static final MediaType TEXT_PLAIN = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private final long id = ID_INCR.getAndIncrement();
    private final String accessToken;
    private final ACCESS_USER accessUser;
    private final AtomicBoolean disconnect = new AtomicBoolean();
    private final Map<String, List<SseEmitter>> connectionMap;
    private final List<Consumer<SseEmitter<ACCESS_USER>>> connectListeners = new ArrayList<>();
    private final List<Consumer<SseEmitter<ACCESS_USER>>> disconnectListeners = new ArrayList<>();
    private final Map<String, Object> attributeMap = new LinkedHashMap<>();
    private boolean connect = false;
    private int count;
    private String channel;

    /**
     * timeout = 0是永不过期
     */
    public SseEmitter(Long timeout) {
        this(timeout, new HashMap<>(), null);
    }

    /**
     * timeout = 0是永不过期
     */
    public SseEmitter(Long timeout, Map<String, List<SseEmitter>> connectionMap, ACCESS_USER accessUser) {
        super(timeout);
        this.connectionMap = connectionMap;
        this.accessUser = accessUser;
        this.accessToken = accessUser != null ? accessUser.getAccessToken() : null;
        connectionMap.computeIfAbsent(accessToken, e -> Collections.synchronizedList(new ArrayList<>()))
                .add(this);
        log.info("sse connection create : {}", this);
    }

    public static SseEventBuilder event() {
        return new SseEventBuilderImpl();
    }

    public long getId() {
        return id;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public ACCESS_USER getAccessUser() {
        return accessUser;
    }

    public Map<String, Object> getAttributeMap() {
        return attributeMap;
    }

    public <T> T getAttribute(String key) {
        return (T) attributeMap.get(key);
    }

    public <T> T setAttribute(String key, Object value) {
        return (T) attributeMap.put(key, value);
    }

    public <T> T removeAttribute(String key) {
        return (T) attributeMap.remove(key);
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public void addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer) {
        if (connect) {
            try {
                consumer.accept(this);
            } catch (Exception e) {
                log.warn("addConnectListener connectListener error = {} {}", e.toString(), consumer, e);
            }
        } else {
            connectListeners.add(consumer);
        }
    }

    public void addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer) {
        if (isDisconnect()) {
            consumer.accept(this);
        } else {
            disconnectListeners.add(consumer);
        }
    }

    @Override
    protected void extendResponse(ServerHttpResponse outputMessage) {
        super.extendResponse(outputMessage);
        connect = true;
        for (Consumer<SseEmitter<ACCESS_USER>> connectListener : new ArrayList<>(connectListeners)) {
            try {
                connectListener.accept(this);
            } catch (Exception e) {
                log.warn("connectListener error = {} {}", e.toString(), connectListener, e);
            }
        }
        connectListeners.clear();
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        if (builder instanceof SseEventBuilderImpl) {
            String id = ((SseEventBuilderImpl) builder).id;
            String name = ((SseEventBuilderImpl) builder).name;
            log.info("sse connection send {} : {}, id = {}, name = {}", ++count, this, id, name);
        } else {
            log.info("sse connection send {} : {}", ++count, this);
        }
        super.send(builder);
    }

    public boolean isDisconnect() {
        return disconnect.get();
    }

    public boolean disconnect() {
        boolean remove = false;
        if (disconnect.compareAndSet(false, true)) {
            for (Consumer<SseEmitter<ACCESS_USER>> disconnectListener : new ArrayList<>(disconnectListeners)) {
                try {
                    disconnectListener.accept(this);
                } catch (Exception e) {
                    log.warn("disconnectListener error = {} {}", e.toString(), disconnectListener, e);
                }
            }
            disconnectListeners.clear();

            List<SseEmitter> sseEmitterList = connectionMap.get(accessToken);
            if (sseEmitterList != null) {
                try {
                    remove = sseEmitterList.remove(this);
                    log.info("sse connection disconnect : {}", this);
                } catch (Exception e) {
                    remove = false;
                }
                if (sseEmitterList.isEmpty()) {
                    connectionMap.remove(accessToken);
                }
            }

            try {
                complete();
            } catch (Exception e) {
                log.info("sse connection disconnect exception : {}. {}", e.toString(), this);
            }
        }
        return remove;
    }

    @Override
    public String toString() {
        if (accessUser == null) {
            return id + "#";
        } else {
            return id + "#" + accessUser;
        }
    }

    /**
     * Default implementation of SseEventBuilder.
     */
    private static class SseEventBuilderImpl implements SseEventBuilder {
        private final Set<DataWithMediaType> dataToSend = new LinkedHashSet<>(4);
        private String id;
        private String name;
        @Nullable
        private StringBuilder sb;

        @Override
        public SseEventBuilder id(String id) {
            this.id = id;
            append("id:").append(id).append("\n");
            return this;
        }

        @Override
        public SseEventBuilder name(String name) {
            this.name = name;
            append("event:").append(name).append("\n");
            return this;
        }

        @Override
        public SseEventBuilder reconnectTime(long reconnectTimeMillis) {
            append("retry:").append(String.valueOf(reconnectTimeMillis)).append("\n");
            return this;
        }

        @Override
        public SseEventBuilder comment(String comment) {
            append(":").append(comment).append("\n");
            return this;
        }

        @Override
        public SseEventBuilder data(Object object) {
            return data(object, null);
        }

        @Override
        public SseEventBuilder data(Object object, @Nullable MediaType mediaType) {
            append("data:");
            saveAppendedText();
            this.dataToSend.add(new DataWithMediaType(object, mediaType));
            append("\n");
            return this;
        }

        SseEventBuilderImpl append(String text) {
            if (this.sb == null) {
                this.sb = new StringBuilder();
            }
            this.sb.append(text);
            return this;
        }

        @Override
        public Set<DataWithMediaType> build() {
            if (!StringUtils.hasLength(this.sb) && this.dataToSend.isEmpty()) {
                return Collections.emptySet();
            }
            append("\n");
            saveAppendedText();
            return this.dataToSend;
        }

        private void saveAppendedText() {
            if (this.sb != null) {
                this.dataToSend.add(new DataWithMediaType(this.sb.toString(), TEXT_PLAIN));
                this.sb = null;
            }
        }
    }

}

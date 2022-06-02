package com.github.sseserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SseEmitter<ACCESS_USER extends AccessUser & AccessToken> extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
    private final static Logger log = LoggerFactory.getLogger(SseEmitter.class);
    private static final AtomicLong ID_INCR = new AtomicLong();
    private static final MediaType TEXT_PLAIN = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private final long id = newId();
    private final ACCESS_USER accessUser;
    private final AtomicBoolean disconnect = new AtomicBoolean();
    private final List<Consumer<SseEmitter<ACCESS_USER>>> connectListeners = new ArrayList<>();
    private final List<Consumer<SseEmitter<ACCESS_USER>>> disconnectListeners = new ArrayList<>();
    private final Map<String, Object> attributeMap = new LinkedHashMap<>();
    private final long createTime = System.currentTimeMillis();
    private boolean connect = false;
    private int count;
    private String channel;

    private String requestIp;
    private String requestDomain;
    private String userAgent;

    private final Map<String, Object> httpParameters = new LinkedHashMap<>();
    private Map<String, String> httpHeaders = new LinkedHashMap<>();
    private Cookie[] httpCookies;

    /**
     * timeout = 0是永不过期
     */
    public SseEmitter(Long timeout) {
        this(timeout, null);
    }

    /**
     * timeout = 0是永不过期
     */
    public SseEmitter(Long timeout, ACCESS_USER accessUser) {
        super(timeout);
        this.accessUser = accessUser;
    }

    private static long newId() {
        long id = ID_INCR.getAndIncrement();
        if (id == Integer.MAX_VALUE) {
            id = 0;
            ID_INCR.set(1);
        }
        return id;
    }

    public static SseEventBuilder event() {
        return new SseEventBuilderImpl();
    }

    private static Long castLong(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Date) {
            return ((Date) value).getTime();
        }
        return Long.valueOf(value.toString());
    }

    public void setHttpCookies(Cookie[] httpCookies) {
        this.httpCookies = httpCookies;
    }

    public Map<String, Object> getHttpParameters() {
        return httpParameters;
    }

    public Cookie[] getHttpCookies() {
        return httpCookies;
    }

    public Map<String, String> getHttpHeaders() {
        return httpHeaders;
    }

    public String getRequestIp() {
        return requestIp;
    }

    public void setRequestIp(String requestIp) {
        this.requestIp = requestIp;
    }

    public String getRequestDomain() {
        return requestDomain;
    }

    public void setRequestDomain(String requestDomain) {
        this.requestDomain = requestDomain;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getCount() {
        return count;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getId() {
        return id;
    }

    public Date getAccessTime() {
        Long accessTime = castLong(httpParameters.get("accessTime"));
        return accessTime != null ? new Date(accessTime) : null;
    }

    public List<String> getListeners() {
        String listeners = (String) httpParameters.get("listeners");
        return listeners != null && listeners.length() > 0 ? Arrays.asList(listeners.split(",")) : Collections.emptyList();
    }

    public String getClientId() {
        return (String) httpParameters.get("clientId");
    }

    public String getScreen() {
        return (String) httpParameters.get("screen");
    }

    public Long getTotalJSHeapSize() {
        return castLong(httpParameters.get("totalJSHeapSize"));
    }

    public Long getUsedJSHeapSize() {
        return castLong(httpParameters.get("usedJSHeapSize"));
    }

    public Long getJsHeapSizeLimit() {
        return castLong(httpParameters.get("jsHeapSizeLimit"));
    }

    public String getClientVersion() {
        return (String) httpParameters.get("clientVersion");
    }

    public Object getUserId() {
        return accessUser != null && accessUser instanceof AccessUser ? ((AccessUser) accessUser).getId() : null;
    }

    public String getAccessToken() {
        return accessUser != null && accessUser instanceof AccessToken ? ((AccessToken) accessUser).getAccessToken() : null;
    }

    public Object getCustomerId() {
        return accessUser != null && accessUser instanceof CustomerAccessUser ? ((CustomerAccessUser) accessUser).getCustomerId() : null;
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

    public boolean isConnect() {
        return connect;
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
            try {
                consumer.accept(this);
            } catch (Exception e) {
                log.warn("addDisConnectListener connectListener error = {} {}", e.toString(), consumer, e);
            }
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
            log.debug("sse connection send {} : {}, id = {}, name = {}", ++count, this, id, name);
        } else {
            log.debug("sse connection send {} : {}", ++count, this);
        }
        super.send(builder);
    }

    public boolean isDisconnect() {
        return disconnect.get();
    }

    public boolean disconnect() {
        if (disconnect.compareAndSet(false, true)) {
            for (Consumer<SseEmitter<ACCESS_USER>> disconnectListener : new ArrayList<>(disconnectListeners)) {
                try {
                    disconnectListener.accept(this);
                } catch (Exception e) {
                    log.warn("disconnectListener error = {} {}", e.toString(), disconnectListener, e);
                }
            }
            disconnectListeners.clear();

            try {
                complete();
            } catch (Exception e) {
                log.warn("sse connection disconnect exception : {}. {}", e.toString(), this);
            }
            return true;
        } else {
            return false;
        }
    }

    public boolean isMessageChange(Object newMessage, String messageType) {
        Object oldMessage = getAttribute(messageType);
        if (Objects.equals(oldMessage, newMessage)) {
            return false;
        }
        setAttribute(messageType, newMessage);
        return true;
    }

    public <MESSAGE, MESSAGE_ID> List<MESSAGE> distinctMessageList(List<MESSAGE> messageList,
                                                                   Function<MESSAGE, MESSAGE_ID> idGetter,
                                                                   String messageType) {
        Set<MESSAGE_ID> distinctSet = (Set) getAttributeMap().computeIfAbsent(messageType, o -> new HashSet<>());
        return messageList.stream()
                .filter(e -> !distinctSet.contains(idGetter.apply(e)))
                .peek(e -> distinctSet.add(idGetter.apply(e)))
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        if (accessUser == null) {
            return id + "#";
        } else {
            return id + "#" + accessUser;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof SseEmitter) {
            return ((SseEmitter) obj).id == this.id;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(this.id);
    }

    /**
     * Default implementation of SseEventBuilder.
     */
    private static class SseEventBuilderImpl implements SseEventBuilder {
        private final Set<DataWithMediaType> dataToSend = new LinkedHashSet<>(4);
        private String id;
        private String name;
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
        public SseEventBuilder data(Object object, MediaType mediaType) {
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
            if ((sb == null || sb.length() == 0) && this.dataToSend.isEmpty()) {
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

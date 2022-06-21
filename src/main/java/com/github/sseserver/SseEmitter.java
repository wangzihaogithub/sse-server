package com.github.sseserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
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
    private final Map<String, Object> httpParameters = new LinkedHashMap<>();
    private final Map<String, String> httpHeaders = new LinkedHashMap<>();
    private boolean connect = false;
    private int count;
    private int requestUploadCount;
    private int requestMessageCount;
    private long lastRequestTimestamp;
    private String channel;
    private String requestIp;
    private String requestDomain;
    private String userAgent;
    private Cookie[] httpCookies;
    /**
     * 前端已正在监听的钩子, 值是 {@link SseEventBuilder#name(String)}
     */
    private Set<String> listeners;
    private ScheduledFuture<?> timeoutCheckFuture;

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

    public static SseEventBuilder event(String name, Object data) {
        return new SseEventBuilderImpl().name(name).data(data);
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

    void requestUpload() {
        this.requestUploadCount++;
        this.lastRequestTimestamp = System.currentTimeMillis();
    }

    void requestMessage() {
        this.requestMessageCount++;
        this.lastRequestTimestamp = System.currentTimeMillis();
    }

    public int getRequestUploadCount() {
        return requestUploadCount;
    }

    public int getRequestMessageCount() {
        return requestMessageCount;
    }

    public long getLastRequestTimestamp() {
        return lastRequestTimestamp;
    }

    public Map<String, Object> getHttpParameters() {
        return httpParameters;
    }

    public Cookie[] getHttpCookies() {
        return httpCookies;
    }

    public void setHttpCookies(Cookie[] httpCookies) {
        this.httpCookies = httpCookies;
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

    public boolean isTimeout() {
        Long timeout = getTimeout();
        if (timeout == null) {
            // servlet 默认30秒
            timeout = 30_000L;
        } else if (timeout <= 0L) {
            // 0是永不超时
            return false;
        }
        return (System.currentTimeMillis() - createTime) > timeout;
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

    /**
     * 前端JS 已正在监听的钩子, 值是 {@link SseEventBuilder#name(String)}
     *
     * @return
     */
    public Set<String> getListeners() {
        if (this.listeners == null) {
            String listeners = (String) httpParameters.get("listeners");
            this.listeners = listeners != null && listeners.length() > 0 ? new LinkedHashSet<>(Arrays.asList(listeners.split(","))) : Collections.emptySet();
        }
        return this.listeners;
    }

    /**
     * 前端JS是否在监听这个事件, 值是 {@link SseEventBuilder#name(String)}
     * 前端没监听就不用推消息
     *
     * @return true=在监听
     */
    public boolean existListener(String sseListenerName) {
        return getListeners().contains(sseListenerName);
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

    public void send(String name, Object data) throws IOException {
        send(event().name(name).data(data));
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        count++;
        if (builder instanceof SseEventBuilderImpl) {
            String id = ((SseEventBuilderImpl) builder).id;
            String name = ((SseEventBuilderImpl) builder).name;
            log.debug("sse connection send {} : {}, id = {}, name = {}", count, this, id, name);
        } else {
            log.debug("sse connection send {} : {}", count, this);
        }
        try {
            super.send(builder);
        } catch (IllegalStateException e) {
            /* tomcat recycle bug.  socketWrapper is null. is read op cancel then recycle()
             * Http11OutputBuffer: 254行，对端网络关闭， 但没触发onError或onTimeout回调， 这时不知道是否不可用了
             *
             * Caused by: java.lang.NullPointerException
             * 	at org.apache.coyote.http11.Http11OutputBuffer$SocketOutputBuffer.doWrite(Http11OutputBuffer.java:530)
             * 	at org.apache.coyote.http11.filters.ChunkedOutputFilter.doWrite(ChunkedOutputFilter.java:110)
             * 	at org.apache.coyote.http11.Http11OutputBuffer.doWrite(Http11OutputBuffer.java:189)
             * 	at org.apache.coyote.Response.doWrite(Response.java:599)
             * 	at org.apache.catalina.connector.OutputBuffer.realWriteBytes(OutputBuffer.java:329)
             * 	at org.apache.catalina.connector.OutputBuffer.flushByteBuffer(OutputBuffer.java:766)
             * 	at org.apache.catalina.connector.OutputBuffer.doFlush(OutputBuffer.java:288)
             * 	at org.apache.catalina.connector.OutputBuffer.flush(OutputBuffer.java:262)
             * 	at org.apache.catalina.connector.CoyoteOutputStream.flush(CoyoteOutputStream.java:118)
             * 	at sun.nio.cs.StreamEncoder.implFlush(StreamEncoder.java:297)
             * 	at sun.nio.cs.StreamEncoder.flush(StreamEncoder.java:141)
             * 	at java.io.OutputStreamWriter.flush(OutputStreamWriter.java:229)
             * 	at org.springframework.util.StreamUtils.copy(StreamUtils.java:124)
             * 	at org.springframework.http.converter.StringHttpMessageConverter.writeInternal(StringHttpMessageConverter.java:106)
             * 	at org.springframework.http.converter.StringHttpMessageConverter.writeInternal(StringHttpMessageConverter.java:43)
             * 	at org.springframework.http.converter.AbstractHttpMessageConverter.write(AbstractHttpMessageConverter.java:227)
             * 	at org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler$HttpMessageConvertingHandler.sendInternal(ResponseBodyEmitterReturnValueHandler.java:191)
             * 	at org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler$HttpMessageConvertingHandler.send(ResponseBodyEmitterReturnValueHandler.java:184)
             * 	at org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.sendInternal(ResponseBodyEmitter.java:189)
             */
//            if (e.getCause() instanceof NullPointerException) {
            disconnect();
            throw new ClosedChannelException();
//            }
        }
    }

    public boolean isDisconnect() {
        return disconnect.get();
    }

    private void cancelTimeoutTask() {
        ScheduledFuture future = this.timeoutCheckFuture;
        if (future != null) {
            this.timeoutCheckFuture = null;
            future.cancel(false);
        }
    }

    void setTimeoutCheckFuture(ScheduledFuture<?> timeoutCheckFuture) {
        this.timeoutCheckFuture = timeoutCheckFuture;
    }

    void disconnectByTimeoutCheck() {
        this.timeoutCheckFuture = null;
        disconnect();
    }

    public boolean disconnect() {
        cancelTimeoutTask();
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

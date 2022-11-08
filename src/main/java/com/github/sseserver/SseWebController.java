package com.github.sseserver;

import com.github.sseserver.util.PageInfo;
import com.github.sseserver.util.WebUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消息事件推送 (非分布式)
 * 注: !! 这里是示例代码, 根据自己项目封装的用户逻辑, 继承类或复制到自己项目里都行
 * <p>
 * 1. 如果用nginx代理, 要加下面的配置
 * # 长连接配置
 * proxy_buffering off;
 * proxy_read_timeout 7200s;
 * proxy_pass http://xx.xx.xx.xx:xxx;
 * proxy_http_version 1.1; #nginx默认是http1.0, 改为1.1 支持长连接, 和后端保持长连接,复用,防止出现文件句柄打开数量过多的错误
 * proxy_set_header Connection ""; # 去掉Connection的close字段
 *
 * @author hao 2021年12月7日19:29:51
 */
//@RestController
//@RequestMapping("/a/sse")
//@RequestMapping("/b/sse")
public class SseWebController<ACCESS_USER extends AccessUser & AccessToken> {
    @Autowired
    protected HttpServletRequest request;
    protected LocalConnectionService localConnectionService;

    @Value("${server.port:8080}")
    private Integer serverPort;
    private String sseServerIdHeaderName = "Sse-Server-Id";
    private Integer clientIdMaxConnections = 3;
    private Long keepaliveTime;

    public Long getKeepaliveTime() {
        return keepaliveTime;
    }

    public void setKeepaliveTime(Long keepaliveTime) {
        this.keepaliveTime = keepaliveTime;
    }

    public Integer getClientIdMaxConnections() {
        return clientIdMaxConnections;
    }

    public void setClientIdMaxConnections(Integer clientIdMaxConnections) {
        this.clientIdMaxConnections = clientIdMaxConnections;
    }

    public String getSseServerIdHeaderName() {
        return sseServerIdHeaderName;
    }

    public void setSseServerIdHeaderName(String sseServerIdHeaderName) {
        this.sseServerIdHeaderName = sseServerIdHeaderName;
    }

    /**
     * 前端文件
     */
    @RequestMapping("")
    public Object index() {
        return ssejs();
    }

    /**
     * 前端文件
     */
    @RequestMapping("/sse.js")
    public Object ssejs() {
        HttpHeaders headers = new HttpHeaders();
        settingResponseHeader(headers);
        headers.set("Content-Type", "application/javascript;charset=utf-8");
        InputStream stream = SseWebController.class.getResourceAsStream("/sse.js");
        Resource body = new InputStreamResource(stream);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    public void setLocalConnectionService(LocalConnectionService localConnectionService) {
        this.localConnectionService = localConnectionService;
    }

    @Autowired(required = false)
    public void setLocalConnectionServiceMap(Map<String, LocalConnectionService> localConnectionServiceMap) {
        if (this.localConnectionService == null && localConnectionServiceMap != null && localConnectionServiceMap.size() > 0) {
            this.localConnectionService = localConnectionServiceMap.values().iterator().next();
        }
    }


    /**
     * 获取当前登录用户, 这里返回后, 就可以获取 {@link SseEmitter#getAccessUser()}
     *
     * @return 使用者自己系统的用户
     */
    protected ACCESS_USER getAccessUser() {
        return null;
    }

    protected Object wrapOkResponse(Object result) {
        return new ResponseWrap<>(result);
    }

    protected Object onMessage(String path, SseEmitter<ACCESS_USER> connection, Map<String, Object> message) {
        return path;
    }

    protected Object onUpload(String path, SseEmitter<ACCESS_USER> connection, Map<String, Object> message, Collection<Part> files) {
        return path;
    }

    protected void onConnect(SseEmitter<ACCESS_USER> conncet, Map<String, Object> query) {
        if (clientIdMaxConnections != null) {
            disconnectClientIdMaxConnections(conncet, clientIdMaxConnections);
        }
    }

    protected void onDisconnect(List<SseEmitter<ACCESS_USER>> disconnectList, ACCESS_USER accessUser, Map query) {

    }

    protected ResponseEntity buildIfLoginVerifyErrorResponse(ACCESS_USER accessUser,
                                                             Map query, Map body,
                                                             Long keepaliveTime) {
        if (accessUser == null) {
            return buildUnauthorizedResponse();
        }
        return null;
    }

    protected ResponseEntity buildIfConnectVerifyErrorResponse(SseEmitter<ACCESS_USER> emitter) {
        return null;
    }

    protected ResponseEntity buildUnauthorizedResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setConnection("close");
        settingResponseHeader(headers);
        return new ResponseEntity<>("", headers, HttpStatus.UNAUTHORIZED);
    }

    protected Long choseKeepaliveTime(Long clientKeepaliveTime, Long serverKeepaliveTime) {
        if (serverKeepaliveTime != null) {
            return serverKeepaliveTime;
        } else {
            return clientKeepaliveTime;
        }
    }

    /**
     * 创建连接
     */
    @RequestMapping("/connect")
    public Object connect(@RequestParam Map query, @RequestBody(required = false) Map body,
                          Long keepaliveTime) {
        // args
        Map<String, Object> attributeMap = new LinkedHashMap<>(query);
        if (body != null) {
            attributeMap.putAll(body);
        }
        Long choseKeepaliveTime = choseKeepaliveTime(keepaliveTime, getKeepaliveTime());

        // Verify 1 login
        ACCESS_USER currentUser = getAccessUser();
        ResponseEntity errorResponseEntity = buildIfLoginVerifyErrorResponse(currentUser, query, body, choseKeepaliveTime);
        if (errorResponseEntity != null) {
            return errorResponseEntity;
        }

        // build connect
        SseEmitter<ACCESS_USER> emitter = localConnectionService.connect(currentUser, choseKeepaliveTime, attributeMap);
        if (emitter == null) {
            return buildUnauthorizedResponse();
        }

        // dump
        String channel = Objects.toString(attributeMap.get("channel"), null);
        emitter.setChannel(isBlank(channel) ? null : channel);
        emitter.setUserAgent(request.getHeader("User-Agent"));
        emitter.setRequestIp(WebUtil.getRequestIpAddr(request));
        emitter.setRequestDomain(WebUtil.getRequestDomain(request, false));
        emitter.setHttpCookies(request.getCookies());
        emitter.getHttpParameters().putAll(attributeMap);
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            emitter.getHttpHeaders().put(name, request.getHeader(name));
        }

        // Verify 2 connect
        errorResponseEntity = buildIfConnectVerifyErrorResponse(emitter);
        if (errorResponseEntity != null) {
            return errorResponseEntity;
        }

        // callback
        onConnect(emitter, attributeMap);
        settingResponseHeader(emitter.getResponseHeaders());
        return emitter;
    }

    /**
     * 新增监听
     *
     * @return http原生响应
     */
    @RequestMapping("/addListener")
    public ResponseEntity addListener(@RequestBody ListenerReq req) {
        if (req == null || req.isInvalid()) {
            return responseEntity(Collections.singletonMap("listener", null));
        }
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        SseEmitter<ACCESS_USER> emitter = localConnectionService.getConnectionById(req.getConnectionId());
        emitter.getListeners().addAll(req.getListener());
        return responseEntity(Collections.singletonMap("listener", emitter.getListeners()));
    }

    /**
     * 移除监听
     *
     * @return http原生响应
     */
    @RequestMapping("/removeListener")
    public ResponseEntity removeListener(@RequestBody ListenerReq req) {
        if (req == null || req.isInvalid()) {
            return responseEntity(Collections.singletonMap("listener", null));
        }
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        SseEmitter<ACCESS_USER> emitter = localConnectionService.getConnectionById(req.getConnectionId());
        for (String s : req.getListener()) {
            emitter.getListeners().remove(s);
        }
        return responseEntity(Collections.singletonMap("listener", emitter.getListeners()));
    }

    /**
     * 收到前端的消息
     *
     * @return http原生响应
     */
    @RequestMapping("/message/{path}")
    public ResponseEntity message(@PathVariable String path, Long connectionId, @RequestParam Map query, @RequestBody(required = false) Map body) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        SseEmitter<ACCESS_USER> emitter = localConnectionService.getConnectionById(connectionId);
        Map message = new LinkedHashMap<>(query);
        message.remove("connectionId");
        if (body != null) {
            message.putAll(body);
        }
        if (emitter != null) {
            emitter.requestMessage();
        }
        Object responseBody = onMessage(path, emitter, message);
        return responseEntity(responseBody);
    }

    /**
     * 收到前端上传的数据
     *
     * @return http原生响应
     */
    @RequestMapping("/upload/{path}")
    public ResponseEntity upload(@PathVariable String path, HttpServletRequest request, Long connectionId, @RequestParam Map query, @RequestBody(required = false) Map body) throws IOException, ServletException {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        SseEmitter<ACCESS_USER> emitter = localConnectionService.getConnectionById(connectionId);
        Map message = new LinkedHashMap<>(query);
        message.remove("connectionId");
        if (body != null) {
            message.putAll(body);
        }
        if (emitter != null) {
            emitter.requestUpload();
        }

        Object responseBody = onUpload(path, emitter, message, request.getParts());
        return responseEntity(responseBody);
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect/{connectionId}")
    public ResponseEntity disconnect(@PathVariable Long connectionId, @RequestParam Map query) {
        SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
        if (disconnect != null) {
            ACCESS_USER currentUser = getAccessUser();
            onDisconnect(Collections.singletonList(disconnect), currentUser, query);
        }
        return responseEntity(Collections.singletonMap("count", disconnect != null ? 1 : 0));
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect")
    public ResponseEntity disconnect0(Long connectionId, @RequestParam Map query) {
        if (connectionId != null) {
            SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
            if (disconnect != null) {
                ACCESS_USER currentUser = getAccessUser();
                onDisconnect(Collections.singletonList(disconnect), currentUser, query);
            }
            return responseEntity(Collections.singletonMap("count", disconnect != null ? 1 : 0));
        } else {
            ACCESS_USER currentUser = getAccessUser();
            if (currentUser != null) {
                List<SseEmitter<ACCESS_USER>> count = localConnectionService.disconnectByAccessToken(currentUser.getAccessToken());
                if (count.size() > 0) {
                    onDisconnect(count, currentUser, query);
                }
                return responseEntity(Collections.singletonMap("count", count.size()));
            } else {
                return responseEntity(Collections.singletonMap("count", 0));
            }
        }
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnectUser")
    public ResponseEntity disconnectUser(String userId, @RequestParam Map query) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        List<SseEmitter<ACCESS_USER>> disconnectList = localConnectionService.disconnectByUserId(userId);
        if (disconnectList.size() > 0) {
            onDisconnect(disconnectList, currentUser, query);
        }
        return responseEntity(Collections.singletonMap("count", disconnectList.size()));
    }

    @RequestMapping("/users")
    public ResponseEntity users(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                                String name) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        List<? extends AccessUser> list = localConnectionService.getUsers();
        String nameTrim = name != null ? name.trim().toLowerCase() : null;
        if (nameTrim != null && nameTrim.length() > 0) {
            list = list.stream()
                    .filter(e -> {
                        String eachName = e.getName();
                        if (eachName == null) {
                            return false;
                        }
                        return eachName.toLowerCase().contains(nameTrim);
                    })
                    .collect(Collectors.toList());
        }
        PageInfo<Object> pageInfo = PageInfo.of(list, pageNum, pageSize).map(e -> mapToUserVO((ACCESS_USER) e));
        return responseEntity(pageInfo);
    }

    @RequestMapping("/connections")
    public Object connections(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                              @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                              String name,
                              String clientId,
                              Long id) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }

        List<SseEmitter<? extends AccessUser>> list = (List) localConnectionService.getConnectionAll();
        String nameTrim = name != null ? name.trim().toLowerCase() : null;
        list = list.stream()
                .filter(e -> {
                    if (id != null) {
                        return e.getId() == id;
                    }
                    if (clientId != null && clientId.length() > 0) {
                        return clientId.equals(e.getClientId());
                    }
                    if (nameTrim == null || nameTrim.isEmpty()) {
                        return true;
                    }
                    AccessUser accessUser = e.getAccessUser();
                    if (accessUser == null) {
                        return false;
                    }
                    String eachName = accessUser.getName();
                    if (eachName == null) {
                        return false;
                    }
                    return eachName.toLowerCase().contains(nameTrim);
                })
                .sorted(Comparator.comparing((Function<SseEmitter<? extends AccessUser>, String>)
                                emitter -> Optional.ofNullable(emitter)
                                        .map(SseEmitter::getAccessUser)
                                        .map(AccessUser::getName)
                                        .orElse(""))
                        .thenComparingLong(SseEmitter::getId))
                .collect(Collectors.toList());
        PageInfo<Object> pageInfo = PageInfo.of(list, pageNum, pageSize)
                .map(e -> mapToConnectionVO((SseEmitter<ACCESS_USER>) e));
        return responseEntity(pageInfo);
    }

    protected ResponseEntity responseEntity(Object responseBody) {
        HttpHeaders headers = new HttpHeaders();
        settingResponseHeader(headers);
        return new ResponseEntity<>(wrapOkResponse(responseBody), headers, HttpStatus.OK);
    }

    protected void settingResponseHeader(HttpHeaders responseHeaders) {
        String sseServerIdHeaderName = this.sseServerIdHeaderName;
        if (sseServerIdHeaderName != null && sseServerIdHeaderName.length() > 0) {
            responseHeaders.set(sseServerIdHeaderName, getSseServerId());
        }
        responseHeaders.set("Sse-Server-Version", SseEmitter.VERSION);
    }

    protected String getSseServerId() {
        return WebUtil.getIPAddress(serverPort);
    }

    protected void disconnectClientIdMaxConnections(SseEmitter<ACCESS_USER> conncet, int clientIdMaxConnections) {
        Object userId = conncet.getUserId();
        String clientId = conncet.getClientId();

        List<SseEmitter<? extends AccessUser>> clientConnectionList = localConnectionService.getConnectionByUserId(userId).stream()
                .filter(e -> Objects.equals(e.getClientId(), clientId))
                .sorted(Comparator.comparing(SseEmitter::getId))
                .collect(Collectors.toList());

        if (clientConnectionList.size() > clientIdMaxConnections) {
            List<SseEmitter<? extends AccessUser>> sseEmitters =
                    clientConnectionList.subList(0, clientConnectionList.size() - clientIdMaxConnections);
            for (SseEmitter<? extends AccessUser> sseEmitter : sseEmitters) {
                sseEmitter.disconnect();
            }
        }
    }

    protected Object mapToUserVO(ACCESS_USER user) {
        return user;
    }

    protected Object mapToConnectionVO(SseEmitter<ACCESS_USER> emitter) {
        ConnectionVO vo = new ConnectionVO();
        vo.setId(emitter.getId());
        vo.setMessageCount(emitter.getCount());
        vo.setTimeout(emitter.getTimeout());
        vo.setChannel(emitter.getChannel());
        vo.setCreateTime(new Date(emitter.getCreateTime()));
        vo.setAccessTime(emitter.getAccessTime());

        vo.setLocationHref(emitter.getLocationHref());
        vo.setListeners(emitter.getListeners());
        vo.setRequestMessageCount(emitter.getRequestMessageCount());
        vo.setRequestUploadCount(emitter.getRequestUploadCount());
        long lastRequestTimestamp = emitter.getLastRequestTimestamp();
        if (lastRequestTimestamp > 0) {
            vo.setLastRequestTime(new Date(lastRequestTimestamp));
        }

        vo.setAccessToken(emitter.getAccessToken());
        vo.setAccessUserId(emitter.getUserId());
        vo.setAccessUser(emitter.getAccessUser());

        vo.setClientId(emitter.getClientId());
        vo.setClientVersion(emitter.getClientVersion());
        vo.setClientImportModuleTime(emitter.getClientImportModuleTime());
        vo.setClientInstanceId(emitter.getClientInstanceId());
        vo.setClientInstanceTime(emitter.getClientInstanceTime());

        vo.setRequestIp(emitter.getRequestIp());
        vo.setRequestDomain(emitter.getRequestDomain());
        vo.setUserAgent(emitter.getUserAgent());
        vo.setHttpHeaders(emitter.getHttpHeaders());
        vo.setHttpParameters(emitter.getHttpParameters());

        vo.setScreen(emitter.getScreen());
        vo.setJsHeapSizeLimit(emitter.getJsHeapSizeLimit());
        vo.setTotalJSHeapSize(emitter.getTotalJSHeapSize());
        vo.setUsedJSHeapSize(emitter.getUsedJSHeapSize());
        return vo;
    }

    protected SseEmitter.SseEventBuilder buildEvent(Map rawMessage) {
        Map message = new LinkedHashMap(rawMessage);
        SseEmitter.SseEventBuilder event = SseEmitter.event();
        Object id = message.remove("id");
        if (id != null) {
            event.id(id.toString());
        }
        Object name = message.remove("name");
        if (name != null) {
            event.name(name.toString());
        }
        Object comment = message.remove("comment");
        if (comment != null) {
            event.comment(comment.toString());
        }
        Object reconnectTime = message.remove("reconnectTime");
        if (reconnectTime != null) {
            event.reconnectTime(Long.parseLong(reconnectTime.toString()));
        }
        if (!message.isEmpty()) {
            event.data(message);
        }
        return event;
    }

    public boolean isBlank(CharSequence str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if ((!Character.isWhitespace(str.charAt(i)))) {
                return false;
            }
        }
        return true;
    }

    public static class ListenerReq {
        private List<String> listener;
        private Long connectionId;

        public boolean isInvalid() {
            return listener == null || listener.isEmpty() || connectionId == null;
        }

        public List<String> getListener() {
            return listener;
        }

        public Long getConnectionId() {
            return connectionId;
        }

        public void setConnectionId(Long connectionId) {
            this.connectionId = connectionId;
        }

        public void setListener(List<String> listener) {
            this.listener = listener;
        }
    }

    public static class ResponseWrap<T> implements Serializable {

        /**
         * 请求是否成功
         */
        private boolean success = true;
        /**
         * 成功或者失败的code错误码
         */
        private int code = 200;
        /**
         * 成功时返回的数据，失败时返回具体的异常信息
         */
        private T data;
        /**
         * 请求失败返回的提示信息，给前端进行页面展示的信息
         */
        private String message;
        /**
         * 请求失败返回的提示信息，排查用的信息
         */
        private String errorMessage;

        public ResponseWrap() {
        }

        public ResponseWrap(T data) {
            this.data = data;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

    public static class ConnectionVO {
        // connection
        private Long id;
        private Date createTime;
        private Long timeout;
        private Date accessTime;
        private Integer messageCount;
        private String channel;

        // request
        private Date lastRequestTime;
        private Integer requestMessageCount;
        private Integer requestUploadCount;
        private Set<String> listeners;
        private String locationHref;

        // user
        private Object accessUserId;
        private String accessToken;
        private AccessUser accessUser;

        // client
        private String clientId;
        private String clientVersion;
        private Long clientImportModuleTime;
        private String clientInstanceId;
        private Long clientInstanceTime;

        // http
        private String requestIp;
        private String requestDomain;
        private String userAgent;
        private Map<String, Object> httpParameters;
        private Map<String, String> httpHeaders;

        // browser
        private String screen;
        private Long totalJSHeapSize;
        private Long usedJSHeapSize;
        private Long jsHeapSizeLimit;

        public Long getClientImportModuleTime() {
            return clientImportModuleTime;
        }

        public void setClientImportModuleTime(Long clientImportModuleTime) {
            this.clientImportModuleTime = clientImportModuleTime;
        }

        public String getClientInstanceId() {
            return clientInstanceId;
        }

        public void setClientInstanceId(String clientInstanceId) {
            this.clientInstanceId = clientInstanceId;
        }

        public Long getClientInstanceTime() {
            return clientInstanceTime;
        }

        public void setClientInstanceTime(Long clientInstanceTime) {
            this.clientInstanceTime = clientInstanceTime;
        }

        public String getLocationHref() {
            return locationHref;
        }

        public void setLocationHref(String locationHref) {
            this.locationHref = locationHref;
        }

        public String getScreen() {
            return screen;
        }

        public void setScreen(String screen) {
            this.screen = screen;
        }

        public Long getTotalJSHeapSize() {
            return totalJSHeapSize;
        }

        public void setTotalJSHeapSize(Long totalJSHeapSize) {
            this.totalJSHeapSize = totalJSHeapSize;
        }

        public Long getUsedJSHeapSize() {
            return usedJSHeapSize;
        }

        public void setUsedJSHeapSize(Long usedJSHeapSize) {
            this.usedJSHeapSize = usedJSHeapSize;
        }

        public Long getJsHeapSizeLimit() {
            return jsHeapSizeLimit;
        }

        public void setJsHeapSizeLimit(Long jsHeapSizeLimit) {
            this.jsHeapSizeLimit = jsHeapSizeLimit;
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

        public Map<String, Object> getHttpParameters() {
            return httpParameters;
        }

        public void setHttpParameters(Map<String, Object> httpParameters) {
            this.httpParameters = httpParameters;
        }

        public Map<String, String> getHttpHeaders() {
            return httpHeaders;
        }

        public void setHttpHeaders(Map<String, String> httpHeaders) {
            this.httpHeaders = httpHeaders;
        }

        public Date getAccessTime() {
            return accessTime;
        }

        public void setAccessTime(Date accessTime) {
            this.accessTime = accessTime;
        }

        public Date getLastRequestTime() {
            return lastRequestTime;
        }

        public void setLastRequestTime(Date lastRequestTime) {
            this.lastRequestTime = lastRequestTime;
        }

        public Integer getRequestMessageCount() {
            return requestMessageCount;
        }

        public void setRequestMessageCount(Integer requestMessageCount) {
            this.requestMessageCount = requestMessageCount;
        }

        public Integer getRequestUploadCount() {
            return requestUploadCount;
        }

        public void setRequestUploadCount(Integer requestUploadCount) {
            this.requestUploadCount = requestUploadCount;
        }

        public Set<String> getListeners() {
            return listeners;
        }

        public void setListeners(Set<String> listeners) {
            this.listeners = listeners;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientVersion() {
            return clientVersion;
        }

        public void setClientVersion(String clientVersion) {
            this.clientVersion = clientVersion;
        }

        public Date getCreateTime() {
            return createTime;
        }

        public void setCreateTime(Date createTime) {
            this.createTime = createTime;
        }

        public Integer getMessageCount() {
            return messageCount;
        }

        public void setMessageCount(Integer messageCount) {
            this.messageCount = messageCount;
        }

        public Long getTimeout() {
            return timeout;
        }

        public void setTimeout(Long timeout) {
            this.timeout = timeout;
        }

        public Date getExpireTime() {
            if (timeout != null && timeout > 0) {
                return new Date(createTime.getTime() + timeout);
            }
            return null;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getAccessUserName() {
            return accessUser != null ? accessUser.getName() : null;
        }

        public Object getAccessUserId() {
            return accessUserId;
        }

        public void setAccessUserId(Object accessUserId) {
            this.accessUserId = accessUserId;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public AccessUser getAccessUser() {
            return accessUser;
        }

        public void setAccessUser(AccessUser accessUser) {
            this.accessUser = accessUser;
        }
    }

}

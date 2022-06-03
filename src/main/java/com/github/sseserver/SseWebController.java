package com.github.sseserver;

import com.github.sseserver.util.PageInfo;
import com.github.sseserver.util.WebUtil;
import org.springframework.beans.factory.annotation.Autowired;
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
    protected LocalConnectionService localConnectionService;

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

    }

    protected void onDisconnect(List<SseEmitter<ACCESS_USER>> disconnectList, ACCESS_USER accessUser, Map query) {

    }

    protected ResponseEntity buildIfConnectVerifyErrorResponse(ACCESS_USER accessUser,
                                                               Map query, Map body,
                                                               Long keepaliveTime, HttpServletRequest request) {
        if (accessUser == null) {
            return buildUnauthorizedResponse();
        }
        return null;
    }

    protected ResponseEntity buildUnauthorizedResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setConnection("close");
        return new ResponseEntity<>("", headers, HttpStatus.UNAUTHORIZED);
    }

    /**
     * 创建连接
     */
    @RequestMapping("/connect")
    public Object connect(@RequestParam Map query, @RequestBody(required = false) Map body,
                          Long keepaliveTime, HttpServletRequest request) {
        // args
        Map<String, Object> attributeMap = new LinkedHashMap<>(query);
        if (body != null) {
            attributeMap.putAll(body);
        }

        // Verify login
        ACCESS_USER accessUser = getAccessUser();
        ResponseEntity responseEntity = buildIfConnectVerifyErrorResponse(accessUser, query, body, keepaliveTime, request);
        if (responseEntity != null) {
            return responseEntity;
        }

        // build connect
        SseEmitter<ACCESS_USER> emitter = localConnectionService.connect(accessUser, keepaliveTime, attributeMap);

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

        // callback
        onConnect(emitter, attributeMap);
        return emitter;
    }

    /**
     * 收到前端的消息
     *
     * @return http原生响应
     */
    @RequestMapping("/message/{path}")
    public ResponseEntity message(@PathVariable String path, Long connectionId, @RequestParam Map query, @RequestBody(required = false) Map body) {
        ACCESS_USER accessUser = getAccessUser();
        if (accessUser == null) {
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
        return ResponseEntity.ok(wrapOkResponse(onMessage(path, emitter, message)));
    }

    /**
     * 收到前端上传的数据
     *
     * @return http原生响应
     */
    @RequestMapping("/upload/{path}")
    public ResponseEntity upload(@PathVariable String path, HttpServletRequest request, Long connectionId, @RequestParam Map query, @RequestBody(required = false) Map body) throws IOException, ServletException {
        ACCESS_USER accessUser = getAccessUser();
        if (accessUser == null) {
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
        return ResponseEntity.ok(wrapOkResponse(onUpload(path, emitter, message, request.getParts())));
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect/{connectionId}")
    public ResponseEntity disconnect(@PathVariable Long connectionId, @RequestParam Map query) {
        SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
        if (disconnect != null) {
            ACCESS_USER accessUser = getAccessUser();
            onDisconnect(Collections.singletonList(disconnect), accessUser, query);
        }
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", disconnect != null ? 1 : 0)));
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect")
    public ResponseEntity disconnect0(Long connectionId, @RequestParam Map query) {
        if (connectionId != null) {
            SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
            if (disconnect != null) {
                ACCESS_USER accessUser = getAccessUser();
                onDisconnect(Collections.singletonList(disconnect), accessUser, query);
            }
            return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", disconnect != null ? 1 : 0)));
        } else {
            ACCESS_USER accessUser = getAccessUser();
            if (accessUser != null) {
                List<SseEmitter<ACCESS_USER>> count = localConnectionService.disconnectByAccessToken(accessUser.getAccessToken());
                if (count.size() > 0) {
                    onDisconnect(count, accessUser, query);
                }
                return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", count.size())));
            } else {
                return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", 0)));
            }
        }
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnectUser")
    public ResponseEntity disconnectUser(String userId, @RequestParam Map query) {
        ACCESS_USER accessUser = getAccessUser();
        if (accessUser == null) {
            return buildUnauthorizedResponse();
        }
        List<SseEmitter<ACCESS_USER>> disconnectList = localConnectionService.disconnectByUserId(userId);
        if (disconnectList.size() > 0) {
            onDisconnect(disconnectList, accessUser, query);
        }
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", disconnectList.size())));
    }

    @RequestMapping("/users")
    public Object users(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                        @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                        String name) {
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
        return ResponseEntity.ok(wrapOkResponse(pageInfo));
    }

    @RequestMapping("/connections")
    public Object connections(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                              @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                              String name,
                              String clientId,
                              Long id) {
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
        return ResponseEntity.ok(wrapOkResponse(PageInfo.of(list, pageNum, pageSize).map(e -> mapToConnectionVO((SseEmitter<ACCESS_USER>) e))));
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

        vo.setAccessToken(emitter.getAccessToken());
        vo.setAccessUserId(emitter.getUserId());
        vo.setAccessUser(emitter.getAccessUser());

        vo.setClientId(emitter.getClientId());
        vo.setClientVersion(emitter.getClientVersion());

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

        // user
        private Object accessUserId;
        private String accessToken;
        private AccessUser accessUser;

        // client
        private String clientId;
        private String clientVersion;

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

package com.github.sseserver.local;

import com.github.sseserver.AccessToken;
import com.github.sseserver.AccessUser;
import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.remote.ConnectionDTO;
import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.PageInfo;
import com.github.sseserver.util.PlatformDependentUtil;
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
import org.springframework.web.context.request.async.DeferredResult;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;
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
public class SseWebController<ACCESS_USER> {
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
        emitter.setChannel(channel == null || channel.isEmpty() ? null : channel);
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
        if (emitter == null) {
            return responseEntity(Collections.singletonMap("error", "connectionId not exist"));
        } else {
            emitter.addListener(req.getListener());
            return responseEntity(Collections.singletonMap("listener", emitter.getListeners()));
        }
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
        if (emitter == null) {
            return responseEntity(Collections.singletonMap("error", "connectionId not exist"));
        } else {
            emitter.removeListener(req.getListener());
            return responseEntity(Collections.singletonMap("listener", emitter.getListeners()));
        }
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
    @RequestMapping("/disconnect")
    public Object disconnect(Long connectionId, @RequestParam Map query,
                             Boolean cluster,
                             @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        if (connectionId != null) {
            return disconnectConnection(connectionId, query, cluster, timeout);
        } else {
            ACCESS_USER currentUser = getAccessUser();
            if (currentUser instanceof AccessToken) {
                String accessToken = ((AccessToken) currentUser).getAccessToken();
                return disconnectAccessToken(accessToken, query, cluster, timeout, currentUser);
            } else if (currentUser instanceof AccessUser) {
                Serializable id = ((AccessUser) currentUser).getId();
                return disconnectUser(Objects.toString(id, null), query, cluster, timeout);
            } else {
                return responseEntity(buildDisconnectResult(0, false));
            }
        }
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect/{connectionId}")
    public Object disconnectConnection(@PathVariable Long connectionId, @RequestParam Map query,
                                       Boolean cluster,
                                       @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
        int localCount = disconnect != null ? 1 : 0;
        if (disconnect != null) {
            ACCESS_USER currentUser = getAccessUser();
            onDisconnect(Collections.singletonList(disconnect), currentUser, query);
        }
        if (cluster == null || cluster) {
            cluster = localConnectionService.isEnableCluster();
        }
        if (cluster) {
            DeferredResult<ResponseEntity> result = new DeferredResult<>(timeout, responseEntity(buildDisconnectResult(localCount, true)));
            localConnectionService.getCluster().disconnectByConnectionId(connectionId)
                    .whenComplete((remoteCount, throwable) -> {
                        if (throwable != null) {
                            result.setErrorResult(throwable);
                        } else {
                            int count = remoteCount + localCount;
                            result.setResult(responseEntity(buildDisconnectResult(count, false)));
                        }
                    });
            return result;
        } else {
            return responseEntity(buildDisconnectResult(localCount, false));
        }
    }

    /**
     * 关闭连接
     */
    public Object disconnectAccessToken(String accessToken, Map query,
                                        Boolean cluster,
                                        Long timeout,
                                        ACCESS_USER currentUser) {
        List<SseEmitter<ACCESS_USER>> disconnectList = localConnectionService.disconnectByAccessToken(accessToken);
        if (disconnectList.size() > 0) {
            onDisconnect(disconnectList, currentUser, query);
        }
        if (cluster == null || cluster) {
            cluster = localConnectionService.isEnableCluster();
        }
        if (cluster) {
            DeferredResult<ResponseEntity> result = new DeferredResult<>(timeout, responseEntity(buildDisconnectResult(disconnectList.size(), true)));
            localConnectionService.getCluster().disconnectByAccessToken(accessToken).whenComplete((remoteCount, throwable) -> {
                if (throwable != null) {
                    result.setErrorResult(throwable);
                } else {
                    int count = remoteCount + disconnectList.size();
                    result.setResult(responseEntity(buildDisconnectResult(count, false)));
                }
            });
            return result;
        } else {
            return responseEntity(buildDisconnectResult(disconnectList.size(), false));
        }
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnectUser")
    public Object disconnectUser(String userId, @RequestParam Map query,
                                 Boolean cluster,
                                 @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        List<SseEmitter<ACCESS_USER>> disconnectList = localConnectionService.disconnectByUserId(userId);
        if (disconnectList.size() > 0) {
            onDisconnect(disconnectList, currentUser, query);
        }
        if (cluster == null || cluster) {
            cluster = localConnectionService.isEnableCluster();
        }
        if (cluster) {
            DeferredResult<ResponseEntity> result = new DeferredResult<>(timeout, () -> responseEntity(buildDisconnectResult(disconnectList.size(), true)));
            localConnectionService.getCluster().disconnectByUserId(userId).whenComplete((remoteCount, throwable) -> {
                if (throwable != null) {
                    result.setErrorResult(throwable);
                } else {
                    int count = remoteCount + disconnectList.size();
                    result.setResult(responseEntity(buildDisconnectResult(count, false)));
                }
            });
            return result;
        } else {
            return responseEntity(buildDisconnectResult(disconnectList.size(), false));
        }
    }

    @RequestMapping("/repositoryMessages")
    public Object repositoryMessages(RepositoryMessagesReq req) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        Integer pageNum = req.getPageNum();
        Integer pageSize = req.getPageSize();
        Long timeout = req.getTimeout();
        Boolean cluster = req.getCluster();
        if (cluster == null || cluster) {
            cluster = localConnectionService.isEnableCluster();
        }

        CompletableFuture<List<Message>> future;
        if (req.isEmptyCondition()) {
            if (cluster) {
                future = localConnectionService.getClusterMessageRepository().listAsync();
            } else {
                future = CompletableFuture.completedFuture(localConnectionService.getLocalMessageRepository().list());
            }
        } else {
            if (cluster) {
                future = localConnectionService.getClusterMessageRepository().selectAsync(req);
            } else {
                future = CompletableFuture.completedFuture(localConnectionService.getLocalMessageRepository().select(req));
            }
        }

        DeferredResult<ResponseEntity> result = new DeferredResult<>(timeout, () -> responseEntity(PageInfo.timeout()));
        future.whenComplete((messages, throwable) -> {
            if (throwable != null) {
                result.setErrorResult(throwable);
            } else {
                List<Object> filterSortList = messages.stream()
                        .filter(Objects::nonNull)
                        .map(this::mapToMessageVO)
                        .collect(Collectors.toList());
                result.setResult(responseEntity(PageInfo.of(filterSortList, pageNum, pageSize)));
            }
        });
        return result;
    }

    @RequestMapping("/users")
    public Object users(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                        @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                        String name,
                        Boolean cluster,
                        @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        if (cluster == null || cluster) {
            cluster = localConnectionService.isEnableCluster();
        }
        CompletableFuture<List<ACCESS_USER>> future;
        if (cluster) {
            future = localConnectionService.getCluster().getUsersAsync(SseServerProperties.AutoType.CLASS_NOT_FOUND_USE_MAP);
        } else {
            future = CompletableFuture.completedFuture(localConnectionService.getUsers());
        }

        DeferredResult<ResponseEntity> result = new DeferredResult<>(timeout, () -> responseEntity(PageInfo.timeout()));
        future.whenComplete((users, throwable) -> {
            if (throwable != null) {
                result.setErrorResult(throwable);
            } else {
                String nameTrim = name != null ? name.trim().toLowerCase() : null;
                List<Object> filterSortList = users.stream()
                        .filter(Objects::nonNull)
                        .filter(e -> {
                            String eachName = null;
                            if (e instanceof AccessUser) {
                                eachName = ((AccessUser) e).getName();
                            } else if (e instanceof Map) {
                                eachName = Objects.toString(((Map<?, ?>) e).get("name"), null);
                            }
                            if (nameTrim != null && nameTrim.length() > 0) {
                                if (eachName != null && eachName.length() > 0) {
                                    return eachName.toLowerCase().contains(nameTrim);
                                } else {
                                    return false;
                                }
                            }
                            return true;
                        })
                        .map(this::mapToUserVO)
                        .collect(Collectors.toList());
                result.setResult(responseEntity(PageInfo.of(filterSortList, pageNum, pageSize)));
            }
        });
        return result;
    }

    @RequestMapping("/connections")
    public Object connections(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                              @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                              String name,
                              String clientId,
                              Long id,
                              Boolean cluster,
                              @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        ACCESS_USER currentUser = getAccessUser();
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        if (cluster == null || cluster) {
            cluster = localConnectionService.isEnableCluster();
        }
        CompletableFuture<List<ConnectionDTO<ACCESS_USER>>> future;
        if (cluster) {
            future = localConnectionService.getCluster().getConnectionDTOAllAsync(SseServerProperties.AutoType.CLASS_NOT_FOUND_USE_MAP);
        } else {
            future = CompletableFuture.completedFuture(localConnectionService.getConnectionDTOAll());
        }

        DeferredResult<ResponseEntity> result = new DeferredResult<>(timeout, () -> responseEntity(PageInfo.timeout()));
        future.whenComplete((connectionDTOAll, throwable) -> {
            if (throwable != null) {
                result.setErrorResult(throwable);
            } else {
                String nameTrim = name != null ? name.trim().toLowerCase() : null;
                List<Object> filterSortList = connectionDTOAll.stream()
                        .filter(Objects::nonNull)
                        .filter(e -> {
                            if (id != null) {
                                return id.equals(e.getId());
                            }
                            if (clientId != null && clientId.length() > 0) {
                                return clientId.equals(e.getClientId());
                            }
                            if (nameTrim != null && nameTrim.length() > 0) {
                                String eachName = e.getAccessUserName();
                                if (eachName != null && eachName.length() > 0) {
                                    return eachName.toLowerCase().contains(nameTrim);
                                } else {
                                    return false;
                                }
                            }
                            return true;
                        })
                        .sorted(Comparator.comparing((Function<ConnectionDTO, String>)
                                        e -> Objects.toString(e.getAccessUserName(), ""))
                                .thenComparing(ConnectionDTO::getCreateTime))
                        .map(this::mapToConnectionVO)
                        .collect(Collectors.toList());
                result.setResult(responseEntity(PageInfo.of(filterSortList, pageNum, pageSize)));
            }
        });
        return result;
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
        responseHeaders.set("Sse-Server-Version", PlatformDependentUtil.SSE_SERVER_VERSION);
    }

    protected String getSseServerId() {
        return WebUtil.getIPAddress(serverPort);
    }

    protected void disconnectClientIdMaxConnections(SseEmitter<ACCESS_USER> conncet, int clientIdMaxConnections) {
        Serializable userId = conncet.getUserId();
        String clientId = conncet.getClientId();

        List<SseEmitter> clientConnectionList = localConnectionService.getConnectionByUserId(userId).stream()
                .filter(e -> Objects.equals(e.getClientId(), clientId))
                .sorted(Comparator.comparingLong((ToLongFunction<SseEmitter>)
                                SseEmitter::getCreateTime)
                        .thenComparing(SseEmitter::getId))
                .collect(Collectors.toList());

        if (clientConnectionList.size() > clientIdMaxConnections) {
            List<SseEmitter> sseEmitters =
                    clientConnectionList.subList(0, clientConnectionList.size() - clientIdMaxConnections);
            for (SseEmitter sseEmitter : sseEmitters) {
                sseEmitter.disconnect();
            }
        }
    }

    protected Map<String, Object> buildDisconnectResult(Integer count, boolean timeout) {
        if (timeout) {
            Map<String, Object> map = new LinkedHashMap<>(2);
            map.put("count", count);
            map.put("timeout", true);
            return map;
        } else {
            return Collections.singletonMap("count", count);
        }
    }

    protected Object mapToMessageVO(Message message) {
        return message;
    }

    protected Object mapToUserVO(ACCESS_USER user) {
        return user;
    }

    protected Object mapToConnectionVO(ConnectionDTO<ACCESS_USER> connectionDTO) {
        return connectionDTO;
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

        public void setListener(List<String> listener) {
            this.listener = listener;
        }

        public Long getConnectionId() {
            return connectionId;
        }

        public void setConnectionId(Long connectionId) {
            this.connectionId = connectionId;
        }
    }

    public static class RepositoryMessagesReq implements MessageRepository.Query {
        private Integer pageNum = 1;
        private Integer pageSize = 100;
        private Long timeout = 5000L;
        private Boolean cluster;

        private String tenantId;
        private String channel;
        private String accessToken;
        private String userId;
        private Set<String> listeners;

        public boolean isEmptyCondition() {
            return (tenantId == null || tenantId.isEmpty())
                    && (channel == null || channel.isEmpty())
                    && (accessToken == null || accessToken.isEmpty())
                    && (userId == null || userId.isEmpty())
                    && (listeners == null || listeners.isEmpty());
        }

        public Integer getPageNum() {
            return pageNum;
        }

        public void setPageNum(Integer pageNum) {
            this.pageNum = pageNum;
        }

        public Integer getPageSize() {
            return pageSize;
        }

        public void setPageSize(Integer pageSize) {
            this.pageSize = pageSize;
        }

        public Long getTimeout() {
            return timeout;
        }

        public void setTimeout(Long timeout) {
            this.timeout = timeout;
        }

        public Boolean getCluster() {
            return cluster;
        }

        public void setCluster(Boolean cluster) {
            this.cluster = cluster;
        }

        @Override
        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        @Override
        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        @Override
        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        @Override
        public Set<String> getListeners() {
            return listeners;
        }

        public void setListeners(Set<String> listeners) {
            this.listeners = listeners;
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

}

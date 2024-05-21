package com.github.sseserver.local;

import com.github.sseserver.AccessUser;
import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.remote.ClusterConnectionService;
import com.github.sseserver.remote.ConnectionByUserIdDTO;
import com.github.sseserver.remote.ConnectionDTO;
import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.PageInfo;
import com.github.sseserver.util.PlatformDependentUtil;
import com.github.sseserver.util.WebUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * 消息事件推送 (分布式)
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
    public static final String API_CONNECT_STREAM = "/connect";

    public static final String API_ADD_LISTENER_DO = "/connect/addListener.do";
    public static final String API_REMOVE_LISTENER_DO = "/connect/removeListener.do";
    public static final String API_MESSAGE_DO = "/connect/message/{path}.do";
    public static final String API_UPLOAD_DO = "/connect/upload/{path}.do";
    public static final String API_DISCONNECT_DO = "/connect/disconnect.do";

    public static final String API_REPOSITORY_MESSAGES_JSON = "/connect/repositoryMessages.json";
    public static final String API_USER_JSON = "/connect/users.json";

    /**
     * @deprecated v1.2.8
     */
    @Deprecated
    public static final String API_CONNECTIONS_JSON_V1 = "/connections";
    public static final String API_CONNECTIONS_JSON = "/connect/connections.json";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    @Autowired(required = false)
    protected HttpServletRequest request;
    protected LocalConnectionService localConnectionService;
    private final ClusterBatchDisconnectRunnable batchDisconnectRunnable = new ClusterBatchDisconnectRunnable(() -> localConnectionService != null ? localConnectionService.getCluster() : null);
    @Value("${server.port:8080}")
    private Integer serverPort;
    private String sseServerIdHeaderName = "Sse-Server-Id";
    private Integer clientIdMaxConnections = 3;
    private Long keepaliveTime;
    private boolean enableGetJson = false;

    protected boolean isGetJson(String api) {
        return api != null && api.endsWith(".json");
    }

    public boolean isEnableGetJson() {
        return enableGetJson;
    }

    public void setEnableGetJson(boolean enableGetJson) {
        this.enableGetJson = enableGetJson;
    }

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
        Resource body = readSseJs();
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    protected Resource readSseJs() {
        InputStream stream = SseWebController.class.getResourceAsStream("/sse.js");
        return new InputStreamResource(stream);
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
     * @param api 是哪个接口调用的getAccessUser
     * @return 使用者自己系统的用户
     * @see #API_CONNECT_STREAM
     * @see #API_ADD_LISTENER_DO
     * @see #API_REMOVE_LISTENER_DO
     * @see #API_MESSAGE_DO
     * @see #API_UPLOAD_DO
     * @see #API_DISCONNECT_DO
     * @see #API_REPOSITORY_MESSAGES_JSON
     * @see #API_USER_JSON
     * @see #API_CONNECTIONS_JSON
     */
    protected ACCESS_USER getAccessUser(String api) {
        return null;
    }

    /**
     * 当前登录用户是否有权限访问这个接口
     *
     * @param currentUser 当前登录用户
     * @param api         是哪个接口调用的getAccessUser
     * @return true=有权限，false=无权限，会返回 {@link #buildPermissionRejectResponse(Object, String)}
     * @see #API_CONNECT_STREAM
     * @see #API_ADD_LISTENER_DO
     * @see #API_REMOVE_LISTENER_DO
     * @see #API_MESSAGE_DO
     * @see #API_UPLOAD_DO
     * @see #API_DISCONNECT_DO
     * @see #API_REPOSITORY_MESSAGES_JSON
     * @see #API_USER_JSON
     * @see #API_CONNECTIONS_JSON
     */
    protected boolean hasPermission(ACCESS_USER currentUser, String api) {
        return isEnableGetJson() || !isGetJson(api);
    }

    protected ResponseEntity buildIfPermissionErrorResponse(ACCESS_USER currentUser, String api) {
        if (hasPermission(currentUser, api)) {
            return null;
        } else {
            return buildPermissionRejectResponse(currentUser, api);
        }
    }

    protected ResponseEntity buildPermissionRejectResponse(ACCESS_USER currentUser, String api) {
        HttpHeaders headers = new HttpHeaders();
        headers.setConnection("close");
        settingResponseHeader(headers);
        Map<String, String> body = Collections.singletonMap("error",
                "get json api default is disabled. if you need use, place use SseWebController#setEnableGetJson(true);");
        return new ResponseEntity<>(body, headers, HttpStatus.UNAUTHORIZED);
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
        disconnectClientIdMaxConnections(conncet, getClientIdMaxConnections());
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
    @RequestMapping(value = API_CONNECT_STREAM, method = {RequestMethod.GET, RequestMethod.POST})
    public Object connect(@RequestParam Map query, @RequestBody(required = false) Map body,
                          Long keepaliveTime) {
        // args
        Map<String, Object> attributeMap = new LinkedHashMap<>(query);
        if (body != null) {
            attributeMap.putAll(body);
        }
        Long choseKeepaliveTime = choseKeepaliveTime(keepaliveTime, getKeepaliveTime());

        // Verify 1 login
        ACCESS_USER currentUser = getAccessUser(API_CONNECT_STREAM);
        ResponseEntity loginVerifyErrorResponse = buildIfLoginVerifyErrorResponse(currentUser, query, body, choseKeepaliveTime);
        if (loginVerifyErrorResponse != null) {
            return loginVerifyErrorResponse;
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_CONNECT_STREAM);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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
        emitter.setRequestIp(getRequestIpAddr(request));
        emitter.setRequestDomain(getRequestDomain(request));
        emitter.setHttpCookies(request.getCookies());
        emitter.getHttpParameters().putAll(attributeMap);
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            emitter.getHttpHeaders().put(name, request.getHeader(name));
        }

        // Verify 2 connect
        loginVerifyErrorResponse = buildIfConnectVerifyErrorResponse(emitter);
        if (loginVerifyErrorResponse != null) {
            return loginVerifyErrorResponse;
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
    @PostMapping(API_ADD_LISTENER_DO)
    public ResponseEntity addListener(@RequestBody ListenerReq req) {
        if (req == null || req.isInvalid()) {
            return responseEntity(Collections.singletonMap("listener", null));
        }
        ACCESS_USER currentUser = getAccessUser(API_ADD_LISTENER_DO);
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_ADD_LISTENER_DO);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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
    @PostMapping(API_REMOVE_LISTENER_DO)
    public ResponseEntity removeListener(@RequestBody ListenerReq req) {
        if (req == null || req.isInvalid()) {
            return responseEntity(Collections.singletonMap("listener", null));
        }
        ACCESS_USER currentUser = getAccessUser(API_REMOVE_LISTENER_DO);
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_REMOVE_LISTENER_DO);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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
    @PostMapping(API_MESSAGE_DO)
    public ResponseEntity message(@PathVariable String path, Long connectionId, @RequestParam Map query, @RequestBody(required = false) Map body) {
        ACCESS_USER currentUser = getAccessUser(API_MESSAGE_DO);
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_MESSAGE_DO);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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
    @PostMapping(API_UPLOAD_DO)
    public ResponseEntity upload(@PathVariable String path, HttpServletRequest request, Long connectionId, @RequestParam Map query, @RequestBody(required = false) Map body) throws IOException, ServletException {
        ACCESS_USER currentUser = getAccessUser(API_UPLOAD_DO);
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_UPLOAD_DO);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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
    @PostMapping(API_DISCONNECT_DO)
    public Object disconnect(Long connectionId, @RequestParam Map query,
                             Boolean cluster,
                             @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        if (connectionId == null) {
            return responseEntity(buildDisconnectResult(0, false));
        }
        SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
        int localCount = disconnect != null ? 1 : 0;
        if (disconnect != null) {
            ACCESS_USER currentUser = getAccessUser(API_DISCONNECT_DO);
            onDisconnect(Collections.singletonList(disconnect), currentUser, query);
        }
        if (cluster == null || cluster) {
            cluster = localConnectionService.isEnableCluster();
        }
        if (cluster && localCount == 0) {
            DeferredResult<ResponseEntity> result = new DeferredResult<>(timeout, responseEntity(buildDisconnectResult(localCount, true)));
            localConnectionService.getCluster().disconnectByConnectionId(connectionId)
                    .whenComplete((remoteCount, throwable) -> {
                        if (throwable != null) {
                            logger.warn("disconnectConnection exception = {}", throwable, throwable);
                            result.setResult(responseEntity(buildDisconnectResult(0, false)));
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

    @GetMapping(API_REPOSITORY_MESSAGES_JSON)
    public Object repositoryMessages(RepositoryMessagesReq req) {
        ACCESS_USER currentUser = getAccessUser(API_REPOSITORY_MESSAGES_JSON);
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_REPOSITORY_MESSAGES_JSON);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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

    @GetMapping(API_USER_JSON)
    public Object users(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                        @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                        String name,
                        Boolean cluster,
                        @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        ACCESS_USER currentUser = getAccessUser(API_USER_JSON);
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_USER_JSON);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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

    /**
     * @deprecated v1.2.8
     */
    @Deprecated
    @GetMapping(API_CONNECTIONS_JSON_V1)
    public Object connectionsV1(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                                String name,
                                String clientId,
                                Long id,
                                Boolean cluster,
                                @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        return connections(pageNum, pageSize, name, clientId, id, cluster, timeout);
    }

    @GetMapping(API_CONNECTIONS_JSON)
    public Object connections(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                              @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                              String name,
                              String clientId,
                              Long id,
                              Boolean cluster,
                              @RequestParam(required = false, defaultValue = "5000") Long timeout) {
        ACCESS_USER currentUser = getAccessUser(API_CONNECTIONS_JSON);
        if (currentUser == null) {
            return buildUnauthorizedResponse();
        }
        ResponseEntity permissionErrorResponse = buildIfPermissionErrorResponse(currentUser, API_CONNECTIONS_JSON);
        if (permissionErrorResponse != null) {
            return permissionErrorResponse;
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

    protected void disconnectClientIdMaxConnections(SseEmitter<ACCESS_USER> conncet, Integer clientIdMaxConnections) {
        if (clientIdMaxConnections == null) {
            return;
        }
        if (localConnectionService.isEnableCluster()) {
            disconnectClientIdMaxConnectionsCluster(conncet, clientIdMaxConnections);
        } else {
            disconnectClientIdMaxConnectionsLocal(conncet, clientIdMaxConnections);
        }
    }

    protected List<SseEmitter> disconnectClientIdMaxConnectionsLocal(SseEmitter<ACCESS_USER> conncet, int clientIdMaxConnections) {
        Serializable userId = conncet.getUserId();
        String clientId = conncet.getClientId();
        String requestDomain = conncet.getRequestDomain();
        String tenantId = Objects.toString(conncet.getTenantId(), "");

        List<SseEmitter> clientConnectionList = localConnectionService.getConnectionByUserId(userId).stream()
                .filter(e -> Objects.equals(e.getClientId(), clientId))
                .filter(e -> Objects.equals(e.getRequestDomain(), requestDomain))
                .filter(e -> Objects.equals(Objects.toString(e.getTenantId(), ""), tenantId))
                .sorted(Comparator.comparingLong((ToLongFunction<SseEmitter>)
                                SseEmitter::getCreateTime)
                        .thenComparing(SseEmitter::getId))
                .collect(Collectors.toList());

        if (clientConnectionList.size() > clientIdMaxConnections) {
            List<SseEmitter> disconnectList =
                    clientConnectionList.subList(0, clientConnectionList.size() - clientIdMaxConnections);
            for (SseEmitter sseEmitter : disconnectList) {
                sseEmitter.disconnect();
            }
            return disconnectList;
        } else {
            return Collections.emptyList();
        }
    }

    protected java.util.concurrent.CompletableFuture<List<ConnectionByUserIdDTO>> disconnectClientIdMaxConnectionsCluster(SseEmitter<ACCESS_USER> conncet, int clientIdMaxConnections) {
        Serializable userId = conncet.getUserId();
        String clientId = conncet.getClientId();
        String requestDomain = conncet.getRequestDomain();
        String tenantId = Objects.toString(conncet.getTenantId(), "");

        ClusterConnectionService cluster = localConnectionService.getCluster();
        return cluster.getConnectionDTOByUserIdAsync(userId).thenApply(connections -> {
            List<ConnectionByUserIdDTO> clientConnectionList = connections.stream()
                    .filter(e -> Objects.equals(e.getClientId(), clientId))
                    .filter(e -> Objects.equals(e.getRequestDomain(), requestDomain))
                    .filter(e -> Objects.equals(Objects.toString(e.getAccessTenantId(), ""), tenantId))
                    .sorted(Comparator.comparing(ConnectionByUserIdDTO::getCreateTime)
                            .thenComparing(ConnectionByUserIdDTO::getId))
                    .collect(Collectors.toList());

            if (clientConnectionList.size() > clientIdMaxConnections) {
                List<ConnectionByUserIdDTO> disconnectList =
                        clientConnectionList.subList(0, clientConnectionList.size() - clientIdMaxConnections);
                if (!disconnectList.isEmpty()) {
                    List<Long> disconnectIdList = disconnectList.stream().map(ConnectionByUserIdDTO::getId).collect(Collectors.toList());
                    batchDisconnectRunnable.addAll(disconnectIdList);
                    localConnectionService.getScheduled().schedule(batchDisconnectRunnable, 1000, TimeUnit.MILLISECONDS);
                }
                return disconnectList;
            } else {
                return Collections.emptyList();
            }
        });
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

    protected String getRequestIpAddr(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果是多级代理，那么取第一个ip为客户ip
        if (ip != null) {
            ip = ip.split(",", 2)[0].trim();
        }
        return ip;
    }

    protected String getRequestDomain(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        StringBuffer sb = url.delete(url.length() - request.getRequestURI().length(), url.length());

        if (sb.toString().startsWith("http://localhost")) {
            String host = request.getHeader("host");
            if (host != null && !host.isEmpty()) {
                sb = new StringBuffer("http://" + host);
            }
        }
        return WebUtil.rewriteHttpToHttpsIfSecure(sb.toString(), request.isSecure());
    }

    private static class ClusterBatchDisconnectRunnable implements Runnable {
        private final Collection<Long> batchDisconnectIdList = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Supplier<ClusterConnectionService> serviceSupplier;

        private ClusterBatchDisconnectRunnable(Supplier<ClusterConnectionService> serviceSupplier) {
            this.serviceSupplier = serviceSupplier;
        }

        void addAll(Collection<Long> disconnectIdList) {
            synchronized (batchDisconnectIdList) {
                batchDisconnectIdList.addAll(disconnectIdList);
            }
        }

        @Override
        public void run() {
            if (batchDisconnectIdList.isEmpty()) {
                return;
            }

            List<Long> idList;
            synchronized (batchDisconnectIdList) {
                if (batchDisconnectIdList.isEmpty()) {
                    return;
                }
                idList = new ArrayList<>(batchDisconnectIdList);
                batchDisconnectIdList.clear();
            }

            ClusterConnectionService service = serviceSupplier.get();
            if (service != null) {
                service.disconnectByConnectionIds(idList);
            }
        }
    }

}

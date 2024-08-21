package com.github.sseserver.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.remote.ServiceDiscoveryService;
import com.github.sseserver.util.AutoTypeBean;
import com.github.sseserver.util.TypeUtil;
import com.github.sseserver.util.WebUtil;
import com.sun.net.httpserver.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class LocalController implements Closeable {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final HttpServer httpServer;
    private final Supplier<LocalConnectionService> localConnectionServiceSupplier;
    private final Supplier<MessageRepository> localMessageRepositorySupplier;
    private final Supplier<? extends ServiceDiscoveryService> discoverySupplier;
    private final boolean primary;

    public LocalController(Supplier<LocalConnectionService> localConnectionServiceSupplier,
                           Supplier<MessageRepository> localMessageRepositorySupplier,
                           Supplier<ServiceDiscoveryService> discoverySupplier,
                           boolean primary) {
        this(WebUtil.getIPAddress(), localConnectionServiceSupplier, localMessageRepositorySupplier, discoverySupplier, primary);
    }

    public LocalController(String ip,
                           Supplier<LocalConnectionService> localConnectionServiceSupplier,
                           Supplier<MessageRepository> localMessageRepositorySupplier,
                           Supplier<ServiceDiscoveryService> discoverySupplier,
                           boolean primary) {
        this.localMessageRepositorySupplier = localMessageRepositorySupplier;
        this.localConnectionServiceSupplier = localConnectionServiceSupplier;
        this.discoverySupplier = discoverySupplier;
        this.primary = primary;
        this.httpServer = createHttpServer(ip);
        configHttpServer(httpServer);
        httpServer.start();
    }

    public boolean isPrimary() {
        return primary;
    }

    public InetSocketAddress getAddress() {
        return httpServer.getAddress();
    }

    public void registerInstance() {
        if (discoverySupplier == null) {
            return;
        }
        InetSocketAddress address = httpServer.getAddress();
        ServiceDiscoveryService discoveryService = discoverySupplier.get();
        if (discoveryService == null) {
            return;
        }
        for (int i = 0, retry = 3; i < retry; i++) {
            try {
                discoveryService.registerInstance(address.getAddress().getHostAddress(), address.getPort());
                return;
            } catch (Exception e) {
                if (i == retry - 1) {
                    throw e;
                } else {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        throw e;
                    }
                }
            }
        }
    }

    protected HttpServer createHttpServer(String ip) {
        while (true) {
            try {
                // 0 = random port
                return HttpServer.create(new InetSocketAddress(ip, 0), 0);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    protected HttpContext configConnectionQueryService(HttpServer httpServer) {
        return httpServer.createContext("/ConnectionQueryService/",
                new ConnectionQueryServiceHttpHandler(localConnectionServiceSupplier));
    }

    protected HttpContext configSendService(HttpServer httpServer) {
        return httpServer.createContext("/SendService/",
                new SendServiceHttpHandler(localConnectionServiceSupplier));
    }

    protected HttpContext configRemoteConnectionService(HttpServer httpServer) {
        return httpServer.createContext("/RemoteConnectionService/",
                new RemoteConnectionServiceHttpHandler(localConnectionServiceSupplier));
    }

    protected HttpContext configMessageRepository(HttpServer httpServer) {
        return httpServer.createContext("/MessageRepository/",
                new MessageRepositoryHttpHandler(localMessageRepositorySupplier));
    }

    protected void configAuthenticator(List<HttpContext> httpContextList) {
        for (HttpContext httpContext : httpContextList) {
            httpContext.setAuthenticator(new AuthorizationHeaderAuthenticator(discoverySupplier));
        }
    }

    protected void configFilters(List<HttpContext> httpContextList) {
        for (HttpContext httpContext : httpContextList) {
            List<Filter> filters = httpContext.getFilters();
            filters.add(new ErrorPageFilter());
        }
    }

    protected void configHttpServer(HttpServer httpServer) {
        List<HttpContext> contextList = new ArrayList<>(2);
        contextList.add(configConnectionQueryService(httpServer));
        contextList.add(configSendService(httpServer));
        contextList.add(configRemoteConnectionService(httpServer));
        contextList.add(configMessageRepository(httpServer));

        configAuthenticator(contextList);
        configFilters(contextList);
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    public static class RemoteConnectionServiceHttpHandler extends AbstractHttpHandler {
        private final Supplier<LocalConnectionService> supplier;

        public RemoteConnectionServiceHttpHandler(Supplier<LocalConnectionService> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle0(HttpExchange request) throws IOException {
            LocalConnectionService service = supplier != null ? supplier.get() : null;
            String rpcMethodName = getRpcMethodName();
            switch (rpcMethodName) {
                case "active": {
                    if (service instanceof LocalConnectionServiceImpl) {
                        ((LocalConnectionServiceImpl) service).localActive(body("userId"), body("accessToken"));
                        writeResponse(request, 1);
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "setDurationByUserId": {
                    if (service != null) {
                        writeResponse(request, service.setDurationByUserId(
                                        body("userId"), body("durationSecond"))
                                .size());
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "setDurationByAccessToken": {
                    if (service != null) {
                        writeResponse(request, service.setDurationByAccessToken(
                                        body("accessToken"), body("durationSecond"))
                                .size());
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "disconnectByUserId": {
                    if (service != null) {
                        writeResponse(request, service.disconnectByUserId(
                                body("userId")
                        ).size());
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "disconnectByAccessToken": {
                    if (service != null) {
                        writeResponse(request, service.disconnectByAccessToken(
                                body("accessToken")
                        ).size());
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "disconnectByConnectionId": {
                    if (service != null) {
                        writeResponse(request, service.disconnectByConnectionId(
                                body("connectionId", Long.class),
                                body("duration", Long.class),
                                body("sessionDuration", Long.class)
                        ) != null ? 1 : 0);
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "disconnectByConnectionIds": {
                    if (service != null) {
                        Collection<?> connectionIds = body("connectionIds", Collection.class);
                        if (connectionIds == null) {
                            writeResponse(request, 0);
                        } else {
                            writeResponse(request, service.disconnectByConnectionIds(connectionIds.stream()
                                    .map(TypeUtil::castToLong)
                                    .collect(Collectors.toList())
                            ).size());
                        }
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                default: {
                    request.sendResponseHeaders(404, 0);
                    break;
                }
            }
        }
    }

    public static class SendServiceHttpHandler extends AbstractHttpHandler {
        private final Supplier<? extends SendService<Integer>> supplier;

        public SendServiceHttpHandler(Supplier<? extends SendService<Integer>> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle0(HttpExchange request) throws IOException {
            String rpcMethodName = getRpcMethodName();
            SendService<Integer> service = supplier != null ? supplier.get() : null;

            if (service != null) {
                Object scopeOnWriteable = body("scopeOnWriteable");
                if (Boolean.TRUE.equals(scopeOnWriteable)) {
                    service.scopeOnWriteable(() -> {
                        handleCase(request, rpcMethodName, service);
                        return null;
                    });
                } else {
                    handleCase(request, rpcMethodName, service);
                }
            } else {
                writeResponse(request, 0);
            }
        }

        public void handleCase(HttpExchange request, String rpcMethodName, SendService<Integer> service) throws IOException {
            switch (rpcMethodName) {
                case "sendAll": {
                    writeResponse(request, service.sendAll(
                            body("eventName"),
                            body("body")
                    ));
                    break;
                }
                case "sendAllListening": {
                    writeResponse(request, service.sendAllListening(
                            body("eventName"),
                            body("body")
                    ));
                    break;
                }
                case "sendByChannel": {
                    Object channels = body("channels");
                    if (channels instanceof Collection) {
                        writeResponse(request, service.sendByChannel(
                                (Collection<String>) channels,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByChannel(
                                (String) channels,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                case "sendByChannelListening": {
                    Object channels = body("channels");
                    if (channels instanceof Collection) {
                        writeResponse(request, service.sendByChannelListening(
                                (Collection<String>) channels,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByChannelListening(
                                (String) channels,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                case "sendByAccessToken": {
                    Object accessTokens = body("accessTokens");
                    if (accessTokens instanceof Collection) {
                        writeResponse(request, service.sendByAccessToken(
                                (Collection<String>) accessTokens,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByAccessToken(
                                (String) accessTokens,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                case "sendByAccessTokenListening": {
                    Object accessTokens = body("accessTokens");
                    if (accessTokens instanceof Collection) {
                        writeResponse(request, service.sendByAccessTokenListening(
                                (Collection<String>) accessTokens,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByAccessTokenListening(
                                (String) accessTokens,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                case "sendByUserId": {
                    Object userIds = body("userIds");
                    if (userIds instanceof Collection) {
                        writeResponse(request, service.sendByUserId(
                                (Collection<Serializable>) userIds,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByUserId(
                                (String) userIds,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                case "sendByUserIdListening": {
                    Object userIds = body("userIds");
                    if (userIds instanceof Collection) {
                        writeResponse(request, service.sendByUserIdListening(
                                (Collection<Serializable>) userIds,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByUserIdListening(
                                (String) userIds,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                case "sendByTenantId": {
                    Object tenantIds = body("tenantIds");
                    if (tenantIds instanceof Collection) {
                        writeResponse(request, service.sendByTenantId(
                                (Collection<Serializable>) tenantIds,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByTenantId(
                                (String) tenantIds,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                case "sendByTenantIdListening": {
                    Object tenantIds = body("tenantIds");
                    if (tenantIds instanceof Collection) {
                        writeResponse(request, service.sendByTenantIdListening(
                                (Collection<Serializable>) tenantIds,
                                body("eventName"),
                                body("body")
                        ));
                    } else {
                        writeResponse(request, service.sendByTenantIdListening(
                                (String) tenantIds,
                                body("eventName"),
                                body("body")
                        ));
                    }
                    break;
                }
                default: {
                    request.sendResponseHeaders(404, 0);
                    break;
                }
            }
        }
    }

    public static class ConnectionQueryServiceHttpHandler extends AbstractHttpHandler {
        private final Supplier<? extends ConnectionQueryService> supplier;

        public ConnectionQueryServiceHttpHandler(Supplier<? extends ConnectionQueryService> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle0(HttpExchange request) throws IOException {
            String rpcMethodName = getRpcMethodName();
            ConnectionQueryService service = supplier != null ? supplier.get() : null;
            switch (rpcMethodName) {
                case "isOnline": {
                    if (service != null) {
                        writeResponse(request, service.isOnline(
                                query("userId")
                        ));
                    } else {
                        writeResponse(request, false);
                    }
                    break;
                }
                case "getUser": {
                    if (service != null) {
                        writeResponse(request, service.getUser(
                                query("userId")
                        ));
                    } else {
                        writeResponse(request, null);
                    }
                    break;
                }
                case "getUsers": {
                    if (service != null) {
                        writeResponse(request, service.getUsers());
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getUsersByListening": {
                    if (service != null) {
                        writeResponse(request, service.getUsersByListening(
                                query("sseListenerName")
                        ));
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getUsersByTenantIdListening": {
                    if (service != null) {
                        writeResponse(request, service.getUsersByTenantIdListening(
                                query("tenantId"),
                                query("sseListenerName")
                        ));
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getUserIds": {
                    if (service != null) {
                        writeResponse(request, service.getUserIds(String.class));
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getUserIdsByListening": {
                    if (service != null) {
                        writeResponse(request, service.getUserIdsByListening(
                                query("sseListenerName"),
                                String.class
                        ));
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getUserIdsByTenantIdListening": {
                    if (service != null) {
                        writeResponse(request, service.getUserIdsByTenantIdListening(
                                query("tenantId"),
                                query("sseListenerName"),
                                String.class
                        ));
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getAccessTokens": {
                    if (service != null) {
                        writeResponse(request, service.getAccessTokens());
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getTenantIds": {
                    if (service != null) {
                        writeResponse(request, service.getTenantIds(
                                String.class
                        ));
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getConnectionDTOAll": {
                    if (service != null) {
                        writeResponse(request, service.getConnectionDTOAll());
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getConnectionDTOByUserId": {
                    if (service != null) {
                        writeResponse(request, service.getConnectionDTOByUserId(query("userId")));
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getChannels": {
                    if (service != null) {
                        writeResponse(request, service.getChannels());
                    } else {
                        writeResponse(request, Collections.emptyList());
                    }
                    break;
                }
                case "getAccessTokenCount": {
                    if (service != null) {
                        writeResponse(request, service.getAccessTokenCount());
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "getUserCount": {
                    if (service != null) {
                        writeResponse(request, service.getUserCount());
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                case "getConnectionCount": {
                    if (service != null) {
                        writeResponse(request, service.getConnectionCount());
                    } else {
                        writeResponse(request, 0);
                    }
                    break;
                }
                default: {
                    request.sendResponseHeaders(404, 0);
                    break;
                }
            }
        }
    }

    public static class MessageRepositoryHttpHandler extends AbstractHttpHandler {
        private final Supplier<? extends MessageRepository> supplier;

        public MessageRepositoryHttpHandler(Supplier<? extends MessageRepository> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle0(HttpExchange request) throws IOException {
            MessageRepository service = supplier != null ? supplier.get() : null;
            if (service == null) {
                request.sendResponseHeaders(404, 0);
                return;
            }

            String rpcMethodName = getRpcMethodName();
            switch (rpcMethodName) {
                case "insert": {
                    writeResponse(request, service.insert(
                            new RemoteRequestMessage(request.getPrincipal(), body())
                    ), false);
                    break;
                }
                case "list": {
                    writeResponse(request, service.list(), false);
                    break;
                }
                case "select": {
                    writeResponse(request, service.select(
                            new RequestQuery()
                    ), false);
                    break;
                }
                case "delete": {
                    writeResponse(request, service.delete(
                            body("id", String.class)
                    ), false);
                    break;
                }
                default: {
                    request.sendResponseHeaders(404, 0);
                    break;
                }
            }
        }

        public static class RemoteRequestMessage extends AutoTypeBean implements Message {
            private final HttpPrincipal principal;

            private final String listenerName;
            private final Collection<? extends Serializable> userIdList;
            private final Collection<? extends Serializable> tenantIdList;
            private final Collection<String> accessTokenList;
            private final Collection<String> channelList;
            private final Object body;
            private final String eventName;
            private final String id;
            private final int filters;

            public RemoteRequestMessage(HttpPrincipal principal, Map body) {
                this.principal = principal;
                this.listenerName = body(body, "listenerName");
                this.userIdList = body(body, "userIdList");
                this.tenantIdList = body(body, "tenantIdList");
                this.accessTokenList = body(body, "accessTokenList");
                this.channelList = body(body, "channelList");
                this.body = body(body, "body");
                this.eventName = body(body, "eventName");
                this.id = body(body, "id");
                this.filters = body(body, "filters");
                setArrayClassName(body(body, "arrayClassName"));
                setObjectClassName(body(body, "objectClassName"));
            }

            public static <T> T body(Map body, String name) {
                return (T) body.get(name);
            }

            @Override
            public String getListenerName() {
                return listenerName;
            }

            @Override
            public Collection<? extends Serializable> getUserIdList() {
                return userIdList;
            }

            @Override
            public Collection<? extends Serializable> getTenantIdList() {
                return tenantIdList;
            }

            @Override
            public Collection<String> getAccessTokenList() {
                return accessTokenList;
            }

            @Override
            public Collection<String> getChannelList() {
                return channelList;
            }

            @Override
            public Object getBody() {
                return body;
            }

            @Override
            public String getEventName() {
                return eventName;
            }

            @Override
            public String getId() {
                return id;
            }

            @Override
            public int getFilters() {
                return filters;
            }
        }

        public class RequestQuery implements MessageRepository.Query {
            private Set<String> listeners;

            @Override
            public Serializable getTenantId() {
                return body("tenantId");
            }

            @Override
            public String getChannel() {
                return body("channel");
            }

            @Override
            public String getAccessToken() {
                return body("accessToken");
            }

            @Override
            public Serializable getUserId() {
                return body("userId");
            }

            @Override
            public Set<String> getListeners() {
                if (this.listeners == null) {
                    Object listeners = body("listeners");
                    if (listeners instanceof Set) {
                        this.listeners = (Set) listeners;
                    } else if (listeners instanceof Collection) {
                        this.listeners = new HashSet((Collection) listeners);
                    } else {
                        return null;
                    }
                }
                return this.listeners;
            }
        }

    }

    public static class AuthorizationHeaderAuthenticator extends Authenticator {
        private final Supplier<? extends ServiceDiscoveryService> supplier;

        public AuthorizationHeaderAuthenticator(Supplier<? extends ServiceDiscoveryService> supplier) {
            this.supplier = supplier;
        }

        @Override
        public Result authenticate(HttpExchange exchange) {
            ServiceDiscoveryService service = supplier != null ? supplier.get() : null;
            if (service == null) {
                return new Failure(401);
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            HttpPrincipal principal = service.login(authorization);
            if (principal != null) {
                return new Success(principal);
            } else {
                return new Failure(401);
            }
        }
    }

    public static class ErrorPageFilter extends Filter {
        @Override
        public void doFilter(HttpExchange request, Chain chain) throws IOException {
            try {
                chain.doFilter(request);
            } catch (Throwable e) {
                byte[] body = ("<h1>500 Internal Server Error</h1>" + e).getBytes(UTF_8);
                if (request.getResponseCode() == -1) {
                    request.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                    request.sendResponseHeaders(500, body.length);
                    OutputStream responseBody = request.getResponseBody();
                    responseBody.write(body);
                    responseBody.close();
                }
            }
        }

        @Override
        public String description() {
            return getClass().getSimpleName();
        }
    }

    public static abstract class AbstractHttpHandler implements HttpHandler {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ThreadLocal<HttpExchange> REQUEST_THREAD_LOCAL = new ThreadLocal<>();
        private final ThreadLocal<Map> BODY_THREAD_LOCAL = new ThreadLocal<>();

        private static boolean isKeepAlive(HttpExchange request) {
            String connection = request.getRequestHeaders().getFirst("Connection");
            String protocol = request.getProtocol();
            if (protocol.endsWith("1.0")) {
                return "keep-alive".equalsIgnoreCase(connection);
            } else {
                //不包含close就是保持
                return !"close".equalsIgnoreCase(connection);
            }
        }

        protected String query(String name) {
            return WebUtil.getQueryParam(REQUEST_THREAD_LOCAL.get().getRequestURI().getQuery(), name);
        }

        protected Map body() {
            return BODY_THREAD_LOCAL.get();
        }

        protected <T> T body(String name, Class<T> type) {
            Object body = body(name);
            return TypeUtil.cast(body, type);
        }

        protected <T> T body(String name) {
            Map body = BODY_THREAD_LOCAL.get();
            if (body == null) {
                return null;
            }
            return (T) body.get(name);
        }

        public String getRpcMethodName() {
            HttpExchange request = REQUEST_THREAD_LOCAL.get();
            return request.getRequestURI().getPath().substring(request.getHttpContext().getPath().length());
        }

        @Override
        public final void handle(HttpExchange request) throws IOException {
            Headers requestHeaders = request.getRequestHeaders();
            String contentLength = requestHeaders.getFirst("content-length");
            if (contentLength != null && Long.parseLong(contentLength) > 0) {
                String contentType = requestHeaders.getFirst("content-type");
                if (contentType != null && contentType.startsWith("application/json")) {
                    Map body = objectMapper.readValue(request.getRequestBody(), Map.class);
                    BODY_THREAD_LOCAL.set(body);
                }
            }
            try {
                REQUEST_THREAD_LOCAL.set(request);
                handle0(request);
            } finally {
                REQUEST_THREAD_LOCAL.remove();
                BODY_THREAD_LOCAL.remove();
            }
        }

        public void handle0(HttpExchange httpExchange) throws IOException {

        }

        protected void writeResponse(HttpExchange request, Object data) throws IOException {
            writeResponse(request, data, true);
        }

        protected void writeResponse(HttpExchange request, Object data, boolean autoType) throws IOException {
            request.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            if (isKeepAlive(request)) {
                request.getResponseHeaders().set("Connection", "keep-alive");
            } else {
                request.getResponseHeaders().set("Connection", "close");
            }

            Response body = new Response();
            body.setData(data);
            if (autoType) {
                body.retainClassName(data);
            }

            request.sendResponseHeaders(200, 0L);
            OutputStream out = request.getResponseBody();
            objectMapper.writeValue(out, body);
            out.close();
        }
    }

    public static class Response<T> extends AutoTypeBean {
        private T data;

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }
}

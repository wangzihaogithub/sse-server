package com.github.sseserver.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.SendService;
import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.remote.ServiceDiscoveryService;
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

public class LocalController implements Closeable {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final com.sun.net.httpserver.HttpServer httpServer;
    private final Supplier<LocalConnectionService> localConnectionServiceSupplier;
    private final Supplier<MessageRepository> localMessageRepositorySupplier;
    private final Supplier<? extends ServiceDiscoveryService> discoverySupplier;

    public LocalController(Supplier<LocalConnectionService> localConnectionServiceSupplier,
                           Supplier<MessageRepository> localMessageRepositorySupplier,
                           Supplier<ServiceDiscoveryService> discoverySupplier) {
        this(WebUtil.getIPAddress(), localConnectionServiceSupplier, localMessageRepositorySupplier, discoverySupplier);
    }

    public LocalController(String ip,
                           Supplier<LocalConnectionService> localConnectionServiceSupplier,
                           Supplier<MessageRepository> localMessageRepositorySupplier,
                           Supplier<ServiceDiscoveryService> discoverySupplier) {
        this.localMessageRepositorySupplier = localMessageRepositorySupplier;
        this.localConnectionServiceSupplier = localConnectionServiceSupplier;
        this.discoverySupplier = discoverySupplier;
        this.httpServer = createHttpServer(ip);
        configHttpServer(httpServer);
        httpServer.start();
        if (discoverySupplier != null) {
            registerInstance(discoverySupplier, httpServer.getAddress());
        }
    }

    public InetSocketAddress getAddress() {
        return httpServer.getAddress();
    }

    protected void registerInstance(Supplier<ServiceDiscoveryService> discoverySupplier, InetSocketAddress address) {
        ServiceDiscoveryService discoveryService = discoverySupplier.get();
        discoveryService.registerInstance(address.getAddress().getHostAddress(), address.getPort());
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
        return httpServer.createContext("/SendServiceHttpHandler/",
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

//        configAuthenticator(contextList);
        configFilters(contextList);
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    public static class RemoteConnectionServiceHttpHandler extends AbstractHttpHandler {
        private final Supplier<? extends LocalConnectionService> supplier;

        public RemoteConnectionServiceHttpHandler(Supplier<? extends LocalConnectionService> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle0(HttpExchange request) throws IOException {
            String rpcMethodName = getRpcMethodName();
            LocalConnectionService service = supplier.get();
            switch (rpcMethodName) {
                case "disconnectByUserId": {
                    writeResponse(request, service.disconnectByUserId(
                            body("userId")
                    ).size());
                    break;
                }
                case "disconnectByAccessToken": {
                    writeResponse(request, service.disconnectByAccessToken(
                            body("accessToken")
                    ).size());
                    break;
                }
                case "disconnectByConnectionId": {
                    writeResponse(request, service.disconnectByConnectionId(
                            body("connectionId")
                    ) != null ? 1 : 0);
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
            SendService<Integer> service = supplier.get();
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
            ConnectionQueryService service = supplier.get();
            switch (rpcMethodName) {
                case "isOnline": {
                    writeResponse(request, service.isOnline(
                            query("userId")
                    ));
                    break;
                }
                case "getUser": {
                    writeResponse(request, service.getUser(
                            query("userId")
                    ));
                    break;
                }
                case "getUsers": {
                    writeResponse(request, service.getUsers());
                    break;
                }
                case "getUsersByListening": {
                    writeResponse(request, service.getUsersByListening(
                            query("sseListenerName")
                    ));
                    break;
                }
                case "getUsersByTenantIdListening": {
                    writeResponse(request, service.getUsersByTenantIdListening(
                            query("tenantId"),
                            query("sseListenerName")
                    ));
                    break;
                }
                case "getUserIds": {
                    writeResponse(request, service.getUserIds(String.class));
                    break;
                }
                case "getUserIdsByListening": {
                    writeResponse(request, service.getUserIdsByListening(
                            query("sseListenerName"),
                            String.class
                    ));
                    break;
                }
                case "getUserIdsByTenantIdListening": {
                    writeResponse(request, service.getUserIdsByTenantIdListening(
                            query("tenantId"),
                            query("sseListenerName"),
                            String.class
                    ));
                    break;
                }
                case "getAccessTokens": {
                    writeResponse(request, service.getAccessTokens());
                    break;
                }
                case "getTenantIds": {
                    writeResponse(request, service.getTenantIds(
                            String.class
                    ));
                    break;
                }
                case "getChannels": {
                    writeResponse(request, service.getChannels());
                    break;
                }
                case "getAccessTokenCount": {
                    writeResponse(request, service.getAccessTokenCount());
                    break;
                }
                case "getUserCount": {
                    writeResponse(request, service.getUserCount());
                    break;
                }
                case "getConnectionCount": {
                    writeResponse(request, service.getConnectionCount());
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
        private final Message repositoryMessage = new RemoteRequestMessage();

        public MessageRepositoryHttpHandler(Supplier<? extends MessageRepository> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void handle0(HttpExchange request) throws IOException {
            String rpcMethodName = getRpcMethodName();
            MessageRepository service = supplier.get();
            switch (rpcMethodName) {
                case "insert": {
                    writeResponse(request, service.insert(
                            repositoryMessage
                    ));
                    break;
                }
                case "select": {
                    writeResponse(request, service.select(
                            new RequestQuery()
                    ));
                    break;
                }
                case "delete": {
                    writeResponse(request, service.delete(body("id")));
                    break;
                }
                default: {
                    request.sendResponseHeaders(404, 0);
                    break;
                }
            }
        }

        public class RemoteRequestMessage implements Message {

            @Override
            public String getListenerName() {
                return body("listenerName");
            }

            @Override
            public Collection<? extends Serializable> getUserIdList() {
                return body("userIdList");
            }

            @Override
            public Collection<? extends Serializable> getTenantIdList() {
                return body("tenantIdList");
            }

            @Override
            public Collection<String> getAccessTokenList() {
                return body("accessTokenList");
            }

            @Override
            public Collection<String> getChannelList() {
                return body("channelList");
            }

            @Override
            public Serializable getBody() {
                return body("body");
            }

            @Override
            public String getEventName() {
                return body("eventName");
            }

            @Override
            public String getId() {
                return body("id");
            }

            @Override
            public int getFilters() {
                return body("filters");
            }
        }

        public class RequestQuery implements MessageRepository.Query {
            private Set<String> listeners;

            @Override
            public Serializable getTenantId() {
                return body("filters");
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
        public Authenticator.Result authenticate(HttpExchange exchange) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            ServiceDiscoveryService service = supplier.get();
            HttpPrincipal principal = service.login(authorization);
            if (principal != null) {
                return new Authenticator.Success(principal);
            } else {
                return new Authenticator.Failure(401);
            }
        }
    }

    public static class ErrorPageFilter extends Filter {
        @Override
        public void doFilter(HttpExchange request, Filter.Chain chain) throws IOException {
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
            String contentLength = request.getRequestHeaders().getFirst("content-length");
            if (contentLength != null && Long.parseLong(contentLength) > 0) {
                Map body = objectMapper.readValue(request.getRequestBody(), Map.class);
                BODY_THREAD_LOCAL.set(body);
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
            request.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            if (isKeepAlive(request)) {
                request.getResponseHeaders().set("Connection", "keep-alive");
            } else {
                request.getResponseHeaders().set("Connection", "close");
            }

            Response body = new Response();
            body.setData(data);
            body.autoCastClassName(true);

            request.sendResponseHeaders(200, 0L);
            OutputStream out = request.getResponseBody();
            objectMapper.writeValue(out, body);
            out.close();
        }
    }

    public static class Response<T> {
        private T data;
        private Map<String, Collection<Integer>> arrayClassName;
        private String objectClassName;

        public Map<String, Collection<Integer>> getArrayClassName() {
            return arrayClassName;
        }

        public void setArrayClassName(Map<String, Collection<Integer>> arrayClassName) {
            this.arrayClassName = arrayClassName;
        }

        public String getObjectClassName() {
            return objectClassName;
        }

        public void setObjectClassName(String objectClassName) {
            this.objectClassName = objectClassName;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public void autoCastClassName(boolean server) {
            if (server) {
                if (data instanceof Collection) {
                    int i = 0;
                    Map<String, Collection<Integer>> classMap = new HashMap<>(1);
                    for (Object item : (Collection) data) {
                        if (!TypeUtil.isBasicType(item)) {
                            classMap.computeIfAbsent(item.getClass().getName(), e -> new ArrayList<>())
                                    .add(i);
                        }
                        i++;
                    }
                    arrayClassName = classMap;
                } else if (TypeUtil.isBasicType(data)) {

                } else {
                    objectClassName = data.getClass().getName();
                }
            } else {
                if (objectClassName != null) {
                    data = TypeUtil.castBean(data, objectClassName);
                } else if (arrayClassName != null && data instanceof Collection) {
                    for (Map.Entry<String, Collection<Integer>> entry : arrayClassName.entrySet()) {
                        Collection<Integer> value = entry.getValue();
                        if (!(value instanceof Set)) {
                            entry.setValue(new HashSet<>(value));
                        }
                    }
                    List list = new ArrayList(((Collection<?>) data).size());
                    int i = 0;
                    for (Object item : (Collection) data) {
                        String objectClassName = null;
                        for (Map.Entry<String, Collection<Integer>> entry : arrayClassName.entrySet()) {
                            if (entry.getValue().contains(i)) {
                                objectClassName = entry.getKey();
                                break;
                            }
                        }
                        if (objectClassName == null) {
                            list.add(item);
                        } else {
                            list.add(TypeUtil.castBean(item, objectClassName));
                        }
                        i++;
                    }
                    this.data = (T) list;
                }
            }
        }
    }
}

package com.github.sseserver.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sseserver.ConnectionQueryService;
import com.github.sseserver.remote.ServiceDiscoveryService;
import com.github.sseserver.util.WebUtil;
import com.sun.net.httpserver.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class LocalConnectionController implements Closeable {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final com.sun.net.httpserver.HttpServer httpServer;
    private final Supplier<LocalConnectionService> localConnectionServiceSupplier;
    private final Supplier<ServiceDiscoveryService> discoverySupplier;

    public LocalConnectionController(Supplier<LocalConnectionService> localConnectionServiceSupplier, Supplier<ServiceDiscoveryService> discoverySupplier) {
        this(WebUtil.getIPAddress(), localConnectionServiceSupplier, discoverySupplier);
    }

    public LocalConnectionController(String ip, Supplier<LocalConnectionService> localConnectionServiceSupplier, Supplier<ServiceDiscoveryService> discoverySupplier) {
        this.discoverySupplier = discoverySupplier;
        this.localConnectionServiceSupplier = localConnectionServiceSupplier;
        this.httpServer = createHttpServer(ip);
        configHttpServer(httpServer);
        httpServer.start();
        if (discoverySupplier != null) {
            registerInstance(discoverySupplier, httpServer.getAddress());
        }
    }

    protected void registerInstance(Supplier<ServiceDiscoveryService> discoverySupplier, InetSocketAddress address) {
        ServiceDiscoveryService discoveryService = discoverySupplier.get();
        discoveryService.registerInstance(address.getAddress().getHostAddress(), address.getPort());
    }

    protected HttpServer createHttpServer(String ip) {
        while (true) {
            try {
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
        httpServer.setExecutor(Runnable::run);

        List<HttpContext> contextList = new ArrayList<>(1);
        contextList.add(configConnectionQueryService(httpServer));

        configAuthenticator(contextList);
        configFilters(contextList);
    }

    public InetSocketAddress getAddress() {
        return httpServer.getAddress();
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
                            arg("userId")
                    ));
                    break;
                }
                case "getUser": {
                    writeResponse(request, service.getUser(
                            arg("userId")
                    ));
                    break;
                }
                case "getUsers": {
                    writeResponse(request, service.getUsers());
                    break;
                }
                case "getUsersByListening": {
                    writeResponse(request, service.getUsersByListening(
                            arg("sseListenerName")
                    ));
                    break;
                }
                case "getUsersByTenantIdListening": {
                    writeResponse(request, service.getUsersByTenantIdListening(
                            arg("tenantId"),
                            arg("sseListenerName")
                    ));
                    break;
                }
                default: {
                    break;
                }
            }
        }
    }

    public static class AuthorizationHeaderAuthenticator extends Authenticator {
        private final Supplier<ServiceDiscoveryService> discoverySupplier;

        public AuthorizationHeaderAuthenticator(Supplier<ServiceDiscoveryService> discoverySupplier) {
            this.discoverySupplier = discoverySupplier;
        }

        @Override
        public Authenticator.Result authenticate(HttpExchange exchange) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            ServiceDiscoveryService discoveryService = discoverySupplier.get();
            HttpPrincipal principal = discoveryService.login(authorization);
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
                    request.getResponseBody().write(body);
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

        protected String arg(String name) {
            return WebUtil.getQueryParam(REQUEST_THREAD_LOCAL.get().getRequestURI().getQuery(), name);
        }

        public String getRpcMethodName() {
            HttpExchange request = REQUEST_THREAD_LOCAL.get();
            return request.getRequestURI().getPath().substring(request.getHttpContext().getPath().length());
        }

        @Override
        public final void handle(HttpExchange request) throws IOException {
            try {
                REQUEST_THREAD_LOCAL.set(request);
                handle0(request);
            } finally {
                REQUEST_THREAD_LOCAL.remove();
            }
        }

        public void handle0(HttpExchange httpExchange) throws IOException {

        }

        protected void writeResponse(HttpExchange request, Object data) throws IOException {
            request.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            Response response = new Response();
            response.setData(data);

            byte[] bytes = objectMapper.writeValueAsBytes(response);
            request.sendResponseHeaders(200, bytes.length);
            request.getResponseBody().write(bytes);
        }
    }

    public static class Response<T> {
        private T data;

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }
}

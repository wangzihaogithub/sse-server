package com.github.sseserver.util;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.codecs.DefaultHttpRequestWriterFactory;
import org.apache.http.impl.nio.codecs.DefaultHttpResponseParserFactory;
import org.apache.http.impl.nio.conn.ManagedNHttpClientConnectionFactory;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.conn.NHttpConnectionFactory;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.protocol.HttpContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ApacheHttpUtil {
    private static final AtomicInteger acceptThreadId = new AtomicInteger();
    private static final AtomicInteger ioThreadId = new AtomicInteger();

    public static long keepaliveSeconds = Long.getLong("ApacheHttpUtil.keepaliveSeconds",
            60L);
    public static int defaultMaxPerRoute = Integer.getInteger("ApacheHttpUtil.defaultMaxPerRoute",
            100);

    public static SpringUtil.AsyncClientHttpRequestFactory newRequestFactory(int connectTimeout, int readTimeout, int maxThreads, String threadName) {
        // HTTPConnection工厂 ：配置请求/解析响应
        NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory =
                new ManagedNHttpClientConnectionFactory(
                        DefaultHttpRequestWriterFactory.INSTANCE,
                        DefaultHttpResponseParserFactory.INSTANCE, HeapByteBufferAllocator.INSTANCE);

        // 为支持的协议方案创建自定义连接套接字工厂的注册表。
        Registry<SchemeIOSessionStrategy> sessionStrategyRegistry = RegistryBuilder.<SchemeIOSessionStrategy>create()
                .register("http", NoopIOSessionStrategy.INSTANCE)
                .build();

        //DNS解析器
        DnsResolver dnsResolver = SystemDefaultDnsResolver.INSTANCE;

        // Create I/O reactor configuration
        IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setIoThreadCount(maxThreads)
                .setConnectTimeout(connectTimeout)
                .setSoTimeout(readTimeout)
                .setSoKeepAlive(true)
                .setTcpNoDelay(false)
                .build();

        ThreadFactory acceptThreadFactory = r -> {
            Thread thread = new Thread(r, threadName + "-Accept-" + acceptThreadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadFactory ioThreadFactory = r -> {
            Thread thread = new Thread(r, threadName + "-IO-" + ioThreadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };

        // 创建一个定制的I/O reactort
        ConnectingIOReactor ioReactor;
        try {
            ioReactor = new DefaultConnectingIOReactor(ioReactorConfig, acceptThreadFactory);
        } catch (IOReactorException e) {
            throw new IllegalStateException("ApacheHttpUtil.newRequestFactory fail : " + e, e);
        }

        // 使用自定义配置创建连接管理器。
        PoolingNHttpClientConnectionManager asyncConnManager = new PoolingNHttpClientConnectionManager(
                ioReactor, connFactory, sessionStrategyRegistry, null, dnsResolver, keepaliveSeconds, TimeUnit.SECONDS);

        //创建连接配置
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setMalformedInputAction(CodingErrorAction.IGNORE)
                .setUnmappableInputAction(CodingErrorAction.IGNORE)
                .setCharset(Charset.forName("UTF-8"))
                .build();
        // 将连接管理器配置为默认使用或针对特定主机使用连接配置。
        asyncConnManager.setDefaultConnectionConfig(connectionConfig);

        // 配置永久连接的最大总数或每个路由限制
        // 可以保留在池中或由连接管理器租用。
        //每个路由的默认最大连接，每个路由实际最大连接为默认为DefaultMaxPreRoute控制，而MaxTotal是控制整个池子最大数
        asyncConnManager.setMaxTotal(maxThreads);
        asyncConnManager.setDefaultMaxPerRoute(defaultMaxPerRoute);

        // 创建全局请求配置
        RequestConfig defaultRequestConfig = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .setConnectionRequestTimeout(readTimeout)
                .setSocketTimeout((int) (keepaliveSeconds * 1000))
                .setConnectTimeout(connectTimeout)
                .setExpectContinueEnabled(true)
                .build();

        /**
         *  KeepAlive策略
         */
        class CustomConnectionKeepAliveStrategy implements ConnectionKeepAliveStrategy {
            @Override
            public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
                long keepAliveDuration = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
                return keepAliveDuration > 0 ? keepAliveDuration : keepaliveSeconds * 1000L;
            }
        }

        CustomConnectionKeepAliveStrategy keepAliveStrategy = new CustomConnectionKeepAliveStrategy();

        // Create an HttpClientUtils with the given custom dependencies and configuration.
        HttpAsyncClient asyncHttpClient = HttpAsyncClients.custom()
                .setConnectionManager(asyncConnManager)
                .setDefaultRequestConfig(defaultRequestConfig)
                .setThreadFactory(ioThreadFactory)
//                .setConnectionManagerShared(true)
                .setMaxConnTotal(maxThreads)
                // 有 Keep-Alive 认里面的值，没有的话永久有效
//                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                // 换成自定义的
                .setKeepAliveStrategy(keepAliveStrategy)
                .build();

        HttpComponentsAsyncClientHttpRequestFactory factory = new HttpComponentsAsyncClientHttpRequestFactory(asyncHttpClient);
        factory.startAsyncClient();
        return factory;
    }

    public static class HttpComponentsAsyncClientHttpRequestFactory
            implements SpringUtil.AsyncClientHttpRequestFactory, DisposableBean {
        private final HttpAsyncClient asyncClient;

        public HttpComponentsAsyncClientHttpRequestFactory(HttpAsyncClient asyncClient) {
            this.asyncClient = asyncClient;
        }

        public HttpAsyncClient startAsyncClient() {
            HttpAsyncClient client = asyncClient;
            if (client instanceof CloseableHttpAsyncClient) {
                CloseableHttpAsyncClient closeableAsyncClient = (CloseableHttpAsyncClient) client;
                if (!closeableAsyncClient.isRunning()) {
                    closeableAsyncClient.start();
                }
            }
            return client;
        }

        protected HttpUriRequest createHttpUriRequest(String httpMethod, URI uri) {
            switch (httpMethod) {
                case "GET":
                    return new HttpGet(uri);
                case "HEAD":
                    return new HttpHead(uri);
                case "POST":
                    return new HttpPost(uri);
                case "PUT":
                    return new HttpPut(uri);
                case "PATCH":
                    return new HttpPatch(uri);
                case "DELETE":
                    return new ApacheHttpUtil.HttpDelete(uri);
                case "OPTIONS":
                    return new HttpOptions(uri);
                case "TRACE":
                    return new HttpTrace(uri);
                default:
                    throw new IllegalArgumentException("Invalid HTTP method: " + httpMethod);
            }
        }

        @Override
        public SpringUtil.AsyncClientHttpRequest createAsyncRequest(URI uri, String httpMethod) throws IOException {
            HttpAsyncClient client = startAsyncClient();
            HttpUriRequest httpRequest = createHttpUriRequest(httpMethod, uri);
            HttpContext context = HttpClientContext.create();
            return new HttpComponentsAsyncClientHttpRequest(client, httpRequest, context);
        }

        @Override
        public void destroy() throws Exception {
            HttpAsyncClient httpClient = asyncClient;
            if (httpClient instanceof Closeable) {
                ((Closeable) httpClient).close();
            }
        }
    }

    final static class HttpComponentsAsyncClientHttpRequest extends SpringUtil.AbstractBufferingAsyncClientHttpRequest {
        private final HttpAsyncClient httpClient;
        private final HttpUriRequest httpRequest;
        private final HttpContext httpContext;

        HttpComponentsAsyncClientHttpRequest(HttpAsyncClient client, HttpUriRequest request, HttpContext context) {
            this.httpClient = client;
            this.httpRequest = request;
            this.httpContext = context;
        }

        @Override
        public String getMethod() {
            return this.httpRequest.getMethod();
        }

        @Override
        protected CompletableFuture<SpringUtil.HttpEntity<InputStream>> executeInternal(SpringUtil.HttpHeaders headers, byte[] bufferedOutput) throws IOException {
            addHeaders(this.httpRequest, headers);
            if (this.httpRequest instanceof HttpEntityEnclosingRequest) {
                HttpEntityEnclosingRequest entityEnclosingRequest = (HttpEntityEnclosingRequest) this.httpRequest;
                HttpEntity requestEntity = new NByteArrayEntity(bufferedOutput);
                entityEnclosingRequest.setEntity(requestEntity);
            }

            CompletableFuture<SpringUtil.HttpEntity<InputStream>> future = new CompletableFuture<>();
            this.httpClient.execute(this.httpRequest, this.httpContext, new FutureCallback<HttpResponse>() {
                @Override
                public void completed(HttpResponse httpResponse) {
                    future.complete(new ApacheClientHttpResponse(httpResponse));
                }

                @Override
                public void failed(Exception e) {
                    future.completeExceptionally(e);
                }

                @Override
                public void cancelled() {
                    future.completeExceptionally(new IOException("cancelled"));
                }
            });
            return future;
        }

        static void addHeaders(HttpUriRequest httpRequest, SpringUtil.HttpHeaders headers) {
            headers.forEach((headerName, headerValues) -> {
                if (HttpHeaders.COOKIE.equalsIgnoreCase(headerName)) {  // RFC 6265
                    String headerValue = StringUtils.collectionToDelimitedString(headerValues, "; ");
                    httpRequest.addHeader(headerName, headerValue);
                } else if (!"Content-Length".equalsIgnoreCase(headerName) &&
                        !"Transfer-Encoding".equalsIgnoreCase(headerName)) {
                    for (String headerValue : headerValues) {
                        httpRequest.addHeader(headerName, headerValue);
                    }
                }
            });
        }
    }

    static class ApacheClientHttpResponse extends SpringUtil.HttpEntity<InputStream> {
        private SpringUtil.HttpHeaders headers;
        private final HttpResponse httpResponse;

        public ApacheClientHttpResponse(HttpResponse httpResponse) {
            this.httpResponse = httpResponse;
        }

        @Override
        public SpringUtil.HttpHeaders getHeaders() {
            if (this.headers == null) {
                this.headers = new SpringUtil.HttpHeaders();
                for (Header header : httpResponse.getAllHeaders()) {
                    this.headers.computeIfAbsent(header.getName(), e -> new ArrayList<>())
                            .add(header.getValue());
                }
            }
            return this.headers;
        }

        @Override
        public int getStatus() {
            return httpResponse.getStatusLine().getStatusCode();
        }

        @Override
        public InputStream getBody() {
            HttpEntity entity = httpResponse.getEntity();
            try {
                return entity != null ? entity.getContent() : new ByteArrayInputStream(new byte[0]);
            } catch (IOException e) {
                LambdaUtil.sneakyThrows(e);
                return null;
            }
        }
    }

    private static class HttpDelete extends HttpEntityEnclosingRequestBase {

        public HttpDelete(URI uri) {
            super();
            setURI(uri);
        }

        @Override
        public String getMethod() {
            return "DELETE";
        }
    }

}

package com.github.sseserver.util;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
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
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.protocol.HttpContext;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsAsyncClientHttpRequestFactory;

import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ApacheHttpUtil {
    private static final AtomicInteger acceptThreadId = new AtomicInteger();
    private static final AtomicInteger ioThreadId = new AtomicInteger();

    public static long keepaliveSeconds = Long.getLong("ApacheHttpUtil.keepaliveSeconds",
            60L);
    public static int maxTotal = Integer.getInteger("ApacheHttpUtil.maxTotal",
            200);
    public static int defaultMaxPerRoute = Integer.getInteger("ApacheHttpUtil.defaultMaxPerRoute",
            100);

    public static AsyncClientHttpRequestFactory newRequestFactory(int connectTimeout, int readTimeout, int maxThreads, String threadName) {
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

        // Create an HttpClientUtils with the given custom dependencies and configuration.
        HttpAsyncClient asyncHttpClient = HttpAsyncClients.custom()
                .setConnectionManager(asyncConnManager)
                .setDefaultRequestConfig(defaultRequestConfig)
                .setThreadFactory(ioThreadFactory)
//                .setConnectionManagerShared(true)
                .setMaxConnTotal(maxThreads)
                // 有 Keep-Alive 认里面的值，没有的话永久有效
                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                // 换成自定义的
                .setKeepAliveStrategy(new CustomConnectionKeepAliveStrategy())
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                new PoolingHttpClientConnectionManager(keepaliveSeconds, TimeUnit.SECONDS);
        connectionManager.setMaxTotal(maxTotal);
        connectionManager.setDefaultMaxPerRoute(defaultMaxPerRoute);
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(defaultRequestConfig)
                .setConnectionManager(connectionManager)
                .evictIdleConnections(keepaliveSeconds, TimeUnit.SECONDS)
//                .setConnectionManagerShared(true)
                .disableAutomaticRetries()
                // 有 Keep-Alive 认里面的值，没有的话永久有效
                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                // 换成自定义的
                .setKeepAliveStrategy(new CustomConnectionKeepAliveStrategy())
                .build();

        HttpComponentsAsyncClientHttpRequestFactory factory = new HttpComponentsAsyncClientHttpRequestFactory(httpClient, asyncHttpClient);
        factory.afterPropertiesSet();
        return factory;
    }

}

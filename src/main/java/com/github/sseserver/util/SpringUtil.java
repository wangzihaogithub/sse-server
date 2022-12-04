package com.github.sseserver.util;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.AsyncRestTemplate;

import java.nio.charset.Charset;

public class SpringUtil {
    public static final HttpHeaders EMPTY_HEADERS = new HttpHeaders();

    private static final boolean SUPPORT_NETTY4;
    private static final boolean SUPPORT_OKHTTP3;

    static {
        boolean supportNetty4;
        try {
            Class.forName("io.netty.channel.ChannelHandler");
            supportNetty4 = true;
        } catch (Throwable e) {
            supportNetty4 = false;
        }
        SUPPORT_NETTY4 = supportNetty4;

        boolean supportOkhttp3;
        try {
            Class.forName("okhttp3.OkHttpClient");
            supportOkhttp3 = true;
        } catch (Throwable e) {
            supportOkhttp3 = false;
        }
        SUPPORT_OKHTTP3 = supportOkhttp3;
    }

    public static AsyncRestTemplate newAsyncRestTemplate(int connectTimeout, int readTimeout,
                                                         int threadsIfAsyncRequest, int threadsIfBlockRequest,
                                                         String threadName,
                                                         String account, String password) {
        String authorization = "Basic " + HttpHeaders.encodeBasicAuth(account, password, Charset.forName("ISO-8859-1"));

        AsyncRestTemplate restTemplate;
        if (SUPPORT_NETTY4) {
            restTemplate = new AsyncRestTemplate(NettyUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName));
        } else if (SUPPORT_OKHTTP3) {
            restTemplate = new AsyncRestTemplate(OkhttpUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName));
        } else {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setDaemon(true);
            executor.setThreadNamePrefix(threadName + "-");
            executor.setCorePoolSize(0);
            executor.setKeepAliveSeconds(60);
            executor.setMaxPoolSize(threadsIfBlockRequest);
            executor.setWaitForTasksToCompleteOnShutdown(true);
            ClientHttpRequestFactory factory = new ClientHttpRequestFactory(executor);
            factory.setConnectTimeout(connectTimeout);
            factory.setReadTimeout(readTimeout);
            restTemplate = new AsyncRestTemplate(factory);
        }
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("Authorization", authorization);
            return execution.executeAsync(request, body);
        });
        return restTemplate;
    }

    public static void close(AsyncRestTemplate restTemplate) {
        AsyncClientHttpRequestFactory factory = restTemplate.getAsyncRequestFactory();
        if (factory instanceof DisposableBean) {
            try {
                ((DisposableBean) factory).destroy();
            } catch (Exception ignored) {
            }
        }
    }

    public static String filterNonAscii(String str) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '"') {
                builder.append('\'');
            } else if (c == ':') {
                builder.append('-');
            } else if (c >= 32 && c <= 126) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    public static class ClientHttpRequestFactory extends SimpleClientHttpRequestFactory implements DisposableBean {
        private final ThreadPoolTaskExecutor threadPool;

        public ClientHttpRequestFactory(ThreadPoolTaskExecutor threadPool) {
            this.threadPool = threadPool;
            threadPool.afterPropertiesSet();
            setTaskExecutor(threadPool);
        }

        @Override
        public void destroy() {
            threadPool.shutdown();
        }
    }

}

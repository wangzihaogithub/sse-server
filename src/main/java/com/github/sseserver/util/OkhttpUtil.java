package com.github.sseserver.util;

import okhttp3.Cache;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;

import java.io.IOException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class OkhttpUtil {

    public static OkHttp3ClientHttpRequestFactory newRequestFactory(int connectTimeout, int readTimeout, int maxThreads, String threadName) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(new ThreadPoolExecutor(
                        0, maxThreads,
                        60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                        r -> {
                            Thread result = new Thread(r, threadName);
                            result.setDaemon(true);
                            return result;
                        })))
                .build();
        OkHttp3ClientHttpRequestFactory factory = new OkHttp3ClientHttpRequestFactory() {
            @Override
            public void destroy() throws IOException {
                // Clean up the client if we created it in the constructor
                Cache cache = okHttpClient.cache();
                if (cache != null) {
                    cache.close();
                }
                okHttpClient.dispatcher().executorService().shutdown();
                okHttpClient.connectionPool().evictAll();
            }
        };
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}

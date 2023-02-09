package com.github.sseserver.util;

import okhttp3.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class OkhttpUtil {

    public static SpringUtil.AsyncClientHttpRequestFactory newRequestFactory(int connectTimeout, int readTimeout, int maxThreads, String threadName) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .dispatcher(new Dispatcher(new ThreadPoolExecutor(
                        0, maxThreads,
                        60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                        r -> {
                            Thread result = new Thread(r, threadName);
                            result.setDaemon(true);
                            return result;
                        })))
                .build();
        OkHttp3ClientHttpRequestFactory factory = new OkHttp3ClientHttpRequestFactory(okHttpClient);
        return factory;
    }

    public static class OkHttp3ClientHttpRequestFactory
            implements SpringUtil.AsyncClientHttpRequestFactory, DisposableBean {
        private final OkHttpClient client;

        public OkHttp3ClientHttpRequestFactory(OkHttpClient client) {
            this.client = client;
        }

        @Override
        public SpringUtil.AsyncClientHttpRequest createAsyncRequest(URI uri, String httpMethod) {
            return new OkHttp3AsyncClientHttpRequest(this.client, uri, httpMethod);
        }

        @Override
        public void destroy() throws IOException {
            Cache cache = this.client.cache();
            if (cache != null) {
                cache.close();
            }
            this.client.dispatcher().executorService().shutdown();
            this.client.connectionPool().evictAll();
        }
    }

    static class OkHttp3AsyncClientHttpRequest extends SpringUtil.AbstractBufferingAsyncClientHttpRequest {
        private final OkHttpClient client;
        private final URI uri;
        private final String method;

        public OkHttp3AsyncClientHttpRequest(OkHttpClient client, URI uri, String method) {
            this.client = client;
            this.uri = uri;
            this.method = method;
        }

        static Request buildRequest(SpringUtil.HttpHeaders headers, byte[] content, URI uri, String method)
                throws MalformedURLException {

            okhttp3.MediaType contentType = getContentType(headers);
            RequestBody body = (content.length > 0 ||
                    okhttp3.internal.http.HttpMethod.requiresRequestBody(method) ?
                    RequestBody.create(contentType, content) : null);

            Request.Builder builder = new Request.Builder().url(uri.toURL()).method(method, body);
            headers.forEach((headerName, headerValues) -> {
                for (String headerValue : headerValues) {
                    builder.addHeader(headerName, headerValue);
                }
            });
            return builder.build();
        }

        private static okhttp3.MediaType getContentType(SpringUtil.HttpHeaders headers) {
            String rawContentType = headers.getFirst("Content-Type");
            return (StringUtils.hasText(rawContentType) ? okhttp3.MediaType.parse(rawContentType) : null);
        }

        @Override
        public String getMethod() {
            return this.method;
        }

        @Override
        protected CompletableFuture<SpringUtil.HttpEntity<InputStream>> executeInternal(SpringUtil.HttpHeaders headers, byte[] content)
                throws IOException {
            Request request = buildRequest(headers, content, this.uri, this.method);
            CompletableFuture<SpringUtil.HttpEntity<InputStream>> future = new CompletableFuture<>();
            Call call = this.client.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onResponse(Call call, Response response) {
                    future.complete(new OkHttp3ClientHttpResponse(response));
                }

                @Override
                public void onFailure(Call call, IOException ex) {
                    future.completeExceptionally(ex);
                }
            });
            return future;
        }
    }

    static class OkHttp3ClientHttpResponse extends SpringUtil.HttpEntity<InputStream> implements Closeable {
        private final Response response;
        private volatile SpringUtil.HttpHeaders headers;

        public OkHttp3ClientHttpResponse(Response response) {
            this.response = response;
        }

        @Override
        public int getStatus() {
            return this.response.code();
        }

        @Override
        public InputStream getBody() {
            ResponseBody body = this.response.body();
            return (body != null ? body.byteStream() : new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public SpringUtil.HttpHeaders getHeaders() {
            SpringUtil.HttpHeaders headers = this.headers;
            if (headers == null) {
                headers = new SpringUtil.HttpHeaders();
                for (String headerName : this.response.headers().names()) {
                    for (String headerValue : this.response.headers(headerName)) {
                        headers.computeIfAbsent(headerName, e -> new ArrayList<>())
                                .add(headerValue);
                    }
                }
                this.headers = headers;
            }
            return headers;
        }

        @Override
        public void close() {
            ResponseBody body = this.response.body();
            if (body != null) {
                body.close();
            }
        }
    }

}

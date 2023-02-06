package com.github.sseserver.util;

import okhttp3.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.*;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;

import javax.swing.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        OkHttp3ClientHttpRequestFactory factory = new OkHttp3ClientHttpRequestFactory(okHttpClient) {
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
        return factory;
    }

    /*
     * Copyright 2002-2019 the original author or authors.
     *
     * Licensed under the Apache License, Version 2.0 (the "License");
     * you may not use this file except in compliance with the License.
     * You may obtain a copy of the License at
     *
     *      https://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    /**
     * {@link ClientHttpRequestFactory} implementation that uses
     * <a href="https://square.github.io/okhttp/">OkHttp</a> 3.x to create requests.
     *
     * @author Luciano Leggieri
     * @author Arjen Poutsma
     * @author Roy Clarkson
     * @since 4.3
     */
    public static class OkHttp3ClientHttpRequestFactory
            implements SpringUtil.AsyncClientHttpRequestFactory, DisposableBean {

        private OkHttpClient client;

        private final boolean defaultClient;


        /**
         * Create a factory with a default {@link OkHttpClient} instance.
         */
        public OkHttp3ClientHttpRequestFactory() {
            this.client = new OkHttpClient();
            this.defaultClient = true;
        }

        /**
         * Create a factory with the given {@link OkHttpClient} instance.
         * @param client the client to use
         */
        public OkHttp3ClientHttpRequestFactory(OkHttpClient client) {
            Assert.notNull(client, "OkHttpClient must not be null");
            this.client = client;
            this.defaultClient = false;
        }


        /**
         * Set the underlying read timeout in milliseconds.
         * A value of 0 specifies an infinite timeout.
         */
        public void setReadTimeout(int readTimeout) {
            this.client = this.client.newBuilder()
                    .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                    .build();
        }

        /**
         * Set the underlying write timeout in milliseconds.
         * A value of 0 specifies an infinite timeout.
         */
        public void setWriteTimeout(int writeTimeout) {
            this.client = this.client.newBuilder()
                    .writeTimeout(writeTimeout, TimeUnit.MILLISECONDS)
                    .build();
        }

        /**
         * Set the underlying connect timeout in milliseconds.
         * A value of 0 specifies an infinite timeout.
         */
        public void setConnectTimeout(int connectTimeout) {
            this.client = this.client.newBuilder()
                    .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                    .build();
        }


        public ClientHttpRequest createRequest(URI uri, String httpMethod) {
            return null;
        }

        @Override
        public SpringUtil.AsyncClientHttpRequest createAsyncRequest(URI uri, String httpMethod) {
            return new OkHttp3AsyncClientHttpRequest(this.client, uri, httpMethod);
        }


        @Override
        public void destroy() throws IOException {
            if (this.defaultClient) {
                // Clean up the client if we created it in the constructor
                Cache cache = this.client.cache();
                if (cache != null) {
                    cache.close();
                }
                this.client.dispatcher().executorService().shutdown();
                this.client.connectionPool().evictAll();
            }
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

        public String getMethod() {
            return this.method;
        }

        public URI getURI() {
            return this.uri;
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


        private static class OkHttpListenableFuture extends SettableListenableFuture<ClientHttpResponse> {

            private final Call call;

            public OkHttpListenableFuture(Call call) {
                this.call = call;

            }

            @Override
            protected void interruptTask() {
                this.call.cancel();
            }
        }

    }

    static class OkHttp3ClientHttpResponse extends SpringUtil.HttpEntity<InputStream> {

        private final Response response;

        @Nullable
        private volatile SpringUtil.HttpHeaders headers;

        public OkHttp3ClientHttpResponse(Response response) {
            Assert.notNull(response, "Response must not be null");
            this.response = response;
        }

        @Override
        public int getStatus() {
            return this.response.code();
        }

        @Override
        public InputStream getBody() {
            ResponseBody body = this.response.body();
            return (body != null ? body.byteStream() :  new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public SpringUtil.HttpHeaders getHeaders() {
            SpringUtil.HttpHeaders headers = this.headers;
            if (headers == null) {
                headers = new SpringUtil.HttpHeaders();
                for (String headerName : this.response.headers().names()) {
                    for (String headerValue : this.response.headers(headerName)) {
                        headers.computeIfAbsent(headerName,e-> new ArrayList<>())
                                .add(headerValue);
                    }
                }
                this.headers = headers;
            }
            return headers;
        }

        public void close() {
            ResponseBody body = this.response.body();
            if (body != null) {
                body.close();
            }
        }

    }

}

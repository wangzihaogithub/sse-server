package com.github.sseserver.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SpringUtil {

    public static <T> T getBean(String beanName, Class<T> type, ListableBeanFactory beanFactory) {
        if (beanFactory.containsBeanDefinition(beanName)) {
            try {
                Object bean = beanFactory.getBean(beanName);
                return type.isInstance(bean) ? (T) bean : null;
            } catch (Exception e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static AsyncRestTemplate newAsyncRestTemplate(int connectTimeout, int readTimeout,
                                                         int threadsIfAsyncRequest, int threadsIfBlockRequest,
                                                         String threadName,
                                                         String account, String password) {
        String authorization = "Basic " + encodeBasicAuth(account, password, Charset.forName("ISO-8859-1"));
        AsyncClientHttpRequestFactory factory = newAsyncClientHttpRequestFactory(connectTimeout, readTimeout,
                threadsIfAsyncRequest, threadsIfBlockRequest, threadName);
        return new AsyncRestTemplate(factory, authorization);
    }

    public static AsyncClientHttpRequestFactory newAsyncClientHttpRequestFactory(int connectTimeout, int readTimeout,
                                                                                 int threadsIfAsyncRequest, int threadsIfBlockRequest,
                                                                                 String threadName) {
        AsyncClientHttpRequestFactory result;
        String httpRequestFactory = PlatformDependentUtil.getHttpRequestFactory();
        switch (httpRequestFactory) {
            case "apache": {
                result = ApacheHttpUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName);
                break;
            }
            case "netty4": {
                result = NettyUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName);
                break;
            }
            case "okhttp3": {
                result = OkhttpUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName);
                break;
            }
            default:
            case "simple": {
                result = newRequestFactory(connectTimeout, readTimeout, threadsIfBlockRequest, threadName);
                break;
            }
        }
        return result;
    }

    public static AsyncClientHttpRequestFactory newRequestFactory(int connectTimeout, int readTimeout, int maxThreads, String threadName) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(0, maxThreads,
                60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadFactory() {
            private final AtomicInteger id = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                String name = threadName + "-" + id.getAndIncrement();
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        }, (r, executor1) -> {
            if (executor1.isShutdown()) {
                throw new RejectedExecutionException("ThreadPoolExecutor shutdown！");
            } else {
                r.run();
            }
        });

        ClientHttpRequestFactory factory = new ClientHttpRequestFactory(executor);
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    public static String encodeBasicAuth(String username, String password, Charset charset) {
        String credentialsString = username + ":" + password;
        byte[] encodedBytes = Base64.getEncoder().encode(credentialsString.getBytes(charset));
        return new String(encodedBytes, charset);
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

    private static String resolving(String url, Object... uriVariables) {
        if (uriVariables.length == 0) {
            return url;
        }
        StringBuilder sqlBuffer = new StringBuilder(url);
        int urlLength = url.length();
        int beginIndex = 0;
        String beginSymbol = "{";
        String endSymbol = "}";

        int uriVariablesIndex = 0;
        while (true) {
            beginIndex = url.indexOf(beginSymbol, beginIndex);
            if (beginIndex == -1) {
                break;
            }
            beginIndex = beginIndex + beginSymbol.length();
            int endIndex = url.indexOf(endSymbol, beginIndex);

            int offset = urlLength - sqlBuffer.length();
            int offsetBegin = beginIndex - beginSymbol.length() - offset;
            int offsetEnd = endIndex + endSymbol.length() - offset;
            if (uriVariablesIndex >= uriVariables.length) {
                break;
            }
            sqlBuffer.replace(offsetBegin, offsetEnd, String.valueOf(uriVariables[uriVariablesIndex]));
            uriVariablesIndex++;
        }
        return sqlBuffer.toString();
    }

    public interface AsyncClientHttpRequestFactory {
        AsyncClientHttpRequest createAsyncRequest(URI uri, String httpMethod) throws IOException;
    }

    public interface AsyncClientHttpRequest {
        String getMethod();

        HttpHeaders getHeaders();

        OutputStream getBody() throws IOException;

        CompletableFuture<HttpEntity<InputStream>> executeAsync() throws IOException;
    }

    @FunctionalInterface
    public interface AsyncRequestCallback {
        void doWithRequest(AsyncClientHttpRequest request) throws IOException;
    }

    public static class AsyncRestTemplate implements AutoCloseable {
        private final AsyncClientHttpRequestFactory factory;
        private final String authorization;
        private final AtomicBoolean close = new AtomicBoolean(false);
        private final ObjectMapper objectMapper = new ObjectMapper();

        public AsyncRestTemplate(AsyncClientHttpRequestFactory factory, String authorization) {
            this.factory = factory;
            this.authorization = authorization;
        }

        @Override
        public void close() {
            if (this.close.compareAndSet(false, true)) {
                if (factory instanceof DisposableBean) {
                    try {
                        ((DisposableBean) factory).destroy();
                    } catch (Exception ignored) {
                    }
                } else if (factory instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) factory).close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        public <T> CompletableFuture<HttpEntity<T>> getForEntity(String url, Class<T> responseType, Object... uriVariables) {
            ResponseEntityResponseExtractor<T> responseExtractor = new ResponseEntityResponseExtractor<>(responseType, objectMapper);
            String resolvingUrl = resolving(url, uriVariables);
            URI uri = URI.create(resolvingUrl);
            return doExecute(uri, "GET", null, responseExtractor);
        }

        public <T> CompletableFuture<HttpEntity<T>> postForEntity(String url, Object body, Class<T> responseType) {
            AsyncRequestCallback requestCallback = request -> {
                request.getHeaders().put("Content-Type", new ArrayList<>(Collections.singletonList("application/json;charset=UTF-8")));
                if (body == null) {
                    request.getHeaders().setContentLength(0L);
                } else {
                    OutputStream out = request.getBody();
                    objectMapper.writeValue(out, body);
                    out.close();
                }
            };
            ResponseEntityResponseExtractor<T> responseExtractor = new ResponseEntityResponseExtractor<>(responseType, objectMapper);
            URI uri = URI.create(url);
            return doExecute(uri, "POST", requestCallback, responseExtractor);
        }

        protected <T> CompletableFuture<HttpEntity<T>> doExecute(URI url, String method,
                                                                 AsyncRequestCallback requestCallback,
                                                                 ResponseEntityResponseExtractor<T> responseExtractor) {
            CompletableFuture<HttpEntity<T>> bodyFuture = new CompletableFuture<>();
            try {
                AsyncClientHttpRequest request = factory.createAsyncRequest(url, method);
                request.getHeaders().put("Authorization", new ArrayList<>(Collections.singletonList(authorization)));
                request.getHeaders().put("Accept", new ArrayList<>(Collections.singletonList("application/json, application/*+json, text/plain, text/html, */*")));

                if (requestCallback != null) {
                    requestCallback.doWithRequest(request);
                }
                CompletableFuture<HttpEntity<InputStream>> responseFuture = request.executeAsync();
                responseFuture.whenComplete((streamResponse, throwable) -> {
                    if (throwable != null) {
                        bodyFuture.completeExceptionally(throwable);
                    } else {
                        try {
                            HttpEntity<T> bodyResponse = responseExtractor.extractData(streamResponse);
                            bodyFuture.complete(bodyResponse);
                        } catch (IOException e) {
                            bodyFuture.completeExceptionally(e);
                        }
                    }
                    if (streamResponse instanceof AutoCloseable) {
                        try {
                            ((AutoCloseable) streamResponse).close();
                        } catch (Exception ignored) {

                        }
                    }
                });
            } catch (IOException ex) {
                bodyFuture.completeExceptionally(new IOException("I/O error on " + method +
                        " request for \"" + url + "\":" + ex.getMessage(), ex));
            }
            return bodyFuture;
        }

        private static class ResponseEntityResponseExtractor<T> {
            private final ObjectMapper objectMapper;
            private final Class<T> responseType;

            public ResponseEntityResponseExtractor(Class<T> responseType, ObjectMapper objectMapper) {
                this.responseType = responseType;
                this.objectMapper = objectMapper;
            }

            public HttpEntity<T> extractData(HttpEntity<InputStream> response) throws IOException {
                InputStream stream = response.getBody();
                T body = objectMapper.readValue(stream, responseType);
                return new HttpEntity<>(body, response.getHeaders(), response.getStatus());
            }
        }
    }

    public static class ClientHttpRequestFactory implements AsyncClientHttpRequestFactory, DisposableBean {
        private final ThreadPoolExecutor threadPool;
        private Proxy proxy;
        private int connectTimeout = -1;
        private int readTimeout = -1;

        public ClientHttpRequestFactory(ThreadPoolExecutor threadPool) {
            this.threadPool = threadPool;
        }

        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public void setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
        }

        public void setProxy(Proxy proxy) {
            this.proxy = proxy;
        }

        @Override
        public void destroy() {
            if (!threadPool.isShutdown()) {
                threadPool.shutdown();
            }
        }

        @Override
        public AsyncClientHttpRequest createAsyncRequest(URI uri, String httpMethod) throws IOException {
            URL url = uri.toURL();
            URLConnection urlConnection = (proxy != null ? url.openConnection(proxy) : url.openConnection());
            if (!(urlConnection instanceof HttpURLConnection)) {
                throw new IllegalStateException(
                        "HttpURLConnection required for [" + url + "] but got: " + urlConnection);
            }
            HttpURLConnection connection = (HttpURLConnection) urlConnection;
            if (this.connectTimeout >= 0) {
                connection.setConnectTimeout(this.connectTimeout);
            }
            if (this.readTimeout >= 0) {
                connection.setReadTimeout(this.readTimeout);
            }

            boolean mayWrite = ("POST".equals(httpMethod) || "PUT".equals(httpMethod) ||
                    "PATCH".equals(httpMethod) || "DELETE".equals(httpMethod));
            connection.setDoInput(true);
            connection.setInstanceFollowRedirects("GET".equals(httpMethod));
            connection.setDoOutput(mayWrite);
            connection.setRequestMethod(httpMethod);
            return new SimpleBufferingAsyncClientHttpRequest(connection, this.threadPool);
        }
    }

    public static class SimpleBufferingAsyncClientHttpRequest extends AbstractBufferingAsyncClientHttpRequest {
        private final HttpURLConnection connection;
        private final Executor executor;

        SimpleBufferingAsyncClientHttpRequest(HttpURLConnection connection, Executor executor) {
            this.connection = connection;
            this.executor = executor;
        }

        static void addHeaders(HttpURLConnection connection, HttpHeaders headers) {
            String method = connection.getRequestMethod();
            if (method.equals("PUT") || method.equals("DELETE")) {
                if (!StringUtils.hasText(headers.getFirst("Accept"))) {
                    // Avoid "text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2"
                    // from HttpUrlConnection which prevents JSON error response details.
                    headers.put("Accept", new ArrayList<>(Collections.singletonList("*/*")));
                }
            }
            headers.forEach((headerName, headerValues) -> {
                if ("Cookie".equalsIgnoreCase(headerName)) {  // RFC 6265
                    String headerValue = StringUtils.collectionToDelimitedString(headerValues, "; ");
                    connection.setRequestProperty(headerName, headerValue);
                } else {
                    for (String headerValue : headerValues) {
                        String actualHeaderValue = headerValue != null ? headerValue : "";
                        connection.addRequestProperty(headerName, actualHeaderValue);
                    }
                }
            });
        }

        @Override
        public String getMethod() {
            return this.connection.getRequestMethod();
        }

        @Override
        protected CompletableFuture<HttpEntity<InputStream>> executeInternal(HttpHeaders headers, byte[] bufferedOutput) throws IOException {
            CompletableFuture<HttpEntity<InputStream>> future = new CompletableFuture<>();
            this.executor.execute(() -> {
                try {
                    addHeaders(this.connection, headers);
                    // JDK <1.8 doesn't support getOutputStream with HTTP DELETE
                    if ("DELETE".equals(getMethod()) && bufferedOutput.length == 0) {
                        this.connection.setDoOutput(false);
                    }
                    if (this.connection.getDoOutput()) {
                        this.connection.setFixedLengthStreamingMode(bufferedOutput.length);
                    }
                    this.connection.connect();
                    if (this.connection.getDoOutput()) {
                        OutputStream outputStream = this.connection.getOutputStream();
                        outputStream.write(bufferedOutput);
                        outputStream.close();
                    } else {
                        // Immediately trigger the request in a no-output scenario as well
                        this.connection.getResponseCode();
                    }
                    future.complete(new SimpleClientHttpResponse(this.connection));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        }

        final static class SimpleClientHttpResponse extends HttpEntity<InputStream> implements Closeable {
            private final HttpURLConnection connection;
            private HttpHeaders headers;
            private InputStream responseStream;

            SimpleClientHttpResponse(HttpURLConnection connection) {
                this.connection = connection;
            }

            @Override
            public int getStatus() {
                try {
                    return this.connection.getResponseCode();
                } catch (IOException e) {
                    LambdaUtil.sneakyThrows(e);
                    return 0;
                }
            }

            @Override
            public HttpHeaders getHeaders() {
                if (this.headers == null) {
                    this.headers = new HttpHeaders();
                    // Header field 0 is the status line for most HttpURLConnections, but not on GAE
                    String name = this.connection.getHeaderFieldKey(0);
                    if (StringUtils.hasLength(name)) {
                        this.headers.computeIfAbsent(name, e -> new ArrayList<>())
                                .add(connection.getHeaderField(0));
                    }
                    int i = 1;
                    while (true) {
                        name = this.connection.getHeaderFieldKey(i);
                        if (!StringUtils.hasLength(name)) {
                            break;
                        }
                        this.headers.computeIfAbsent(name, e -> new ArrayList<>())
                                .add(connection.getHeaderField(i));
                        i++;
                    }
                }
                return this.headers;
            }

            @Override
            public InputStream getBody() {
                InputStream errorStream = this.connection.getErrorStream();
                try {
                    this.responseStream = (errorStream != null ? errorStream : this.connection.getInputStream());
                    return this.responseStream;
                } catch (IOException e) {
                    LambdaUtil.sneakyThrows(e);
                    return null;
                }
            }

            @Override
            public void close() {
                try {
                    InputStream responseStream = this.responseStream;
                    if (responseStream == null) {
                        getBody();
                        responseStream = this.responseStream;
                    }

                    // drain
                    byte[] buffer = new byte[4096];
                    while ((responseStream.read(buffer)) != -1) {
                    }
                    responseStream.close();
                } catch (Exception ex) {
                    // ignore
                }
            }
        }
    }

    public static abstract class AbstractBufferingAsyncClientHttpRequest implements AsyncClientHttpRequest {
        private final HttpHeaders headers = new HttpHeaders();
        private ByteArrayOutputStream bufferedOutput;
        private boolean executed = false;

        @Override
        public HttpHeaders getHeaders() {
            return this.headers;
        }

        @Override
        public OutputStream getBody() throws IOException {
            if (executed) {
                throw new IllegalStateException("ClientHttpRequest already executed");
            }
            ByteArrayOutputStream bufferedOutput = this.bufferedOutput;
            if (bufferedOutput == null) {
                this.bufferedOutput = bufferedOutput = new ByteArrayOutputStream(1024);
            }
            return bufferedOutput;
        }

        @Override
        public CompletableFuture<HttpEntity<InputStream>> executeAsync() throws IOException {
            if (executed) {
                throw new IllegalStateException("ClientHttpRequest already executed");
            }
            byte[] bytes = this.bufferedOutput == null ? new byte[0] : this.bufferedOutput.toByteArray();
            if (headers.getContentLength() < 0) {
                headers.setContentLength(bytes.length);
            }
            CompletableFuture<HttpEntity<InputStream>> result = executeInternal(headers, bytes);
            this.bufferedOutput = null;
            this.executed = true;
            return result;
        }

        protected abstract CompletableFuture<HttpEntity<InputStream>> executeInternal(
                HttpHeaders headers, byte[] bufferedOutput) throws IOException;
    }

    public static class HttpHeaders extends LinkedCaseInsensitiveMap<List<String>> {
        public long getContentLength() {
            String value = getFirst("Content-Length");
            return (value != null ? Long.parseLong(value) : -1);
        }

        public void setContentLength(long contentLength) {
            ArrayList<String> list = new ArrayList<>();
            list.add(Long.toString(contentLength));
            put("Content-Length", list);
        }

        public String getFirst(String headerName) {
            List<String> list = get(headerName);
            return list == null || list.isEmpty() ? null : list.get(0);
        }
    }

    public static class HttpEntity<T> {
        private int status;
        private HttpHeaders headers;
        private T body;

        public HttpEntity() {
        }

        public HttpEntity(T body, HttpHeaders headers, int status) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public T getBody() {
            return body;
        }

        public int getStatus() {
            return status;
        }

        public HttpHeaders getHeaders() {
            return headers;
        }
    }

}

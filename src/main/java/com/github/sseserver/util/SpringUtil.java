package com.github.sseserver.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureAdapter;
import org.springframework.web.client.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SpringUtil {
    public static final HttpHeaders EMPTY_HEADERS = new HttpHeaders();

    public static class AsyncRestTemplate {
        private final SpringUtil.AsyncClientHttpRequestFactory factory;
        private final AtomicBoolean close = new AtomicBoolean(false);
        private final String authorization;

        private final ObjectMapper objectMapper = new ObjectMapper();

        public AsyncRestTemplate(SpringUtil.AsyncClientHttpRequestFactory factory, String authorization) {
            this.factory = factory;
            this.authorization = authorization;
        }

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

        public <T> CompletableFuture<HttpEntity<T>> getForEntity(String url, Class<T> responseType, Object... uriVariables)
                throws RestClientException {
            AsyncRequestCallback requestCallback = acceptHeaderRequestCallback(responseType);
            ResponseEntityResponseExtractor<T> responseExtractor = new ResponseEntityResponseExtractor<>(responseType, objectMapper);
            URI uri = null;
            return doExecute(uri, "GET", requestCallback, responseExtractor);
        }

        public <T> CompletableFuture<HttpEntity<T>> postForEntity(String url, Object body,
                                                                 Class<T> responseType) throws RestClientException {
            AsyncRequestCallback requestCallback = httpEntityCallback(body, responseType);
            ResponseEntityResponseExtractor<T> responseExtractor = new ResponseEntityResponseExtractor<>(responseType, objectMapper);
            URI uri = null;
            return doExecute(uri, "POST", requestCallback, responseExtractor);
        }

        /**
         * Response extractor for {@link org.springframework.http.HttpEntity}.
         */
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
                return new HttpEntity(body, response.getHeaders(), response.getStatus());
            }
        }

        protected <T> CompletableFuture<HttpEntity<T>> doExecute(URI url, String method,
                                                      AsyncRequestCallback requestCallback,
                                                      ResponseEntityResponseExtractor<T> responseExtractor) throws RestClientException {
            try {
                AsyncClientHttpRequest request = factory.createAsyncRequest(url, method);
                if (requestCallback != null) {
                    requestCallback.doWithRequest(request);
                }
                CompletableFuture<HttpEntity<InputStream>> responseFuture = request.executeAsync();
                CompletableFuture<HttpEntity<T>> bodyFuture = new CompletableFuture<>();
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
                });
                return bodyFuture;
            } catch (IOException ex) {
                throw new ResourceAccessException("I/O error on " + method.name() +
                        " request for \"" + url + "\":" + ex.getMessage(), ex);
            }
        }

        /**
         * Future returned from
         * {@link #doExecute(URI, HttpMethod, AsyncRequestCallback, ResponseExtractor)}.
         */
        private static class ResponseExtractorFuture<T> extends ListenableFutureAdapter<T, ClientHttpResponse> {

            private final HttpMethod method;

            private final URI url;

            @Nullable
            private final ResponseEntityResponseExtractor<T> responseExtractor;

            public ResponseExtractorFuture(HttpMethod method, URI url,
                                           CompletableFuture<HttpEntity<InputStream>> clientHttpResponseFuture,
                                           @Nullable ResponseEntityResponseExtractor<T> responseExtractor) {

                super(clientHttpResponseFuture);
                this.method = method;
                this.url = url;
                this.responseExtractor = responseExtractor;
            }

            @Override
            @Nullable
            protected final T adapt(ClientHttpResponse response) throws ExecutionException {
                try {
                    if (!getErrorHandler().hasError(response)) {
                        logResponseStatus(this.method, this.url, response);
                    } else {
                        handleResponseError(this.method, this.url, response);
                    }
                    return convertResponse(response);
                } catch (Throwable ex) {
                    throw new ExecutionException(ex);
                } finally {
                    response.close();
                }
            }

            @Nullable
            protected T convertResponse(HttpEntity<InputStream> response) throws IOException {
                return (this.responseExtractor != null ? this.responseExtractor.extractData(response).getBody() : null);
            }
        }

    }

    public static AsyncRestTemplate newAsyncRestTemplate(int connectTimeout, int readTimeout,
                                                         int threadsIfAsyncRequest, int threadsIfBlockRequest,
                                                         String threadName,
                                                         String account, String password) {
        String authorization = "Basic " + encodeBasicAuth(account, password, Charset.forName("ISO-8859-1"));
        SpringUtil.AsyncClientHttpRequestFactory factory = newAsyncClientHttpRequestFactory(connectTimeout, readTimeout,
                threadsIfAsyncRequest, threadsIfBlockRequest, threadName);

        AsyncRestTemplate restTemplate = new AsyncRestTemplate(factory, authorization);
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("Authorization", authorization);
            return execution.executeAsync(request, body);
        });
        return restTemplate;
    }

    public static SpringUtil.AsyncClientHttpRequestFactory newAsyncClientHttpRequestFactory(int connectTimeout, int readTimeout,
                                                                                            int threadsIfAsyncRequest, int threadsIfBlockRequest,
                                                                                            String threadName) {
        SpringUtil.AsyncClientHttpRequestFactory result;
        if (PlatformDependentUtil.isSupportApacheHttp()) {
            result = ApacheHttpUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName);
        } else if (PlatformDependentUtil.isSupportNetty4()) {
            result = NettyUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName);
        } else if (PlatformDependentUtil.isSupportOkhttp3()) {
            result = OkhttpUtil.newRequestFactory(connectTimeout, readTimeout, threadsIfAsyncRequest, threadName);
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

            result = factory;
        }
        return result;
    }

    public static void close(AsyncRestTemplate restTemplate) {
        if (restTemplate instanceof AutoCloseable) {
            try {
                ((AutoCloseable) restTemplate).close();
            } catch (Exception ignored) {
            }
        }
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

    public static abstract class AbstractBufferingAsyncClientHttpRequest extends AbstractAsyncClientHttpRequest {

        private ByteArrayOutputStream bufferedOutput = new ByteArrayOutputStream(1024);


        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) throws IOException {
            return this.bufferedOutput;
        }

        @Override
        protected CompletableFuture<HttpEntity<InputStream>>executeInternal(HttpHeaders headers) throws IOException {
            byte[] bytes = this.bufferedOutput.toByteArray();
            if (headers.getContentLength() < 0) {
                headers.setContentLength(bytes.length);
            }
            CompletableFuture<HttpEntity<InputStream>> result = executeInternal(headers, bytes);
            this.bufferedOutput = new ByteArrayOutputStream(0);
            return result;
        }

        /**
         * Abstract template method that writes the given headers and content to the HTTP request.
         *
         * @param headers        the HTTP headers
         * @param bufferedOutput the body content
         * @return the response object for the executed request
         */
        protected abstract CompletableFuture<HttpEntity<InputStream>>  executeInternal(
                HttpHeaders headers, byte[] bufferedOutput) throws IOException;

    }
    public static class HttpHeaders extends LinkedCaseInsensitiveMap<List<String>> {
        public void setContentLength(long contentLength) {
            ArrayList<String> list = new ArrayList<>();
            list.add(Long.toString(contentLength));
            put("Content-Length", list);
        }
        public long getContentLength() {
            String value = getFirst("Content-Length");
            return (value != null ? Long.parseLong(value) : -1);
        }
        public String getFirst(String headerName) {
            List<String> list = get(headerName);
            return list== null || list.isEmpty()?null: list.get(0);
        }
    }
    public static abstract class AbstractAsyncClientHttpRequest implements AsyncClientHttpRequest {

        private final HttpHeaders headers = new HttpHeaders();

        private boolean executed = false;


        @Override
        public final HttpHeaders getHeaders() {
            return (this.executed ? HttpHeaders.readOnlyHttpHeaders(this.headers) : this.headers);
        }

        @Override
        public final OutputStream getBody() throws IOException {
            assertNotExecuted();
            return getBodyInternal(this.headers);
        }

        @Override
        public CompletableFuture<HttpEntity<InputStream>> executeAsync() throws IOException {
            assertNotExecuted();
            CompletableFuture<HttpEntity<InputStream>> result = executeInternal(this.headers);
            this.executed = true;
            return result;
        }

        /**
         * Asserts that this request has not been {@linkplain #executeAsync() executed} yet.
         *
         * @throws IllegalStateException if this request has been executed
         */
        protected void assertNotExecuted() {
            Assert.state(!this.executed, "ClientHttpRequest already executed");
        }


        /**
         * Abstract template method that returns the body.
         *
         * @param headers the HTTP headers
         * @return the body output stream
         */
        protected abstract OutputStream getBodyInternal(HttpHeaders headers) throws IOException;

        /**
         * Abstract template method that writes the given headers and content to the HTTP request.
         *
         * @param headers the HTTP headers
         * @return the response object for the executed request
         */
        protected abstract CompletableFuture<HttpEntity<InputStream>> executeInternal(HttpHeaders headers)
                throws IOException;

    }

    public interface AsyncClientHttpRequestFactory {

        /**
         * Create a new asynchronous {@link AsyncClientHttpRequest} for the specified URI
         * and HTTP method.
         * <p>The returned request can be written to, and then executed by calling
         * {@link AsyncClientHttpRequest#executeAsync()}.
         *
         * @param uri        the URI to create a request for
         * @param httpMethod the HTTP method to execute
         * @return the created request
         * @throws IOException in case of I/O errors
         */
        AsyncClientHttpRequest createAsyncRequest(URI uri, String httpMethod) throws IOException;

    }

    public interface AsyncClientHttpRequest  {
        String getMethod();
        HttpHeaders getHeaders();
        OutputStream getBody() throws IOException;

        /**
         * Return the URI of the request (including a query string if any,
         * but only if it is well-formed for a URI representation).
         * @return the URI of the request (never {@code null})
         */
        URI getURI();
        /**
         * Execute this request asynchronously, resulting in a Future handle.
         * {@link ClientHttpResponse} that can be read.
         *
         * @return the future response result of the execution
         * @throws java.io.IOException in case of I/O errors
         */
        CompletableFuture<HttpEntity<InputStream>> executeAsync() throws IOException;

    }

    public static class HttpEntity<T> {
        private  int status;
        private  HttpHeaders headers;
        private  T body;

        public HttpEntity() {
        }

        public HttpEntity( T body,HttpHeaders headers, int status) {
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
    @FunctionalInterface
    public interface AsyncRequestCallback {

        void doWithRequest(AsyncClientHttpRequest request) throws IOException;

    }

}

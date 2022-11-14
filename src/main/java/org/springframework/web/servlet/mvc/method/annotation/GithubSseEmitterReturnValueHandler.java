package org.springframework.web.servlet.mvc.method.annotation;

import com.github.sseserver.SseEmitter;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.DelegatingServerHttpResponse;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.Assert;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GithubSseEmitterReturnValueHandler implements HandlerMethodReturnValueHandler {
    private final Supplier<Collection<HttpMessageConverter>> messageConverters;

    public GithubSseEmitterReturnValueHandler(Supplier<Collection<HttpMessageConverter>> messageConverters) {
        this.messageConverters = new Lazy(() -> initSseConverters(messageConverters.get()));
    }

    private static Collection<HttpMessageConverter> initSseConverters(Collection<HttpMessageConverter> converters) {
        for (HttpMessageConverter converter : converters) {
            if (converter.canWrite(String.class, MediaType.TEXT_PLAIN)) {
                return new ArrayList<>(converters);
            }
        }
        List<HttpMessageConverter> result = new ArrayList<>(converters.size() + 1);
        result.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
        result.addAll(converters);
        return result;
    }


    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        Class bodyType = ResponseEntity.class.isAssignableFrom(returnType.getParameterType()) ?
                ResolvableType.forMethodParameter(returnType).getGeneric().resolve() :
                returnType.getParameterType();
        return (bodyType != null && (com.github.sseserver.SseEmitter.class.isAssignableFrom(bodyType)));
    }

    @Override
    public void handleReturnValue(Object returnValue, MethodParameter returnType,
                                  ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {
        if (returnValue == null) {
            mavContainer.setRequestHandled(true);
            return;
        }

        HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
        Assert.state(response != null, "No HttpServletResponse");
        ServerHttpResponse outputMessage = new ServletServerHttpResponse(response);

        if (returnValue instanceof ResponseEntity) {
            ResponseEntity responseEntity = (ResponseEntity) returnValue;
            response.setStatus(responseEntity.getStatusCodeValue());
            outputMessage.getHeaders().putAll(responseEntity.getHeaders());
            returnValue = responseEntity.getBody();
            if (returnValue == null) {
                mavContainer.setRequestHandled(true);
                outputMessage.flush();
                return;
            }
        }

        ServletRequest request = webRequest.getNativeRequest(ServletRequest.class);
        Assert.state(request != null, "No ServletRequest");

        ResponseBodyEmitter emitter = (ResponseBodyEmitter) returnValue;
        emitter.extendResponse(outputMessage);

        // At this point we know we're streaming..
        ShallowEtagHeaderFilter.disableContentCaching(request);

        // Wrap the response to ignore further header changes
        // Headers will be flushed at the first write
        outputMessage = new StreamingServletServerHttpResponse(outputMessage);

        HttpMessageConvertingHandler handler;
        try {
            DeferredResult deferredResult = new DeferredResult<>(emitter.getTimeout());
            WebAsyncUtils.getAsyncManager(webRequest).startDeferredResultProcessing(deferredResult, mavContainer);
            handler = new HttpMessageConvertingHandler(outputMessage, deferredResult);
        } catch (Throwable ex) {
            emitter.initializeWithError(ex);
            throw ex;
        }

        emitter.initialize(handler);

        // writeableReady
        if (!handler.isComplete() && emitter instanceof SseEmitter) {
            ((SseEmitter) emitter).writeableReady();
        }
    }


    /**
     * ResponseBodyEmitter.Handler that writes with HttpMessageConverter's.
     */
    private class HttpMessageConvertingHandler implements ResponseBodyEmitter.Handler {
        private boolean complete = false;
        private final ServerHttpResponse outputMessage;

        private final DeferredResult deferredResult;

        public HttpMessageConvertingHandler(ServerHttpResponse outputMessage, DeferredResult deferredResult) {
            this.outputMessage = outputMessage;
            this.deferredResult = deferredResult;
        }

        @Override
        public void send(Object data, MediaType mediaType) throws IOException {
            sendInternal(data, mediaType);
        }

        @SuppressWarnings("unchecked")
        private <T> void sendInternal(T data, MediaType mediaType) throws IOException {
            for (HttpMessageConverter converter : messageConverters.get()) {
                if (converter.canWrite(data.getClass(), mediaType)) {
                    ((HttpMessageConverter<T>) converter).write(data, mediaType, this.outputMessage);
                    this.outputMessage.flush();
                    return;
                }
            }
            throw new IllegalArgumentException("No suitable converter for " + data.getClass());
        }

        public boolean isComplete() {
            return complete;
        }

        @Override
        public void complete() {
            complete = true;
            try {
                this.outputMessage.flush();
                this.deferredResult.setResult(null);
            } catch (IOException ex) {
                this.deferredResult.setErrorResult(ex);
            }
        }

        @Override
        public void completeWithError(Throwable failure) {
            complete = true;
            this.deferredResult.setErrorResult(failure);
        }

        @Override
        public void onTimeout(Runnable callback) {
            this.deferredResult.onTimeout(callback);
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.deferredResult.onError(callback);
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.deferredResult.onCompletion(callback);
        }
    }


    /**
     * Wrap to silently ignore header changes HttpMessageConverter's that would
     * otherwise cause HttpHeaders to raise exceptions.
     */
    private static class StreamingServletServerHttpResponse extends DelegatingServerHttpResponse {

        private final HttpHeaders mutableHeaders = new HttpHeaders();

        public StreamingServletServerHttpResponse(ServerHttpResponse delegate) {
            super(delegate);
            this.mutableHeaders.putAll(delegate.getHeaders());
        }

        @Override
        public HttpHeaders getHeaders() {
            return this.mutableHeaders;
        }

    }

    public static class Lazy implements Supplier<Collection<HttpMessageConverter>> {
        private Supplier<Collection<HttpMessageConverter>> supplier;
        private Collection<HttpMessageConverter> value;
        private volatile boolean resolved = false;

        public Lazy(Supplier<Collection<HttpMessageConverter>> supplier) {
            this.supplier = supplier;
        }

        public Collection<HttpMessageConverter> get() {
            if (resolved) {
                return value;
            }
            this.value = supplier.get();
            this.supplier = null;
            this.resolved = true;
            return value;
        }
    }

}

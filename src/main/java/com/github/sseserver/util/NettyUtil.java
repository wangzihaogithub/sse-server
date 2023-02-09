package com.github.sseserver.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.net.ssl.SSLException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class NettyUtil {

    public static SpringUtil.AsyncClientHttpRequestFactory newRequestFactory(int connectTimeout, int readTimeout, int maxThreads, String threadName) {
        NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(maxThreads, new DefaultThreadFactory(threadName, true));
        Netty4ClientHttpRequestFactory factory = new Netty4ClientHttpRequestFactory(eventLoopGroup);
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    public static class Netty4ClientHttpRequestFactory implements
            SpringUtil.AsyncClientHttpRequestFactory, DisposableBean {
        private final EventLoopGroup eventLoopGroup;
        // 100MB
        private int maxResponseSize = 1024 * 1024 * 100;
        private SslContext sslContext;
        private int connectTimeout = -1;
        private int readTimeout = -1;
        private volatile Bootstrap bootstrap;

        public Netty4ClientHttpRequestFactory(EventLoopGroup eventLoopGroup) {
            this.eventLoopGroup = eventLoopGroup;
        }

        public void setMaxResponseSize(int maxResponseSize) {
            this.maxResponseSize = maxResponseSize;
        }

        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public void setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
        }

        private SslContext getDefaultClientSslContext() {
            try {
                return SslContextBuilder.forClient().build();
            } catch (SSLException ex) {
                throw new IllegalStateException("Could not create default client SslContext", ex);
            }
        }

        @Override
        public SpringUtil.AsyncClientHttpRequest createAsyncRequest(URI uri, String httpMethod) {
            return new Netty4ClientHttpRequest(getBootstrap(uri), uri, httpMethod);
        }

        private Bootstrap getBootstrap(URI uri) {
            boolean isSecure = (uri.getPort() == 443 || "https".equalsIgnoreCase(uri.getScheme()));
            if (isSecure) {
                return buildBootstrap(uri, true);
            } else {
                Bootstrap bootstrap = this.bootstrap;
                if (bootstrap == null) {
                    bootstrap = buildBootstrap(uri, false);
                    this.bootstrap = bootstrap;
                }
                return bootstrap;
            }
        }

        private Bootstrap buildBootstrap(URI uri, boolean isSecure) {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(this.eventLoopGroup).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) throws Exception {
                            SocketChannelConfig config = channel.config();
                            if (connectTimeout >= 0) {
                                config.setConnectTimeoutMillis(connectTimeout);
                            }
                            ChannelPipeline pipeline = channel.pipeline();
                            if (isSecure) {
                                if (sslContext == null) {
                                    sslContext = getDefaultClientSslContext();
                                }
                                pipeline.addLast(sslContext.newHandler(channel.alloc(), uri.getHost(), uri.getPort()));
                            }
                            pipeline.addLast(new HttpClientCodec());
                            pipeline.addLast(new HttpObjectAggregator(maxResponseSize));
                            if (readTimeout > 0) {
                                pipeline.addLast(new ReadTimeoutHandler(readTimeout,
                                        TimeUnit.MILLISECONDS));
                            }
                        }
                    });
            return bootstrap;
        }

        @Override
        public void destroy() {
            this.eventLoopGroup.shutdownGracefully();
        }
    }

    static class Netty4ClientHttpRequest extends SpringUtil.AbstractBufferingAsyncClientHttpRequest {
        private final Bootstrap bootstrap;
        private final URI uri;
        private final String method;
        private final ByteBufOutputStream body;

        public Netty4ClientHttpRequest(Bootstrap bootstrap, URI uri, String method) {
            this.bootstrap = bootstrap;
            this.uri = uri;
            this.method = method;
            this.body = new ByteBufOutputStream(Unpooled.buffer(1024));
        }

        private static int getPort(URI uri) {
            int port = uri.getPort();
            if (port == -1) {
                if ("http".equalsIgnoreCase(uri.getScheme())) {
                    port = 80;
                } else if ("https".equalsIgnoreCase(uri.getScheme())) {
                    port = 443;
                }
            }
            return port;
        }

        @Override
        public ByteBufOutputStream getBody() {
            return body;
        }

        @Override
        protected CompletableFuture<SpringUtil.HttpEntity<InputStream>> executeInternal(SpringUtil.HttpHeaders headers, byte[] bufferedOutput) throws IOException {
            final CompletableFuture<SpringUtil.HttpEntity<InputStream>> responseFuture = new CompletableFuture<>();
            ChannelFutureListener connectionListener = future -> {
                if (future.isSuccess()) {
                    Channel channel = future.channel();
                    channel.pipeline().addLast(new RequestExecuteHandler(responseFuture));
                    FullHttpRequest nettyRequest = createFullHttpRequest(headers);
                    channel.writeAndFlush(nettyRequest);
                } else {
                    responseFuture.completeExceptionally(future.cause());
                }
            };
            this.bootstrap.connect(this.uri.getHost(), getPort(this.uri)).addListener(connectionListener);
            return responseFuture;
        }

        @Override
        public String getMethod() {
            return this.method;
        }

        private FullHttpRequest createFullHttpRequest(SpringUtil.HttpHeaders headers) {
            io.netty.handler.codec.http.HttpMethod nettyMethod =
                    io.netty.handler.codec.http.HttpMethod.valueOf(this.method);

            String authority = this.uri.getRawAuthority();
            String path = this.uri.toString().substring(this.uri.toString().indexOf(authority) + authority.length());
            FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, nettyMethod, path, this.body.buffer());

            nettyRequest.headers().set("Host", this.uri.getHost() + ":" + getPort(this.uri));
            nettyRequest.headers().set("Connection", "close");
            headers.forEach((headerName, headerValues) -> nettyRequest.headers().add(headerName, headerValues));
            if (!nettyRequest.headers().contains("Content-Length") && this.body.buffer().readableBytes() > 0) {
                nettyRequest.headers().set("Content-Length", this.body.buffer().readableBytes());
            }
            return nettyRequest;
        }

        private static class RequestExecuteHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
            private final CompletableFuture<SpringUtil.HttpEntity<InputStream>> responseFuture;

            public RequestExecuteHandler(CompletableFuture<SpringUtil.HttpEntity<InputStream>> responseFuture) {
                this.responseFuture = responseFuture;
            }

            @Override
            protected void channelRead0(ChannelHandlerContext context, FullHttpResponse response) throws Exception {
                this.responseFuture.complete(new Netty4ClientHttpResponse(context, response));
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
                this.responseFuture.completeExceptionally(cause);
            }
        }
    }

    static class Netty4ClientHttpResponse extends SpringUtil.HttpEntity<InputStream> implements Closeable {
        private final ChannelHandlerContext context;
        private final FullHttpResponse nettyResponse;
        private final ByteBufInputStream body;
        private volatile SpringUtil.HttpHeaders headers;

        public Netty4ClientHttpResponse(ChannelHandlerContext context, FullHttpResponse nettyResponse) {
            this.context = context;
            this.nettyResponse = nettyResponse;
            this.body = new ByteBufInputStream(this.nettyResponse.content());
            this.nettyResponse.retain();
        }

        @Override
        public int getStatus() {
            try {
                return this.nettyResponse.getStatus().code();
            } catch (Throwable e) {
                return this.nettyResponse.status().code();
            }
        }

        @Override
        public SpringUtil.HttpHeaders getHeaders() {
            SpringUtil.HttpHeaders headers = this.headers;
            if (headers == null) {
                headers = new SpringUtil.HttpHeaders();
                for (Map.Entry<String, String> entry : this.nettyResponse.headers()) {
                    headers.computeIfAbsent(entry.getKey(), e -> new ArrayList<>())
                            .add(entry.getValue());
                }
                this.headers = headers;
            }
            return headers;
        }

        @Override
        public InputStream getBody() {
            return this.body;
        }

        @Override
        public void close() {
            this.nettyResponse.release();
            this.context.close();
        }
    }

}

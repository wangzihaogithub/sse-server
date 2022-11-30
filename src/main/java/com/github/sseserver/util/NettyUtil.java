package com.github.sseserver.util;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.springframework.http.client.Netty4ClientHttpRequestFactory;

public class NettyUtil {

    public static Netty4ClientHttpRequestFactory newRequestFactory(int connectTimeout, int readTimeout, int maxThreads,String threadName) {
        NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(maxThreads, new DefaultThreadFactory(threadName, true));
        Netty4ClientHttpRequestFactory factory = new Netty4ClientHttpRequestFactory(eventLoopGroup) {
            @Override
            public void destroy() {
                eventLoopGroup.shutdownGracefully();
            }
        };
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        factory.setMaxResponseSize(Integer.MAX_VALUE);
        return factory;
    }
}

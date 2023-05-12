package com.github.sseserver.util;

public class PlatformDependentUtil {
    public static final String SSE_SERVER_VERSION = "1.2.7";
    private static final boolean SUPPORT_NETTY4;
    private static final boolean SUPPORT_OKHTTP3;
    private static final boolean SUPPORT_APACHE_HTTP;
    private static final boolean SUPPORT_SPRINGFRAMEWORK_WEB;

    static {
        boolean supportNetty4;
        try {
            Class.forName("io.netty.channel.ChannelHandler");
            Class.forName("io.netty.handler.codec.http.FullHttpResponse");
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

        boolean supportApacheHttp;
        try {
            Class.forName("org.apache.http.impl.nio.client.HttpAsyncClients");
            supportApacheHttp = true;
        } catch (Throwable e) {
            supportApacheHttp = false;
        }
        SUPPORT_APACHE_HTTP = supportApacheHttp;

        boolean supportSpringframeworkWeb;
        try {
            Class.forName("org.springframework.web.method.support.HandlerMethodReturnValueHandler");
            Class.forName("org.springframework.web.servlet.mvc.method.annotation.SseEmitter");
            Class.forName("javax.servlet.http.HttpServletRequest");
            supportSpringframeworkWeb = true;
        } catch (Throwable e) {
            supportSpringframeworkWeb = false;
        }
        SUPPORT_SPRINGFRAMEWORK_WEB = supportSpringframeworkWeb;
    }

    public static boolean isSupportSpringframeworkWeb() {
        return SUPPORT_SPRINGFRAMEWORK_WEB;
    }

    public static boolean isSupportApacheHttp() {
        return SUPPORT_APACHE_HTTP;
    }

    public static boolean isSupportNetty4() {
        return SUPPORT_NETTY4;
    }

    public static boolean isSupportOkhttp3() {
        return SUPPORT_OKHTTP3;
    }

    public static String getHttpRequestFactory() {
        String httpRequestFactory = System.getProperty("sseserver.PlatformDependentUtil.httpRequestFactory", "auto");
        httpRequestFactory = httpRequestFactory.toLowerCase();
        if ("auto".equals(httpRequestFactory)) {
            if (SUPPORT_APACHE_HTTP) {
                return "apache";
            } else if (SUPPORT_NETTY4) {
                return "netty4";
            } else if (SUPPORT_OKHTTP3) {
                return "okhttp3";
            } else {
                return "simple";
            }
        } else {
            return httpRequestFactory;
        }
    }

}

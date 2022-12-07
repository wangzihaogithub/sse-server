package com.github.sseserver;

import com.github.sseserver.util.WebUtil;

import java.io.IOException;
import java.net.InetSocketAddress;

public class LocalConnectionControllerTest {
    public static void main(String[] args) throws IOException {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(0);

        String b = WebUtil.getQueryParam("a=1&b=w%q", "b");
    }
}

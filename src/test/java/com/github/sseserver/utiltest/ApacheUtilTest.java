package com.github.sseserver.utiltest;

import com.github.sseserver.util.ApacheHttpUtil;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.SpringUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class ApacheUtilTest {

    public static void main(String[] args) throws IOException, InterruptedException {
        SpringUtil.AsyncClientHttpRequestFactory apache = ApacheHttpUtil.newRequestFactory(1000, 1000, 1, "apache");
        SpringUtil.AsyncClientHttpRequest get = apache.createAsyncRequest(URI.create("http://10.0.65.98/nacos/#/serviceManagement?dataId=&group=&appName=&namespace=zhipin-quake-rd&namespaceShowName=zhipin-quake-rd&serviceNameParam=&groupNameParam="), "GET");
        CompletableFuture<SpringUtil.HttpEntity<InputStream>> future = get.executeAsync();
        future.whenComplete((inputStreamHttpEntity, throwable) -> {
            SpringUtil.HttpHeaders headers = inputStreamHttpEntity.getHeaders();

            System.out.println("inputStreamHttpEntity = " + inputStreamHttpEntity);
        });

        Thread.currentThread().join();
        System.out.println("future = " + future);
    }
}

package com.github.sseserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class SseServerApplicationTests {

    public static void main(String[] args) {
        SpringApplication.run(SseServerApplicationTests.class, args);
    }

    @Bean
    public LocalConnectionService localConnectionService() {
        return new LocalConnectionServiceImpl();
    }

    /**
     * http://localhost:8080/api/sse/connect
     */
    @RestController
    @RequestMapping("/api/sse")
    public static class MyController extends SseWebController {
        @Override
        protected AccessUser getAccessUser() {
            return super.getAccessUser();
        }
    }

}
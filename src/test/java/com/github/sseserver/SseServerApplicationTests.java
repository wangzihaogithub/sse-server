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
        /**
         * 获取当前登录用户
         *
         * @return 自己业务系统的登录连接令牌
         */
        @Override
        protected AccessUser getAccessUser() {
            MyAccessUser user = new MyAccessUser();
            user.setId(1);
            user.setName("hao");
            user.setAccessToken("ak123456");
            return user;
        }
    }

    public static class MyAccessUser implements AccessUser {
        private String accessToken;
        private Integer id;
        private String name;

        @Override
        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        @Override
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }
    }
}
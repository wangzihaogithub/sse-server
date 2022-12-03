package com.github.sseserver;

import com.github.netty.springboot.EnableNettyEmbedded;
import com.github.sseserver.local.LocalConnectionService;
import com.github.sseserver.local.LocalConnectionServiceImpl;
import com.github.sseserver.local.SseWebController;
import com.github.sseserver.remote.DistributedConnectionService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@EnableScheduling
@EnableNettyEmbedded
@SpringBootApplication
public class SseServerApplicationTests {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(SseServerApplicationTests.class, args);
        new Thread("aa"){
            @Override
            public void run() {
                while (true) {

                    try {
                        Thread.sleep(1000);
                        DistributedConnectionService service = context.getBean(DistributedConnectionService.class);
                        boolean online = service.isOnline(1);
                        System.out.println("online = " + online);

                    } catch (Exception e) {
                        e.printStackTrace();
                        ;
                    }
                }
            }
        }.start();
    }

    @Bean
    public LocalConnectionService localConnectionService() {
        LocalConnectionServiceImpl service = new LocalConnectionServiceImpl();

        service.addListeningChangeWatch(event -> {
            service.getScheduled().schedule(() -> {
                event.getAfter().removeAll(event.getBefore());
                for (String s : event.getAfter()) {
                    service.atLeastOnce().sendAllListening(s, event.getEventName());
                }

                List<Object> users = service.distributed().getUsers();
                System.out.println("users = " + users);
            }, 3000, TimeUnit.MILLISECONDS);
        });
        return service;
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

    public static class MyAccessUser implements AccessUser, AccessToken {
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
        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        @Override
        public String getName() {
            return name;
        }


        public void setName(String name) {
            this.name = name;
        }
    }
}
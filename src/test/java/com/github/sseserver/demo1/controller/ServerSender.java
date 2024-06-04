package com.github.sseserver.demo1.controller;

import com.github.sseserver.DistributedConnectionService;
import com.github.sseserver.springboot.SseConnectionServiceMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class ServerSender {
    @Autowired
    private DistributedConnectionService c1;
    @Autowired
    private DistributedConnectionService c2;
//    @Resource
//    private DistributedConnectionService localConnectionServicec1;
    @Autowired
    private SseConnectionServiceMap sseConnectionServiceMap;

    @PostConstruct
    public void init() {
        new ScheduledThreadPoolExecutor(1)
                .scheduleWithFixedDelay(() -> {
                    DistributedConnectionService f = sseConnectionServiceMap.getDistributed("c1");

                    // 每5秒发送消息
                    List<MyAccessUser> users = c2.getCluster().getUsers();
                    List<String> userIdList = users.stream().map(MyAccessUser::getId).collect(Collectors.toList());
                    for (MyAccessUser user : users) {
                        c2.qos().sendByUserId(user.getId(),
                                "server-push", "{\"name\":\"ServerSender#sendByUserId（" + userIdList + "） 服务端推送的，当前用户ID数量为" + userIdList.size() + "\"}");
                    }
                }, 1, 3, TimeUnit.SECONDS);
    }

}

package com.github.sseserver.demo1.controller;

import com.github.sseserver.local.LocalConnectionService;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class ServerSender {
    @Resource
    private LocalConnectionService localConnectionService;

    @PostConstruct
    public void init() {
        new ScheduledThreadPoolExecutor(1)
                .scheduleWithFixedDelay(() -> {
                    // 每5秒发送消息
                    List<MyAccessUser> users = localConnectionService.getCluster().getUsers();
                    List<String> userIdList = users.stream().map(MyAccessUser::getId).collect(Collectors.toList());
                    for (MyAccessUser user : users) {
                        localConnectionService.sendByUserId(user.getId(),
                                "server-push", "{\"name\":\"ServerSender#sendByUserId（" + userIdList + "） 服务端推送的，当前用户ID数量为" + userIdList.size() + "\"}");
                    }
                }, 1, 3, TimeUnit.SECONDS);
    }

}

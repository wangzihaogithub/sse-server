package com.github.sseserver;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单机长连接(非分布式)
 * 1. 如果用nginx代理, 要加下面的配置
 * # 长连接配置
 * proxy_buffering off;
 * proxy_read_timeout 7200s;
 * proxy_pass http://xx.xx.xx.xx:xxx;
 * proxy_http_version 1.1; #nginx默认是http1.0, 改为1.1 支持长连接, 和后端保持长连接,复用,防止出现文件句柄打开数量过多的错误
 * proxy_set_header Connection ""; # 去掉Connection的close字段
 *
 * @author hao 2021年12月7日19:27:41
 */
public interface LocalConnectionService {

    /**
     * 创建用户连接并返回 SseEmitter
     *
     * @param accessUser    用户令牌
     * @param keepaliveTime 链接最大保持时间 ，0表示不过期。默认30秒，超过时间未完成会抛出异常：AsyncRequestTimeoutException
     * @return SseEmitter
     */
    <ACCESS_USER extends AccessUser> SseEmitter<ACCESS_USER> connect(ACCESS_USER accessUser, Long keepaliveTime);

    <ACCESS_USER extends AccessUser> void addConnectListener(String accessToken, String channel, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser> void addConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser> void addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser> void addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser> void addDisConnectListener(String accessToken, Consumer<SseEmitter<ACCESS_USER>> consumer);

    <ACCESS_USER extends AccessUser> int send(SseEmitter<ACCESS_USER> sseEmitter, SseEventBuilder message);

    /**
     * 给指定链接发送信息
     */
    int send(long connectionId, SseEventBuilder message);

    /**
     * 给指定管道发送信息
     */
    int sendToChannel(String channel, SseEventBuilder message);

    /**
     * 给指定用户发送信息
     */
    int send(String accessToken, SseEventBuilder message);

    /**
     * 群发消息
     *
     * @return 发送成功几个人
     */
    int send(Collection<String> accessTokens, SseEventBuilder message);

    /**
     * 群发所有人
     */
    int sendAll(SseEventBuilder message);

    /**
     * 移除用户连接
     *
     * @return 移除了几个链接
     */
    <ACCESS_USER extends AccessUser> List<SseEmitter<ACCESS_USER>> disconnect(String accessToken);

    /**
     * 移除用户连接
     *
     * @return 是否成功
     */
    <ACCESS_USER extends AccessUser> SseEmitter<ACCESS_USER> disconnect(String accessToken, Long connectionId);

    /**
     * 获取当前连接信息
     */
    List<String> getAccessTokens();

    /**
     * 获取当前用户数量
     */
    int getUserCount();

    /**
     * 获取当前连接数量
     */
    int getConnectionCount();

}
package com.github.sseserver;

/**
 * 当前登录用户
 *
 * @author hao 2021年12月13日13:48:58
 */
public interface AccessUser {

    /**
     * 防止循环调用 或 NULL值穿透
     */
    AccessUser NULL = () -> "";

    /**
     * 使用者自己业务系统的登录连接令牌
     *
     * @return
     */
    String getAccessToken();

}

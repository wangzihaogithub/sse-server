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
    AccessUser NULL = new AccessUser() {
        @Override
        public String getAccessToken() {
            return null;
        }

        @Override
        public String getName() {
            return null;
        }

        @Override
        public Integer getId() {
            return null;
        }

    };

    /**
     * 使用者自己业务系统的登录连接令牌
     *
     * @return 自己业务系统的登录连接令牌
     */
    String getAccessToken();

    String getName();

    Integer getId();

}

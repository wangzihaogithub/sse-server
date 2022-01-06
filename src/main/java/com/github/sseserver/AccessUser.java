package com.github.sseserver;

/**
 * 当前登录用户
 *
 * @author hao 2021年12月13日13:48:58
 */
public interface AccessUser {
    String getName();

    String getRole();

    Integer getId();

    default boolean isBySystemOperate() {
        return false;
    }
}

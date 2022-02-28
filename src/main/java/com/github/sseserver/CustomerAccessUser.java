package com.github.sseserver;

/**
 * 客户作为租户数据隔离的用户
 *
 * @author hao 2021年12月13日13:48:58
 */
public interface CustomerAccessUser extends AccessUser {

    Object getCustomerId();

}

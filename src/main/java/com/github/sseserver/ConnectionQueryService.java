package com.github.sseserver;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public interface ConnectionQueryService {

    /* getUser */

    boolean isOnline(Serializable userId);

    <ACCESS_USER> ACCESS_USER getUser(Serializable userId);

    <ACCESS_USER> List<ACCESS_USER> getUsers();

    <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName);

    <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName);

    /* getUserIds */

    <T> Collection<T> getUserIds(Class<T> type);

    <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type);

    <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type);

    /* getAccessToken */

    Collection<String> getAccessTokens();

    /* getTenantId */

    <T> List<T> getTenantIds(Class<T> type);

    /* getChannels */

    List<String> getChannels();

    /* count */

    /**
     * 获取当前登录端数量
     */
    int getAccessTokenCount();

    /**
     * 获取当前用户数量
     */
    int getUserCount();

    /**
     * 获取当前连接数量
     */
    int getConnectionCount();

}

package com.github.sseserver;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;

/**
 * 推送消息接口
 *
 * @author wangzihaogithub 2022-11-12
 */
public interface SendService<RESPONSE> {

    /* All */

    <T> T scopeOnWriteable(Callable<T> runnable);

    RESPONSE sendAll(String eventName, Object body);

    RESPONSE sendAllListening(String eventName, Object body);

    /* Channel */

    RESPONSE sendByChannel(Collection<String> channels, String eventName, Object body);

    RESPONSE sendByChannelListening(Collection<String> channels, String eventName, Object body);

    default RESPONSE sendByChannel(String channel, String eventName, Object body) {
        return sendByChannel(Collections.singletonList(channel), eventName, body);
    }

    default RESPONSE sendByChannelListening(String channel, String eventName, Object body) {
        return sendByChannelListening(Collections.singletonList(channel), eventName, body);
    }

    /* AccessToken */

    RESPONSE sendByAccessToken(Collection<String> accessTokens, String eventName, Object body);

    RESPONSE sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Object body);

    default RESPONSE sendByAccessToken(String accessToken, String eventName, Object body) {
        return sendByAccessToken(Collections.singletonList(accessToken), eventName, body);
    }

    default RESPONSE sendByAccessTokenListening(String accessToken, String eventName, Object body) {
        return sendByAccessTokenListening(Collections.singletonList(accessToken), eventName, body);
    }

    /* UserId */

    RESPONSE sendByUserId(Collection<? extends Serializable> userIds, String eventName, Object body);

    RESPONSE sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Object body);

    default RESPONSE sendByUserId(Serializable userId, String eventName, Object body) {
        return sendByUserId(Collections.singletonList(userId), eventName, body);
    }

    default RESPONSE sendByUserIdListening(Serializable userId, String eventName, Object body) {
        return sendByUserIdListening(Collections.singletonList(userId), eventName, body);
    }

    /* TenantId */

    RESPONSE sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Object body);

    RESPONSE sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Object body);

    default RESPONSE sendByTenantId(Serializable tenantId, String eventName, Object body) {
        return sendByTenantId(Collections.singletonList(tenantId), eventName, body);
    }

    default RESPONSE sendByTenantIdListening(Serializable tenantId, String eventName, Object body) {
        return sendByTenantIdListening(Collections.singletonList(tenantId), eventName, body);
    }


}

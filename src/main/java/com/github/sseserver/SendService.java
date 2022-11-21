package com.github.sseserver;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;

/**
 * 推送消息接口
 *
 * @author wangzihaogithub 2022-11-12
 */
public interface SendService<RESPONSE> {

    /* All */

    RESPONSE sendAll(String eventName, Serializable body);

    RESPONSE sendAllListening(String eventName, Serializable body);

    /* Channel */

    RESPONSE sendByChannel(Collection<String> channels, String eventName, Serializable body);

    RESPONSE sendByChannelListening(Collection<String> channels, String eventName, Serializable body);

    default RESPONSE sendByChannel(String channel, String eventName, Serializable body) {
        return sendByChannel(Collections.singletonList(channel), eventName, body);
    }

    default RESPONSE sendByChannelListening(String channel, String eventName, Serializable body) {
        return sendByChannelListening(Collections.singletonList(channel), eventName, body);
    }

    /* AccessToken */

    RESPONSE sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body);

    RESPONSE sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body);

    default RESPONSE sendByAccessToken(String accessToken, String eventName, Serializable body) {
        return sendByAccessToken(Collections.singletonList(accessToken), eventName, body);
    }

    default RESPONSE sendByAccessTokenListening(String accessToken, String eventName, Serializable body) {
        return sendByAccessTokenListening(Collections.singletonList(accessToken), eventName, body);
    }

    /* UserId */

    RESPONSE sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body);

    RESPONSE sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body);

    default RESPONSE sendByUserId(Serializable userId, String eventName, Serializable body) {
        return sendByUserId(Collections.singletonList(userId), eventName, body);
    }

    default RESPONSE sendByUserIdListening(Serializable userId, String eventName, Serializable body) {
        return sendByUserIdListening(Collections.singletonList(userId), eventName, body);
    }

    /* TenantId */

    RESPONSE sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body);

    RESPONSE sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body);

    default RESPONSE sendByTenantId(Serializable tenantId, String eventName, Serializable body) {
        return sendByTenantId(Collections.singletonList(tenantId), eventName, body);
    }

    default RESPONSE sendByTenantIdListening(Serializable tenantId, String eventName, Serializable body) {
        return sendByTenantIdListening(Collections.singletonList(tenantId), eventName, body);
    }


}

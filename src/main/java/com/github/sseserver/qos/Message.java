package com.github.sseserver.qos;

import java.io.Serializable;
import java.util.Collection;
import java.util.UUID;

public interface Message extends Serializable {
    int FILTER_TENANT_ID = (1 << 1);
    int FILTER_ACCESS_TOKEN = (1 << 2);
    int FILTER_USER_ID = (1 << 3);
    int FILTER_LISTENER_NAME = (1 << 4);
    int FILTER_CHANNEL = (1 << 5);

    String getListenerName();
    Collection<? extends Serializable> getUserIdList();
    Collection<? extends Serializable> getTenantIdList();
    Collection<String> getAccessTokenList();
    Collection<String> getChannelList();

    Serializable getBody();
    String getEventName();
    String getId();
    void setId(String id);

    static String newId(){
        return UUID.randomUUID().toString();
    }

    int getFilters();
    default boolean isFilter(int filter) {
        return (getFilters() & filter) != 0;
    }

}
package com.github.sseserver.qos;

import com.github.sseserver.util.AutoTypeBean;

import java.io.Serializable;
import java.util.Collection;

public class AtLeastOnceMessage extends AutoTypeBean implements Message {
    private String id;

    private String eventName;
    private Object body;
    private int filters;

    private String listenerName;
    private Collection<? extends Serializable> tenantIdList;
    private Collection<? extends Serializable> userIdList;
    private Collection<String> accessTokenList;
    private Collection<String> channelList;

    public AtLeastOnceMessage() {
    }

    public AtLeastOnceMessage(String eventName, Object body, int filters) {
        this.eventName = eventName;
        this.filters = filters;
        this.body = body;
        retainClassName(body);
    }

    @Override
    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    @Override
    public int getFilters() {
        return filters;
    }

    public void setFilters(int filters) {
        this.filters = filters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message)) {
            return false;
        }
        Message that = (Message) o;
        if (id == null) {
            return false;
        } else {
            return id.equals(that.getId());
        }
    }

    @Override
    public int hashCode() {
        return id == null ? super.hashCode() : id.hashCode();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public Object getBody() {
        return body;
    }

    public void setBody(Object body) {
        this.body = body;
    }

    @Override
    public String getListenerName() {
        return listenerName;
    }

    public void setListenerName(String listenerName) {
        this.listenerName = listenerName;
    }

    @Override
    public Collection<? extends Serializable> getTenantIdList() {
        return tenantIdList;
    }

    public void setTenantIdList(Collection<? extends Serializable> tenantIdList) {
        this.tenantIdList = tenantIdList;
    }

    @Override
    public Collection<? extends Serializable> getUserIdList() {
        return userIdList;
    }

    public void setUserIdList(Collection<? extends Serializable> userIdList) {
        this.userIdList = userIdList;
    }

    @Override
    public Collection<String> getAccessTokenList() {
        return accessTokenList;
    }

    public void setAccessTokenList(Collection<String> accessTokenList) {
        this.accessTokenList = accessTokenList;
    }

    @Override
    public Collection<String> getChannelList() {
        return channelList;
    }

    public void setChannelList(Collection<String> channelList) {
        this.channelList = channelList;
    }
}

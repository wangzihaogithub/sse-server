package com.github.sseserver.qos.impl;

import com.github.sseserver.qos.Message;

import java.io.Serializable;
import java.util.Collection;

public class AtLeastOnceMessage implements Message {
    private String id;

    private String eventName;
    private Serializable body;
    private int filters;

    private String listenerName;
    private Collection<? extends Serializable> tenantIdList;
    private Collection<? extends Serializable> userIdList;
    private Collection<String> accessTokenList;
    private Collection<String> channelList;

    public AtLeastOnceMessage() {
    }

    public AtLeastOnceMessage(String eventName, Serializable body, int filters) {
        this.eventName = eventName;
        this.body = body;
        this.filters = filters;
    }

    @Override
    public String getEventName() {
        return eventName;
    }

    @Override
    public int getFilters() {
        return filters;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public Serializable getBody() {
        return body;
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

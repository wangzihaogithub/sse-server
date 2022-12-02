package com.github.sseserver.qos;

import java.io.Serializable;
import java.util.*;

public class MemoryMessageRepository implements MessageRepository {
    protected final Map<String, Message> messageMap = Collections.synchronizedMap(new LinkedHashMap<>(6));

    @Override
    public String insert(Message message) {
        String id = newId();
        messageMap.put(id, message);
        message.setId(id);
        return id;
    }

    @Override
    public List<Message> select(Query query) {
        if (messageMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<Message> list = new ArrayList<>(2);
        for (Message message : messageMap.values()) {
            if (match(query, message)) {
                list.add(message);
            }
        }
        return list;
    }

    @Override
    public boolean delete(String id) {
        if (id != null) {
            return messageMap.remove(id) != null;
        } else {
            return false;
        }
    }

    @Override
    public void close() {
        messageMap.clear();
    }

    protected boolean match(Query query, Message message) {
        if (message.isFilter(Message.FILTER_TENANT_ID)
                && !exist(query.getTenantId(), message.getTenantIdList())) {
            return false;
        }
        if (message.isFilter(Message.FILTER_CHANNEL)
                && !exist(query.getChannel(), message.getChannelList())) {
            return false;
        }
        if (message.isFilter(Message.FILTER_ACCESS_TOKEN)
                && !exist(query.getAccessToken(), message.getAccessTokenList())) {
            return false;
        }
        if (message.isFilter(Message.FILTER_USER_ID)
                && !exist(query.getUserId(), message.getUserIdList())) {
            return false;
        }
        if (message.isFilter(Message.FILTER_LISTENER_NAME)
                && !query.existListener(message.getListenerName())) {
            return false;
        }
        return true;
    }

    protected String newId() {
        return Message.newId();
    }

    protected boolean exist(Serializable v1, Collection<? extends Serializable> v2) {
        for (Serializable v : v2) {
            if (equals(v1, v)) {
                return true;
            }
        }
        return false;
    }

    protected boolean equals(Serializable v1, Serializable v2) {
        if (v1 == v2) {
            return true;
        }
        if (v1 == null || v2 == null) {
            return false;
        }
        if (v1.getClass() == v2.getClass()) {
            return v1.equals(v2);
        } else {
            return v1.toString().equals(v2.toString());
        }
    }

}

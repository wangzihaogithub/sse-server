package com.github.sseserver.qos;

import java.io.Serializable;
import java.util.*;
import java.util.function.Consumer;

public class MemoryMessageRepository implements MessageRepository {
    public int maxThresholdSize = Integer.getInteger("sseserver.MemoryMessageRepository.maxThresholdSize",
            1000);
    protected final Map<String, Message> messageMap = Collections.synchronizedMap(new LinkedHashMap<String, Message>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > maxThresholdSize;
        }
    });
    protected final List<Consumer<Message>> deleteListenerList = new LinkedList<>();

    @Override
    public String insert(Message message) {
        String id = message.getId();
        messageMap.put(id, message);
        return id;
    }

    @Override
    public List<Message> list() {
        return new ArrayList<>(messageMap.values());
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
    public Message delete(String id) {
        if (id != null) {
            Message remove = messageMap.remove(id);
            if (remove != null) {
                for (Consumer<Message> messageConsumer : deleteListenerList) {
                    messageConsumer.accept(remove);
                }
            }
            return remove;
        } else {
            return null;
        }
    }

    @Override
    public void close() {
        messageMap.clear();
    }

    @Override
    public void addDeleteListener(Consumer<Message> listener) {
        deleteListenerList.add(listener);
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

    public int getMaxThresholdSize() {
        return maxThresholdSize;
    }

    public void setMaxThresholdSize(int maxThresholdSize) {
        this.maxThresholdSize = maxThresholdSize;
    }
}

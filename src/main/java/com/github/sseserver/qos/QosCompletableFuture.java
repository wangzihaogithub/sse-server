package com.github.sseserver.qos;

import java.util.concurrent.CompletableFuture;

public class QosCompletableFuture<ACCESS_USER> extends CompletableFuture<Delivered<ACCESS_USER>> {
    /**
     * 消息ID
     * {@link Message#newId()}
     */
    private String id;
    private final Delivered<ACCESS_USER> delivered;

    public QosCompletableFuture() {
        delivered = new Delivered<>();
        delivered.setStartTimestamp(System.currentTimeMillis());
    }

    public String getMessageId() {
        return id;
    }

    public void setMessageId(String id) {
        this.id = id;
    }

    public Delivered<ACCESS_USER> getDelivered() {
        return delivered;
    }

}

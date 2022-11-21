package com.github.sseserver.qos;

import java.util.concurrent.CompletableFuture;

public class QosCompletableFuture<T> extends CompletableFuture<T> {
    /**
     * 消息ID
     * {@link Message#newId()}
     */
    private String messageId;
    private long startTimestamp = System.currentTimeMillis();
    private long endTimestamp;

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public long getCostMs() {
        return endTimestamp - startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }
}

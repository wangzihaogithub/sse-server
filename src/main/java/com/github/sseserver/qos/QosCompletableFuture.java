package com.github.sseserver.qos;

import com.github.sseserver.util.CompletableFuture;

public class QosCompletableFuture<T> extends CompletableFuture<T> {
    /**
     * 消息ID
     * {@link Message#newId()}
     */
    private String messageId;

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

}

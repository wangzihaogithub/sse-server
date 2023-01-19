package com.github.sseserver.qos;

import com.github.sseserver.util.CompletableFuture;

import java.util.Objects;

public class QosCompletableFuture<T> extends CompletableFuture<T> {
    /**
     * 消息ID
     * {@link Message#newId(String, String)}}
     */
    private final String messageId;

    public QosCompletableFuture(String messageId) {
        this.messageId = Objects.requireNonNull(messageId);
        setExceptionallyPrefix("at qos messageId :" + messageId);
    }

    public String getMessageId() {
        return messageId;
    }

}

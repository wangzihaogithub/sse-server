package com.github.sseserver.qos;

import com.github.sseserver.SseEmitter;

import java.util.List;

public interface MessageRepository extends AutoCloseable {
    String insert(Message message);

    <ACCESS_USER> List<Message> select(SseEmitter<ACCESS_USER> query);

    void delete(String id);

    default void close() {

    }
}

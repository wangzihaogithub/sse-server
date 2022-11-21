package com.github.sseserver.qos;

import com.github.sseserver.local.SseEmitter;

import java.util.List;

public interface MessageRepository extends AutoCloseable {
    /**
     * 存储
     *
     * @param message message
     * @return messageID
     */
    String insert(Message message);

    /**
     * 查询满足条件的消息
     *
     * @param query         条件
     * @param <ACCESS_USER> ACCESS_USER
     * @return 满足条件的消息
     */
    <ACCESS_USER> List<Message> select(SseEmitter<ACCESS_USER> query);

    /**
     * 删除
     *
     * @param id messageID
     * @return true=删除成功
     */
    boolean delete(String id);

    default void close() {

    }
}

package com.github.sseserver.qos;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public interface MessageRepository extends AutoCloseable {
    /**
     * 存储
     *
     * @param message message
     * @return messageID
     */
    String insert(Message message);

    /**
     * 返回全部的消息
     *
     * @return 全部的消息
     */
    List<Message> list();

    /**
     * 查询满足条件的消息
     *
     * @param query 条件
     * @return 满足条件的消息
     */
    List<Message> select(Query query);

    /**
     * 删除
     *
     * @param id messageID
     * @return true=删除成功
     */
    Message delete(String id);

    default void close() {

    }

    void addDeleteListener(Consumer<Message> listener);

    interface Query {
        Serializable getTenantId();

        String getChannel();

        String getAccessToken();

        Serializable getUserId();

        Set<String> getListeners();

        default boolean existListener(String listener) {
            Set<String> listeners = getListeners();
            return listeners != null && listeners.contains(listener);
        }
    }
}

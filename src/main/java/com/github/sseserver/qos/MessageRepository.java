package com.github.sseserver.qos;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

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
    boolean delete(String id);

    default void close() {

    }

    interface Query {
        Serializable getTenantId();

        String getChannel();

        String getAccessToken();

        Serializable getUserId();

        Set<String> getListeners();

        default boolean existListener(String listener) {
            return getListeners().contains(listener);
        }
    }
}

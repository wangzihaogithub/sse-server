package com.github.sseserver;

import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.qos.QosCompletableFuture;
import com.github.sseserver.remote.ClusterConnectionService;
import com.github.sseserver.remote.ClusterMessageRepository;
import com.github.sseserver.remote.ServiceDiscoveryService;

public interface DistributedConnectionService {

    /**
     * QOS 保证发送质量接口，支持分布式
     * 目前实现的级别是至少收到一次 {@link com.github.sseserver.qos.AtLeastOnceSendService}
     *
     * @return 消息保障，至少收到一次,Integer必定大于0，Integer 是成功发送的连接数量
     */
    SendService<QosCompletableFuture<Integer>> qos();

    MessageRepository getLocalMessageRepository();

    /* distributed 分布式接口 */

    boolean isEnableCluster();

    ClusterConnectionService getCluster();

    ServiceDiscoveryService getDiscovery();

    ClusterMessageRepository getClusterMessageRepository();

    /**
     * 可以在spring里多实例 （例如：HR系统的用户链接，猎头系统的用户链接）
     *
     * @return spring的bean名称
     */
    String getBeanName();
}

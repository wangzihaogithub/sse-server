package com.github.sseserver.remote;

import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.KeepaliveSocket;
import com.github.sseserver.util.ReferenceCounted;
import com.github.sseserver.util.SnowflakeIdWorker;
import com.github.sseserver.util.SpringUtil;
import com.sun.net.httpserver.HttpPrincipal;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RedisServiceDiscoveryService implements ServiceDiscoveryService, DisposableBean {
    public static final String DEVICE_ID = String.valueOf(SnowflakeIdWorker.INSTANCE.nextId());
    private static final int TEST_SOCKET_TIMEOUT = Integer.getInteger("sseserver.RedisServiceDiscoveryService.testSocketTimeout", 150);
    private static volatile ScheduledExecutorService scheduled;
    private static final int MIN_REDIS_INSTANCE_EXPIRE_SEC = 2;

    private final int redisInstanceExpireSec;
    private final byte[] keySubBytes;
    private final byte[] keyPubSubBytes;
    private final byte[] keyPubUnsubBytes;
    private final byte[] keySetBytes;
    private final ScanOptions keySetScanOptions;
    private final MessageListener messageListener;
    private final Jackson2JsonRedisSerializer<ServerInstance> instanceSerializer = new Jackson2JsonRedisSerializer<>(ServerInstance.class);
    private final SseServerProperties.Remote remoteConfig;
    private final RedisTemplate<byte[], byte[]> redisTemplate = new RedisTemplate<>();
    private final ServerInstance instance = new ServerInstance();

    private long heartbeatCount;
    private byte[] instanceBytes;
    private Map<String, ServerInstance> instanceMap = new LinkedHashMap<>();
    private ScheduledFuture<?> heartbeatScheduledFuture;

    private boolean destroy;
    private volatile ReferenceCounted<List<RemoteConnectionService>> connectionServiceListRef = new ReferenceCounted<>(Collections.emptyList());
    private volatile ReferenceCounted<List<RemoteMessageRepository>> messageRepositoryListRef = new ReferenceCounted<>(Collections.emptyList());

    public RedisServiceDiscoveryService(Object redisConnectionFactory,
                                        String groupName,
                                        String redisKeyRootPrefix,
                                        int redisInstanceExpireSec,
                                        SseServerProperties.Remote remoteConfig) {
        String shortGroupName = String.valueOf(Math.abs(groupName.hashCode()));
        if (shortGroupName.length() <= groupName.length()) {
            shortGroupName = groupName;
        }
        String account = SpringUtil.filterNonAscii(shortGroupName + "-" + DEVICE_ID);
        this.instance.setDeviceId(DEVICE_ID);
        this.instance.setAccount(account);
        this.instance.setPassword(UUID.randomUUID().toString().replace("-", ""));

        this.redisInstanceExpireSec = Math.max(redisInstanceExpireSec, MIN_REDIS_INSTANCE_EXPIRE_SEC);
        this.remoteConfig = remoteConfig;
        StringRedisSerializer keySerializer = StringRedisSerializer.UTF_8;
        this.keyPubSubBytes = keySerializer.serialize(redisKeyRootPrefix + shortGroupName + ":c:sub");
        this.keyPubUnsubBytes = keySerializer.serialize(redisKeyRootPrefix + shortGroupName + ":c:unsub");
        this.keySubBytes = keySerializer.serialize(redisKeyRootPrefix + shortGroupName + ":c:*");
        this.keySetBytes = keySerializer.serialize(redisKeyRootPrefix + shortGroupName + ":d:" + DEVICE_ID);
        this.keySetScanOptions = ScanOptions.scanOptions()
                .count(20)
                .match(redisKeyRootPrefix + shortGroupName + ":d:*")
                .build();

        this.messageListener = (message, pattern) -> {
            if (this.destroy) {
                return;
            }
            byte[] channel = message.getChannel();
            if (Arrays.equals(channel, keyPubSubBytes)) {
                onServerInstanceOnline(instanceSerializer.deserialize(message.getBody()));
            } else if (Arrays.equals(channel, keyPubUnsubBytes)) {
                onServerInstanceOffline(instanceSerializer.deserialize(message.getBody()));
            }
        };

        this.redisTemplate.setConnectionFactory((RedisConnectionFactory) redisConnectionFactory);
        this.redisTemplate.afterPropertiesSet();
    }

    @Override
    public HttpPrincipal login(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return null;
        }
        String token = authorization.substring("Basic ".length());
        String[] accountAndPassword = new String(Base64.getDecoder().decode(token)).split(":", 2);
        if (accountAndPassword.length != 2) {
            return null;
        }
        String account = accountAndPassword[0];
        String password = accountAndPassword[1];
        ServerInstance instance = selectInstanceByAccount(account);
        if (instance == null) {
            return null;
        }
        String dbPassword = instance.getPassword();
        if (Objects.equals(dbPassword, password)) {
            return new HttpPrincipal(account, password);
        }
        return null;
    }

    protected ServerInstance selectInstanceByAccount(String account) {
        return instanceMap.get(account);
    }

    public synchronized ReferenceCounted<List<RemoteConnectionService>> rebuildConnectionService(List<ServerInstance> instanceList) {
        ReferenceCounted<List<RemoteConnectionService>> old = this.connectionServiceListRef;
        this.connectionServiceListRef = new ReferenceCounted<>(newConnectionService(instanceList));
        if (old != null) {
            old.destroy(list -> {
                for (RemoteConnectionService service : list) {
                    service.close();
                }
            });
        }
        return old;
    }

    public synchronized ReferenceCounted<List<RemoteMessageRepository>> rebuildMessageRepository(List<ServerInstance> instanceList) {
        ReferenceCounted<List<RemoteMessageRepository>> old = this.messageRepositoryListRef;
        this.messageRepositoryListRef = new ReferenceCounted<>(newMessageRepository(instanceList));
        if (old != null) {
            old.destroy(list -> {
                for (RemoteMessageRepository service : list) {
                    service.close();
                }
            });
        }
        return old;
    }

    public List<RemoteMessageRepository> newMessageRepository(List<ServerInstance> instanceList) {
        List<RemoteMessageRepository> list = new ArrayList<>(instanceList.size());
        for (ServerInstance instance : instanceList) {
            if (isLocalDevice(instance)) {
                continue;
            }
            String account = instance.getAccount();
            String password = instance.getPassword();
            try {
                URL url = new URL(String.format("http://%s:%d", instance.getIp(), instance.getPort()));
                RemoteMessageRepository service = new RemoteMessageRepository(url, account, password, remoteConfig.getMessageRepository());
                list.add(service);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(
                        String.format("newMessageRepository => new URL fail!  account = '%s', password = '%s', IP = '%s', port = %d ",
                                account, password, instance.getIp(), instance.getPort()), e);
            }
        }
        return list;
    }

    public List<RemoteConnectionService> newConnectionService(List<ServerInstance> instanceList) {
        List<RemoteConnectionService> list = new ArrayList<>(instanceList.size());
        for (ServerInstance instance : instanceList) {
            if (isLocalDevice(instance)) {
                continue;
            }
            String account = instance.getAccount();
            String password = instance.getPassword();
            try {
                URL url = new URL(String.format("http://%s:%d", instance.getIp(), instance.getPort()));
                RemoteConnectionServiceImpl service = new RemoteConnectionServiceImpl(url, account, password, remoteConfig.getConnectionService());
                list.add(service);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(
                        String.format("newConnectionService => new URL fail!  account = '%s', password = '%s', IP = '%s', port = %d ",
                                account, password, instance.getIp(), instance.getPort()), e);
            }
        }
        return list;
    }

    @Override
    public void registerInstance(String ip, int port) {
        instance.setIp(ip);
        instance.setPort(port);
        instanceBytes = instanceSerializer.serialize(instance);

        Map<String, ServerInstance> instanceMap = redisTemplate.execute(connection -> {
            connection.set(keySetBytes, instanceBytes, Expiration.seconds(redisInstanceExpireSec), RedisStringCommands.SetOption.UPSERT);
            connection.publish(keyPubSubBytes, instanceBytes);
            connection.pSubscribe(messageListener, keySubBytes);
            return getInstanceMap(connection);
        }, true);
        updateInstance(filterInstance(instanceMap));
        this.heartbeatScheduledFuture = scheduledHeartbeat();
    }

    private ScheduledFuture<?> scheduledHeartbeat() {
        ScheduledFuture<?> scheduledFuture = this.heartbeatScheduledFuture;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        int delay;
        if (redisInstanceExpireSec == MIN_REDIS_INSTANCE_EXPIRE_SEC) {
            delay = 500;
        } else {
            delay = redisInstanceExpireSec * 1000 - 100;
        }
        return getScheduled().scheduleWithFixedDelay(() -> {
            redisTemplate.execute(connection -> {
                // 续期过期时间
                Long ttl = connection.ttl(keySetBytes, TimeUnit.SECONDS);
                if (ttl != null && ttl > 0) {
                    long exp = redisInstanceExpireSec * (heartbeatCount + 2) - ttl;
                    Boolean success = connection.expire(keySetBytes, exp);
                    if (success == null || !success) {
                        redisSetInstance(connection);
                    }
                } else {
                    redisSetInstance(connection);
                }
                heartbeatCount++;
                return null;
            }, true);
        }, delay, delay, TimeUnit.MILLISECONDS);
    }

    private Boolean redisSetInstance(RedisConnection connection) {
        return connection.set(keySetBytes, instanceBytes, Expiration.seconds(redisInstanceExpireSec), RedisStringCommands.SetOption.UPSERT);
    }

    public void updateInstance(Map<String, ServerInstance> instanceMap) {
        this.instanceMap = instanceMap;
        List<ServerInstance> instanceList = new ArrayList<>(instanceMap.values());
        rebuildConnectionService(instanceList);
        rebuildMessageRepository(instanceList);
    }

    private void onServerInstanceOnline(ServerInstance instance) {
        if (!isLocalDevice(instance)) {
            updateInstance(filterInstance(getInstanceMap()));
        }
    }

    private void onServerInstanceOffline(ServerInstance instance) {
        if (!isLocalDevice(instance)) {
            updateInstance(filterInstance(getInstanceMap()));
        }
    }

    public Map<String, ServerInstance> getInstanceMap() {
        RedisCallback<Map<String, ServerInstance>> redisCallback = this::getInstanceMap;
        return redisTemplate.execute(redisCallback);
    }

    private Map<String, ServerInstance> filterInstance(Map<String, ServerInstance> instanceMap) {
        if (instanceMap == null || instanceMap.isEmpty()) {
            instanceMap = this.instanceMap;
        }
        Map<String, ServerInstance> connectInstanceMap = new LinkedHashMap<>(instanceMap.size());
        for (Map.Entry<String, ServerInstance> entry : instanceMap.entrySet()) {
            ServerInstance instance = entry.getValue();
            if (isLocalDevice(instance)) {
                connectInstanceMap.put(entry.getKey(), entry.getValue());
            } else {
                try (KeepaliveSocket socket = new KeepaliveSocket(instance.getIp(), instance.getPort())) {
                    if (socket.isConnected(TEST_SOCKET_TIMEOUT)) {
                        connectInstanceMap.put(entry.getKey(), entry.getValue());
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return connectInstanceMap;
    }

    public Map<String, ServerInstance> getInstanceMap(RedisConnection connection) {
        Map<String, ServerInstance> map = new LinkedHashMap<>();
        try (Cursor<byte[]> cursor = connection.scan(keySetScanOptions)) {
            while (cursor.hasNext()) {
                byte[] key = cursor.next();
                if (key == null) {
                    continue;
                }
                byte[] body = connection.get(key);
                if (body == null) {
                    continue;
                }
                ServerInstance instance = instanceSerializer.deserialize(body);
                if (instance != null) {
                    map.put(instance.getAccount(), instance);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return map;
    }

    protected boolean isLocalDevice(ServerInstance instance) {
        return Objects.equals(instance.getDeviceId(), DEVICE_ID);
    }

    @Override
    public ReferenceCounted<List<RemoteConnectionService>> getConnectionServiceListRef() {
        return connectionServiceListRef.open();
    }

    @Override
    public ReferenceCounted<List<RemoteMessageRepository>> getMessageRepositoryListRef() {
        return messageRepositoryListRef.open();
    }

    @Override
    public void destroy() {
        this.destroy = true;
        redisTemplate.execute(connection -> {
            connection.expire(keySetBytes, 0);
            connection.publish(keyPubUnsubBytes, instanceBytes);
            return null;
        }, true);
    }

    public static class ServerInstance {
        private String ip;
        private Integer port;
        private String deviceId;
        private String account;
        private String password;

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    private static ScheduledExecutorService getScheduled() {
        if (scheduled == null) {
            synchronized (RedisServiceDiscoveryService.class) {
                if (scheduled == null) {
                    scheduled = new ScheduledThreadPoolExecutor(1, r -> {
                        Thread result = new Thread(r, "SseRedisServiceDiscoveryHeartbeat");
                        result.setDaemon(true);
                        return result;
                    });
                }
            }
        }
        return scheduled;
    }

}

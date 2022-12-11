package com.github.sseserver.remote;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.github.sseserver.util.ReferenceCounted;
import com.github.sseserver.util.SpringUtil;
import com.github.sseserver.util.WebUtil;
import com.sun.net.httpserver.HttpPrincipal;

import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Timestamp;
import java.util.*;

public class NacosServiceDiscoveryService implements ServiceDiscoveryService {
    public static final String METADATA_NAME_DEVICE_ID = "deviceId";
    public static final String METADATA_NAME_ACCOUNT = "account";
    public static final String METADATA_NAME_PASSWORD = "password";

    public static final String[] USER_DIRS = System.getProperty("user.dir").split("[/\\\\]");
    public static final String PROJECT_NAME = USER_DIRS[USER_DIRS.length - 1];

    public static final String METADATA_VALUE_DEVICE_ID = SpringUtil.filterNonAscii(
            limit(PROJECT_NAME, 10) + "-" + WebUtil.getIPAddress(WebUtil.port)
                    + "(" + new Timestamp(System.currentTimeMillis()) + ")");

    private static int idIncr = 0;
    private final NamingService namingService;
    private final String account;
    private final String serviceName;
    private final String groupName;
    private final List<String> clusterName;
    private volatile ReferenceCounted<List<RemoteConnectionService>> connectionServiceListRef = new ReferenceCounted<>(Collections.emptyList());
    private volatile ReferenceCounted<List<RemoteMessageRepository>> messageRepositoryListRef = new ReferenceCounted<>(Collections.emptyList());
    private List<Instance> instanceList;
    private Instance lastRegisterInstance;
    private EventListener onEvent;

    public NacosServiceDiscoveryService(String groupName,
                                        String serviceName,
                                        String clusterName,
                                        Properties nacosProperties) {
        this.groupName = groupName;
        this.serviceName = serviceName;
        this.clusterName = clusterName == null || clusterName.isEmpty() ?
                null : Arrays.asList(clusterName.split(","));
        this.account = SpringUtil.filterNonAscii(groupName + "-" + METADATA_VALUE_DEVICE_ID);

        try {
            this.namingService = createNamingService(nacosProperties);
        } catch (NacosException e) {
            throw new IllegalArgumentException(
                    "com.github.sseserver.remote.NacosServiceDiscoveryService createNamingService fail : " + e, e);
        }

        try {
            subscribe();
        } catch (NacosException e) {
            throw new IllegalArgumentException(
                    "com.github.sseserver.remote.NacosServiceDiscoveryService subscribe fail : " + e, e);
        }
    }

    public synchronized void subscribe() throws NacosException {
        boolean b = invokeNacosBefore();
        try {
            if (onEvent != null) {
                namingService.unsubscribe(serviceName, groupName, clusterName, onEvent);
            }
            onEvent = this::onEvent;
            namingService.subscribe(serviceName, groupName, clusterName, onEvent);
        } finally {
            invokeNacosAfter(b);
        }
    }

    public void onEvent(Event event) {
        if (!(event instanceof NamingEvent)) {
            return;
        }
        NamingEvent namingEvent = ((NamingEvent) event);
        List<Instance> instanceList = this.instanceList = namingEvent.getInstances();
        rebuildConnectionService(instanceList);
        rebuildMessageRepository(instanceList);
    }

    public synchronized ReferenceCounted<List<RemoteConnectionService>> rebuildConnectionService(List<Instance> instanceList) {
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

    public synchronized ReferenceCounted<List<RemoteMessageRepository>> rebuildMessageRepository(List<Instance> instanceList) {
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

    public synchronized NamingService createNamingService(Properties properties) throws NacosException {
        boolean b = invokeNacosBefore();
        try {
            if (clusterName != null) {
                properties.put("clusterName", String.join(",", clusterName));
            }
            return NamingFactory.createNamingService(properties);
        } finally {
            invokeNacosAfter(b);
        }
    }

    @Override
    public synchronized void registerInstance(String ip, int port) {
        Instance lastRegisterInstance = this.lastRegisterInstance;
        if (lastRegisterInstance != null) {
            boolean b = invokeNacosBefore();
            try {
                namingService.deregisterInstance(serviceName, groupName, lastRegisterInstance);
                this.lastRegisterInstance = null;
            } catch (NacosException e) {
                throw new IllegalStateException(
                        "com.github.sseserver.remote.NacosServiceDiscoveryService deregisterInstance fail : " + e, e);
            } finally {
                invokeNacosAfter(b);
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>(3);
        metadata.put(METADATA_NAME_DEVICE_ID, METADATA_VALUE_DEVICE_ID);
        metadata.put(METADATA_NAME_ACCOUNT, account);
        metadata.put(METADATA_NAME_PASSWORD, UUID.randomUUID().toString().replace("-", ""));

        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(1.0D);
        if (clusterName != null) {
            instance.setClusterName(String.join(",", clusterName));
        }
        instance.setMetadata(metadata);

        boolean b = invokeNacosBefore();
        try {
            namingService.registerInstance(serviceName, groupName, instance);
            this.lastRegisterInstance = instance;
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "com.github.sseserver.remote.NacosServiceDiscoveryService registerInstance fail : " + e, e);
        } finally {
            invokeNacosAfter(b);
        }
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
        Instance instance = selectInstanceByAccount(account);
        if (instance == null) {
            return null;
        }
        String dbPassword = getPassword(instance);
        if (Objects.equals(dbPassword, password)) {
            return new HttpPrincipal(account, password);
        }
        return null;
    }

    protected Instance selectInstanceByAccount(String account) {
        for (Instance instance : instanceList) {
            String itemAccount = getAccount(instance);
            if (Objects.equals(itemAccount, account)) {
                return instance;
            }
        }
        return null;
    }

    public List<RemoteMessageRepository> newMessageRepository(List<Instance> instanceList) {
        List<RemoteMessageRepository> list = new ArrayList<>(instanceList.size());
        for (Instance instance : instanceList) {
            if (isLocalDevice(instance)) {
                continue;
            }
            String account = getAccount(instance);
            String password = getPassword(instance);
            try {
                URL url = new URL(String.format("http://%s:%s@%s:%d", account, password, instance.getIp(), instance.getPort()));
                RemoteMessageRepository service = new RemoteMessageRepository(url, account, password);
                list.add(service);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(
                        String.format("newMessageRepository => new URL fail!  account = '%s', password = '%s', IP = '%s', port = %d ",
                                account, password, instance.getIp(), instance.getPort()), e);
            }
        }
        return list;
    }

    public List<RemoteConnectionService> newConnectionService(List<Instance> instanceList) {
        List<RemoteConnectionService> list = new ArrayList<>(instanceList.size());
        for (Instance instance : instanceList) {
            if (isLocalDevice(instance)) {
                continue;
            }
            String account = getAccount(instance);
            String password = getPassword(instance);
            try {
                URL url = new URL(String.format("http://%s:%s@%s:%d", account, password, instance.getIp(), instance.getPort()));
                RemoteConnectionServiceImpl service = new RemoteConnectionServiceImpl(url, account, password);
                list.add(service);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(
                        String.format("newConnectionService => new URL fail!  account = '%s', password = '%s', IP = '%s', port = %d ",
                                account, password, instance.getIp(), instance.getPort()), e);
            }
        }
        return list;
    }

    protected String getAccount(Instance instance) {
        return instance.getMetadata().get(METADATA_NAME_ACCOUNT);
    }

    protected String getPassword(Instance instance) {
        return instance.getMetadata().get(METADATA_NAME_PASSWORD);
    }

    protected boolean isLocalDevice(Instance instance) {
        String deviceId = instance.getMetadata().get(METADATA_NAME_DEVICE_ID);
        return Objects.equals(deviceId, METADATA_VALUE_DEVICE_ID);
    }

    @Override
    public ReferenceCounted<List<RemoteConnectionService>> getConnectionServiceListRef() {
        return connectionServiceListRef.open();
    }

    @Override
    public ReferenceCounted<List<RemoteMessageRepository>> getMessageRepositoryListRef() {
        return messageRepositoryListRef.open();
    }

    protected boolean invokeNacosBefore() {
        boolean isProjectNameNull = System.getProperty("project.name") == null;
        if (isProjectNameNull) {
            System.setProperty("project.name", PROJECT_NAME);
        }
        return isProjectNameNull;
    }

    protected void invokeNacosAfter(boolean missProjectName) {
        if (missProjectName) {
            System.getProperties().remove("project.name");
        }
    }

    private static String limit(String string, int len) {
        return string.length() > len ? string.substring(0, len) : string;
    }

}

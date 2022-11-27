package com.github.sseserver.remote;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.github.sseserver.springboot.SseServerProperties;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class NacosServiceDiscoveryService implements ServiceDiscoveryService {
    public static final String METADATA_NAME_DEVICE_ID = "deviceId";
    public static final String METADATA_NAME_ACCOUNT = "account";
    public static final String METADATA_NAME_PASSWORD = "password";
    public static final String METADATA_VALUE_DEVICE_ID = UUID.randomUUID().toString();
    private static final Pattern NON_ASCII_PATTERN = Pattern.compile("\\P{ASCII}");

    private List<RemoteConnectionService> clientList;
    private List<Instance> instanceList;
    private Instance lastRegisterInstance;

    private final NamingService namingService;
    private final String serviceName;
    private final String groupName;
    private final String clusterName;
    private final String projectName;

    public NacosServiceDiscoveryService(String groupName,
                                        SseServerProperties.Remote.Nacos nacos) {
        this.groupName = groupName;
        this.serviceName = nacos.getServiceName();
        this.clusterName = nacos.getClusterName();
        String[] userDirs = System.getProperty("user.dir").split("[/\\\\]");
        this.projectName = userDirs[userDirs.length - 1];

        try {
            this.namingService = createNamingService(nacos.buildProperties());
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

    public void subscribe() throws NacosException {
        boolean b = invokeNacosBefore();
        try {
            namingService.subscribe(serviceName, groupName, Arrays.asList(clusterName), this::onEvent);
        } finally {
            invokeNacosAfter(b);
        }
    }

    public void onEvent(Event event) {
        if (!(event instanceof NamingEvent)) {
            return;
        }
        NamingEvent namingEvent = ((NamingEvent) event);
        rebuild(this.instanceList = namingEvent.getInstances());
    }

    @Override
    public List<RemoteConnectionService> rebuild() {
        boolean b = invokeNacosBefore();
        List<Instance> instanceList;
        try {
            instanceList = namingService.getAllInstances(serviceName, groupName, Arrays.asList(clusterName));
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "com.github.sseserver.remote.NacosServiceDiscoveryService getAllInstances fail : " + e, e);
        } finally {
            invokeNacosAfter(b);
        }
        return rebuild(instanceList);
    }

    public List<RemoteConnectionService> rebuild(List<Instance> instanceList) {
        List<RemoteConnectionService> old = this.clientList;
        this.clientList = newInstances(instanceList);
        close(old);
        return old;
    }

    public NamingService createNamingService(Properties properties) throws NacosException {
        boolean b = invokeNacosBefore();
        try {
            return NamingFactory.createNamingService(properties);
        } finally {
            invokeNacosAfter(b);
        }
    }

    @Override
    public void registerInstance(String ip, int port) {
        Instance currentInstance = this.lastRegisterInstance;
        if (currentInstance != null) {
            boolean b = invokeNacosBefore();
            try {
                namingService.deregisterInstance(serviceName, groupName, currentInstance);
                this.lastRegisterInstance = null;
            } catch (NacosException e) {
                throw new IllegalStateException(
                        "com.github.sseserver.remote.NacosServiceDiscoveryService deregisterInstance fail : " + e, e);
            } finally {
                invokeNacosAfter(b);
            }
        }

        String account = projectName + "-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();

        Map<String, String> metadata = new LinkedHashMap<>(3);
        metadata.put(METADATA_NAME_DEVICE_ID, METADATA_VALUE_DEVICE_ID);
        metadata.put(METADATA_NAME_ACCOUNT, filterNonAscii(account));
        metadata.put(METADATA_NAME_PASSWORD, password);

        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(1.0D);
        instance.setClusterName(clusterName);
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
        if (!authorization.startsWith("Basic ")) {
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

    public void close(List<RemoteConnectionService> list) {
        if (list == null) {
            return;
        }
        for (RemoteConnectionService service : list) {
            try {
                service.close();
            } catch (IOException ignored) {

            }
        }
    }

    public List<RemoteConnectionService> newInstances(List<Instance> instanceList) {
        List<RemoteConnectionService> list = new ArrayList<>(instanceList.size());
        for (Instance instance : instanceList) {
            if (isLocalDevice(instance)) {
                continue;
            }
            String account = getAccount(instance);
            String password = getPassword(instance);
            try {
                URL url = new URL(String.format("http://%s:%s@%s:%d", account, password, instance.getIp(), instance.getPort()));
                RemoteConnectionServiceImpl service = new RemoteConnectionServiceImpl(url);
                list.add(service);
            } catch (MalformedURLException ignored) {
                // 不可能出现错误
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
    public List<RemoteConnectionService> getServiceList() {
        return clientList;
    }

    protected boolean invokeNacosBefore() {
        boolean missProjectName = System.getProperty("project.name") == null;
        if (missProjectName) {
            System.setProperty("project.name", projectName);
        }
        return missProjectName;
    }

    protected void invokeNacosAfter(boolean missProjectName) {
        if (missProjectName) {
            System.getProperties().remove("project.name");
        }
    }

    private static String filterNonAscii(String account) {
        return NON_ASCII_PATTERN.matcher(account)
                .replaceAll("")
                .replace(" ", "");
    }

}

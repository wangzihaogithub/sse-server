package com.github.sseserver.remote;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.github.sseserver.springboot.SseServerProperties;
import com.github.sseserver.util.WebUtil;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.*;

public class NacosServiceDiscoveryService implements ServiceDiscoveryService {
    private List<RemoteConnectionService> serviceList;
    private NamingService namingService;
    private SseServerProperties.Remote.Nacos nacos;

    private String serviceName;
    private String groupName;
    private String clusterName;
    private String projectName;
    private Integer port;
    private String ip;
    private Map<String, String> metadata;

    public NacosServiceDiscoveryService(String groupName,
                                        SseServerProperties.Remote.Nacos nacos,
                                        Environment springEnvironment) {
        initProperties(groupName, nacos, springEnvironment);

        try {
            createNamingService();
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

        try {
            registerInstance();
        } catch (NacosException e) {
            throw new IllegalArgumentException(
                    "com.github.sseserver.remote.NacosServiceDiscoveryService registerInstance fail : " + e, e);
        }
    }

    private void initProperties(String groupName, SseServerProperties.Remote.Nacos nacos, Environment springEnvironment) {
        this.nacos = nacos;
        this.port = springEnvironment.getProperty("server.port", Integer.class, 8080);
        this.ip = WebUtil.getIPAddress();
        this.groupName = groupName;

        String serviceName = nacos.getServiceName();
        if (serviceName == null || serviceName.isEmpty()) {
            String springApplicationName = springEnvironment.getProperty("spring.application.name", "");
            if (springApplicationName.length() > 0) {
                serviceName = springApplicationName;
            } else {
                serviceName = "sse-server";
            }
        }
        this.serviceName = serviceName;

        String clusterName = nacos.getClusterName();
        if (clusterName == null || clusterName.isEmpty()) {
            clusterName = Constants.DEFAULT_CLUSTER_NAME;
        }
        this.clusterName = clusterName;

        String[] userDirs = System.getProperty("user.dir").split("[/\\\\]");
        this.projectName = userDirs[userDirs.length - 1];

        this.metadata = new LinkedHashMap<>();
    }

    public void subscribe() throws NacosException {
        boolean b = invokeNacosBefore();
        try {
            namingService.subscribe(serviceName, groupName, Arrays.asList(clusterName), event -> {
                if (!(event instanceof NamingEvent)) {
                    return;
                }
                NamingEvent namingEvent = ((NamingEvent) event);
                rebuild(namingEvent.getInstances());
            });
        } finally {
            invokeNacosAfter(b);
        }
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
        List<RemoteConnectionService> old = this.serviceList;
        this.serviceList = newInstances(instanceList);
        close(old);
        return old;
    }

    public void createNamingService() throws NacosException {
        boolean b = invokeNacosBefore();
        try {
            this.namingService = NamingFactory.createNamingService(nacos.buildProperties());
        } finally {
            invokeNacosAfter(b);
        }
    }

    public void registerInstance() throws NacosException {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(1.0D);
        instance.setClusterName(clusterName);
        instance.setMetadata(metadata);

        boolean b = invokeNacosBefore();
        try {
            namingService.registerInstance(serviceName, groupName, instance);
        } finally {
            invokeNacosAfter(b);
        }
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
            if (isCurrentServer(instance)) {
                continue;
            }
            RemoteConnectionServiceImpl service = new RemoteConnectionServiceImpl(instance.getIp(), instance.getPort());
            list.add(service);
        }
        return list;
    }

    private boolean isCurrentServer(Instance instance) {
        return Objects.equals(ip, instance.getIp())
                && Objects.equals(port, instance.getPort());
    }

    @Override
    public List<RemoteConnectionService> getServiceList() {
        return serviceList;
    }

    private boolean invokeNacosBefore() {
        boolean missProjectName = System.getProperty("project.name") == null;
        if (missProjectName) {
            System.setProperty("project.name", projectName);
        }
        return missProjectName;
    }

    private void invokeNacosAfter(boolean missProjectName) {
        if (missProjectName) {
            System.getProperties().remove("project.name");
        }
    }

}

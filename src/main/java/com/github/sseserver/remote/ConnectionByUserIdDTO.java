package com.github.sseserver.remote;

import com.github.sseserver.local.SseEmitter;

import java.util.Collection;
import java.util.Date;
import java.util.Map;

public class ConnectionByUserIdDTO {
    // connection
    /**
     * 连接的唯一标识符
     */
    private Long id;

    /**
     * 连接创建时间
     */
    private Date createTime;

    /**
     * 连接超时时间（毫秒）
     */
    private Long timeout;

    /**
     * 连接访问时间
     */
    private Date accessTime;

    /**
     * 连接接收到的消息数量
     */
    private Integer messageCount;

    /**
     * 连接使用的频道
     */
    private String channel;

    // request

    /**
     * 上一次请求时间
     */
    private Date lastRequestTime;

    /**
     * 请求中的消息数量
     */
    private Integer requestMessageCount;

    /**
     * 请求中的上传文件数量
     */
    private Integer requestUploadCount;

    /**
     * 请求中监听的事件
     */
    private Collection<String> listeners;

    /**
     * 请求的位置信息（URL）
     */
    private String locationHref;

    // user

    /**
     * 用户租户ID标识符
     */
    private Object accessTenantId;

    /**
     * 用户唯一标识符
     */
    private Object accessUserId;

    /**
     * 用户的访问令牌
     */
    private String accessToken;

    // client

    /**
     * 客户端唯一标识符
     */
    private String clientId;

    /**
     * 客户端版本号
     */
    private String clientVersion;

    /**
     * 客户端导入模块的时间
     */
    private Long clientImportModuleTime;

    /**
     * 客户端实例的唯一标识符
     */
    private String clientInstanceId;

    /**
     * 客户端实例的时间
     */
    private Long clientInstanceTime;

    // server

    /**
     * 服务器唯一标识符
     */
    private String serverId;

    // http

    /**
     * 请求的 IP 地址
     */
    private String requestIp;

    /**
     * 请求的域名
     */
    private String requestDomain;

    /**
     * 用户代理信息
     */
    private String userAgent;

    /**
     * HTTP 请求参数
     */
    private Map<String, Object> httpParameters;

    // browser

    /**
     * 浏览器屏幕信息（宽度 x 高度）`${window_screen.width}x${window_screen.height}`
     */
    private String screen;

    /**
     * JavaScript 堆内存总大小
     */
    private Long totalJSHeapSize;

    /**
     * JavaScript 堆内存使用大小
     */
    private Long usedJSHeapSize;

    /**
     * JavaScript 堆内存限制
     */
    private Long jsHeapSizeLimit;

    public static <ACCESS_USER> ConnectionByUserIdDTO convert(SseEmitter<ACCESS_USER> connection) {
        ConnectionByUserIdDTO dto = new ConnectionByUserIdDTO();
        dto.setId(connection.getId());
        dto.setMessageCount(connection.getCount());
        dto.setTimeout(connection.getTimeout());
        dto.setChannel(connection.getChannel());
        dto.setCreateTime(new Date(connection.getCreateTime()));
        dto.setAccessTime(connection.getAccessTime());

        dto.setLocationHref(connection.getLocationHref());
        dto.setListeners(connection.getListeners());
        dto.setRequestMessageCount(connection.getRequestMessageCount());
        dto.setRequestUploadCount(connection.getRequestUploadCount());
        long lastRequestTimestamp = connection.getLastRequestTimestamp();
        if (lastRequestTimestamp > 0) {
            dto.setLastRequestTime(new Date(lastRequestTimestamp));
        }

        dto.setAccessToken(connection.getAccessToken());
        dto.setAccessUserId(connection.getUserId());
        dto.setAccessTenantId(connection.getTenantId());

        dto.setClientId(connection.getClientId());
        dto.setClientVersion(connection.getClientVersion());
        dto.setClientImportModuleTime(connection.getClientImportModuleTime());
        dto.setClientInstanceId(connection.getClientInstanceId());
        dto.setClientInstanceTime(connection.getClientInstanceTime());

        dto.setServerId(connection.getServerId());

        dto.setRequestIp(connection.getRequestIp());
        dto.setRequestDomain(connection.getRequestDomain());
        dto.setUserAgent(connection.getUserAgent());
        dto.setHttpParameters(connection.getHttpParameters());

        dto.setScreen(connection.getScreen());
        dto.setJsHeapSizeLimit(connection.getJsHeapSizeLimit());
        dto.setTotalJSHeapSize(connection.getTotalJSHeapSize());
        dto.setUsedJSHeapSize(connection.getUsedJSHeapSize());
        return dto;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public Long getClientImportModuleTime() {
        return clientImportModuleTime;
    }

    public void setClientImportModuleTime(Long clientImportModuleTime) {
        this.clientImportModuleTime = clientImportModuleTime;
    }

    public String getClientInstanceId() {
        return clientInstanceId;
    }

    public void setClientInstanceId(String clientInstanceId) {
        this.clientInstanceId = clientInstanceId;
    }

    public Long getClientInstanceTime() {
        return clientInstanceTime;
    }

    public void setClientInstanceTime(Long clientInstanceTime) {
        this.clientInstanceTime = clientInstanceTime;
    }

    public String getLocationHref() {
        return locationHref;
    }

    public void setLocationHref(String locationHref) {
        this.locationHref = locationHref;
    }

    public String getScreen() {
        return screen;
    }

    public void setScreen(String screen) {
        this.screen = screen;
    }

    public Long getTotalJSHeapSize() {
        return totalJSHeapSize;
    }

    public void setTotalJSHeapSize(Long totalJSHeapSize) {
        this.totalJSHeapSize = totalJSHeapSize;
    }

    public Long getUsedJSHeapSize() {
        return usedJSHeapSize;
    }

    public void setUsedJSHeapSize(Long usedJSHeapSize) {
        this.usedJSHeapSize = usedJSHeapSize;
    }

    public Long getJsHeapSizeLimit() {
        return jsHeapSizeLimit;
    }

    public void setJsHeapSizeLimit(Long jsHeapSizeLimit) {
        this.jsHeapSizeLimit = jsHeapSizeLimit;
    }

    public String getRequestIp() {
        return requestIp;
    }

    public void setRequestIp(String requestIp) {
        this.requestIp = requestIp;
    }

    public String getRequestDomain() {
        return requestDomain;
    }

    public void setRequestDomain(String requestDomain) {
        this.requestDomain = requestDomain;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Map<String, Object> getHttpParameters() {
        return httpParameters;
    }

    public void setHttpParameters(Map<String, Object> httpParameters) {
        this.httpParameters = httpParameters;
    }

    public Date getAccessTime() {
        return accessTime;
    }

    public void setAccessTime(Date accessTime) {
        this.accessTime = accessTime;
    }

    public Date getLastRequestTime() {
        return lastRequestTime;
    }

    public void setLastRequestTime(Date lastRequestTime) {
        this.lastRequestTime = lastRequestTime;
    }

    public Integer getRequestMessageCount() {
        return requestMessageCount;
    }

    public void setRequestMessageCount(Integer requestMessageCount) {
        this.requestMessageCount = requestMessageCount;
    }

    public Integer getRequestUploadCount() {
        return requestUploadCount;
    }

    public void setRequestUploadCount(Integer requestUploadCount) {
        this.requestUploadCount = requestUploadCount;
    }

    public Collection<String> getListeners() {
        return listeners;
    }

    public void setListeners(Collection<String> listeners) {
        this.listeners = listeners;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
    }

    public Long getTimeout() {
        return timeout;
    }

    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    public Date getExpireTime() {
        if (timeout != null && timeout > 0) {
            return new Date(createTime.getTime() + timeout);
        }
        return null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Object getAccessUserId() {
        return accessUserId;
    }

    public void setAccessUserId(Object accessUserId) {
        this.accessUserId = accessUserId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Object getAccessTenantId() {
        return accessTenantId;
    }

    public void setAccessTenantId(Object accessTenantId) {
        this.accessTenantId = accessTenantId;
    }
}

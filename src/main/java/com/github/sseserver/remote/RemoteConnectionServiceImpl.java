package com.github.sseserver.remote;

import com.github.sseserver.local.LocalConnectionController.Response;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.Collection;
import java.util.List;

public class RemoteConnectionServiceImpl implements RemoteConnectionService {
    private RestTemplate restTemplate = new RestTemplate();
    private final URL url;
    private final String urlString;

    public RemoteConnectionServiceImpl(URL url) {
        this.url = url;
        this.urlString = url.toString();
    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public boolean isOnline(Serializable userId) {
        Response<Boolean> response = restTemplate.getForObject(
                urlString + "/ConnectionQueryService/isOnline?userId={userId}", Response.class,
                userId);
        return response.getData();
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        Response<ACCESS_USER> response = restTemplate.getForObject(
                urlString + "/ConnectionQueryService/getUser?userId={userId}", Response.class,
                userId);
        return response.getData();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        Response<List<ACCESS_USER>> response = restTemplate.getForObject(
                urlString + "/ConnectionQueryService/getUsers", Response.class);
        return response.getData();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        Response<List<ACCESS_USER>> response = restTemplate.getForObject(
                urlString + "/ConnectionQueryService/getUsersByListening?sseListenerName={sseListenerName}", Response.class,
                sseListenerName);
        return response.getData();
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        Response<List<ACCESS_USER>> response = restTemplate.getForObject(
                urlString + "/ConnectionQueryService/getUsersByTenantIdListening?tenantId={tenantId}&sseListenerName={sseListenerName}", Response.class,
                tenantId, sseListenerName);
        return response.getData();
    }

    @Override
    public <T> Collection<T> getUserIds(Class<T> type) {
        return null;
    }

    @Override
    public <T> List<T> getUserIdsByListening(String sseListenerName, Class<T> type) {
        return null;
    }

    @Override
    public <T> List<T> getUserIdsByTenantIdListening(Serializable tenantId, String sseListenerName, Class<T> type) {
        return null;
    }

    @Override
    public Collection<String> getAccessTokens() {
        return null;
    }

    @Override
    public <T> List<T> getTenantIds(Class<T> type) {
        return null;
    }

    @Override
    public List<String> getChannels() {
        return null;
    }

    @Override
    public int getAccessTokenCount() {
        return 0;
    }

    @Override
    public int getUserCount() {
        return 0;
    }

    @Override
    public int getConnectionCount() {
        return 0;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendAll(String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendAllListening(String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByChannel(Collection<String> channels, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByChannelListening(Collection<String> channels, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByAccessToken(Collection<String> accessTokens, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByAccessTokenListening(Collection<String> accessTokens, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByUserId(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByUserIdListening(Collection<? extends Serializable> userIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByTenantId(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> sendByTenantIdListening(Collection<? extends Serializable> tenantIds, String eventName, Serializable body) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> disconnectByUserId(Serializable userId) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> disconnectByAccessToken(String accessToken) {
        return null;
    }

    @Override
    public RemoteCompletableFuture<Integer> disconnectByConnectionId(Long connectionId) {
        return null;
    }
}

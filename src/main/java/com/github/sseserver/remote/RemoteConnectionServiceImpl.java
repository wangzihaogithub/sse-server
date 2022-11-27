package com.github.sseserver.remote;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public class RemoteConnectionServiceImpl implements RemoteConnectionService{
    public RemoteConnectionServiceImpl(String ip,Integer port) {
    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public boolean isOnline(Serializable userId) {
        return false;
    }

    @Override
    public <ACCESS_USER> ACCESS_USER getUser(Serializable userId) {
        return null;
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsers() {
        return null;
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByListening(String sseListenerName) {
        return null;
    }

    @Override
    public <ACCESS_USER> List<ACCESS_USER> getUsersByTenantIdListening(Serializable tenantId, String sseListenerName) {
        return null;
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

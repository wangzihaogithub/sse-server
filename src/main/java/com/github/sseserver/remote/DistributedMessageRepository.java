package com.github.sseserver.remote;

import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DistributedMessageRepository implements MessageRepository {
    private final static Logger log = LoggerFactory.getLogger(DistributedConnectionServiceImpl.class);
    private final Supplier<MessageRepository> localRepositorySupplier;
    private final Supplier<ReferenceCounted<List<RemoteMessageRepository>>> remoteRepositorySupplier;

    public DistributedMessageRepository(Supplier<MessageRepository> localRepositorySupplier,
                                        Supplier<ReferenceCounted<List<RemoteMessageRepository>>> remoteRepositorySupplier) {
        this.localRepositorySupplier = localRepositorySupplier;
        this.remoteRepositorySupplier = remoteRepositorySupplier;
    }

    public MessageRepository getLocalRepository() {
        return localRepositorySupplier.get();
    }

    public ReferenceCounted<List<RemoteMessageRepository>> getRemoteRepositoryRef() {
        if (remoteRepositorySupplier == null) {
            return new ReferenceCounted<>(Collections.emptyList());
        }
        return remoteRepositorySupplier.get();
    }

    @Override
    public String insert(Message message) {
        return getLocalRepository().insert(message);
    }

    @Override
    public List<Message> select(Query query) {
        return selectAsync(query).block();
    }

    @Override
    public boolean delete(String id) {
        return deleteAsync(id, null).block();
    }

    public DistributedCompletableFuture<Boolean> deleteAsync(String id, String remoteMessageRepositoryId) {
        boolean isMessageAtRemoteId = remoteMessageRepositoryId != null;
        try (ReferenceCounted<List<RemoteMessageRepository>> ref = getRemoteRepositoryRef()) {
            List<RemoteMessageRepository> serviceList;
            if (isMessageAtRemoteId) {
                serviceList = ref.get().stream()
                        .filter(e -> Objects.equals(remoteMessageRepositoryId, e.getId()))
                        .collect(Collectors.toList());
            } else {
                serviceList = ref.get();
            }

            List<RemoteCompletableFuture<Boolean, RemoteMessageRepository>> remoteFutureList = new ArrayList<>(serviceList.size());
            for (RemoteMessageRepository remote : serviceList) {
                RemoteCompletableFuture<Boolean, RemoteMessageRepository> future = remote.deleteAsync(id);
                remoteFutureList.add(future);
            }

            DistributedCompletableFuture<Boolean> future = new DistributedCompletableFuture<>();
            if (!isMessageAtRemoteId) {
                boolean delete = getLocalRepository().delete(id);
                if (delete) {
                    future.complete(true);
                    return future;
                }
            }

            boolean[] success = new boolean[1];
            CompletableFuture.join(remoteFutureList, future, () -> {
                InterruptedException interruptedException = null;
                for (RemoteCompletableFuture<Boolean, RemoteMessageRepository> remoteFuture : remoteFutureList) {
                    try {
                        Boolean part;
                        if (interruptedException != null) {
                            if (remoteFuture.isDone()) {
                                part = remoteFuture.get();
                            } else {
                                continue;
                            }
                        } else {
                            part = remoteFuture.get();
                        }
                        if (part != null) {
                            success[0] |= part;
                        }
                    } catch (InterruptedException exception) {
                        interruptedException = exception;
                    } catch (ExecutionException exception) {
                        handleDeleteRemoteException(remoteFuture, exception);
                    }
                }
                return success[0];
            });
            return future;
        }
    }

    public DistributedCompletableFuture<List<Message>> selectAsync(Query query) {
        try (ReferenceCounted<List<RemoteMessageRepository>> ref = getRemoteRepositoryRef()) {
            List<RemoteMessageRepository> serviceList = ref.get();

            List<RemoteCompletableFuture<List<Message>, RemoteMessageRepository>> remoteFutureList = new ArrayList<>(serviceList.size());
            for (RemoteMessageRepository remote : serviceList) {
                RemoteCompletableFuture<List<Message>, RemoteMessageRepository> future = remote.selectAsync(query);
                remoteFutureList.add(future);
            }

            List<Message> messageList = new ArrayList<>(getLocalRepository().select(query));
            DistributedCompletableFuture<List<Message>> future = new DistributedCompletableFuture<>();
            CompletableFuture.join(remoteFutureList, future, () -> {
                InterruptedException interruptedException = null;
                for (RemoteCompletableFuture<List<Message>, RemoteMessageRepository> remoteFuture : remoteFutureList) {
                    try {
                        List<Message> part;
                        if (interruptedException != null) {
                            if (remoteFuture.isDone()) {
                                part = remoteFuture.get();
                            } else {
                                continue;
                            }
                        } else {
                            part = remoteFuture.get();
                        }
                        if (part != null) {
                            messageList.addAll(part);
                        }
                    } catch (InterruptedException exception) {
                        interruptedException = exception;
                    } catch (ExecutionException exception) {
                        handleSelectRemoteException(remoteFuture, exception);
                    }
                }
                return messageList;
            });
            return future;
        }
    }

    protected void handleSelectRemoteException(RemoteCompletableFuture<List<Message>, RemoteMessageRepository> remoteFuture,
                                               ExecutionException exception) {
        log.debug("RemoteMessageRepository {} , SelectRemoteException {}",
                remoteFuture.getClient(), exception, exception);
    }

    protected void handleDeleteRemoteException(RemoteCompletableFuture<Boolean, RemoteMessageRepository> remoteFuture,
                                               ExecutionException exception) {
        log.debug("RemoteMessageRepository {} , DeleteRemoteException {}",
                remoteFuture.getClient(), exception, exception);
    }
}

package com.github.sseserver.remote;

import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.util.CompletableFuture;
import com.github.sseserver.util.LambdaUtil;
import com.github.sseserver.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class ClusterMessageRepository implements MessageRepository {
    private final static Logger log = LoggerFactory.getLogger(ClusterConnectionServiceImpl.class);
    private final Supplier<MessageRepository> localRepositorySupplier;
    private final Supplier<ReferenceCounted<List<RemoteMessageRepository>>> remoteRepositorySupplier;
    private final boolean primary;

    /**
     * @param localRepositorySupplier  非必填
     * @param remoteRepositorySupplier 非必填
     * @param primary 是否主要
     */
    public ClusterMessageRepository(Supplier<MessageRepository> localRepositorySupplier,
                                    Supplier<ReferenceCounted<List<RemoteMessageRepository>>> remoteRepositorySupplier,
                                    boolean primary) {
        this.localRepositorySupplier = localRepositorySupplier;
        this.remoteRepositorySupplier = remoteRepositorySupplier;
        this.primary = primary;
    }

    @Override
    public boolean isPrimary() {
        return primary;
    }

    public MessageRepository getLocalRepository() {
        return localRepositorySupplier != null ? localRepositorySupplier.get() : null;
    }

    public ReferenceCounted<List<RemoteMessageRepository>> getRemoteRepositoryRef() {
        if (remoteRepositorySupplier == null) {
            return new ReferenceCounted<>(Collections.emptyList());
        }
        return remoteRepositorySupplier.get();
    }

    @Override
    public String insert(Message message) {
        MessageRepository localRepository = getLocalRepository();
        if (localRepository != null) {
            return localRepository.insert(message);
        } else {
            return message.getId();
        }
    }

    @Override
    public List<Message> list() {
        ClusterCompletableFuture<List<Message>, ClusterMessageRepository> future = listAsync();
        return future.block();
    }

    @Override
    public List<Message> select(Query query) {
        ClusterCompletableFuture<List<Message>, ClusterMessageRepository> future = selectAsync(query);
        return future.block();
    }

    @Override
    public Message delete(String id) {
        ClusterCompletableFuture<Message, ClusterMessageRepository> future = deleteAsync(id, null);
        return future.block();
    }

    @Override
    public void addDeleteListener(Consumer<Message> listener) {
        MessageRepository localRepository = getLocalRepository();
        if (localRepository != null) {
            localRepository.addDeleteListener(listener);
        }
    }

    public ClusterCompletableFuture<List<Message>, ClusterMessageRepository> listAsync() {
        return mapReduce(
                RemoteMessageRepository::listAsync,
                MessageRepository::list,
                LambdaUtil.reduceList(),
                LambdaUtil.noop(),
                ArrayList::new);
    }

    public ClusterCompletableFuture<List<Message>, ClusterMessageRepository> selectAsync(Query query) {
        return mapReduce(
                e -> e.selectAsync(query),
                e -> e.select(query),
                LambdaUtil.reduceList(),
                LambdaUtil.noop(),
                ArrayList::new);
    }

    public ClusterCompletableFuture<Message, ClusterMessageRepository> deleteAsync(String id, String remoteMessageRepositoryId) {
        return mapReduce(e -> {
                    if (remoteMessageRepositoryId == null || Objects.equals(e.getId(), remoteMessageRepositoryId)) {
                        return e.deleteAsync(id);
                    } else {
                        RemoteCompletableFuture<Message, RemoteMessageRepository> future = new RemoteCompletableFuture<>();
                        future.setClient(e);
                        future.complete(null);
                        return future;
                    }
                },
                e -> remoteMessageRepositoryId != null ? e.delete(id) : null,
                LambdaUtil.filterNull(),
                LambdaUtil.defaultNull());
    }

    protected <T> ClusterCompletableFuture<T, ClusterMessageRepository> mapReduce(
            Function<RemoteMessageRepository, RemoteCompletableFuture<T, RemoteMessageRepository>> remoteFunction,
            Function<MessageRepository, T> localFunction,
            BiFunction<T, T, T> reduce,
            Supplier<T> supplier) {
        return mapReduce(remoteFunction, localFunction, reduce, o1 -> o1, supplier);
    }

    protected <T, R> ClusterCompletableFuture<R, ClusterMessageRepository> mapReduce(
            Function<RemoteMessageRepository, RemoteCompletableFuture<T, RemoteMessageRepository>> remoteFunction,
            Function<MessageRepository, T> localFunction,
            BiFunction<T, T, T> reduce,
            Function<T, R> finisher,
            Supplier<T> supplier) {
        MessageRepository localRepository = getLocalRepository();
        try (ReferenceCounted<List<RemoteMessageRepository>> ref = getRemoteRepositoryRef()) {
            List<RemoteMessageRepository> serviceList = ref.get();

            List<URL> remoteUrlList = new ArrayList<>(serviceList.size());
            List<RemoteCompletableFuture<T, RemoteMessageRepository>> remoteFutureList = new ArrayList<>(serviceList.size());
            for (RemoteMessageRepository remote : serviceList) {
                remoteUrlList.add(remote.getRemoteUrl());
                // rpc async method call
                remoteFutureList.add(remoteFunction.apply(remote));
            }

            // local method call
            T localPart = localRepository != null ? localFunction.apply(localRepository) : null;

            ClusterCompletableFuture<R, ClusterMessageRepository> future = new ClusterCompletableFuture<>(remoteUrlList, this);
            CompletableFuture.join(remoteFutureList, future, () -> {
                T remotePart = supplier.get();
                InterruptedException interruptedException = null;
                for (RemoteCompletableFuture<T, RemoteMessageRepository> remoteFuture : remoteFutureList) {
                    try {
                        T part;
                        if (interruptedException != null) {
                            if (remoteFuture.isDone()) {
                                part = remoteFuture.get();
                            } else {
                                continue;
                            }
                        } else {
                            part = remoteFuture.get();
                        }
                        remotePart = reduce.apply(remotePart, part);
                    } catch (InterruptedException exception) {
                        interruptedException = exception;
                    } catch (ExecutionException exception) {
                        handleRemoteException(remoteFuture, exception, future);
                    }
                }
                if (localRepository != null) {
                    return finisher.apply(reduce.apply(remotePart, localPart));
                } else {
                    return finisher.apply(remotePart);
                }
            });
            return future;
        }
    }

    protected <R> void handleRemoteException(RemoteCompletableFuture<?, RemoteMessageRepository> remoteFuture,
                                             ExecutionException exception,
                                             ClusterCompletableFuture<R, ClusterMessageRepository> doneFuture) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            cause = exception;
        }
        if (cause instanceof IOException) {
            if (log.isDebugEnabled()) {
                log.debug("RemoteException: RemoteMessageRepository {} , RemoteException {}",
                        remoteFuture.getClient(), exception, exception);
            }
        } else {
            if (!doneFuture.isDone()) {
                doneFuture.setExceptionallyPrefix("ClusterMessageRepository at remoteFuture " + remoteFuture.getClient().getId());
            }
            boolean completeExceptionally = doneFuture.completeExceptionally(cause);
            if (log.isDebugEnabled()) {
                log.debug("RemoteException: RemoteMessageRepository {} , RemoteException {}, completeExceptionally {}",
                        remoteFuture.getClient(), exception, completeExceptionally, exception);
            }
        }
    }

}

package com.github.sseserver.remote;

import com.github.sseserver.qos.Message;
import com.github.sseserver.qos.MessageRepository;
import com.github.sseserver.util.ReferenceCounted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class DistributedMessageRepository implements MessageRepository {
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
        List<Message> messageList = new ArrayList<>(getLocalRepository().select(query));
        try (ReferenceCounted<List<RemoteMessageRepository>> ref = getRemoteRepositoryRef()) {
            for (MessageRepository remote : ref.get()) {
                messageList.addAll(remote.select(query));
            }
        }
        return messageList;
    }

    @Override
    public boolean delete(String id) {
        boolean delete = getLocalRepository().delete(id);
        if (delete) {
            return true;
        }
        try (ReferenceCounted<List<RemoteMessageRepository>> ref = getRemoteRepositoryRef()) {
            for (MessageRepository remote : ref.get()) {
                if (remote.delete(id)) {
                    return true;
                }
            }
        }
        return false;
    }
}

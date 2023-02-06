package com.github.sseserver.qos;

import com.github.sseserver.local.SseEmitter;
import com.github.sseserver.remote.ClusterMessageRepository;
import com.github.sseserver.remote.RemoteResponseMessage;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AtLeastResend<ACCESS_USER> {
    protected final MessageRepository messageRepository;
    protected final Set<String> sendingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public AtLeastResend(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    protected void markSending(List<Message> messageList) {
        int i = 0;
        for (Message message : messageList) {
            if (!sendingSet.add(message.getId())) {
                messageList.set(i, null);
            }
            i++;
        }
    }

    public void resend(SseEmitter<ACCESS_USER> connection) {
        if (messageRepository instanceof ClusterMessageRepository) {
            ((ClusterMessageRepository) messageRepository).selectAsync(connection)
                    .thenAccept(e -> resend(e, connection));
        } else {
            resend(messageRepository.select(connection), connection);
        }
    }

    public void resend(List<Message> messageList, SseEmitter<ACCESS_USER> connection) {
        if (messageList.isEmpty()) {
            return;
        }
        markSending(messageList);

        IOException error = null;
        for (Message message : messageList) {
            if (message == null) {
                // other sending
                continue;
            }
            String id = message.getId();
            try {
                if (!connection.isActive() || !connection.isWriteable() || error != null) {
                    continue;
                }

                connection.send(SseEmitter.event()
                        .id(id)
                        .name(message.getEventName())
                        .comment("resend")
                        .data(message.getBody()));

                if (messageRepository instanceof ClusterMessageRepository) {
                    ClusterMessageRepository repository = ((ClusterMessageRepository) messageRepository);
                    String repositoryId;
                    if (message instanceof RemoteResponseMessage) {
                        repositoryId = ((RemoteResponseMessage) message).getRemoteMessageRepositoryId();
                    } else {
                        repositoryId = null;
                    }
                    repository.deleteAsync(id, repositoryId);
                } else {
                    messageRepository.delete(id);
                }
            } catch (IOException e) {
                error = e;
            } finally {
                sendingSet.remove(id);
            }
        }
    }

}

package me.kezer0.networkCore.session;

import java.util.UUID;

public interface SessionService {
    UUID startSession(UUID playerUuid, String username, String serverName, String locale);
    void endSession(UUID sessionId, String reason);
}

package me.kezer0.networkCore.session;

import me.kezer0.networkCore.NetworkCore;
import me.kezer0.networkCore.database.NetworkDatabase;
import me.kezer0.networkCore.telemetry.TelemetryManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public final class SessionManager implements SessionService {
    private static final SessionManager INSTANCE = new SessionManager();
    private final ConcurrentMap<UUID, SessionRecord> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> online = new ConcurrentHashMap<>();

    private SessionManager() {}
    public static SessionManager getInstance() { return INSTANCE; }

    public void handleJoin(Player player) {
        if (!NetworkDatabase.isEnabled()) return;
        UUID playerUuid = player.getUniqueId();
        online.put(playerUuid, Boolean.TRUE);
        String server = NetworkCore.getInstance().getServerName();
        String locale = player.getLocale();
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try {
                UUID sessionId = NetworkDatabase.startSession(playerUuid, player.getName(), server, locale);
                SessionRecord record = new SessionRecord(sessionId, playerUuid, server, Instant.now());
                active.put(playerUuid, record);

                if (!online.containsKey(playerUuid)) {
                    active.remove(playerUuid, record);
                    NetworkDatabase.endSession(sessionId, "quit_during_login");
                    return;
                }

                TelemetryManager.getInstance().record(playerUuid, "player_join", server, Map.of("locale", locale == null ? "unknown" : locale));
            } catch (Exception ex) {
                log("Failed to start session for " + player.getName(), ex);
            }
        });
    }

    public void handleQuit(Player player, String reason) {
        online.remove(player.getUniqueId());
        SessionRecord record = active.remove(player.getUniqueId());
        if (record == null || !NetworkDatabase.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try {
                NetworkDatabase.endSession(record.id(), reason);
                TelemetryManager.getInstance().record(record.playerUuid(), "player_quit", record.serverName(), Map.of("reason", reason == null ? "disconnect" : reason));
            } catch (Exception ex) {
                log("Failed to end session for " + record.playerUuid(), ex);
            }
        });
    }

    public SessionRecord getActive(UUID playerUuid) { return active.get(playerUuid); }

    public Map<UUID, SessionRecord> snapshot() { return Map.copyOf(active); }

    /** Cleanly closes sessions during a normal server shutdown. Crash recovery handles abnormal exits. */
    public void closeAll(String reason) {
        if (!NetworkDatabase.isEnabled()) { active.clear(); online.clear(); return; }
        for (SessionRecord record : active.values()) {
            try { NetworkDatabase.endSession(record.id(), reason == null ? "server_shutdown" : reason); }
            catch (Exception ex) { log("Failed to close session " + record.id() + " during shutdown", ex); }
        }
        active.clear();
        online.clear();
    }


    @Override
    public UUID startSession(UUID playerUuid, String username, String serverName, String locale) {
        if (!NetworkDatabase.isEnabled()) return null;
        try {
            UUID id = NetworkDatabase.startSession(playerUuid, username, serverName, locale);
            active.put(playerUuid, new SessionRecord(id, playerUuid, serverName, Instant.now()));
            online.put(playerUuid, Boolean.TRUE);
            return id;
        } catch (Exception ex) {
            log("Failed to start manual session for " + playerUuid, ex);
            return null;
        }
    }

    @Override
    public void endSession(UUID sessionId, String reason) {
        if (!NetworkDatabase.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try { NetworkDatabase.endSession(sessionId, reason); }
            catch (Exception ex) { log("Failed to end manual session " + sessionId, ex); }
        });
    }

    private void log(String msg, Exception ex) { NetworkCore.getInstance().getLogger().log(Level.WARNING, msg, ex); }
}

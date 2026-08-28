package me.kezer0.networkCore.telemetry;

import me.kezer0.networkCore.NetworkCore;
import me.kezer0.networkCore.database.NetworkDatabase;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class TelemetryManager implements TelemetryService {
    private static final TelemetryManager INSTANCE = new TelemetryManager();
    private TelemetryManager() {}
    public static TelemetryManager getInstance() { return INSTANCE; }

    @Override
    public void record(UUID playerUuid, String eventType, String serverName, Map<String, Object> metadata) {
        if (!NetworkDatabase.isEnabled() || eventType == null || eventType.isBlank()) return;
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try { NetworkDatabase.recordTelemetry(playerUuid, eventType, serverName, metadata); }
            catch (Exception ex) { NetworkCore.getInstance().getLogger().log(Level.WARNING, "Failed to record telemetry event " + eventType, ex); }
        });
    }
}

package me.kezer0.networkCore.telemetry;

import java.util.Map;
import java.util.UUID;

public interface TelemetryService {
    void record(UUID playerUuid, String eventType, String serverName, Map<String, Object> metadata);
}

package me.kezer0.networkCore.session;

import java.time.Instant;
import java.util.UUID;

public record SessionRecord(UUID id, UUID playerUuid, String serverName, Instant startedAt) {}

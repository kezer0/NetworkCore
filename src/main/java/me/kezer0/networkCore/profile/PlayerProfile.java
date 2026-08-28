package me.kezer0.networkCore.profile;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Network-wide player identity/profile. Mode-specific data belongs to the game plugin. */
public final class PlayerProfile {
    private final UUID uuid;
    private volatile String username;
    private volatile Instant firstJoined;
    private volatile Instant lastSeen;
    private volatile long totalPlaytimeSeconds;
    private volatile long joinCount;
    private volatile String lastServer;
    private volatile String locale;
    private volatile String rank;
    private final ConcurrentMap<String, Boolean> permissions = new ConcurrentHashMap<>();

    public PlayerProfile(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username == null ? "Unknown" : username;
    }

    public UUID getUuid() { return uuid; }
    public String getUsername() { return username; }
    public void setUsername(String username) { if (username != null && !username.isBlank()) this.username = username; }
    public Instant getFirstJoined() { return firstJoined; }
    public void setFirstJoined(Instant value) { this.firstJoined = value; }
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant value) { this.lastSeen = value; }
    public long getTotalPlaytimeSeconds() { return totalPlaytimeSeconds; }
    public void setTotalPlaytimeSeconds(long seconds) { this.totalPlaytimeSeconds = Math.max(0L, seconds); }
    public long getJoinCount() { return joinCount; }
    public void setJoinCount(long count) { this.joinCount = Math.max(0L, count); }
    public String getLastServer() { return lastServer; }
    public void setLastServer(String server) { this.lastServer = server; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getRank() { return rank == null ? "DEFAULT" : rank; }
    public void setRank(String rank) { this.rank = (rank == null || rank.isBlank()) ? "DEFAULT" : rank; }

    public Map<String, Boolean> getPermissions() {
        return Collections.unmodifiableMap(permissions);
    }

    public void setPermission(String permission, boolean value) {
        if (permission == null || permission.isBlank()) return;
        permissions.put(permission.toLowerCase(java.util.Locale.ROOT), value);
    }

    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) return false;
        Boolean value = permissions.get(permission.toLowerCase(java.util.Locale.ROOT));
        return value != null && value;
    }
}

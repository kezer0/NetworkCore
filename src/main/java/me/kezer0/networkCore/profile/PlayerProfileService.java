package me.kezer0.networkCore.profile;

import java.util.UUID;

public interface PlayerProfileService {
    PlayerProfile get(UUID uuid);
    PlayerProfile getOrCreate(UUID uuid, String username);
    boolean isLoaded(UUID uuid);
    String getRank(UUID uuid);
    void setRank(UUID uuid, String rank);
    boolean hasPermission(UUID uuid, String permission);
    void setPermission(UUID uuid, String permission, boolean value);
    long getTotalPlaytimeSeconds(UUID uuid);
}

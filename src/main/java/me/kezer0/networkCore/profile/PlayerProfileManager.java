package me.kezer0.networkCore.profile;

import me.kezer0.networkCore.NetworkCore;
import me.kezer0.networkCore.database.NetworkDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public final class PlayerProfileManager implements PlayerProfileService {
    private static final PlayerProfileManager INSTANCE = new PlayerProfileManager();
    private final ConcurrentMap<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();

    private PlayerProfileManager() {}

    public static PlayerProfileManager getInstance() { return INSTANCE; }

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        cache.put(uuid, new PlayerProfile(uuid, player.getName()));
        final String name = player.getName();
        if (!NetworkDatabase.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try {
                PlayerProfile profile = NetworkDatabase.loadProfile(uuid, name);
                cache.put(uuid, profile);
            } catch (Exception ex) {
                NetworkCore.getInstance().getLogger().log(Level.SEVERE, "Failed to load/start session for " + name, ex);
            }
        });
    }

    public PlayerProfile getCached(UUID uuid) { return uuid == null ? null : cache.get(uuid); }

    public void unload(UUID uuid) {
        if (uuid != null) cache.remove(uuid);
    }

    @Override public PlayerProfile get(UUID uuid) { return getCached(uuid); }
    @Override public PlayerProfile getOrCreate(UUID uuid, String username) { return cache.computeIfAbsent(uuid, id -> new PlayerProfile(id, username)); }
    @Override public boolean isLoaded(UUID uuid) { return getCached(uuid) != null; }
    @Override public String getRank(UUID uuid) { PlayerProfile p = getCached(uuid); return p == null ? "DEFAULT" : p.getRank(); }
    @Override public void setRank(UUID uuid, String rank) {
        PlayerProfile p = getOrCreate(uuid, null);
        if (p != null) p.setRank(rank);
        if (!NetworkDatabase.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try { NetworkDatabase.setRank(uuid, rank); } catch (Exception ex) { log("Failed to set rank for " + uuid, ex); }
        });
    }
    @Override public boolean hasPermission(UUID uuid, String permission) { PlayerProfile p = getCached(uuid); return p != null && p.hasPermission(permission); }
    @Override public void setPermission(UUID uuid, String permission, boolean value) {
        PlayerProfile p = getOrCreate(uuid, null);
        if (p != null) p.setPermission(permission, value);
        if (!NetworkDatabase.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try { NetworkDatabase.setPermission(uuid, permission, value); } catch (Exception ex) { log("Failed to set permission for " + uuid, ex); }
        });
    }
    @Override public long getTotalPlaytimeSeconds(UUID uuid) { PlayerProfile p = getCached(uuid); return p == null ? 0L : p.getTotalPlaytimeSeconds(); }

    public Map<UUID, PlayerProfile> snapshot() { return Map.copyOf(cache); }

    private void log(String message, Exception ex) { NetworkCore.getInstance().getLogger().log(Level.WARNING, message, ex); }
}

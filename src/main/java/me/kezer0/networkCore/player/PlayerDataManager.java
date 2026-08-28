package me.kezer0.networkCore.player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import me.kezer0.networkCore.NetworkCore;
import me.kezer0.networkCore.api.PlayerDataService;
import me.kezer0.networkCore.database.NetworkDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PlayerDataManager implements PlayerDataService {
    private static final ConcurrentMap<UUID, PlayerData> CACHE = new ConcurrentHashMap<>();
    private static PlayerDataManager instance;

    public static PlayerDataManager getInstance() {
        if (instance == null) instance = new PlayerDataManager();
        return instance;
    }

    private PlayerDataManager() {}

    public static void handleJoin(final Player player) {
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        CACHE.putIfAbsent(uuid, new PlayerData(uuid, name));
        if (!NetworkDatabase.isEnabled()) return;

        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try {
                PlayerData loaded = NetworkDatabase.loadPlayer(uuid, name);
                CACHE.put(uuid, loaded);
            } catch (Exception ex) {
                NetworkCore.getInstance().getLogger().log(Level.SEVERE,
                        "Failed to load network player data for " + name, ex);
            }
        });
    }

    public static void handleQuit(Player player) {
        if (player == null) return;
        PlayerData data = CACHE.remove(player.getUniqueId());
        if (data != null) saveAsync(data);
    }

    @Override
    public PlayerData get(UUID uuid) {
        return CACHE.get(uuid);
    }

    public static PlayerData getCached(UUID uuid) {
        return CACHE.get(uuid);
    }

    @Override
    public boolean isLoaded(UUID uuid) {
        return uuid != null && CACHE.containsKey(uuid);
    }

    public static PlayerData getOrCreate(UUID uuid, String username) {
        if (uuid == null) return null;
        return CACHE.computeIfAbsent(uuid, id -> new PlayerData(id, username));
    }

    public static void saveAsync(PlayerData data) {
        if (data == null || !NetworkDatabase.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(NetworkCore.getInstance(), () -> {
            try {
                NetworkDatabase.savePlayer(data);
            } catch (Exception ex) {
                NetworkCore.getInstance().getLogger().log(Level.WARNING,
                        "Failed to save network player data for " + data.getUuid(), ex);
            }
        });
    }

    public static void saveAll() {
        if (!NetworkDatabase.isEnabled()) return;
        for (Map.Entry<UUID, PlayerData> entry : CACHE.entrySet()) {
            try {
                NetworkDatabase.savePlayer(entry.getValue());
            } catch (Exception ex) {
                NetworkCore.getInstance().getLogger().log(Level.WARNING,
                        "Failed to save network player data for " + entry.getKey(), ex);
            }
        }
    }
}

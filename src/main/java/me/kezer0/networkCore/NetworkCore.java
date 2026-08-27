package me.kezer0.networkCore;

import me.kezer0.networkCore.database.NetworkDatabase;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import me.kezer0.networkCore.player.*;

/**
 * Shared network infrastructure plugin.
 *
 * This plugin owns PostgreSQL and shared player data. Game plugins should depend
 * on NetworkCore instead of creating their own PostgreSQL connection pools.
 */
public final class NetworkCore extends JavaPlugin {
    private static NetworkCore instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        NetworkDatabase.initialize(this);
        PlayerDataManager.initializeConfig(this);

        Bukkit.getPluginManager().registerEvents(new PlayerDataListener(), this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, PlayerDataManager::saveAll, 6000L, 6000L);

        getLogger().info("NetworkCore enabled. PostgreSQL and shared player data are owned by NetworkCore.");
    }

    @Override
    public void onDisable() {
        PlayerDataManager.saveAll();
        NetworkDatabase.close();
        instance = null;
    }

    public static NetworkCore getInstance() {
        if (instance == null) throw new IllegalStateException("NetworkCore is not enabled");
        return instance;
    }

}

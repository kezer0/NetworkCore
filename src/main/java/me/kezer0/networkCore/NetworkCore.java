package me.kezer0.networkCore;


import me.kezer0.networkCore.api.DatabaseService;
import me.kezer0.networkCore.api.PlayerDataService;
import me.kezer0.networkCore.database.NetworkDatabase;
import me.kezer0.networkCore.player.PlayerDataManager;
import me.kezer0.networkCore.profile.PlayerProfileManager;
import me.kezer0.networkCore.profile.PlayerProfileService;
import me.kezer0.networkCore.session.SessionManager;
import me.kezer0.networkCore.session.SessionService;
import me.kezer0.networkCore.telemetry.TelemetryManager;
import me.kezer0.networkCore.telemetry.TelemetryService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class NetworkCore extends JavaPlugin {
    private static NetworkCore instance;
    private NetworkDatabase database;
    private PlayerDataManager playerDataManager;
    private final PlayerProfileManager profileManager = PlayerProfileManager.getInstance();
    private final SessionManager sessionManager = SessionManager.getInstance();
    private final TelemetryManager telemetryManager = TelemetryManager.getInstance();
    private String serverName;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        serverName = getConfig().getString("server-name", "unknown");

        NetworkDatabase.initialize(this);
        database = NetworkDatabase.instance();
        playerDataManager = PlayerDataManager.getInstance();

        if (NetworkDatabase.isEnabled()) {
            try {
                NetworkDatabase.recoverOpenSessions(serverName);
            } catch (Exception ex) {
                getLogger().log(java.util.logging.Level.WARNING, "Failed to recover previous sessions for server " + serverName, ex);
            }
        }

        Bukkit.getServicesManager().register(DatabaseService.class, database, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(PlayerDataService.class, playerDataManager, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(PlayerProfileService.class, profileManager, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(SessionService.class, sessionManager, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(TelemetryService.class, telemetryManager, this, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(new PlayerDataListener(), this);

        // Existing skill/quest cache remains supported. It is autosaved every five minutes.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, PlayerDataManager::saveAll, 6000L, 6000L);
        getLogger().info("NetworkCore enabled for server '" + serverName + "'. Shared player identity, sessions, ranks, permissions and telemetry are available.");
    }

    @Override
    public void onDisable() {
        PlayerDataManager.saveAll();
        sessionManager.closeAll("server_shutdown");
        Bukkit.getServicesManager().unregisterAll(this);
        NetworkDatabase.close();
        database = null;
        playerDataManager = null;
        instance = null;
    }

    public static NetworkCore getInstance() {
        if (instance == null) throw new IllegalStateException("NetworkCore is not enabled");
        return instance;
    }

    public String getServerName() { return serverName; }
}

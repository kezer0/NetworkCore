package me.kezer0.networkCore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import me.kezer0.networkCore.api.DatabaseService;
import me.kezer0.networkCore.player.PlayerData;
import me.kezer0.networkCore.profile.PlayerProfile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;

/**
 * Shared PostgreSQL infrastructure for network-wide data.
 *
 * Owns only shared/network data. Economy is owned by the economy plugin through Vault/EternalEconomy,
 * while OneBlock/Survival own their mode-specific data.
 */
public final class NetworkDatabase implements DatabaseService {
    private static NetworkDatabase instance;
    private static HikariDataSource dataSource;
    private static boolean enabled;

    private NetworkDatabase() {}

    public static NetworkDatabase instance() {
        if (instance == null) instance = new NetworkDatabase();
        return instance;
    }

    public static void initialize(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "database.yml");
        if (!file.exists()) plugin.saveResource("database.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        enabled = cfg.getBoolean("enabled", true);
        if (!enabled) {
            plugin.getLogger().info("Network PostgreSQL database is disabled.");
            return;
        }

        try {
            HikariConfig hikari = new HikariConfig();
            String host = cfg.getString("host", "localhost");
            int port = cfg.getInt("port", 5432);
            String database = cfg.getString("database", "minecraft_network");
            String username = cfg.getString("username", "postgres");
            String password = cfg.getString("password", "change-me");

            hikari.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
            hikari.setUsername(username);
            hikari.setPassword(password);
            hikari.setMaximumPoolSize(Math.max(2, cfg.getInt("pool.maximum", 8)));
            hikari.setMinimumIdle(Math.max(1, cfg.getInt("pool.minimum-idle", 2)));
            hikari.setConnectionTimeout(cfg.getLong("pool.connection-timeout-ms", 10000L));
            hikari.setMaxLifetime(cfg.getLong("pool.max-lifetime-ms", 1800000L));
            hikari.setPoolName("NetworkCore-PostgreSQL");

            dataSource = new HikariDataSource(hikari);
            createTables();
            migrateLegacyTables(plugin);
            plugin.getLogger().info("Network PostgreSQL database initialized successfully (minecraft_network / network schema).");
        } catch (Exception ex) {
            enabled = false;
            if (dataSource != null) dataSource.close();
            dataSource = null;
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize the network PostgreSQL database. Shared DB features will be unavailable.", ex);
        }
    }

    @Override
    public boolean isAvailable() {
        return isEnabled();
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!isEnabled()) throw new SQLException("NetworkCore PostgreSQL is not available");
        return dataSource.getConnection();
    }

    public static boolean isEnabled() {
        return enabled && dataSource != null && !dataSource.isClosed();
    }

    private static void createTables() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS network");
            s.execute("CREATE TABLE IF NOT EXISTS network.players (" +
                    "uuid UUID PRIMARY KEY, username VARCHAR(16) NOT NULL, " +
                    "first_joined TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "last_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "total_playtime_seconds BIGINT NOT NULL DEFAULT 0, " +
                    "join_count BIGINT NOT NULL DEFAULT 0, " +
                    "last_server VARCHAR(64), locale VARCHAR(32), " +
                    "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)" );
            s.execute("CREATE TABLE IF NOT EXISTS network.sessions (" +
                    "id UUID PRIMARY KEY, player_uuid UUID NOT NULL REFERENCES network.players(uuid) ON DELETE CASCADE, " +
                    "server_name VARCHAR(64) NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "ended_at TIMESTAMPTZ, duration_seconds BIGINT, end_reason VARCHAR(64))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_network_sessions_player_started ON network.sessions(player_uuid, started_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_network_sessions_open ON network.sessions(server_name) WHERE ended_at IS NULL");
            s.execute("CREATE TABLE IF NOT EXISTS network.ranks (" +
                    "player_uuid UUID PRIMARY KEY REFERENCES network.players(uuid) ON DELETE CASCADE, " +
                    "rank VARCHAR(64) NOT NULL DEFAULT 'DEFAULT', granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "expires_at TIMESTAMPTZ)");
            s.execute("CREATE TABLE IF NOT EXISTS network.permissions (" +
                    "player_uuid UUID NOT NULL REFERENCES network.players(uuid) ON DELETE CASCADE, " +
                    "permission VARCHAR(128) NOT NULL, allowed BOOLEAN NOT NULL, " +
                    "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY(player_uuid, permission))");
            s.execute("CREATE TABLE IF NOT EXISTS network.telemetry_events (" +
                    "id BIGSERIAL PRIMARY KEY, player_uuid UUID REFERENCES network.players(uuid) ON DELETE SET NULL, " +
                    "event_type VARCHAR(64) NOT NULL, server_name VARCHAR(64), event_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "metadata JSONB)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_network_telemetry_player_time ON network.telemetry_events(player_uuid, event_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_network_telemetry_type_time ON network.telemetry_events(event_type, event_at DESC)");

            // Existing shared skills/quests remain network-owned, but are now under the shared schema.
            s.execute("CREATE TABLE IF NOT EXISTS network.player_skills (" +
                    "uuid UUID PRIMARY KEY REFERENCES network.players(uuid) ON DELETE CASCADE, " +
                    "mining_level INT NOT NULL DEFAULT 1, building_level INT NOT NULL DEFAULT 1, combat_level INT NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS network.player_quest (" +
                    "uuid UUID NOT NULL REFERENCES network.players(uuid) ON DELETE CASCADE, quest_id VARCHAR(64) NOT NULL, " +
                    "progress INT NOT NULL DEFAULT 0, completed BOOLEAN NOT NULL DEFAULT FALSE, " +
                    "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(uuid, quest_id))");
        }
    }

    private static void migrateLegacyTables(JavaPlugin plugin) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                if (tableExists(s, "player")) {
                    s.executeUpdate("INSERT INTO network.players(uuid, username, first_joined, last_seen) " +
                            "SELECT uuid, username, first_joined, last_seen FROM public.player " +
                            "ON CONFLICT (uuid) DO NOTHING");
                }
                if (tableExists(s, "player_skills")) {
                    s.executeUpdate("INSERT INTO network.player_skills(uuid, mining_level, building_level, combat_level) " +
                            "SELECT uuid, mining_level, building_level, combat_level FROM public.player_skills " +
                            "ON CONFLICT (uuid) DO NOTHING");
                }
                if (tableExists(s, "player_quest")) {
                    s.executeUpdate("INSERT INTO network.player_quest(uuid, quest_id, progress, completed, updated_at) " +
                            "SELECT uuid, quest_id, progress, completed, updated_at FROM public.player_quest " +
                            "ON CONFLICT (uuid, quest_id) DO NOTHING");
                }
                c.commit();
                plugin.getLogger().info("Legacy NetworkCore player tables migrated when present; legacy tables were not removed.");
            } catch (SQLException ex) {
                c.rollback();
                // The legacy tables may simply not exist on a fresh database.
                plugin.getLogger().fine("No legacy public player tables were migrated: " + ex.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Unable to inspect legacy player tables. New network schema remains usable.", ex);
        }
    }

    private static boolean tableExists(Statement statement, String tableName) throws SQLException {
        try (PreparedStatement p = statement.getConnection().prepareStatement("SELECT to_regclass(?)")) {
            p.setString(1, "public." + tableName);
            try (ResultSet rs = p.executeQuery()) { return rs.next() && rs.getString(1) != null; }
        }
    }

    public static PlayerProfile loadProfile(UUID uuid, String username) throws SQLException {
        requireEnabled();
        String safeName = username == null || username.isBlank() ? "Unknown" : username;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.players(uuid, username) VALUES (?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET username=EXCLUDED.username, updated_at=CURRENT_TIMESTAMP")) {
                    p.setObject(1, uuid);
                    p.setString(2, safeName);
                    p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.player_skills(uuid) VALUES (?) ON CONFLICT DO NOTHING")) {
                    p.setObject(1, uuid); p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.ranks(player_uuid) VALUES (?) ON CONFLICT DO NOTHING")) {
                    p.setObject(1, uuid); p.executeUpdate();
                }

                PlayerProfile profile = new PlayerProfile(uuid, safeName);
                try (PreparedStatement p = c.prepareStatement("SELECT first_joined, last_seen, total_playtime_seconds, join_count, last_server, locale FROM network.players WHERE uuid=?")) {
                    p.setObject(1, uuid);
                    try (ResultSet rs = p.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Player row disappeared while loading " + uuid);
                        profile.setFirstJoined(toInstant(rs.getTimestamp(1)));
                        profile.setLastSeen(toInstant(rs.getTimestamp(2)));
                        profile.setTotalPlaytimeSeconds(rs.getLong(3));
                        profile.setJoinCount(rs.getLong(4));
                        profile.setLastServer(rs.getString(5));
                        profile.setLocale(rs.getString(6));
                    }
                }
                try (PreparedStatement p = c.prepareStatement("SELECT rank, expires_at FROM network.ranks WHERE player_uuid=?")) {
                    p.setObject(1, uuid);
                    try (ResultSet rs = p.executeQuery()) {
                        if (rs.next()) {
                            Timestamp expires = rs.getTimestamp(2);
                            String rank = rs.getString(1);
                            profile.setRank(expires != null && expires.toInstant().isBefore(Instant.now()) ? "DEFAULT" : rank);
                        }
                    }
                }
                try (PreparedStatement p = c.prepareStatement("SELECT permission, allowed FROM network.permissions WHERE player_uuid=?")) {
                    p.setObject(1, uuid);
                    try (ResultSet rs = p.executeQuery()) {
                        while (rs.next()) profile.setPermission(rs.getString(1), rs.getBoolean(2));
                    }
                }
                c.commit();
                return profile;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }


    /** Backwards-compatible shared player data loader for skills/quests. */
    public static PlayerData loadPlayer(UUID uuid, String username) throws SQLException {
        requireEnabled();
        PlayerProfile profile = loadProfile(uuid, username);
        PlayerData data = new PlayerData(uuid, username);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement p = c.prepareStatement("SELECT mining_level, building_level, combat_level FROM network.player_skills WHERE uuid=?")) {
                p.setObject(1, uuid);
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) {
                        data.getSkills().put("mining", rs.getInt(1));
                        data.getSkills().put("building", rs.getInt(2));
                        data.getSkills().put("combat", rs.getInt(3));
                    }
                }
            }
            try (PreparedStatement p = c.prepareStatement("SELECT quest_id, progress, completed FROM network.player_quest WHERE uuid=?")) {
                p.setObject(1, uuid);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        data.getQuests().put(rs.getString(1), new PlayerData.QuestData(rs.getString(1), rs.getInt(2), rs.getBoolean(3)));
                    }
                }
            }
        }
        return data;
    }

    public static void savePlayer(PlayerData data) throws SQLException {
        requireEnabled();
        if (data == null) return;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.players(uuid,username,last_seen) VALUES (?, ?, CURRENT_TIMESTAMP) ON CONFLICT(uuid) DO UPDATE SET username=EXCLUDED.username, last_seen=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP")) {
                    p.setObject(1, data.getUuid());
                    p.setString(2, data.getUsername());
                    p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.player_skills(uuid,mining_level,building_level,combat_level) VALUES (?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET mining_level=EXCLUDED.mining_level, building_level=EXCLUDED.building_level, combat_level=EXCLUDED.combat_level")) {
                    p.setObject(1, data.getUuid());
                    p.setInt(2, Math.max(1, data.getSkills().getOrDefault("mining", 1)));
                    p.setInt(3, Math.max(1, data.getSkills().getOrDefault("building", 1)));
                    p.setInt(4, Math.max(1, data.getSkills().getOrDefault("combat", 1)));
                    p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.player_quest(uuid,quest_id,progress,completed) VALUES (?,?,?,?) ON CONFLICT(uuid,quest_id) DO UPDATE SET progress=EXCLUDED.progress, completed=EXCLUDED.completed, updated_at=CURRENT_TIMESTAMP")) {
                    for (PlayerData.QuestData q : data.getQuests().values()) {
                        p.setObject(1, data.getUuid());
                        p.setString(2, q.getQuestId());
                        p.setInt(3, q.getProgress());
                        p.setBoolean(4, q.isCompleted());
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (SQLException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    public static UUID startSession(UUID uuid, String username, String serverName, String locale) throws SQLException {
        requireEnabled();
        UUID sessionId = UUID.randomUUID();
        String safeName = username == null || username.isBlank() ? "Unknown" : username;
        String safeServer = serverName == null || serverName.isBlank() ? "unknown" : serverName;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.players(uuid, username, last_seen, last_server, locale, join_count) VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?, 1) " +
                        "ON CONFLICT(uuid) DO UPDATE SET username=EXCLUDED.username, last_seen=CURRENT_TIMESTAMP, last_server=EXCLUDED.last_server, locale=EXCLUDED.locale, join_count=network.players.join_count+1, updated_at=CURRENT_TIMESTAMP")) {
                    p.setObject(1, uuid);
                    p.setString(2, safeName);
                    p.setString(3, safeServer);
                    p.setString(4, locale);
                    p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO network.sessions(id, player_uuid, server_name) VALUES (?, ?, ?)")) {
                    p.setObject(1, sessionId);
                    p.setObject(2, uuid);
                    p.setString(3, safeServer);
                    p.executeUpdate();
                }
                c.commit();
                return sessionId;
            } catch (SQLException ex) {
                c.rollback(); throw ex;
            } finally { c.setAutoCommit(true); }
        }
    }

    public static void endSession(UUID sessionId, String reason) throws SQLException {
        requireEnabled();
        if (sessionId == null) return;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID playerUuid = null;
                long duration = 0L;
                try (PreparedStatement p = c.prepareStatement("UPDATE network.sessions SET ended_at=CURRENT_TIMESTAMP, duration_seconds=GREATEST(0, EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at))::BIGINT), end_reason=? WHERE id=? AND ended_at IS NULL RETURNING player_uuid,duration_seconds")) {
                    p.setString(1, reason == null ? "disconnect" : reason);
                    p.setObject(2, sessionId);
                    try (ResultSet rs = p.executeQuery()) {
                        if (rs.next()) { playerUuid = (UUID) rs.getObject(1); duration = rs.getLong(2); }
                    }
                }
                if (playerUuid != null) {
                    try (PreparedStatement p = c.prepareStatement("UPDATE network.players SET total_playtime_seconds=total_playtime_seconds+?, last_seen=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE uuid=?")) {
                        p.setLong(1, duration); p.setObject(2, playerUuid); p.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    public static void recoverOpenSessions(String serverName) throws SQLException {
        requireEnabled();
        String safeServer = serverName == null || serverName.isBlank() ? "unknown" : serverName;
        Map<UUID, Long> durations = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("UPDATE network.sessions SET ended_at=CURRENT_TIMESTAMP, duration_seconds=GREATEST(0, EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at))::BIGINT), end_reason='server_restart' WHERE server_name=? AND ended_at IS NULL RETURNING player_uuid,duration_seconds")) {
                    p.setString(1, safeServer);
                    try (ResultSet rs = p.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = (UUID) rs.getObject(1);
                            durations.merge(uuid, rs.getLong(2), Long::sum);
                        }
                    }
                }
                try (PreparedStatement p = c.prepareStatement("UPDATE network.players SET total_playtime_seconds=total_playtime_seconds+?, last_seen=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE uuid=?")) {
                    for (Map.Entry<UUID, Long> e : durations.entrySet()) {
                        p.setLong(1, e.getValue()); p.setObject(2, e.getKey()); p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (SQLException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    public static void setRank(UUID uuid, String rank) throws SQLException {
        requireEnabled();
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement("INSERT INTO network.ranks(player_uuid,rank) VALUES (?,?) ON CONFLICT(player_uuid) DO UPDATE SET rank=EXCLUDED.rank, granted_at=CURRENT_TIMESTAMP, expires_at=NULL")) {
            p.setObject(1, uuid); p.setString(2, rank == null || rank.isBlank() ? "DEFAULT" : rank); p.executeUpdate();
        }
    }

    public static void setPermission(UUID uuid, String permission, boolean allowed) throws SQLException {
        requireEnabled();
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement("INSERT INTO network.permissions(player_uuid,permission,allowed) VALUES (?,?,?) ON CONFLICT(player_uuid,permission) DO UPDATE SET allowed=EXCLUDED.allowed, updated_at=CURRENT_TIMESTAMP")) {
            p.setObject(1, uuid); p.setString(2, permission.toLowerCase(Locale.ROOT)); p.setBoolean(3, allowed); p.executeUpdate();
        }
    }

    public static void recordTelemetry(UUID uuid, String eventType, String serverName, Map<String, Object> metadata) throws SQLException {
        requireEnabled();
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement("INSERT INTO network.telemetry_events(player_uuid,event_type,server_name,metadata) VALUES (?,?,?,?::jsonb)")) {
            p.setObject(1, uuid);
            p.setString(2, eventType);
            p.setString(3, serverName);
            p.setString(4, toJson(metadata));
            p.executeUpdate();
        }
    }

    private static void requireEnabled() throws SQLException {
        if (!isEnabled()) throw new SQLException("NetworkCore PostgreSQL is not available");
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return "{}";
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(jsonEscape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) b.append("null");
            else if (v instanceof Number || v instanceof Boolean) b.append(v);
            else b.append('"').append(jsonEscape(String.valueOf(v))).append('"');
        }
        return b.append('}').toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
        dataSource = null;
        enabled = false;
    }
}

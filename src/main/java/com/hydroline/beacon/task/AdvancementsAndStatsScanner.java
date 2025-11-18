package com.hydroline.beacon.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.storage.DatabaseManager;
import com.hydroline.beacon.util.PathUtils;
import com.hydroline.beacon.world.WorldFileAccess;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;

public class AdvancementsAndStatsScanner {

    private static final String FILE_TYPE_ADVANCEMENTS = "advancements";
    // Bump the stats file type key to force a rescan after fixing nested parsing.
    private static final String FILE_TYPE_STATS = "stats_v2";

    private final BeaconPlugin plugin;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdvancementsAndStatsScanner(BeaconPlugin plugin) {
        this.plugin = plugin;
    }

    public void scanOnce() {
        WorldFileAccess worldFileAccess = plugin.getWorldFileAccess();
        DatabaseManager db = plugin.getDatabaseManager();
        if (worldFileAccess == null || db == null) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        int filesProcessed = 0;
        int recordsUpserted = 0;

        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);

            for (World world : worldFileAccess.getWorlds()) {
                File advDir = worldFileAccess.getAdvancementsDirectory(world);
                if (advDir.isDirectory()) {
                    File[] files = advDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                    if (files != null) {
                        for (File file : files) {
                            int changed = processPlayerFile(connection, world, FILE_TYPE_ADVANCEMENTS, file);
                            if (changed >= 0) {
                                filesProcessed++;
                                recordsUpserted += changed;
                            }
                        }
                    }
                }

                File statsDir = worldFileAccess.getStatsDirectory(world);
                if (statsDir.isDirectory()) {
                    File[] files = statsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                    if (files != null) {
                        for (File file : files) {
                            int changed = processPlayerFile(connection, world, FILE_TYPE_STATS, file);
                            if (changed >= 0) {
                                filesProcessed++;
                                recordsUpserted += changed;
                            }
                        }
                    }
                }
            }

            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to scan advancements/stats: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (filesProcessed > 0 || recordsUpserted > 0) {
            plugin.getLogger().info("Advancements/Stats scan completed in " + elapsed + " ms, " +
                    "files processed=" + filesProcessed + ", records upserted=" + recordsUpserted);
        }
    }

    private int processPlayerFile(Connection connection, World world, String fileType, File file) {
        long lastModified = file.lastModified();
        String absolutePath = PathUtils.toServerRelativePath(plugin, file);

        try {
            if (!shouldProcessFile(connection, fileType, absolutePath, lastModified)) {
                return 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check sync state for file " + absolutePath + ": " + e.getMessage());
            return -1;
        }

        String fileName = file.getName();
        if (!fileName.toLowerCase().endsWith(".json")) {
            return 0;
        }
        String playerUuid = fileName.substring(0, fileName.length() - ".json".length());

        JsonNode root;
        try (FileInputStream in = new FileInputStream(file)) {
            root = objectMapper.readTree(in);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to read JSON file " + absolutePath + ": " + e.getMessage());
            return -1;
        }

        int upserted = 0;
        long now = System.currentTimeMillis();

        try {
            if (FILE_TYPE_ADVANCEMENTS.equals(fileType)) {
                // full-sync this player's advancements: delete stale rows then re-insert current ones
                deletePlayerAdvancementsForFile(connection, playerUuid);

                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String advancementKey = entry.getKey();
                    String valueJson = entry.getValue().toString();
                    upsertAdvancement(connection, playerUuid, advancementKey, valueJson, now);
                    upserted++;
                }
            } else if (FILE_TYPE_STATS.equals(fileType)) {
                // full-sync this player's stats: delete stale rows then re-insert current ones
                deletePlayerStatsForFile(connection, playerUuid);

                JsonNode statsNode = root.get("stats");
                if (statsNode != null && statsNode.isObject()) {
                    upserted += upsertStatsRecursive(connection, playerUuid, statsNode, "", now);
                } else {
                    upserted += upsertStatsRecursive(connection, playerUuid, root, "", now);
                }
            }

            upsertFileSyncState(connection, fileType, playerUuid, absolutePath, lastModified, now);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to upsert data for file " + absolutePath + ": " + e.getMessage());
            return -1;
        }

        return upserted;
    }

    private int upsertStatsRecursive(Connection connection,
                                     String playerUuid,
                                     JsonNode node,
                                     String prefix,
                                     long lastUpdated) throws SQLException {
        if (node == null || !node.isObject()) {
            return 0;
        }

        int upserted = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldKey = entry.getKey();
            if (prefix.isEmpty() && "DataVersion".equals(fieldKey)) {
                continue;
            }

            String statKey = prefix.isEmpty() ? fieldKey : prefix + ":" + fieldKey;
            JsonNode valueNode = entry.getValue();

            if (valueNode.isObject()) {
                upserted += upsertStatsRecursive(connection, playerUuid, valueNode, statKey, lastUpdated);
            } else if (valueNode.isNumber()) {
                long value = valueNode.asLong(0L);
                upsertStat(connection, playerUuid, statKey, value, lastUpdated);
                upserted++;
            }
        }

        return upserted;
    }

    private boolean shouldProcessFile(Connection connection,
                                      String fileType,
                                      String filePath,
                                      long lastModified) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_modified, last_processed FROM file_sync_state " +
                        "WHERE file_type = ? AND file_path = ?"
        )) {
            ps.setString(1, fileType);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return true;
                }
                long storedLastModified = rs.getLong("last_modified");
                long lastProcessed = rs.getLong("last_processed");
                return storedLastModified != lastModified || lastProcessed < lastModified;
            }
        }
    }

    private void upsertAdvancement(Connection connection,
                                   String playerUuid,
                                   String advancementKey,
                                   String valueJson,
                                   long lastUpdated) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_advancements (player_uuid, advancement_key, value, last_updated) " +
                        "VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(player_uuid, advancement_key) DO UPDATE SET " +
                        "value = excluded.value, last_updated = excluded.last_updated"
        )) {
            ps.setString(1, playerUuid);
            ps.setString(2, advancementKey);
            ps.setBytes(3, valueJson.getBytes(StandardCharsets.UTF_8));
            ps.setLong(4, lastUpdated);
            ps.executeUpdate();
        }
    }

    private void upsertStat(Connection connection,
                            String playerUuid,
                            String statKey,
                            long value,
                            long lastUpdated) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_stats (player_uuid, stat_key, value, last_updated) " +
                        "VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(player_uuid, stat_key) DO UPDATE SET " +
                        "value = excluded.value, last_updated = excluded.last_updated"
        )) {
            ps.setString(1, playerUuid);
            ps.setString(2, statKey);
            ps.setLong(3, value);
            ps.setLong(4, lastUpdated);
            ps.executeUpdate();
        }
    }

    private void deletePlayerAdvancementsForFile(Connection connection,
                                                 String playerUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM player_advancements WHERE player_uuid = ?"
        )) {
            ps.setString(1, playerUuid);
            ps.executeUpdate();
        }
    }

    private void deletePlayerStatsForFile(Connection connection,
                                          String playerUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM player_stats WHERE player_uuid = ?"
        )) {
            ps.setString(1, playerUuid);
            ps.executeUpdate();
        }
    }

    private void upsertFileSyncState(Connection connection,
                                     String fileType,
                                     String playerUuid,
                                     String filePath,
                                     long lastModified,
                                     long lastProcessed) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO file_sync_state (file_type, player_uuid, file_path, last_modified, last_processed) " +
                        "VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(file_type, file_path) DO UPDATE SET " +
                        "last_modified = excluded.last_modified, last_processed = excluded.last_processed"
        )) {
            ps.setString(1, fileType);
            ps.setString(2, playerUuid);
            ps.setString(3, filePath);
            ps.setLong(4, lastModified);
            ps.setLong(5, lastProcessed);
            ps.executeUpdate();
        }
    }
}


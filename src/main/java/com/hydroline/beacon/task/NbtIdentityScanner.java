package com.hydroline.beacon.task;

import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.storage.DatabaseManager;
import com.hydroline.beacon.util.PathUtils;
import com.hydroline.beacon.util.NbtUtils;
import com.hydroline.beacon.world.WorldFileAccess;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class NbtIdentityScanner {

    private final BeaconPlugin plugin;

    public NbtIdentityScanner(BeaconPlugin plugin) {
        this.plugin = plugin;
    }

    public void scanOnce() {
        WorldFileAccess wfa = plugin.getWorldFileAccess();
        DatabaseManager db = plugin.getDatabaseManager();
        if (wfa == null || db == null) return;

        long started = System.currentTimeMillis();
        int filesProcessed = 0;
        int upserts = 0;

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            List<World> worlds = wfa.getWorlds();
            for (World world : worlds) {
                File dir = new File(world.getWorldFolder(), "playerdata");
                if (!dir.isDirectory()) continue;
                File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".dat"));
                if (files == null) continue;
                for (File f : files) {
                    String uuid = stripDat(f.getName());
                    if (uuid == null || uuid.length() < 32) continue;
                    filesProcessed++;
                    String playerName = null;
                    Long firstPlayed = null;
                    Long lastPlayed = null;
                    Map<String, Object> nbt = null;
                    try (FileInputStream in = new FileInputStream(f)) {
                        nbt = NbtUtils.readPlayerDatToMap(in);
                        // Common CraftBukkit path: bukkit -> lastKnownName
                        Object bkt = nbt.get("bukkit");
                        if (bkt instanceof Map) {
                            Object lkn = ((Map<?, ?>) bkt).get("lastKnownName");
                            if (lkn instanceof String) {
                                playerName = (String) lkn;
                            }
                            if (firstPlayed == null) {
                                firstPlayed = asLong(((Map<?, ?>) bkt).get("firstPlayed"));
                            }
                            if (lastPlayed == null) {
                                lastPlayed = asLong(((Map<?, ?>) bkt).get("lastPlayed"));
                            }
                        }
                        if (firstPlayed == null) {
                            firstPlayed = asLong(nbt.get("firstPlayed"));
                        }
                        if (lastPlayed == null) {
                            lastPlayed = asLong(nbt.get("lastPlayed"));
                        }
                    } catch (IOException e) {
                        plugin.getLogger().warning("Failed to parse NBT for " + PathUtils.toServerRelativePath(plugin, f) + ": " + e.getMessage());
                    }

                    long now = System.currentTimeMillis();

                    if (playerName != null && !playerName.isEmpty()) {
                        upsertIdentity(conn, uuid, playerName, firstPlayed, lastPlayed, now);
                        upserts++;
                    }

                    // Keep raw NBT JSON cache in sync with current playerdata
                    if (nbt != null) {
                        try {
                            upsertPlayerNbtCache(conn, uuid, NbtUtils.toJson(nbt), now);
                        } catch (IOException e) {
                            plugin.getLogger().warning("Failed to serialize NBT JSON for " + uuid + ": " + e.getMessage());
                        }
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to scan player identities: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - started;
        if (filesProcessed > 0 || upserts > 0) {
            plugin.getLogger().info("NBT identity scan completed in " + elapsed + " ms, files=" + filesProcessed + ", upserts=" + upserts);
        }
    }

    private void upsertIdentity(Connection conn, String uuid, String name, Long firstPlayed, Long lastPlayed, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_identities (player_uuid, player_name, first_played, last_played, last_updated) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(player_uuid) DO UPDATE SET " +
                        "player_name=excluded.player_name, " +
                        "first_played=COALESCE(excluded.first_played, player_identities.first_played), " +
                        "last_played=COALESCE(excluded.last_played, player_identities.last_played), " +
                        "last_updated=excluded.last_updated"
        )) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            if (firstPlayed != null) {
                ps.setLong(3, firstPlayed);
            } else {
                ps.setNull(3, java.sql.Types.BIGINT);
            }
            if (lastPlayed != null) {
                ps.setLong(4, lastPlayed);
            } else {
                ps.setNull(4, java.sql.Types.BIGINT);
            }
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    private void upsertPlayerNbtCache(Connection conn, String uuid, String rawJson, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_nbt_cache (player_uuid, raw_json, cached_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT(player_uuid) DO UPDATE SET " +
                        "raw_json = excluded.raw_json, " +
                        "cached_at = excluded.cached_at"
        )) {
            ps.setString(1, uuid);
            ps.setString(2, rawJson);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String stripDat(String name) {
        if (name == null) return null;
        if (name.toLowerCase().endsWith(".dat")) {
            return name.substring(0, name.length() - 4);
        }
        return null;
    }
}

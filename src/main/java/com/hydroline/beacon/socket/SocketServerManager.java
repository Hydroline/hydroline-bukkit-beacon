package com.hydroline.beacon.socket;

import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.DataListener;
import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.config.PluginConfig;
import com.hydroline.beacon.task.AdvancementsAndStatsScanner;
import com.hydroline.beacon.task.MtrLogsScanner;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class SocketServerManager {

    private final BeaconPlugin plugin;
    private SocketIOServer server;

    public SocketServerManager(BeaconPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        PluginConfig cfg = plugin.getConfigManager().getCurrentConfig();

        Configuration configuration = new Configuration();
        configuration.setHostname("0.0.0.0");
        configuration.setPort(cfg.getPort());

        server = new SocketIOServer(configuration);
        registerListeners();
        server.start();

        plugin.getLogger().info("Socket.IO server started on port " + cfg.getPort());
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
            plugin.getLogger().info("Socket.IO server stopped.");
        }
    }

    private void registerListeners() {
        server.addEventListener("force_update", ForceUpdateRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        new AdvancementsAndStatsScanner(plugin).scanOnce();
                        new MtrLogsScanner(plugin).scanOnce();
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        ackSender.sendAckData(resp);
                    });
                });

        server.addEventListener("get_player_advancements", PlayerUuidRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        Map<String, Object> resp = new HashMap<>();
                        try {
                            Map<String, String> advancements =
                                    loadAdvancementsForPlayer(data.getPlayerUuid());
                            resp.put("success", true);
                            resp.put("player_uuid", data.getPlayerUuid());
                            resp.put("advancements", advancements);
                            ackSender.sendAckData(resp);
                        } catch (SQLException e) {
                            sendError(ackSender, "DB_ERROR: " + e.getMessage());
                        }
                    });
                });

        server.addEventListener("get_player_stats", PlayerUuidRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        Map<String, Object> resp = new HashMap<>();
                        try {
                            Map<String, Long> stats =
                                    loadStatsForPlayer(data.getPlayerUuid());
                            resp.put("success", true);
                            resp.put("player_uuid", data.getPlayerUuid());
                            resp.put("stats", stats);
                            ackSender.sendAckData(resp);
                        } catch (SQLException e) {
                            sendError(ackSender, "DB_ERROR: " + e.getMessage());
                        }
                    });
                });

        server.addEventListener("list_online_players", AuthOnlyRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        Future<List<Map<String, Object>>> future =
                                Bukkit.getScheduler().callSyncMethod(plugin, this::collectOnlinePlayers);
                        try {
                            List<Map<String, Object>> players = future.get();
                            Map<String, Object> resp = new HashMap<>();
                            resp.put("success", true);
                            resp.put("players", players);
                            ackSender.sendAckData(resp);
                        } catch (InterruptedException | ExecutionException e) {
                            sendError(ackSender, "INTERNAL_ERROR: " + e.getMessage());
                        }
                    });
                });

        server.addEventListener("get_server_time", AuthOnlyRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        Future<Map<String, Object>> future =
                                Bukkit.getScheduler().callSyncMethod(plugin, this::collectServerTime);
                        try {
                            Map<String, Object> info = future.get();
                            Map<String, Object> resp = new HashMap<>();
                            resp.put("success", true);
                            resp.putAll(info);
                            ackSender.sendAckData(resp);
                        } catch (InterruptedException | ExecutionException e) {
                            sendError(ackSender, "INTERNAL_ERROR: " + e.getMessage());
                        }
                    });
                });
    }

    private boolean validateKey(String key) {
        PluginConfig cfg = plugin.getConfigManager().getCurrentConfig();
        return cfg.getKey() != null && cfg.getKey().equals(key);
    }

    private void sendError(AckRequest ackSender, String message) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("error", message);
        ackSender.sendAckData(resp);
    }

    private Map<String, String> loadAdvancementsForPlayer(String playerUuid) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT advancement_key, value FROM player_advancements WHERE player_uuid = ?"
            )) {
                ps.setString(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString("advancement_key");
                        byte[] valueBytes = rs.getBytes("value");
                        String value = valueBytes != null
                                ? new String(valueBytes, java.nio.charset.StandardCharsets.UTF_8)
                                : null;
                        result.put(key, value);
                    }
                }
            }
        }
        return result;
    }

    private Map<String, Long> loadStatsForPlayer(String playerUuid) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT stat_key, value FROM player_stats WHERE player_uuid = ?"
            )) {
                ps.setString(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString("stat_key");
                        long value = rs.getLong("value");
                        result.put(key, value);
                    }
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> collectOnlinePlayers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Object> item = new HashMap<>();
            item.put("uuid", player.getUniqueId().toString());
            item.put("name", player.getName());
            item.put("health", player.getHealth());
            item.put("max_health", player.getMaxHealth());
            GameMode gameMode = player.getGameMode();
            item.put("game_mode", gameMode != null ? gameMode.name() : null);
            World world = player.getWorld();
            item.put("world", world != null ? world.getName() : null);
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> collectServerTime() {
        Map<String, Object> result = new HashMap<>();
        World world = null;
        for (World w : Bukkit.getWorlds()) {
            world = w;
            break;
        }
        if (world == null) {
            result.put("world", null);
            result.put("time", null);
            result.put("full_time", null);
            result.put("do_daylight_cycle", null);
            return result;
        }
        result.put("world", world.getName());
        result.put("time", world.getTime());
        result.put("full_time", world.getFullTime());
        String gamerule = world.getGameRuleValue("doDaylightCycle");
        result.put("do_daylight_cycle", gamerule);
        return result;
    }

    public interface AuthPayload {
        String getKey();
    }

    public static class AuthOnlyRequest implements AuthPayload {
        private String key;

        public AuthOnlyRequest() {
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class ForceUpdateRequest implements AuthPayload {
        private String key;

        public ForceUpdateRequest() {
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class PlayerUuidRequest implements AuthPayload {
        private String key;
        private String playerUuid;

        public PlayerUuidRequest() {
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getPlayerUuid() {
            return playerUuid;
        }

        public void setPlayerUuid(String playerUuid) {
            this.playerUuid = playerUuid;
        }
    }
}


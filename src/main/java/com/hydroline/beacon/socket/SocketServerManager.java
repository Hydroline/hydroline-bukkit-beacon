package com.hydroline.beacon.socket;

import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.ConnectListener;
import com.corundumstudio.socketio.listener.DisconnectListener;
import com.corundumstudio.socketio.listener.ExceptionListener;
import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.config.PluginConfig;
import com.hydroline.beacon.task.AdvancementsAndStatsScanner;
import com.hydroline.beacon.task.MtrLogsScanner;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class SocketServerManager {

    private final BeaconPlugin plugin;
    private SocketIOServer server;
    private final Map<UUID, Long> connectionOpenAt = new ConcurrentHashMap<>();

    public SocketServerManager(BeaconPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        PluginConfig cfg = plugin.getConfigManager().getCurrentConfig();

        Configuration configuration = new Configuration();
        configuration.setHostname("0.0.0.0");
        configuration.setPort(cfg.getPort());

        // Hook exception listener for logging abnormal disconnects and other errors
        configuration.setExceptionListener(new LoggingExceptionListener());

        server = new SocketIOServer(configuration);
        registerListeners();
        server.start();

        plugin.getLogger().info("Socket.IO server started on port " + cfg.getPort());
        plugin.getLogger().info("Socket.IO events registered: force_update, get_player_advancements, get_player_stats, list_online_players, get_server_time, get_player_mtr_logs, get_mtr_log_detail, get_player_sessions, get_player_nbt, lookup_player_identity, list_player_identities, get_players_data, execute_sql, get_status");
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
            plugin.getLogger().info("Socket.IO server stopped.");
        }
    }

    private void registerListeners() {
        // Connection/Disconnection logging
        server.addConnectListener((ConnectListener) client -> {
            connectionOpenAt.put(client.getSessionId(), System.currentTimeMillis());
            plugin.getLogger().info("[Socket.IO] Client connected: " + formatClientInfo(client));
        });

        server.addDisconnectListener((DisconnectListener) client -> {
            Long started = connectionOpenAt.remove(client.getSessionId());
            long duration = started != null ? (System.currentTimeMillis() - started) : -1L;
            String durationStr = duration >= 0 ? (duration + "ms") : "unknown";
            plugin.getLogger().info("[Socket.IO] Client disconnected: " + formatClientInfo(client) + ", sessionDuration=" + durationStr);
        });

        server.addEventListener("force_update", ForceUpdateRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    // Ack immediately to avoid cross-thread ack issues
                    Map<String, Object> accepted = new HashMap<>();
                    accepted.put("success", true);
                    accepted.put("queued", true);
                    ackSender.sendAckData(accepted);

                    // Then perform heavy work asynchronously
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        new AdvancementsAndStatsScanner(plugin).scanOnce();
                        new MtrLogsScanner(plugin).scanOnce();
                    });
                });

        server.addEventListener("get_player_advancements", PlayerIdentityRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                        try {
                        String uuid = ensurePlayerUuid(data.getPlayerUuid(), data.getPlayerName());
                        if (uuid == null) { sendError(ackSender, "NOT_FOUND"); return; }
                        Set<String> filters = normalizeFilterKeys(data.getKeys());
                        int page = data.getPage() != null ? data.getPage() : 1;
                        int pageSize = data.getPageSize() != null ? data.getPageSize() : 100;
                        Map<String, Object> result = loadAdvancementsForPlayer(
                            uuid,
                            filters,
                            page,
                            pageSize
                        );
                        Map<String, String> advancements = (Map<String, String>) result.get("records");
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("player_uuid", uuid);
                        resp.put("advancements", advancements);
                        resp.put("total", result.get("total"));
                        resp.put("page", result.get("page"));
                        resp.put("page_size", result.get("page_size"));
                        ackSender.sendAckData(resp);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    }
                });

        server.addEventListener("get_player_stats", PlayerIdentityRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                        try {
                        String uuid = ensurePlayerUuid(data.getPlayerUuid(), data.getPlayerName());
                        if (uuid == null) { sendError(ackSender, "NOT_FOUND"); return; }
                        Set<String> filters = normalizeFilterKeys(data.getKeys());
                        int page = data.getPage() != null ? data.getPage() : 1;
                        int pageSize = data.getPageSize() != null ? data.getPageSize() : 100;
                        Map<String, Object> result = loadStatsForPlayer(
                            uuid,
                            filters,
                            page,
                            pageSize
                        );
                        Map<String, Long> stats = (Map<String, Long>) result.get("records");
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("player_uuid", uuid);
                        resp.put("stats", stats);
                        resp.put("total", result.get("total"));
                        resp.put("page", result.get("page"));
                        resp.put("page_size", result.get("page_size"));
                        ackSender.sendAckData(resp);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    }
                });

        server.addEventListener("list_online_players", AuthOnlyRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Future<List<Map<String, Object>>> future =
                                Bukkit.getScheduler().callSyncMethod(plugin, this::collectOnlinePlayers);
                        List<Map<String, Object>> players = future.get();
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("players", players);
                        ackSender.sendAckData(resp);
                    } catch (InterruptedException | ExecutionException e) {
                        sendError(ackSender, "INTERNAL_ERROR: " + e.getMessage());
                    }
                });

        server.addEventListener("get_server_time", AuthOnlyRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Future<Map<String, Object>> future =
                                Bukkit.getScheduler().callSyncMethod(plugin, this::collectServerTime);
                        Map<String, Object> info = future.get();
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.putAll(info);
                        ackSender.sendAckData(resp);
                    } catch (InterruptedException | ExecutionException e) {
                        sendError(ackSender, "INTERNAL_ERROR: " + e.getMessage());
                    }
                });

        // get_player_mtr_logs: list MTR logs with optional filters & pagination
        server.addEventListener("get_player_mtr_logs", MtrLogsQueryRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        String uuid = data.getPlayerUuid();
                        if ((uuid == null || uuid.isEmpty()) && data.getPlayerName() != null && !data.getPlayerName().isEmpty()) {
                            uuid = resolveUuidByName(data.getPlayerName());
                            if (uuid == null) { sendError(ackSender, "NOT_FOUND"); return; }
                        }
                        Map<String, Object> result = loadMtrLogs(
                                uuid,
                                data.getSingleDate(),
                                data.getStartDate(),
                                data.getEndDate(),
                                data.getDimensionContext(),
                                data.getEntryId(),
                                data.getChangeType(),
                                data.getPage(),
                                data.getPageSize(),
                                data.getOrder(),
                                data.getOrderColumn()
                        );
                        result.put("success", true);
                        ackSender.sendAckData(result);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        sendError(ackSender, "INVALID_ARGUMENT: " + e.getMessage());
                    }
                });

        // get_mtr_log_detail: fetch single log row by id
        server.addEventListener("get_mtr_log_detail", MtrLogDetailRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    if (data.getId() <= 0) {
                        sendError(ackSender, "INVALID_ARGUMENT: id must be > 0");
                        return;
                    }
                    try {
                        Map<String, Object> log = loadMtrLogById(data.getId());
                        if (log == null) {
                            sendError(ackSender, "NOT_FOUND");
                            return;
                        }
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("log", log);
                        ackSender.sendAckData(resp);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    }
                });

        // get_player_sessions: list player JOIN/QUIT sessions with filters & pagination
        server.addEventListener("get_player_sessions", PlayerSessionsQueryRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        String uuid = data.getPlayerUuid();
                        if ((uuid == null || uuid.isEmpty()) && data.getPlayerName() != null && !data.getPlayerName().isEmpty()) {
                            uuid = resolveUuidByName(data.getPlayerName());
                            if (uuid == null) { sendError(ackSender, "NOT_FOUND"); return; }
                        }
                        Map<String, Object> result = loadPlayerSessions(
                                uuid,
                                data.getEventType(),
                                data.getSingleDate(),
                                data.getStartDate(),
                                data.getEndDate(),
                                data.getStartAt(),
                                data.getEndAt(),
                                data.getPage(),
                                data.getPageSize()
                        );
                        result.put("success", true);
                        ackSender.sendAckData(result);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        sendError(ackSender, "INVALID_ARGUMENT: " + e.getMessage());
                    }
                });

        // lookup_player_identity: resolve UUID/name + metadata from player_identities table
        server.addEventListener("lookup_player_identity", PlayerIdentityRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    boolean hasUuid = data.getPlayerUuid() != null && !data.getPlayerUuid().isEmpty();
                    boolean hasName = data.getPlayerName() != null && !data.getPlayerName().isEmpty();
                    if (!hasUuid && !hasName) {
                        sendError(ackSender, "INVALID_ARGUMENT: playerUuid or playerName required");
                        return;
                    }
                    try {
                        Map<String, Object> identity = null;
                        if (hasUuid) {
                            identity = loadIdentityByUuid(data.getPlayerUuid());
                            if (identity == null && hasName) {
                                identity = loadIdentityByName(data.getPlayerName());
                            }
                        } else if (hasName) {
                            identity = loadIdentityByName(data.getPlayerName());
                        }
                        if (identity == null) {
                            sendError(ackSender, "NOT_FOUND");
                            return;
                        }
                        if (hasUuid && hasName) {
                            Object identityName = identity.get("player_name");
                            if (identityName instanceof String && !((String) identityName).equalsIgnoreCase(data.getPlayerName())) {
                                // warn but still return data as canonical record; mismatch likely stale input
                                plugin.getLogger().warning("lookup_player_identity request name mismatch for UUID " + data.getPlayerUuid());
                            }
                        }
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("identity", identity);
                        ackSender.sendAckData(resp);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    }
                });

        // list_player_identities: paginated dump of player_identities table
        server.addEventListener("list_player_identities", PlayerIdentitiesListRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Map<String, Object> result = loadPlayerIdentities(
                                data.getPage(),
                                data.getPageSize()
                        );
                        result.put("success", true);
                        ackSender.sendAckData(result);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    }
                });

        // get_player_nbt: return raw NBT as JSON (cached in SQLite for X minutes)
        server.addEventListener("get_player_nbt", PlayerIdentityRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) { sendError(ackSender, "INVALID_KEY"); return; }
                    try {
                        String uuid = ensurePlayerUuid(data.getPlayerUuid(), data.getPlayerName());
                        if (uuid == null) { sendError(ackSender, "NOT_FOUND"); return; }
                        Map<String, Object> resp = new HashMap<>();
                        String json = getPlayerNbtJsonCached(uuid);
                        resp.put("success", true);
                        resp.put("player_uuid", uuid);
                        resp.put("nbt", json != null ? com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(json) : null);
                        ackSender.sendAckData(resp);
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    } catch (Exception e) {
                        sendError(ackSender, "INTERNAL_ERROR: " + e.getMessage());
                    }
                });

        // get_status: heartbeat/status snapshot
        server.addEventListener("get_status", AuthOnlyRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        // Collect Bukkit server basics on main thread
                        Future<Map<String, Object>> futureBasics =
                                Bukkit.getScheduler().callSyncMethod(plugin, this::collectServerBasics);
                        Map<String, Object> basics = futureBasics.get();

                        // Load DB totals
                        Map<String, Long> totals = loadDataTotals();

                        Map<String, Object> resp = new HashMap<>();
                        PluginConfig cfg = plugin.getConfigManager().getCurrentConfig();
                        long ticks = cfg.getIntervalTimeTicks();
                        resp.put("success", true);
                        resp.put("interval_time_ticks", ticks);
                        resp.put("interval_time_seconds", ticks / 20.0);
                        resp.putAll(basics);
                        resp.putAll(totals);
                        ackSender.sendAckData(resp);
                    } catch (InterruptedException | ExecutionException e) {
                        sendError(ackSender, "INTERNAL_ERROR: " + e.getMessage());
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    }
                });

        // get_players_data: batch fetch balance/stats/advancements for multiple players
        server.addEventListener("get_players_data", PlayersDataRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Set<String> uuids = new HashSet<>();
                        if (data.getPlayerUuids() != null) {
                            for (String u : data.getPlayerUuids()) {
                                if (u != null && !u.trim().isEmpty()) {
                                    uuids.add(u.trim());
                                }
                            }
                        }
                        if (data.getPlayerNames() != null) {
                            for (String name : data.getPlayerNames()) {
                                if (name != null && !name.trim().isEmpty()) {
                                    String resolved = resolveUuidByName(name.trim());
                                    if (resolved != null) {
                                        uuids.add(resolved);
                                    }
                                }
                            }
                        }

                        if (uuids.size() > 200) {
                            throw new IllegalArgumentException("Too many players; max 200 per request");
                        }

                        boolean needStats = data.getStatKeys() != null && !data.getStatKeys().isEmpty();
                        boolean needAdv = data.getAdvancementKeys() != null && !data.getAdvancementKeys().isEmpty();
                        boolean includeBalance = Boolean.TRUE.equals(data.getIncludeBalance());
                        boolean includeBalanceAll = Boolean.TRUE.equals(data.getIncludeBalanceAll());

                        if ((needStats || needAdv) && uuids.isEmpty()) {
                            throw new IllegalArgumentException("playerUuids/playerNames required when requesting stats or advancements");
                        }

                        Map<String, Object> resp = new HashMap<>();

                        if (includeBalance || includeBalanceAll) {
                            List<String> balanceNames = new ArrayList<>();
                            if (!includeBalanceAll) {
                                if (data.getPlayerNames() != null) {
                                    for (String name : data.getPlayerNames()) {
                                        if (name != null && !name.trim().isEmpty()) {
                                            balanceNames.add(name.trim());
                                        }
                                    }
                                }
                                if (balanceNames.isEmpty() && !uuids.isEmpty()) {
                                    balanceNames.addAll(resolveNamesForUuids(uuids));
                                }
                                if (balanceNames.isEmpty()) {
                                    throw new IllegalArgumentException("playerNames or playerUuids required when includeBalance is true");
                                }
                            }
                            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () ->
                                    collectBalancesMainScoreboard(includeBalanceAll ? null : balanceNames, includeBalanceAll));
                            List<Map<String, Object>> balances = future.get();
                            resp.put("balances", balances);
                        }

                        if (needStats) {
                            Map<String, Map<String, Long>> stats = loadStatsForPlayers(uuids, new HashSet<>(data.getStatKeys()));
                            resp.put("stats", stats);
                        }

                        if (needAdv) {
                            Map<String, Map<String, String>> adv = loadAdvancementsForPlayers(uuids, new HashSet<>(data.getAdvancementKeys()));
                            resp.put("advancements", adv);
                        }

                        resp.put("success", true);
                        ackSender.sendAckData(resp);
                    } catch (IllegalArgumentException e) {
                        sendError(ackSender, "INVALID_ARGUMENT: " + e.getMessage());
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        sendError(ackSender, "INTERNAL_ERROR: interrupted");
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        sendError(ackSender, "INTERNAL_ERROR: " + (cause != null ? cause.getMessage() : e.getMessage()));
                    }
                });

        // execute_sql: read-only SELECT/PRAGMA helper for admin/GraphQL bridge
        server.addEventListener("execute_sql", ExecuteSqlRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Map<String, Object> result = executeSelectSql(data.getSql(), data.getMaxRows());
                        result.put("success", true);
                        ackSender.sendAckData(result);
                    } catch (IllegalArgumentException e) {
                        sendError(ackSender, "INVALID_ARGUMENT: " + e.getMessage());
                    } catch (SQLException e) {
                        sendError(ackSender, "DB_ERROR: " + e.getMessage());
                    }
                });

        // mtr_balance: get/set/add player balance from main scoreboard objective
        server.addEventListener("get_player_balance", PlayerBalanceRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Future<Long> future = Bukkit.getScheduler().callSyncMethod(plugin, () ->
                                getPlayerBalanceOnMainScoreboard(data.getPlayerName()));
                        Long value = future.get();
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("player", data.getPlayerName());
                        resp.put("balance", value);
                        ackSender.sendAckData(resp);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        sendError(ackSender, "INTERNAL_ERROR: interrupted");
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof IllegalArgumentException) {
                            sendError(ackSender, "INVALID_ARGUMENT: " + cause.getMessage());
                        } else {
                            sendError(ackSender, "INTERNAL_ERROR: " + (cause != null ? cause.getMessage() : e.getMessage()));
                        }
                    } catch (IllegalArgumentException e) {
                        sendError(ackSender, "INVALID_ARGUMENT: " + e.getMessage());
                    }
                });

        server.addEventListener("set_player_balance", PlayerBalanceUpdateRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Future<Long> future = Bukkit.getScheduler().callSyncMethod(plugin, () ->
                                setPlayerBalanceOnMainScoreboard(data.getPlayerName(), data.getAmount()));
                        Long value = future.get();
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("player", data.getPlayerName());
                        resp.put("balance", value);
                        ackSender.sendAckData(resp);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        sendError(ackSender, "INTERNAL_ERROR: interrupted");
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof IllegalArgumentException) {
                            sendError(ackSender, "INVALID_ARGUMENT: " + cause.getMessage());
                        } else {
                            sendError(ackSender, "INTERNAL_ERROR: " + (cause != null ? cause.getMessage() : e.getMessage()));
                        }
                    } catch (IllegalArgumentException e) {
                        sendError(ackSender, "INVALID_ARGUMENT: " + e.getMessage());
                    }
                });

        server.addEventListener("add_player_balance", PlayerBalanceUpdateRequest.class,
                (client, data, ackSender) -> {
                    if (!validateKey(data.getKey())) {
                        sendError(ackSender, "INVALID_KEY");
                        return;
                    }
                    try {
                        Future<Long> future = Bukkit.getScheduler().callSyncMethod(plugin, () ->
                                addPlayerBalanceOnMainScoreboard(data.getPlayerName(), data.getAmount()));
                        Long value = future.get();
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("player", data.getPlayerName());
                        resp.put("balance", value);
                        ackSender.sendAckData(resp);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        sendError(ackSender, "INTERNAL_ERROR: interrupted");
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof IllegalArgumentException) {
                            sendError(ackSender, "INVALID_ARGUMENT: " + cause.getMessage());
                        } else {
                            sendError(ackSender, "INTERNAL_ERROR: " + (cause != null ? cause.getMessage() : e.getMessage()));
                        }
                    } catch (IllegalArgumentException e) {
                        sendError(ackSender, "INVALID_ARGUMENT: " + e.getMessage());
                    }
                });
    }

    private long getPlayerBalanceOnMainScoreboard(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("playerName is required");
        }
        org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            throw new IllegalArgumentException("scoreboard manager not available");
        }
        org.bukkit.scoreboard.Scoreboard main = manager.getMainScoreboard();
        if (main == null) {
            throw new IllegalArgumentException("main scoreboard not available");
        }
        org.bukkit.scoreboard.Objective obj = main.getObjective("mtr_balance");
        if (obj == null) {
            throw new IllegalArgumentException("objective mtr_balance not found");
        }
        org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer == null) {
            throw new IllegalArgumentException("player not found: " + playerName);
        }
        org.bukkit.scoreboard.Score score = obj.getScore(offlinePlayer);
        return score.getScore();
    }

    private long setPlayerBalanceOnMainScoreboard(String playerName, long amount) {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("playerName is required");
        }
        org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            throw new IllegalArgumentException("scoreboard manager not available");
        }
        org.bukkit.scoreboard.Scoreboard main = manager.getMainScoreboard();
        if (main == null) {
            throw new IllegalArgumentException("main scoreboard not available");
        }
        org.bukkit.scoreboard.Objective obj = main.getObjective("mtr_balance");
        if (obj == null) {
            throw new IllegalArgumentException("objective mtr_balance not found");
        }
        org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer == null) {
            throw new IllegalArgumentException("player not found: " + playerName);
        }
        org.bukkit.scoreboard.Score score = obj.getScore(offlinePlayer);
        score.setScore((int) amount);
        return score.getScore();
    }

    private long addPlayerBalanceOnMainScoreboard(String playerName, long delta) {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("playerName is required");
        }
        org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            throw new IllegalArgumentException("scoreboard manager not available");
        }
        org.bukkit.scoreboard.Scoreboard main = manager.getMainScoreboard();
        if (main == null) {
            throw new IllegalArgumentException("main scoreboard not available");
        }
        org.bukkit.scoreboard.Objective obj = main.getObjective("mtr_balance");
        if (obj == null) {
            throw new IllegalArgumentException("objective mtr_balance not found");
        }
        org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer == null) {
            throw new IllegalArgumentException("player not found: " + playerName);
        }
        org.bukkit.scoreboard.Score score = obj.getScore(offlinePlayer);
        int current = score.getScore();
        long next = current + delta;
        if (next > Integer.MAX_VALUE) next = Integer.MAX_VALUE;
        if (next < Integer.MIN_VALUE) next = Integer.MIN_VALUE;
        score.setScore((int) next);
        return score.getScore();
    }

    private List<Map<String, Object>> collectBalancesMainScoreboard(List<String> names, boolean all) {
        org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            throw new IllegalArgumentException("scoreboard manager not available");
        }
        org.bukkit.scoreboard.Scoreboard main = manager.getMainScoreboard();
        if (main == null) {
            throw new IllegalArgumentException("main scoreboard not available");
        }
        org.bukkit.scoreboard.Objective obj = main.getObjective("mtr_balance");
        if (obj == null) {
            throw new IllegalArgumentException("objective mtr_balance not found");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (all) {
            for (String entry : obj.getScoreboard().getEntries()) {
                org.bukkit.scoreboard.Score score = obj.getScore(entry);
                Map<String, Object> row = new HashMap<>();
                row.put("player", entry);
                row.put("balance", score.getScore());
                rows.add(row);
            }
        } else if (names != null) {
            for (String name : names) {
                if (name == null || name.trim().isEmpty()) continue;
                org.bukkit.scoreboard.Score score = obj.getScore(name.trim());
                Map<String, Object> row = new HashMap<>();
                row.put("player", name.trim());
                row.put("balance", score.getScore());
                rows.add(row);
            }
        }
        return rows;
    }

    private String formatClientInfo(SocketIOClient client) {
        try {
            HandshakeData hs = client.getHandshakeData();
            InetSocketAddress addr = hs != null ? hs.getAddress() : null;
            String ipPort = null;
            if (addr != null) {
                String ip = addr.getAddress() != null ? addr.getAddress().getHostAddress() : null;
                Integer port = addr.getPort();
                ipPort = (ip != null ? ip : "?") + ":" + port;
            }
            String transport = client.getTransport() != null ? client.getTransport().name() : null;
            String ua = null;
            try {
                HttpHeaders headers = hs != null ? hs.getHttpHeaders() : null;
                ua = headers != null ? headers.get("User-Agent") : null;
            } catch (Throwable ignored) {
                // ignore header extraction failures
            }
            Map<String, List<String>> params = hs != null ? hs.getUrlParams() : null;
            String paramStr = params != null ? params.toString() : "{}";
            String session = client.getSessionId() != null ? client.getSessionId().toString() : "?";
            return "session=" + session + ", ip=" + (ipPort != null ? ipPort : "?") +
                    ", transport=" + (transport != null ? transport : "?") +
                    ", ua=" + (ua != null ? truncate(ua, 120) : "-") +
                    ", params=" + truncate(paramStr, 200);
        } catch (Throwable t) {
            return "<client-info-unavailable>";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private class LoggingExceptionListener implements ExceptionListener {
        @Override
        public void onEventException(Exception e, List<Object> args, SocketIOClient client) {
            plugin.getLogger().warning("[Socket.IO] Event handler exception: " + e.getMessage() + ", client=" + formatClientInfo(client));
        }

        @Override
        public void onDisconnectException(Exception e, SocketIOClient client) {
            plugin.getLogger().warning("[Socket.IO] Abnormal disconnect: " + e.getMessage() + ", client=" + formatClientInfo(client));
        }

        @Override
        public void onConnectException(Exception e, SocketIOClient client) {
            plugin.getLogger().warning("[Socket.IO] Connect exception: " + e.getMessage() + ", client=" + formatClientInfo(client));
        }

        @Override
        public void onPingException(Exception e, SocketIOClient client) {
            plugin.getLogger().warning("[Socket.IO] Ping exception: " + e.getMessage() + ", client=" + formatClientInfo(client));
        }

        @Override
        public boolean exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            plugin.getLogger().warning("[Socket.IO] Pipeline exception: " + e.getMessage());
            return true; // already handled
        }

        @Override
        public void onAuthException(Throwable e, SocketIOClient client) {
            plugin.getLogger().warning("[Socket.IO] Auth exception: " + e.getMessage() + ", client=" + formatClientInfo(client));
        }

        @Override
        public void onPongException(Exception e, SocketIOClient client) {
            plugin.getLogger().warning("[Socket.IO] Pong exception: " + e.getMessage() + ", client=" + formatClientInfo(client));
        }
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

    private Set<String> normalizeFilterKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        Set<String> normalized = new HashSet<>();
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeOrder(String order) {
        if (order == null || order.isEmpty()) {
            return "DESC";
        }
        if ("asc".equalsIgnoreCase(order)) {
            return "ASC";
        }
        if ("desc".equalsIgnoreCase(order)) {
            return "DESC";
        }
        throw new IllegalArgumentException("order must be 'asc' or 'desc'");
    }

    private Map<String, Object> loadAdvancementsForPlayer(String playerUuid,
                                                          Set<String> filterKeys,
                                                          int page,
                                                          int pageSize) throws SQLException {
        if (page <= 0) page = 1;
        if (pageSize <= 0) pageSize = 100;
        if (pageSize > 1000) pageSize = 1000;

        Map<String, String> records = new HashMap<>();
        Set<String> filters = filterKeys != null && !filterKeys.isEmpty() ? new HashSet<>(filterKeys) : null;
        StringBuilder sql = new StringBuilder("SELECT advancement_key, value FROM player_advancements WHERE player_uuid = ?");
        List<String> orderedFilters = null;
        if (filters != null) {
            orderedFilters = new ArrayList<>(filters);
            sql.append(" AND advancement_key IN (");
            for (int i = 0; i < orderedFilters.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(')');
        }

        Map<String, Object> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // count
            StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM player_advancements WHERE player_uuid = ?");
            if (filters != null) {
                countSql.append(" AND advancement_key IN (");
                for (int i = 0; i < orderedFilters.size(); i++) {
                    if (i > 0) {
                        countSql.append(',');
                    }
                    countSql.append('?');
                }
                countSql.append(')');
            }
            try (PreparedStatement cps = conn.prepareStatement(countSql.toString())) {
                int cidx = 1;
                cps.setString(cidx++, playerUuid);
                if (orderedFilters != null) {
                    for (String key : orderedFilters) {
                        cps.setString(cidx++, key);
                    }
                }
                try (ResultSet crs = cps.executeQuery()) {
                    result.put("total", crs.next() ? crs.getLong(1) : 0L);
                }
            }
            long total = (long) result.get("total");
            int offset = (page - 1) * pageSize;
            if (offset >= total) { offset = 0; page = 1; }

            sql.append(" LIMIT ? OFFSET ?");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, playerUuid);
                if (orderedFilters != null) {
                    for (String key : orderedFilters) {
                        ps.setString(idx++, key);
                    }
                }
                ps.setInt(idx++, pageSize);
                ps.setInt(idx, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString("advancement_key");
                        if (filters != null && !filters.contains(key)) {
                            continue;
                        }
                        byte[] valueBytes = rs.getBytes("value");
                        String value = valueBytes != null
                                ? new String(valueBytes, java.nio.charset.StandardCharsets.UTF_8)
                                : null;
                        records.put(key, value);
                    }
                }
            }
        }
        result.put("records", records);
        result.put("page", page);
        result.put("page_size", pageSize);
        return result;
    }

    private Map<String, Object> loadStatsForPlayer(String playerUuid,
                                                   Set<String> filterKeys,
                                                   int page,
                                                   int pageSize) throws SQLException {
        if (page <= 0) page = 1;
        if (pageSize <= 0) pageSize = 100;
        if (pageSize > 1000) pageSize = 1000;

        Map<String, Long> records = new HashMap<>();
        Set<String> filters = filterKeys != null && !filterKeys.isEmpty() ? new HashSet<>(filterKeys) : null;
        StringBuilder sql = new StringBuilder("SELECT stat_key, value FROM player_stats WHERE player_uuid = ?");
        List<String> orderedFilters = null;
        if (filters != null) {
            orderedFilters = new ArrayList<>(filters);
            sql.append(" AND stat_key IN (");
            for (int i = 0; i < orderedFilters.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(')');
        }

        Map<String, Object> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM player_stats WHERE player_uuid = ?");
            if (filters != null) {
                countSql.append(" AND stat_key IN (");
                for (int i = 0; i < orderedFilters.size(); i++) {
                    if (i > 0) {
                        countSql.append(',');
                    }
                    countSql.append('?');
                }
                countSql.append(')');
            }
            try (PreparedStatement cps = conn.prepareStatement(countSql.toString())) {
                int cidx = 1;
                cps.setString(cidx++, playerUuid);
                if (orderedFilters != null) {
                    for (String key : orderedFilters) {
                        cps.setString(cidx++, key);
                    }
                }
                try (ResultSet crs = cps.executeQuery()) {
                    result.put("total", crs.next() ? crs.getLong(1) : 0L);
                }
            }
            long total = (long) result.get("total");
            int offset = (page - 1) * pageSize;
            if (offset >= total) { offset = 0; page = 1; }

            sql.append(" LIMIT ? OFFSET ?");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, playerUuid);
                if (orderedFilters != null) {
                    for (String key : orderedFilters) {
                        ps.setString(idx++, key);
                    }
                }
                ps.setInt(idx++, pageSize);
                ps.setInt(idx, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString("stat_key");
                        if (filters != null && !filters.contains(key)) {
                            continue;
                        }
                        long value = rs.getLong("value");
                        records.put(key, value);
                    }
                }
            }
        }
        result.put("records", records);
        result.put("page", page);
        result.put("page_size", pageSize);
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

    private Map<String, Object> collectServerBasics() {
        Map<String, Object> result = new HashMap<>();
        int maxPlayers = Bukkit.getMaxPlayers();
        int online = Bukkit.getOnlinePlayers().size();
        result.put("server_max_players", maxPlayers);
        result.put("online_player_count", online);
        return result;
    }

    private Map<String, Object> loadMtrLogs(String playerUuid,
                                            String singleDate,
                                            String startDate,
                                            String endDate,
                                            String dimensionContext,
                                            String entryId,
                                            String changeType,
                                            int page,
                                            int pageSize,
                                            String order,
                                            String orderColumn) throws SQLException {
        if (page <= 0) page = 1;
        if (pageSize <= 0) pageSize = 50;
        if (pageSize > 500) pageSize = 500; // hard cap
        // Mutually exclusive date parameters check
        if (singleDate != null && (startDate != null || endDate != null)) {
            throw new IllegalArgumentException("Provide either singleDate or startDate/endDate, not both");
        }

        String orderClause = normalizeOrder(order);
        String orderByColumn = normalizeOrderColumn(orderColumn);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (playerUuid != null && !playerUuid.isEmpty()) {
            where.append(" AND player_uuid = ?"); params.add(playerUuid);
        }
        if (dimensionContext != null && !dimensionContext.isEmpty()) {
            where.append(" AND dimension_context = ?"); params.add(dimensionContext);
        }
        if (entryId != null && !entryId.isEmpty()) {
            where.append(" AND entry_id = ?"); params.add(entryId);
        }
        if (changeType != null && !changeType.isEmpty()) {
            where.append(" AND change_type = ?"); params.add(changeType);
        }
        if (singleDate != null && !singleDate.isEmpty()) {
            // Timestamp assumed ISO prefix; use LIKE 'YYYY-MM-DD%'
            where.append(" AND timestamp LIKE ?"); params.add(singleDate + "%");
        } else {
            if (startDate != null && !startDate.isEmpty()) {
                where.append(" AND timestamp >= ?"); params.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                where.append(" AND timestamp <= ?"); params.add(endDate);
            }
        }

        Map<String, Object> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // count
            try (PreparedStatement cps = conn.prepareStatement("SELECT COUNT(*) FROM mtr_logs" + where)) {
                for (int i = 0; i < params.size(); i++) {
                    cps.setObject(i + 1, params.get(i));
                }
                try (ResultSet crs = cps.executeQuery()) {
                    if (crs.next()) {
                        result.put("total", crs.getLong(1));
                    } else {
                        result.put("total", 0L);
                    }
                }
            }
            long total = (long) result.get("total");
            int offset = (page - 1) * pageSize;
            if (offset >= total) {
                offset = 0; // reset if out of range to still return first page
                page = 1;
            }
                String sql = "SELECT id, timestamp, player_name, player_uuid, class_name, entry_id, entry_name, position, change_type, old_data, new_data, source_file_path, source_line, dimension_context " +
                    "FROM mtr_logs" + where + " ORDER BY " + orderByColumn + " " + orderClause + " LIMIT ? OFFSET ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object p : params) {
                    ps.setObject(idx++, p);
                }
                ps.setInt(idx++, pageSize);
                ps.setInt(idx, offset);
                List<Map<String, Object>> records = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("timestamp", rs.getString("timestamp"));
                        row.put("player_name", rs.getString("player_name"));
                        row.put("player_uuid", rs.getString("player_uuid"));
                        row.put("class_name", rs.getString("class_name"));
                        row.put("entry_id", rs.getString("entry_id"));
                        row.put("entry_name", rs.getString("entry_name"));
                        row.put("position", rs.getString("position"));
                        row.put("change_type", rs.getString("change_type"));
                        row.put("old_data", rs.getString("old_data"));
                        row.put("new_data", rs.getString("new_data"));
                        row.put("source_file_path", rs.getString("source_file_path"));
                        row.put("source_line", rs.getInt("source_line"));
                        row.put("dimension_context", rs.getString("dimension_context"));
                        records.add(row);
                    }
                }
                result.put("records", records);
            }
        }
        result.put("page", page);
        result.put("page_size", pageSize);
        return result;
    }

    private String normalizeOrderColumn(String orderColumn) {
        if (orderColumn == null || orderColumn.isEmpty()) {
            return "timestamp"; // default to timestamp
        }
        String c = orderColumn.toLowerCase();
        switch (c) {
            case "timestamp":
                return "timestamp";
            case "id":
                return "id";
            default:
                throw new IllegalArgumentException("orderColumn must be 'timestamp' or 'id'");
        }
    }

    private Map<String, Object> loadPlayerSessions(String playerUuid,
                                                   String eventType,
                                                   String singleDate,
                                                   String startDate,
                                                   String endDate,
                                                   Long startAt,
                                                   Long endAt,
                                                   int page,
                                                   int pageSize) throws SQLException {
        if (page <= 0) page = 1;
        if (pageSize <= 0) pageSize = 50;
        if (pageSize > 500) pageSize = 500;

        if (singleDate != null && (startDate != null || endDate != null)) {
            throw new IllegalArgumentException("Provide either singleDate or startDate/endDate, not both");
        }
        if ((startDate != null || endDate != null) && (startAt != null || endAt != null)) {
            throw new IllegalArgumentException("Provide either date strings or epoch millis, not both");
        }

        Long rangeStart = null;
        Long rangeEnd = null;
        if (singleDate != null && !singleDate.isEmpty()) {
            long[] r = computeDayRange(singleDate);
            rangeStart = r[0];
            rangeEnd = r[1];
        } else if (startDate != null || endDate != null) {
            long[] r = computeRange(startDate, endDate);
            rangeStart = r[0];
            rangeEnd = r[1];
        } else if (startAt != null || endAt != null) {
            rangeStart = startAt;
            rangeEnd = endAt;
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (playerUuid != null && !playerUuid.isEmpty()) {
            where.append(" AND player_uuid = ?"); params.add(playerUuid);
        }
        if (eventType != null && !eventType.isEmpty()) {
            // Allow JOIN, QUIT, ABNORMAL_QUIT
            String up = eventType.toUpperCase();
            if (!"JOIN".equals(up) && !"QUIT".equals(up) && !"ABNORMAL_QUIT".equals(up)) {
                throw new IllegalArgumentException("eventType must be JOIN, QUIT or ABNORMAL_QUIT");
            }
            where.append(" AND event_type = ?"); params.add(up);
        }
        if (rangeStart != null) { where.append(" AND occurred_at >= ?"); params.add(rangeStart); }
        if (rangeEnd != null)   { where.append(" AND occurred_at <= ?"); params.add(rangeEnd); }

        Map<String, Object> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement cps = conn.prepareStatement("SELECT COUNT(*) FROM player_sessions" + where)) {
                for (int i = 0; i < params.size(); i++) cps.setObject(i + 1, params.get(i));
                try (ResultSet crs = cps.executeQuery()) {
                    result.put("total", crs.next() ? crs.getLong(1) : 0L);
                }
            }
            long total = (long) result.get("total");
            int offset = (page - 1) * pageSize;
            if (offset >= total) { offset = 0; page = 1; }
            String sql = "SELECT id, event_type, occurred_at, player_uuid, player_name, player_ip, world_name, dimension_key, x, y, z " +
                    "FROM player_sessions" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object p : params) ps.setObject(idx++, p);
                ps.setInt(idx++, pageSize);
                ps.setInt(idx, offset);
                List<Map<String, Object>> records = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("event_type", rs.getString("event_type"));
                        row.put("occurred_at", rs.getLong("occurred_at"));
                        row.put("player_uuid", rs.getString("player_uuid"));
                        row.put("player_name", rs.getString("player_name"));
                        row.put("player_ip", rs.getString("player_ip"));
                        row.put("world_name", rs.getString("world_name"));
                        row.put("dimension_key", rs.getString("dimension_key"));
                        row.put("x", rs.getDouble("x"));
                        row.put("y", rs.getDouble("y"));
                        row.put("z", rs.getDouble("z"));
                        records.add(row);
                    }
                }
                result.put("records", records);
            }
        }
        result.put("page", page);
        result.put("page_size", pageSize);
        return result;
    }

    private long[] computeDayRange(String date) {
        // date: YYYY-MM-DD
        java.time.LocalDate d = java.time.LocalDate.parse(date);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long start = d.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        return new long[]{start, end};
    }

    private long[] computeRange(String startDate, String endDate) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Long start = null;
        Long end = null;
        if (startDate != null && !startDate.isEmpty()) {
            java.time.LocalDate d = java.time.LocalDate.parse(startDate);
            start = d.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        if (endDate != null && !endDate.isEmpty()) {
            java.time.LocalDate d = java.time.LocalDate.parse(endDate);
            end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        }
        return new long[]{start != null ? start : Long.MIN_VALUE, end != null ? end : Long.MAX_VALUE};
    }

    private Map<String, Object> loadMtrLogById(long id) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, timestamp, player_name, player_uuid, class_name, entry_id, entry_name, position, change_type, old_data, new_data, source_file_path, source_line, dimension_context " +
                             "FROM mtr_logs WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> row = new HashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("timestamp", rs.getString("timestamp"));
                row.put("player_name", rs.getString("player_name"));
                row.put("player_uuid", rs.getString("player_uuid"));
                row.put("class_name", rs.getString("class_name"));
                row.put("entry_id", rs.getString("entry_id"));
                row.put("entry_name", rs.getString("entry_name"));
                row.put("position", rs.getString("position"));
                row.put("change_type", rs.getString("change_type"));
                row.put("old_data", rs.getString("old_data"));
                row.put("new_data", rs.getString("new_data"));
                row.put("source_file_path", rs.getString("source_file_path"));
                row.put("source_line", rs.getInt("source_line"));
                row.put("dimension_context", rs.getString("dimension_context"));
                return row;
            }
        }
    }

    private Map<String, Long> loadDataTotals() throws SQLException {
        Map<String, Long> totals = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM mtr_logs")) {
                try (ResultSet rs = ps.executeQuery()) {
                    totals.put("mtr_logs_total", rs.next() ? rs.getLong(1) : 0L);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM player_stats")) {
                try (ResultSet rs = ps.executeQuery()) {
                    totals.put("stats_total", rs.next() ? rs.getLong(1) : 0L);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM player_advancements")) {
                try (ResultSet rs = ps.executeQuery()) {
                    totals.put("advancements_total", rs.next() ? rs.getLong(1) : 0L);
                }
            }
        }
        return totals;
    }

    private Map<String, Map<String, Long>> loadStatsForPlayers(Set<String> uuids, Set<String> keys) throws SQLException {
        Set<String> normalizedPlayers = (uuids == null || uuids.isEmpty()) ? null : new HashSet<>(uuids);
        Set<String> normalizedKeys = (keys == null || keys.isEmpty()) ? null : new HashSet<>(keys);

        StringBuilder sql = new StringBuilder("SELECT player_uuid, stat_key, value FROM player_stats WHERE 1=1");
        List<String> orderedPlayers = null;
        if (normalizedPlayers != null) {
            orderedPlayers = new ArrayList<>(normalizedPlayers);
            sql.append(" AND player_uuid IN (");
            for (int i = 0; i < orderedPlayers.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
        }
        List<String> orderedKeys = null;
        if (normalizedKeys != null) {
            orderedKeys = new ArrayList<>(normalizedKeys);
            sql.append(" AND stat_key IN (");
            for (int i = 0; i < orderedKeys.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
        }

        Map<String, Map<String, Long>> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (orderedPlayers != null) {
                for (String u : orderedPlayers) {
                    ps.setString(idx++, u);
                }
            }
            if (orderedKeys != null) {
                for (String k : orderedKeys) {
                    ps.setString(idx++, k);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuid = rs.getString("player_uuid");
                    String key = rs.getString("stat_key");
                    long value = rs.getLong("value");
                    result.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(key, value);
                }
            }
        }
        return result;
    }

    private Map<String, Map<String, String>> loadAdvancementsForPlayers(Set<String> uuids, Set<String> keys) throws SQLException {
        Set<String> normalizedPlayers = (uuids == null || uuids.isEmpty()) ? null : new HashSet<>(uuids);
        Set<String> normalizedKeys = (keys == null || keys.isEmpty()) ? null : new HashSet<>(keys);

        StringBuilder sql = new StringBuilder("SELECT player_uuid, advancement_key, value FROM player_advancements WHERE 1=1");
        List<String> orderedPlayers = null;
        if (normalizedPlayers != null) {
            orderedPlayers = new ArrayList<>(normalizedPlayers);
            sql.append(" AND player_uuid IN (");
            for (int i = 0; i < orderedPlayers.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
        }
        List<String> orderedKeys = null;
        if (normalizedKeys != null) {
            orderedKeys = new ArrayList<>(normalizedKeys);
            sql.append(" AND advancement_key IN (");
            for (int i = 0; i < orderedKeys.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
        }

        Map<String, Map<String, String>> result = new HashMap<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (orderedPlayers != null) {
                for (String u : orderedPlayers) {
                    ps.setString(idx++, u);
                }
            }
            if (orderedKeys != null) {
                for (String k : orderedKeys) {
                    ps.setString(idx++, k);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuid = rs.getString("player_uuid");
                    String key = rs.getString("advancement_key");
                    byte[] valueBytes = rs.getBytes("value");
                    String value = valueBytes != null ? new String(valueBytes, java.nio.charset.StandardCharsets.UTF_8) : null;
                    result.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(key, value);
                }
            }
        }
        return result;
    }

    private Map<String, Object> loadPlayerIdentities(Integer pageParam, Integer pageSizeParam) throws SQLException {
        int page = pageParam != null ? pageParam : 1;
        int pageSize = pageSizeParam != null ? pageSizeParam : 100;
        if (page <= 0) page = 1;
        if (pageSize <= 0) pageSize = 100;
        if (pageSize > 1000) pageSize = 1000;

        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement cps = conn.prepareStatement("SELECT COUNT(*) FROM player_identities")) {
                try (ResultSet rs = cps.executeQuery()) {
                    result.put("total", rs.next() ? rs.getLong(1) : 0L);
                }
            }
            long total = (long) result.get("total");
            int offset = (page - 1) * pageSize;
            if (offset >= total) {
                offset = 0;
                page = 1;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT player_uuid, player_name, first_played, last_played, last_updated FROM player_identities ORDER BY last_updated DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, pageSize);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        records.add(mapIdentityRow(rs));
                    }
                }
            }
        }
        result.put("records", records);
        result.put("page", page);
        result.put("page_size", pageSize);
        return result;
    }

    private String ensurePlayerUuid(String playerUuid, String playerName) throws SQLException {
        if (playerUuid != null && !playerUuid.isEmpty()) return playerUuid;
        if (playerName != null && !playerName.isEmpty()) return resolveUuidByName(playerName);
        return null;
    }

    private String resolveUuidByName(String playerName) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid FROM player_identities WHERE player_name = ? ORDER BY last_updated DESC LIMIT 1")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private Map<String, Object> loadIdentityByUuid(String playerUuid) throws SQLException {
        if (playerUuid == null || playerUuid.isEmpty()) return null;
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, player_name, first_played, last_played, last_updated FROM player_identities WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapIdentityRow(rs);
                }
            }
        }
        return null;
    }

    private Map<String, Object> loadIdentityByName(String playerName) throws SQLException {
        if (playerName == null || playerName.isEmpty()) return null;
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, player_name, first_played, last_played, last_updated FROM player_identities WHERE player_name = ? ORDER BY last_updated DESC LIMIT 1")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapIdentityRow(rs);
                }
            }
        }
        return null;
    }

    private Map<String, Object> mapIdentityRow(ResultSet rs) throws SQLException {
        Map<String, Object> identity = new HashMap<>();
        identity.put("player_uuid", rs.getString("player_uuid"));
        identity.put("player_name", rs.getString("player_name"));
        Long firstPlayed = getNullableLong(rs, "first_played");
        Long lastPlayed = getNullableLong(rs, "last_played");
        identity.put("first_played", firstPlayed);
        identity.put("last_played", lastPlayed);
        identity.put("last_updated", getNullableLong(rs, "last_updated"));
        return identity;
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String getPlayerNbtJsonCached(String playerUuid) throws Exception {
        long now = System.currentTimeMillis();
        long ttlMillis = plugin.getConfigManager().getCurrentConfig().getNbtCacheTtlMinutes() * 60_000L;
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT raw_json, cached_at FROM player_nbt_cache WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString(1);
                        long cachedAt = rs.getLong(2);
                        if (cachedAt + ttlMillis > now) {
                            return json;
                        }
                    }
                }
            }
            // Not cached or expired -> try to load from playerdata
            java.io.File dat = findPlayerDatFile(playerUuid);
            if (dat == null || !dat.isFile()) return null;
            Map<String, Object> map;
            try (java.io.FileInputStream in = new java.io.FileInputStream(dat)) {
                map = com.hydroline.beacon.util.NbtUtils.readPlayerDatToMap(in);
            }
            String json = com.hydroline.beacon.util.NbtUtils.toJson(map);
            try (PreparedStatement ups = conn.prepareStatement(
                    "INSERT INTO player_nbt_cache (player_uuid, raw_json, cached_at) VALUES (?, ?, ?) " +
                            "ON CONFLICT(player_uuid) DO UPDATE SET raw_json=excluded.raw_json, cached_at=excluded.cached_at")) {
                ups.setString(1, playerUuid);
                ups.setString(2, json);
                ups.setLong(3, now);
                ups.executeUpdate();
            }
            // opportunistically upsert identity if missing name
            Object bukkit = map.get("bukkit");
            if (bukkit instanceof Map) {
                Object lkn = ((Map<?, ?>) bukkit).get("lastKnownName");
                if (lkn instanceof String) {
                    Long firstPlayed = extractLongFromIdentityMap(map, "firstPlayed", (Map<?, ?>) bukkit);
                    Long lastPlayed = extractLongFromIdentityMap(map, "lastPlayed", (Map<?, ?>) bukkit);
                    upsertIdentityRow(conn, playerUuid, (String) lkn, firstPlayed, lastPlayed, now);
                }
            }
            return json;
        }
    }

    private void upsertIdentityRow(Connection conn,
                                   String playerUuid,
                                   String playerName,
                                   Long firstPlayed,
                                   Long lastPlayed,
                                   long now) throws SQLException {
        try (PreparedStatement upi = conn.prepareStatement(
                "INSERT INTO player_identities (player_uuid, player_name, first_played, last_played, last_updated) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(player_uuid) DO UPDATE SET " +
                        "player_name=excluded.player_name, " +
                        "first_played=COALESCE(excluded.first_played, player_identities.first_played), " +
                        "last_played=COALESCE(excluded.last_played, player_identities.last_played), " +
                        "last_updated=excluded.last_updated")) {
            upi.setString(1, playerUuid);
            upi.setString(2, playerName);
            if (firstPlayed != null) {
                upi.setLong(3, firstPlayed);
            } else {
                upi.setNull(3, java.sql.Types.BIGINT);
            }
            if (lastPlayed != null) {
                upi.setLong(4, lastPlayed);
            } else {
                upi.setNull(4, java.sql.Types.BIGINT);
            }
            upi.setLong(5, now);
            upi.executeUpdate();
        }
    }

    private Long extractLongFromIdentityMap(Map<String, Object> root, String key, Map<?, ?> bukkitSection) {
        Long value = asLong(root.get(key));
        if (value != null) {
            return value;
        }
        if (bukkitSection != null) {
            return asLong(bukkitSection.get(key));
        }
        return null;
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

    private java.io.File findPlayerDatFile(String playerUuid) {
        com.hydroline.beacon.world.WorldFileAccess wfa = plugin.getWorldFileAccess();
        if (wfa == null) return null;
        for (org.bukkit.World w : wfa.getWorlds()) {
            java.io.File f = new java.io.File(w.getWorldFolder(), "playerdata/" + playerUuid + ".dat");
            if (f.isFile()) return f;
        }
        return null;
    }

    private Map<String, Object> executeSelectSql(String sql, Integer maxRows) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("sql is required");
        }
        String trimmed = sql.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("pragma"))) {
            throw new IllegalArgumentException("Only SELECT or PRAGMA statements are allowed");
        }

        int limit = maxRows != null ? maxRows : 200;
        if (limit <= 0) limit = 1;
        if (limit > 1000) limit = 1000;

        Map<String, Object> result = new HashMap<>();
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(trimmed)) {
            ps.setMaxRows(limit + 1); // fetch one extra row to signal truncation
            boolean hasResult = ps.execute();
            if (!hasResult) {
                result.put("columns", columns);
                result.put("rows", rows);
                result.put("truncated", false);
                return result;
            }

            try (ResultSet rs = ps.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                while (rs.next()) {
                    if (rows.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }

        result.put("columns", columns);
        result.put("rows", rows);
        result.put("truncated", truncated);
        return result;
    }

    private List<String> resolveNamesForUuids(Set<String> uuids) throws SQLException {
        List<String> names = new ArrayList<>();
        if (uuids == null) return names;
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_name FROM player_identities WHERE player_uuid = ? ORDER BY last_updated DESC LIMIT 1")) {
            for (String uuid : uuids) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString(1);
                        if (name != null && !name.isEmpty()) {
                            names.add(name);
                        }
                    }
                }
            }
        }
        return names;
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

    public static class PlayerIdentityRequest implements AuthPayload {
        private String key;
        private String playerUuid;
        private String playerName; // optional
        private List<String> keys; // optional filter
        private Integer page;      // optional, for paginated queries
        private Integer pageSize;  // optional, for paginated queries

        public PlayerIdentityRequest() {
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

        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        public List<String> getKeys() { return keys; }
        public void setKeys(List<String> keys) { this.keys = keys; }

        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public Integer getPageSize() { return pageSize; }
        public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    }

    public static class PlayerIdentitiesListRequest implements AuthPayload {
        private String key;
        private Integer page;
        private Integer pageSize;

        public PlayerIdentitiesListRequest() {}

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public Integer getPageSize() { return pageSize; }
        public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    }

    public static class MtrLogsQueryRequest implements AuthPayload {
        private String key;
        private String playerUuid; // optional
        private String playerName; // optional
        private String singleDate; // YYYY-MM-DD optional
        private String startDate;  // YYYY-MM-DD optional
        private String endDate;    // YYYY-MM-DD optional
        private String dimensionContext; // optional
        private String entryId; // optional
        private String changeType; // optional
        private int page = 1;
        private int pageSize = 50;
        private String order = "desc";
        private String orderColumn; // optional: timestamp|id; default timestamp

        public MtrLogsQueryRequest() {}

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getPlayerUuid() { return playerUuid; }
        public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }
        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        public String getSingleDate() { return singleDate; }
        public void setSingleDate(String singleDate) { this.singleDate = singleDate; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public String getDimensionContext() { return dimensionContext; }
        public void setDimensionContext(String dimensionContext) { this.dimensionContext = dimensionContext; }
        public String getEntryId() { return entryId; }
        public void setEntryId(String entryId) { this.entryId = entryId; }
        public String getChangeType() { return changeType; }
        public void setChangeType(String changeType) { this.changeType = changeType; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
        public String getOrder() { return order; }
        public void setOrder(String order) { this.order = order; }
        public String getOrderColumn() { return orderColumn; }
        public void setOrderColumn(String orderColumn) { this.orderColumn = orderColumn; }
    }

    public static class MtrLogDetailRequest implements AuthPayload {
        private String key;
        private long id;
        public MtrLogDetailRequest() {}
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
    }

    public static class PlayerSessionsQueryRequest implements AuthPayload {
        private String key;
        private String playerUuid; // optional
        private String playerName; // optional
        private String eventType;  // optional, JOIN/QUIT
        private String singleDate; // YYYY-MM-DD optional
        private String startDate;  // YYYY-MM-DD optional
        private String endDate;    // YYYY-MM-DD optional
        private Long startAt;      // epoch millis optional
        private Long endAt;        // epoch millis optional
        private int page = 1;
        private int pageSize = 50;

        public PlayerSessionsQueryRequest() {}

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getPlayerUuid() { return playerUuid; }
        public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }
        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getSingleDate() { return singleDate; }
        public void setSingleDate(String singleDate) { this.singleDate = singleDate; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public Long getStartAt() { return startAt; }
        public void setStartAt(Long startAt) { this.startAt = startAt; }
        public Long getEndAt() { return endAt; }
        public void setEndAt(Long endAt) { this.endAt = endAt; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    }

    public static class PlayersDataRequest implements AuthPayload {
        private String key;
        private List<String> playerUuids;
        private List<String> playerNames;
        private List<String> statKeys;
        private List<String> advancementKeys;
        private Boolean includeBalance;
        private Boolean includeBalanceAll;

        public PlayersDataRequest() {}

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public List<String> getPlayerUuids() { return playerUuids; }
        public void setPlayerUuids(List<String> playerUuids) { this.playerUuids = playerUuids; }
        public List<String> getPlayerNames() { return playerNames; }
        public void setPlayerNames(List<String> playerNames) { this.playerNames = playerNames; }
        public List<String> getStatKeys() { return statKeys; }
        public void setStatKeys(List<String> statKeys) { this.statKeys = statKeys; }
        public List<String> getAdvancementKeys() { return advancementKeys; }
        public void setAdvancementKeys(List<String> advancementKeys) { this.advancementKeys = advancementKeys; }
        public Boolean getIncludeBalance() { return includeBalance; }
        public void setIncludeBalance(Boolean includeBalance) { this.includeBalance = includeBalance; }
        public Boolean getIncludeBalanceAll() { return includeBalanceAll; }
        public void setIncludeBalanceAll(Boolean includeBalanceAll) { this.includeBalanceAll = includeBalanceAll; }
    }

    public static class ExecuteSqlRequest implements AuthPayload {
        private String key;
        private String sql;
        private Integer maxRows;

        public ExecuteSqlRequest() {}

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getSql() { return sql; }
        public void setSql(String sql) { this.sql = sql; }
        public Integer getMaxRows() { return maxRows; }
        public void setMaxRows(Integer maxRows) { this.maxRows = maxRows; }
    }

    public static class PlayerBalanceRequest implements AuthPayload {
        private String key;
        private String playerName;

        public PlayerBalanceRequest() {}

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
    }

    public static class PlayerBalanceUpdateRequest extends PlayerBalanceRequest {
        private long amount;

        public PlayerBalanceUpdateRequest() { super(); }

        public long getAmount() { return amount; }
        public void setAmount(long amount) { this.amount = amount; }
    }
}


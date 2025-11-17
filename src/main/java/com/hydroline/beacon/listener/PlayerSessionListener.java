package com.hydroline.beacon.listener;

import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PlayerSessionListener implements Listener {

    private final BeaconPlugin plugin;

    public PlayerSessionListener(BeaconPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        handleSessionEvent(event.getPlayer(), "JOIN");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        handleSessionEvent(event.getPlayer(), "QUIT");
    }

    private void handleSessionEvent(Player player, String eventType) {
        long occurredAt = System.currentTimeMillis();
        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();

        InetSocketAddress address = player.getAddress();
        String ip = address != null ? address.getAddress().getHostAddress() : null;

        World world = player.getWorld();
        String worldName = world.getName();
        String dimensionKey = world.getEnvironment().name();

        double x = player.getLocation().getX();
        double y = player.getLocation().getY();
        double z = player.getLocation().getZ();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager db = plugin.getDatabaseManager();
            if (db == null) {
                return;
            }
            try (Connection connection = db.getConnection()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO player_sessions (" +
                                "event_type, occurred_at, player_uuid, player_name, player_ip, " +
                                "world_name, dimension_key, x, y, z" +
                                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    ps.setString(1, eventType);
                    ps.setLong(2, occurredAt);
                    ps.setString(3, playerUuid);
                    ps.setString(4, playerName);
                    ps.setString(5, ip);
                    ps.setString(6, worldName);
                    ps.setString(7, dimensionKey);
                    ps.setDouble(8, x);
                    ps.setDouble(9, y);
                    ps.setDouble(10, z);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                Plugin p = this.plugin;
                if (p != null) {
                    p.getLogger().severe("Failed to insert player session record: " + e.getMessage());
                }
            }
        });
    }
}


package com.hydroline.beacon;

import com.hydroline.beacon.config.ConfigManager;
import com.hydroline.beacon.config.PluginConfig;
import com.hydroline.beacon.listener.PlayerSessionListener;
import com.hydroline.beacon.socket.SocketServerManager;
import com.hydroline.beacon.storage.DatabaseManager;
import com.hydroline.beacon.task.ScanScheduler;
import com.hydroline.beacon.world.WorldFileAccess;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class BeaconPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private ScanScheduler scanScheduler;
    private WorldFileAccess worldFileAccess;
    private SocketServerManager socketServerManager;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        PluginConfig cfg = configManager.getCurrentConfig();
        getLogger().info("HydrolineBeacon enabled with port=" + cfg.getPort()
                + ", interval_time_ticks=" + cfg.getIntervalTimeTicks()
                + ", version=" + cfg.getVersion());

        this.databaseManager = new DatabaseManager(this);
        this.worldFileAccess = new WorldFileAccess(Bukkit.getWorlds());
        Bukkit.getPluginManager().registerEvents(new PlayerSessionListener(this), this);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                databaseManager.initialize();
                getLogger().info("SQLite database initialized successfully.");

                // 初始化完成后启动定时异步扫描任务
                this.scanScheduler = new ScanScheduler(this);
                this.scanScheduler.start();

                this.socketServerManager = new SocketServerManager(this);
                this.socketServerManager.start();
            } catch (SQLException e) {
                getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (this.scanScheduler != null) {
            this.scanScheduler.stop();
        }
        if (this.socketServerManager != null) {
            this.socketServerManager.stop();
        }
        getLogger().info("HydrolineBeacon disabled!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public WorldFileAccess getWorldFileAccess() {
        return worldFileAccess;
    }
}

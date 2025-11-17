package com.hydroline.beacon.config;

import org.bukkit.configuration.file.FileConfiguration;

public class PluginConfig {

    private final int port;
    private final String key;
    private final long intervalTimeTicks;
    private final int version;

    public PluginConfig(int port, String key, long intervalTimeTicks, int version) {
        this.port = port;
        this.key = key;
        this.intervalTimeTicks = intervalTimeTicks;
        this.version = version;
    }

    public static PluginConfig fromConfig(FileConfiguration config) {
        int port = config.getInt("port");
        String key = config.getString("key", "");
        long intervalTimeTicks = config.getLong("interval_time");
        int version = config.getInt("version");
        return new PluginConfig(port, key, intervalTimeTicks, version);
    }

    public int getPort() {
        return port;
    }

    public String getKey() {
        return key;
    }

    public long getIntervalTimeTicks() {
        return intervalTimeTicks;
    }

    public int getVersion() {
        return version;
    }
}


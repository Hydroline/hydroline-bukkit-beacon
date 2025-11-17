package com.hydroline.beacon.storage;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final Plugin plugin;
    private final String jdbcUrl;

    public DatabaseManager(Plugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "hydroline_beacon.db");
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    public void initialize() throws SQLException {
        try (Connection connection = getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA journal_mode=WAL");
            }
            createSchema(connection);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS schema_version (" +
                            "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                            "version INTEGER NOT NULL," +
                            "updated_at INTEGER NOT NULL" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_sessions (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "event_type TEXT NOT NULL," +
                            "occurred_at INTEGER NOT NULL," +
                            "player_uuid TEXT NOT NULL," +
                            "player_name TEXT," +
                            "player_ip TEXT," +
                            "world_name TEXT," +
                            "dimension_key TEXT," +
                            "x REAL," +
                            "y REAL," +
                            "z REAL" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_advancements (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "player_uuid TEXT NOT NULL," +
                            "advancement_key TEXT NOT NULL," +
                            "value BLOB NOT NULL," +
                            "last_updated INTEGER NOT NULL," +
                            "UNIQUE(player_uuid, advancement_key)" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_stats (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "player_uuid TEXT NOT NULL," +
                            "stat_key TEXT NOT NULL," +
                            "value INTEGER NOT NULL," +
                            "last_updated INTEGER NOT NULL," +
                            "UNIQUE(player_uuid, stat_key)" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS mtr_logs (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "timestamp TEXT," +
                            "player_name TEXT," +
                            "player_uuid TEXT," +
                            "class_name TEXT," +
                            "entry_id TEXT," +
                            "entry_name TEXT," +
                            "position TEXT," +
                            "change_type TEXT," +
                            "old_data TEXT," +
                            "new_data TEXT," +
                            "source_file_path TEXT," +
                            "source_line INTEGER," +
                            "dimension_context TEXT" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS mtr_files (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "file_path TEXT NOT NULL UNIQUE," +
                            "last_modified INTEGER NOT NULL," +
                            "last_processed INTEGER," +
                            "processed INTEGER NOT NULL DEFAULT 0," +
                            "dimension_context TEXT" +
                            ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS file_sync_state (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "file_type TEXT NOT NULL," +
                            "player_uuid TEXT," +
                            "file_path TEXT NOT NULL," +
                            "last_modified INTEGER NOT NULL," +
                            "last_processed INTEGER," +
                            "UNIQUE(file_type, file_path)" +
                            ")"
            );
        }
    }
}

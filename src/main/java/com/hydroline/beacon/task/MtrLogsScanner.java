package com.hydroline.beacon.task;

import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.storage.DatabaseManager;
import com.hydroline.beacon.world.WorldFileAccess;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.bukkit.World;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MtrLogsScanner {

    private final BeaconPlugin plugin;

    public MtrLogsScanner(BeaconPlugin plugin) {
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
        int rowsInserted = 0;

        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);

            for (World world : worldFileAccess.getWorlds()) {
                List<File> csvFiles = worldFileAccess.findMtrLogFiles(world);
                if (csvFiles.isEmpty()) {
                    continue;
                }

                Map<String, List<File>> byContext = groupByContext(worldFileAccess, world, csvFiles);
                for (Map.Entry<String, List<File>> entry : byContext.entrySet()) {
                    String context = entry.getKey();
                    List<File> files = entry.getValue();
                    if (files.isEmpty()) {
                        continue;
                    }

                    boolean hasExisting = hasAnyFileForContext(connection, context);
                    files.sort(Comparator.comparingLong(File::lastModified).reversed());

                    List<File> toProcess = new ArrayList<>();
                    if (hasExisting) {
                        for (int i = 0; i < files.size() && i < 2; i++) {
                            toProcess.add(files.get(i));
                        }
                    } else {
                        toProcess.addAll(files);
                    }

                    for (File file : toProcess) {
                        int inserted = processCsvFile(connection, file, context);
                        if (inserted >= 0) {
                            filesProcessed++;
                            rowsInserted += inserted;
                        }
                    }
                }
            }

            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to scan MTR logs: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (filesProcessed > 0 || rowsInserted > 0) {
            plugin.getLogger().info("MTR logs scan completed in " + elapsed + " ms, " +
                    "files processed=" + filesProcessed + ", rows inserted=" + rowsInserted);
        }
    }

    private Map<String, List<File>> groupByContext(WorldFileAccess worldFileAccess,
                                                   World world,
                                                   List<File> csvFiles) {
        Map<String, List<File>> byContext = new HashMap<>();
        for (File file : csvFiles) {
            String context = worldFileAccess.deriveDimensionContext(world, file);
            byContext.computeIfAbsent(context, k -> new ArrayList<>()).add(file);
        }
        return byContext;
    }

    private boolean hasAnyFileForContext(Connection connection, String context) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM mtr_files WHERE dimension_context = ? LIMIT 1"
        )) {
            ps.setString(1, context);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int processCsvFile(Connection connection, File file, String context) {
        long lastModified = file.lastModified();
        String path = file.getAbsolutePath();

        try {
            if (!shouldProcessFile(connection, path, lastModified)) {
                return 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check MTR file state for " + path + ": " + e.getMessage());
            return -1;
        }

        int inserted = 0;
        long now = System.currentTimeMillis();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            CSVParser parser = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreEmptyLines()
                    .parse(reader);

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO mtr_logs (" +
                            "timestamp, player_name, player_uuid, class_name, entry_id, entry_name, " +
                            "position, change_type, old_data, new_data, " +
                            "source_file_path, source_line, dimension_context" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                for (CSVRecord record : parser) {
                    ps.setString(1, record.get("Timestamp"));
                    ps.setString(2, record.get("Player Name"));
                    ps.setString(3, record.get("Player UUID"));
                    ps.setString(4, record.get("Class"));
                    ps.setString(5, record.get("ID"));
                    ps.setString(6, record.get("Name"));
                    ps.setString(7, record.get("Position"));
                    ps.setString(8, record.get("Change"));
                    ps.setString(9, record.get("Old Data"));
                    ps.setString(10, record.get("New Data"));
                    ps.setString(11, path);
                    ps.setLong(12, record.getRecordNumber());
                    ps.setString(13, context);
                    ps.addBatch();
                    inserted++;
                }
                ps.executeBatch();
            }

            upsertMtrFileState(connection, path, lastModified, now, context);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to read MTR CSV file " + path + ": " + e.getMessage());
            return -1;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to insert MTR log rows for " + path + ": " + e.getMessage());
            return -1;
        }

        return inserted;
    }

    private boolean shouldProcessFile(Connection connection,
                                      String filePath,
                                      long lastModified) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_modified, processed FROM mtr_files WHERE file_path = ?"
        )) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return true;
                }
                long storedLastModified = rs.getLong("last_modified");
                int processed = rs.getInt("processed");
                return storedLastModified != lastModified || processed == 0;
            }
        }
    }

    private void upsertMtrFileState(Connection connection,
                                    String filePath,
                                    long lastModified,
                                    long lastProcessed,
                                    String context) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mtr_files (" +
                        "file_path, last_modified, last_processed, processed, dimension_context" +
                        ") VALUES (?, ?, ?, 1, ?) " +
                        "ON CONFLICT(file_path) DO UPDATE SET " +
                        "last_modified = excluded.last_modified, " +
                        "last_processed = excluded.last_processed, " +
                        "processed = excluded.processed, " +
                        "dimension_context = excluded.dimension_context"
        )) {
            ps.setString(1, filePath);
            ps.setLong(2, lastModified);
            ps.setLong(3, lastProcessed);
            ps.setString(4, context);
            ps.executeUpdate();
        }
    }
}


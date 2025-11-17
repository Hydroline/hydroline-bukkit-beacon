package com.hydroline.beacon.world;

import org.bukkit.World;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorldFileAccess {

    private final List<World> worldsSnapshot;

    public WorldFileAccess(List<World> worldsSnapshot) {
        this.worldsSnapshot = new ArrayList<>(worldsSnapshot);
    }

    public List<World> getWorlds() {
        return Collections.unmodifiableList(worldsSnapshot);
    }

    public File getAdvancementsDirectory(World world) {
        return new File(world.getWorldFolder(), "advancements");
    }

    public File getStatsDirectory(World world) {
        return new File(world.getWorldFolder(), "stats");
    }

    public File getWorldFolder(World world) {
        return world.getWorldFolder();
    }

    public List<File> findMtrLogFiles(World world) {
        List<File> result = new ArrayList<>();
        File worldFolder = world.getWorldFolder();
        collectCsvLogsUnder(worldFolder, worldFolder, result);
        return result;
    }

    public String deriveDimensionContext(World world, File csvFile) {
        Path worldPath = world.getWorldFolder().toPath();
        Path parentPath = csvFile.getParentFile().toPath();
        Path relativeDir = worldPath.relativize(parentPath);
        String context = relativeDir.toString().replace(File.separatorChar, '/');
        return context;
    }

    private void collectCsvLogsUnder(File worldFolder, File current, List<File> result) {
        if (current == null || !current.exists()) {
            return;
        }
        File[] files = current.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectCsvLogsUnder(worldFolder, file, result);
            } else if (file.isFile()
                    && file.getName().toLowerCase().endsWith(".csv")
                    && "logs".equalsIgnoreCase(file.getParentFile().getName())) {
                result.add(file);
            }
        }
    }
}


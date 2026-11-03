package dev.nofx.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class TrackingStore {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public TrackingStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tracking.yml");
    }

    public void load() {
        if (!file.exists()) {
            try {
                if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                    plugin.getLogger().warning("[NoFX] Could not create plugin data folder.");
                }
                if (file.createNewFile()) {
                    plugin.getLogger().info("[NoFX] Created new tracking.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[NoFX] Could not create tracking.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        if (data == null) {
            return;
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[NoFX] Failed to save tracking.yml: " + e.getMessage());
        }
    }

    private String path(UUID uuid) {
        return "offenders." + uuid.toString();
    }

    private void touch(UUID uuid, String name) {
        String p = path(uuid);
        long now = System.currentTimeMillis();
        data.set(p + ".name", name);
        data.set(p + ".last-seen", now);
        if (!data.contains(p + ".first-seen")) {
            data.set(p + ".first-seen", now);
        }
    }

    public int detectionCount(UUID uuid) {
        return data == null ? 0 : data.getInt(path(uuid) + ".detections", 0);
    }

    public int protectedCount(UUID uuid) {
        return data == null ? 0 : data.getInt(path(uuid) + ".protected", 0);
    }

    public boolean isFlagged(UUID uuid) {
        return data != null && data.getBoolean(path(uuid) + ".flagged", false);
    }

    public boolean isLocked(UUID uuid) {
        return data != null && data.getBoolean(path(uuid) + ".locked", false);
    }

    public int recordDetection(UUID uuid, String name) {
        touch(uuid, name);
        int n = detectionCount(uuid) + 1;
        data.set(path(uuid) + ".detections", n);
        data.set(path(uuid) + ".flagged", true);
        save();
        return n;
    }

    public int recordProtected(UUID uuid, String name) {
        touch(uuid, name);
        int n = protectedCount(uuid) + 1;
        data.set(path(uuid) + ".protected", n);
        data.set(path(uuid) + ".flagged", true);
        save();
        return n;
    }

    public void setLocked(UUID uuid, boolean locked) {
        data.set(path(uuid) + ".locked", locked);
        save();
    }

    public void markClean(UUID uuid) {
        String p = path(uuid);
        data.set(p + ".flagged", false);
        data.set(p + ".locked", false);
        data.set(p + ".last-seen", System.currentTimeMillis());
        save();
    }

    public void forget(UUID uuid) {
        data.set(path(uuid), null);
        save();
    }

    public List<TrackingEntry> entries() {
        List<TrackingEntry> list = new ArrayList<>();
        if (data == null || !data.contains("offenders")) {
            return list;
        }
        ConfigurationSection section = data.getConfigurationSection("offenders");
        if (section == null) {
            return list;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String p = path(uuid);
                list.add(new TrackingEntry(
                        uuid,
                        data.getString(p + ".name", "?"),
                        data.getInt(p + ".detections", 0),
                        data.getInt(p + ".protected", 0),
                        data.getLong(p + ".last-seen", 0L)
                ));
            } catch (IllegalArgumentException ignored) {
            }
        }
        list.sort(Comparator.comparingLong(TrackingEntry::lastSeen).reversed());
        return list;
    }

    public record TrackingEntry(UUID uuid, String name, int detections, int protectedCount, long lastSeen) {
    }
}

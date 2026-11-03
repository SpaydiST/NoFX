package dev.nofx;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import dev.nofx.enforce.Enforcement;
import dev.nofx.scan.Probe;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public class NoFXConfig {

    private static final int CURRENT_CONFIG_VERSION = 8;

    private final JavaPlugin plugin;
    private FileConfiguration cfg;

    private boolean scanEnabled;
    private boolean modRequire;
    private boolean modSkipScan;
    private int modGraceTicks;
    private int modHeartbeatTimeoutTicks;
    private String modKickMessage;
    private Enforcement.ActionSpec modFreecamSpec;
    private boolean onJoinEnabled;
    private int onJoinDelayTicks;
    private boolean firstJoinOnly;
    private int cooldownSeconds;
    private int timeoutTicks;
    private int betweenSignTicks;
    private int signDistance;
    private boolean invisibleClose = true;
    private String scanPlacement = "behind";
    private List<Probe> probes = new ArrayList<>();
    private String controlKeybind;

    private boolean twEnabled;
    private boolean twFaceCheck;
    private double twReachTolerance;
    private int violationsBeforeAction;
    private int violationDecaySeconds;
    private int checksPerSecond;
    private int twActionCooldownSeconds = 5;
    private Enforcement.ActionSpec throughWallSpec;

    private Enforcement.ActionSpec detectSpec;
    private Enforcement.ActionSpec protectedSpec;
    private Enforcement.ActionSpec repeatDetectSpec;
    private Enforcement.ActionSpec repeatProtectedSpec;
    private int repeatAfterDetects;
    private int repeatAfterProtected;
    private boolean lockdownOnProtected;
    private int lockdownWarnCooldownSeconds = 5;
    private int notifyCooldownSeconds;
    private boolean notifyStaff;
    private String notifyPermission;

    private String prefix;
    private final Map<String, String> messages = new HashMap<>();

    public NoFXConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        migrateIfNeeded();
        plugin.reloadConfig();
        cfg = plugin.getConfig();

        ConfigurationSection mod = cfg.getConfigurationSection("mod");
        if (mod != null) {
            modRequire = mod.getBoolean("require", false);
            modSkipScan = mod.getBoolean("skip-scan", false);
            modGraceTicks = Math.max(20, mod.getInt("grace-ticks", 100));
            modHeartbeatTimeoutTicks = Math.max(40, mod.getInt("heartbeat-timeout-ticks", 1200));
            modKickMessage = mod.getString("kick-message",
                    "<red>На этом сервере нужен клиентский мод NoFC. Установи его и зайди снова.</red>");
            modFreecamSpec = Enforcement.spec(mod.getConfigurationSection("on-freecam"));
        }

        ConfigurationSection scan = cfg.getConfigurationSection("scan");
        if (scan != null) {
            scanEnabled = scan.getBoolean("enabled", true);
            ConfigurationSection join = scan.getConfigurationSection("on-join");
            if (join != null) {
                onJoinEnabled = join.getBoolean("enabled", true);
                onJoinDelayTicks = Math.max(0, join.getInt("delay-ticks", 40));
                firstJoinOnly = join.getBoolean("first-join-only", false);
                cooldownSeconds = Math.max(0, join.getInt("cooldown-seconds", 1800));
            }
            timeoutTicks = Math.max(20, scan.getInt("timeout-ticks", 100));
            betweenSignTicks = Math.max(1, scan.getInt("between-sign-ticks", 5));
            signDistance = Math.max(1, Math.min(5, scan.getInt("sign-distance", 3)));
            invisibleClose = scan.getBoolean("invisible-close", true);
            scanPlacement = scan.getString("placement", "behind");
            controlKeybind = scan.getString("control-keybind", "key.forward");
            probes = parseProbes(scan);
        }

        ConfigurationSection tw = cfg.getConfigurationSection("through-wall");
        if (tw != null) {
            twEnabled = tw.getBoolean("enabled", true);
            twFaceCheck = tw.getBoolean("face-check", true);
            twReachTolerance = Math.max(0.0, tw.getDouble("reach-tolerance", 2.0));
            violationsBeforeAction = Math.max(1, tw.getInt("violations-before-action", 3));
            violationDecaySeconds = Math.max(1, tw.getInt("violation-decay-seconds", 10));
            checksPerSecond = Math.max(1, tw.getInt("checks-per-second", 20));
            twActionCooldownSeconds = Math.max(1, tw.getInt("action-cooldown-seconds", 5));
            throughWallSpec = Enforcement.spec(tw);
        }

        ConfigurationSection enf = cfg.getConfigurationSection("enforcement");
        if (enf != null) {
            detectSpec = Enforcement.spec(enf.getConfigurationSection("on-detect"));
            protectedSpec = Enforcement.spec(enf.getConfigurationSection("on-protected"));
            repeatDetectSpec = Enforcement.spec(enf.getConfigurationSection("on-repeat-detect"));
            repeatProtectedSpec = Enforcement.spec(enf.getConfigurationSection("on-repeat-protected"));
            repeatAfterDetects = Math.max(1, enf.getInt("repeat-after-detects", 2));
            repeatAfterProtected = Math.max(1, enf.getInt("repeat-after-protected", 3));
            lockdownOnProtected = enf.getBoolean("lockdown-on-protected", false);
            lockdownWarnCooldownSeconds = Math.max(1, enf.getInt("lockdown-warn-cooldown-seconds", 5));
            notifyCooldownSeconds = Math.max(0, enf.getInt("notify-cooldown-seconds", 30));
            notifyStaff = enf.getBoolean("notify-staff", false);
            notifyPermission = enf.getString("notify-permission", "nofx.admin");
        }

        ConfigurationSection msg = cfg.getConfigurationSection("messages");
        messages.clear();
        if (msg != null) {
            prefix = msg.getString("prefix", "<gray>[<gold>NoFX</gold>]</gray> ");
            messages.clear();
            for (String key : msg.getKeys(false)) {
                messages.put(key, msg.getString(key, ""));
            }
        }
    }

    private void migrateIfNeeded() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
            return;
        }
        FileConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
        if (existing.getInt("config-version", 1) >= CURRENT_CONFIG_VERSION) {
            return;
        }
        String backupName = "config.yml.bak-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        try {
            Files.copy(configFile.toPath(), new File(plugin.getDataFolder(), backupName).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось создать бэкап старого конфига: " + e.getMessage());
        }
        FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(plugin.getResource("config.yml"), StandardCharsets.UTF_8));
        mergeSection(defaults, plugin.getConfig());
        plugin.getConfig().set("config-version", CURRENT_CONFIG_VERSION);
        try {
            plugin.getConfig().save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить обновлённый конфиг: " + e.getMessage());
        }
        plugin.getLogger().info("Конфиг устарел, создан бэкап " + backupName +
                ". Старые настройки сохранены, добавлены новые параметры.");
    }

    static void mergeSection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (!target.getKeys(false).contains(key)) {
                target.set(key, value);
            } else if (value instanceof ConfigurationSection section) {
                ConfigurationSection nested = target.getConfigurationSection(key);
                if (nested != null) {
                    mergeSection(section, nested);
                }
            }
        }
    }

    private List<Probe> parseProbes(ConfigurationSection scan) {
        List<Probe> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        List<?> list = scan.getList("probes");
        if (list == null) {
            return result;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object key = m.get("key");
            Object type = m.get("type");
            Object fallback = m.get("fallback");
            if (!(key instanceof String rawKey) || rawKey.isBlank()) {
                continue;
            }
            String k = rawKey.trim();
            Probe.Type t;
            if (type == null || "KEYBIND".equalsIgnoreCase(String.valueOf(type))) {
                t = Probe.Type.KEYBIND;
            } else if ("TRANSLATE".equalsIgnoreCase(String.valueOf(type))) {
                t = Probe.Type.TRANSLATE;
            } else {
                plugin.getLogger().warning("[NoFX] Unknown probe type '" + type + "' for key '" + k
                        + "'; probe ignored.");
                continue;
            }
            if (!seenKeys.add(k)) {
                plugin.getLogger().warning("[NoFX] Duplicate Freecam probe key '" + k + "' ignored.");
                continue;
            }
            result.add(new Probe(k, t, fallback == null ? "" : String.valueOf(fallback)));
        }
        return result;
    }

    public boolean scanEnabled() {
        return scanEnabled;
    }

    public boolean modRequire() {
        return modRequire;
    }

    public boolean modSkipScan() {
        return modSkipScan;
    }

    public int modGraceTicks() {
        return modGraceTicks;
    }

    public int modHeartbeatTimeoutTicks() {
        return modHeartbeatTimeoutTicks;
    }

    public String modKickMessage() {
        return modKickMessage;
    }

    public Enforcement.ActionSpec modFreecamSpec() {
        return modFreecamSpec;
    }

    public boolean onJoinEnabled() {
        return onJoinEnabled;
    }

    public int onJoinDelayTicks() {
        return onJoinDelayTicks;
    }

    public boolean firstJoinOnly() {
        return firstJoinOnly;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public int timeoutTicks() {
        return timeoutTicks;
    }

    public int betweenSignTicks() {
        return betweenSignTicks;
    }

    public boolean invisibleClose() {
        return invisibleClose;
    }

    public int signDistance() {
        return signDistance;
    }

    public boolean placementBehind() {
        return "behind".equalsIgnoreCase(scanPlacement);
    }

    public List<Probe> probes() {
        return probes;
    }

    public String controlKeybind() {
        return controlKeybind;
    }

    public boolean twEnabled() {
        return twEnabled;
    }

    public boolean twFaceCheck() {
        return twFaceCheck;
    }

    public double twReachTolerance() {
        return twReachTolerance;
    }

    public int violationsBeforeAction() {
        return violationsBeforeAction;
    }

    public int violationDecaySeconds() {
        return violationDecaySeconds;
    }

    public int checksPerSecond() {
        return checksPerSecond;
    }

    public int twActionCooldownSeconds() {
        return twActionCooldownSeconds;
    }

    public Enforcement.ActionSpec throughWallSpec() {
        return throughWallSpec;
    }

    public Enforcement.ActionSpec detectSpec() {
        return detectSpec;
    }

    public Enforcement.ActionSpec protectedSpec() {
        return protectedSpec;
    }

    public Enforcement.ActionSpec repeatDetectSpec() {
        return repeatDetectSpec;
    }

    public Enforcement.ActionSpec repeatProtectedSpec() {
        return repeatProtectedSpec;
    }

    public int repeatAfterDetects() {
        return repeatAfterDetects;
    }

    public int repeatAfterProtected() {
        return repeatAfterProtected;
    }

    public boolean lockdownOnProtected() {
        return lockdownOnProtected;
    }

    public int lockdownWarnCooldownSeconds() {
        return lockdownWarnCooldownSeconds;
    }

    public int notifyCooldownSeconds() {
        return notifyCooldownSeconds;
    }

    public boolean notifyStaff() {
        return notifyStaff;
    }

    public String notifyPermission() {
        return notifyPermission;
    }

    public String prefix() {
        return prefix;
    }

    public String msg(String key) {
        return messages.getOrDefault(key, "");
    }
}

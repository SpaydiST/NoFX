package dev.nofx.enforce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import dev.nofx.NoFXConfig;
import dev.nofx.scan.ScanResult;
import dev.nofx.store.TrackingStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Enforcement {

    public enum Action {
        NONE,
        WARN,
        KICK,
        BAN
    }

    public record ActionSpec(
            Action action,
            String warnMessage,
            String kickMessage,
            String banReason,
            String banDuration,
            List<String> commands
    ) {
    }

    public static ActionSpec spec(ConfigurationSection section) {
        if (section == null) {
            return new ActionSpec(Action.NONE, "", "", "NoFX", "7d", List.of());
        }
        return new ActionSpec(
                parseAction(section.getString("action", "none")),
                section.getString("warn-message", ""),
                section.getString("kick-message", ""),
                section.getString("ban-reason", "NoFX"),
                section.getString("ban-duration", "7d"),
                section.getStringList("commands")
        );
    }

    public static Action parseAction(String raw) {
        if (raw == null) {
            return Action.NONE;
        }
        try {
            return Action.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Action.NONE;
        }
    }

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_KICK_MESSAGE = "<red>Отключение NoFX.</red>";
    private static final long LOG_ROTATE_BYTES = 5L * 1024L * 1024L;

    private final JavaPlugin plugin;
    private final NoFXConfig config;
    private final TrackingStore tracking;

    public Enforcement(JavaPlugin plugin, NoFXConfig config, TrackingStore tracking) {
        this.plugin = plugin;
        this.config = config;
        this.tracking = tracking;
    }

    public void handleScanResult(Player target, ScanResult verdict, List<String> detectedProbes) {
        if (target == null || !target.isOnline()) {
            return;
        }
        switch (verdict) {
            case DETECTED -> {
                int n = tracking.detectionCount(target.getUniqueId());
                run(target, n >= config.repeatAfterDetects() ? config.repeatDetectSpec() : config.detectSpec());
                logDetection(target.getName(), "DETECTED", detectedProbes, n);
            }
            case PROTECTED -> {
                int n = tracking.protectedCount(target.getUniqueId());
                run(target, n >= config.repeatAfterProtected() ? config.repeatProtectedSpec() : config.protectedSpec());
                logDetection(target.getName(), "PROTECTED", detectedProbes, n);
            }
            case SKIPPED -> logDetection(target.getName(), "INCOMPLETE", List.of(), 0);
            case NOT_DETECTED -> logDetection(target.getName(), "CLEAN", List.of(), 0);
        }
    }

    public void handleThroughWall(Player target) {
        run(target, config.throughWallSpec());
        logDetection(target.getName(), "THROUGH-WALL", List.of(), tracking.detectionCount(target.getUniqueId()));
    }

    public void handleModFreecam(Player target, double cx, double cy, double cz) {
        if (target == null || !target.isOnline()) {
            return;
        }
        int n = tracking.recordDetection(target.getUniqueId(), target.getName());
        ActionSpec spec = n >= config.repeatAfterDetects()
                ? config.repeatDetectSpec()
                : config.modFreecamSpec();
        run(target, spec);
        logDetection(target.getName(), "MOD-FREECAM", List.of("mod-freecam"), n);
    }

    public void runAction(Player target, ActionSpec spec) {
        run(target, spec);
    }

    private void run(Player target, ActionSpec spec) {
        if (target == null || !target.isOnline()) {
            return;
        }
        if (spec == null || spec.action() == Action.NONE) {
            return;
        }
        String name = target.getName();

        switch (spec.action()) {
            case WARN -> {
                // Предупреждения показываются в action bar, НЕ в чат.
                if (spec.warnMessage() != null && !spec.warnMessage().isEmpty()) {
                    target.sendActionBar(parse(spec.warnMessage()));
                }
            }
            case KICK -> {
                String msg = spec.kickMessage() != null && !spec.kickMessage().isEmpty()
                        ? spec.kickMessage() : DEFAULT_KICK_MESSAGE;
                kick(target, msg);
            }
            case BAN -> ban(target, spec);
            case NONE -> {
            }
        }

        for (String cmd : spec.commands()) {
            String resolved = cmd.replace("%player%", name);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }
    }

    private void logDetection(String player, String event, List<String> probes, int count) {
        File log = new File(plugin.getDataFolder(), "nofx-detections.log");
        try {
            if (log.exists() && log.length() > LOG_ROTATE_BYTES) {
                File rotated = new File(plugin.getDataFolder(), "nofx-detections.log.1");
                Files.move(log.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[NoFX] Failed to rotate detections log: " + e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(LocalDateTime.now().format(LOG_TIME)).append(' ').append(event).append(' ').append(player);
        if (count > 0) {
            sb.append(" (count=").append(count).append(')');
        }
        if (!probes.isEmpty()) {
            sb.append(" probes=[").append(String.join(", ", probes)).append(']');
        }
        try {
            Files.write(log.toPath(),
                    (sb + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("[NoFX] Failed to write detections log: " + e.getMessage());
        }
    }

    private void ban(Player target, ActionSpec spec) {
        if (target == null || !target.isOnline()) {
            return;
        }
        String reason = spec.banReason() != null && !spec.banReason().isEmpty() ? spec.banReason() : "NoFX";
        Duration duration = parseBanDuration(spec.banDuration());
        try {
            target.ban(reason, duration, "NoFX", true);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[NoFX] Failed to ban " + target.getName() + ": " + e.getMessage());
        }
    }

    static Duration parseBanDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        long totalSeconds = 0;
        for (Matcher m = Pattern.compile("(\\d+)\\s*([smhdw])").matcher(s); m.find(); ) {
            long value = Long.parseLong(m.group(1));
            totalSeconds += switch (m.group(2).charAt(0)) {
                case 's' -> value;
                case 'm' -> value * 60L;
                case 'h' -> value * 3600L;
                case 'd' -> value * 86400L;
                case 'w' -> value * 7L * 86400L;
                default -> 0L;
            };
        }
        return totalSeconds <= 0 ? null : Duration.ofSeconds(totalSeconds);
    }

    private void kick(Player target, String message) {
        if (target == null || !target.isOnline()) {
            return;
        }
        target.kick(parse(message));
    }

    private static Component parse(String template) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        return MiniMessage.miniMessage().deserialize(template);
    }
}

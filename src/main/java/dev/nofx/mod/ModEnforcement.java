package dev.nofx.mod;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.java.JavaPlugin;
import dev.nofx.NoFXConfig;
import dev.nofx.enforce.Enforcement;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModEnforcement implements Listener, PluginMessageListener {

    public static final String CHANNEL = "nofc:main";

    public static final int OP_HELLO = 0;
    public static final int OP_PING = 1;
    public static final int OP_FREECAM = 2;

    private static final double MAX_FREECAM_DISTANCE = 5120.0;
    private static final long FREECAM_ACTION_COOLDOWN_MS = 2000L;

    private final JavaPlugin plugin;
    private final NoFXConfig config;
    private final Enforcement enforcement;
    private final Map<UUID, Long> verifiedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHeartbeatAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFreecamAction = new ConcurrentHashMap<>();

    public ModEnforcement(JavaPlugin plugin, NoFXConfig config, Enforcement enforcement) {
        this.plugin = plugin;
        this.config = config;
        this.enforcement = enforcement;
    }

    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 20L, 20L);
    }

    public void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL);
    }

    public boolean hasMod(UUID uuid) {
        Long lastHeartbeat = lastHeartbeatAt.get(uuid);
        if (lastHeartbeat == null) {
            return false;
        }
        long timeoutMs = config.modHeartbeatTimeoutTicks() * 50L;
        if (System.currentTimeMillis() - lastHeartbeat > timeoutMs) {
            lastHeartbeatAt.remove(uuid, lastHeartbeat);
            return false;
        }
        return true;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || player == null) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            int opcode = in.readUnsignedByte();
            switch (opcode) {
                case OP_HELLO -> {
                    verifiedAt.put(player.getUniqueId(), System.currentTimeMillis());
                    plugin.getLogger().info("[NoFX] " + player.getName() + " подтвердил клиентский мод NoFC (hello).");
                }
                case OP_PING -> {
                    // Heartbeat принимается только после hello.
                    if (verifiedAt.containsKey(player.getUniqueId())) {
                        long now = System.currentTimeMillis();
                        verifiedAt.put(player.getUniqueId(), now);
                        lastHeartbeatAt.put(player.getUniqueId(), now);
                    }
                }
                case OP_FREECAM -> {
                    // Сигнал freecam принимается только после hello.
                    if (!verifiedAt.containsKey(player.getUniqueId())) {
                        return;
                    }
                    double cx = in.readDouble();
                    double cy = in.readDouble();
                    double cz = in.readDouble();
                    in.readDouble();
                    in.readDouble();
                    in.readDouble();
                    handleFreecam(player, cx, cy, cz);
                }
                default -> plugin.getLogger().info("[NoFX] Неизвестный opcode " + opcode + " от " + player.getName());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[NoFX] Ошибка разбора сообщения NoFC от " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handleFreecam(Player player, double cx, double cy, double cz) {
        if (player.hasPermission("nofx.bypass")) {
            return;
        }
        if (!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cz)) {
            plugin.getLogger().warning("[NoFX] Игнорирован freecam-сигнал с нечисловыми координатами от " + player.getName());
            return;
        }
        Location body = player.getLocation();
        Location camera = new Location(body.getWorld(), cx, cy, cz);
        if (body.distanceSquared(camera) > MAX_FREECAM_DISTANCE * MAX_FREECAM_DISTANCE) {
            plugin.getLogger().warning("[NoFX] Игнорирован freecam-сигнал с неправдоподобной позицией от " + player.getName());
            return;
        }
        int blockX = camera.getBlockX();
        int blockY = camera.getBlockY();
        int blockZ = camera.getBlockZ();
        if (blockY < body.getWorld().getMinHeight() || blockY >= body.getWorld().getMaxHeight()
                || !body.getWorld().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return;
        }
        Block cameraBlock = body.getWorld().getBlockAt(blockX, blockY, blockZ);
        if (cameraBlock.isPassable()) {
            plugin.getLogger().warning("[NoFX] Игнорирован freecam-сигнал вне твёрдого блока от " + player.getName());
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastFreecamAction.get(player.getUniqueId());
        if (last != null && now - last < FREECAM_ACTION_COOLDOWN_MS) {
            return;
        }
        lastFreecamAction.put(player.getUniqueId(), now);
        enforcement.handleModFreecam(player, cx, cy, cz);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!config.modRequire() || player.hasPermission("nofx.bypass")) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!verifiedAt.containsKey(player.getUniqueId())) {
                enforcement.runAction(player, kickSpec());
            }
        }, config.modGraceTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        verifiedAt.remove(uuid);
        lastHeartbeatAt.remove(uuid);
        lastFreecamAction.remove(uuid);
    }

    private void sweep() {
        if (!config.modRequire()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (player.hasPermission("nofx.bypass")) {
                continue;
            }
            Long last = verifiedAt.get(player.getUniqueId());
            if (last != null && now - last > config.modHeartbeatTimeoutTicks() * 50L) {
                enforcement.runAction(player, kickSpec());
            }
        }
    }

    private Enforcement.ActionSpec kickSpec() {
        String msg = config.modKickMessage();
        return new Enforcement.ActionSpec(Enforcement.Action.KICK, "", msg, msg, "7d", List.of());
    }
}

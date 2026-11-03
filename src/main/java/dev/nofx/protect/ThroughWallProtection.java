package dev.nofx.protect;

import org.bukkit.Location;
import org.bukkit.FluidCollisionMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import dev.nofx.NoFXConfig;
import dev.nofx.enforce.Enforcement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ThroughWallProtection implements Listener {

    // Окно «недавних» позиций глаза (мс). При беге/повороте клиент прицеливается
    // чуть РАНЬШЕ, чем сервер обрабатывает пакет, поэтому настоящая точка взгляда
    // на тике события чуть смещена от позиции игрока. Считаем взаимодействие
    // легитимным, если оно видно/достижимо из ЛЮБОЙ позиции глаза за последние
    // 500 мс — тогда рывки при движении не дают ложных срабатываний.
    private static final long EYE_BUFFER_MS = 500L;

    private record EyeSample(long time, Vector pos) {
    }

    private final NoFXConfig config;
    private final Enforcement enforcement;
    private final Map<UUID, Long> lastCheck = new HashMap<>();
    private final Map<UUID, Deque<Long>> violations = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final Map<UUID, Deque<EyeSample>> recentEyes = new HashMap<>();

    public ThroughWallProtection(NoFXConfig config, Enforcement enforcement) {
        this.config = config;
        this.enforcement = enforcement;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        BlockFace face = event.getBlockFace();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        Location point = face != null ? center.clone().add(face.getDirection().multiply(0.5)) : center;
        if (check(event.getPlayer(), block, point)) {
            handle(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (check(player, null, closestPoint(player, event.getRightClicked()))) {
            handle(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && check(player, null, closestPoint(player, event.getEntity()))) {
            handle(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block clicked = event.getBlockClicked();
        if (check(event.getPlayer(), clicked, clicked.getLocation().add(0.5, 0.5, 0.5))) {
            handle(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block clicked = event.getBlockClicked();
        if (check(event.getPlayer(), clicked, clicked.getLocation().add(0.5, 0.5, 0.5))) {
            handle(event.getPlayer());
        }
    }

    private Location closestPoint(Player player, Entity entity) {
        BoundingBox box = entity.getBoundingBox();
        Vector eye = player.getEyeLocation().toVector();
        double x = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double y = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double z = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        return new Location(entity.getWorld(), x, y, z);
    }

    private boolean check(Player player, Block block, Location point) {
        if (!config.twEnabled()) {
            return false;
        }
        if (player.hasPermission("nofx.bypass")) {
            return false;
        }
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastCheck.get(uuid);
        if (last != null && now - last < 1000L / config.checksPerSecond()) {
            return false;
        }
        lastCheck.put(uuid, now);

        // Фиксируем текущую позицию глаза в буфер недавних позиций.
        Deque<EyeSample> eyes = recentEyes.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        long cutoff = now - EYE_BUFFER_MS;
        while (!eyes.isEmpty() && eyes.peekFirst().time() < cutoff) {
            eyes.pollFirst();
        }
        Vector eyePos = player.getEyeLocation().toVector();
        eyes.addLast(new EyeSample(now, eyePos));

        double base = 4.5;
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        if (attr != null) {
            base = attr.getValue();
        }
        double max = base + config.twReachTolerance();
        double maxSq = max * max;

        // Достижимость: нарушение, только если точка недосягаема из ЛЮБОЙ
        // недавней позиции глаза (при беге глаз «догоняет» клик через буфер).
        if (eyes.stream()
                .noneMatch(s -> s.pos().distanceSquared(point.toVector()) <= maxSq)) {
            return true;
        }

        if (!config.twFaceCheck()) {
            return false;
        }
        // Видимость: блок или сущность должны быть видны из ЛЮБОЙ недавней
        // позиции глаза. Для блока используем выбранную грань, для сущности —
        // ближайшую к глазам точку её bounding box.
        Vector to = point.toVector();
        boolean anyVisible = false;
        for (EyeSample sample : eyes) {
            Vector dir = to.clone().subtract(sample.pos());
            double distance = dir.length();
            if (distance < 0.1) {
                anyVisible = true;
                break;
            }
            RayTraceResult result = player.getWorld().rayTraceBlocks(
                    new Location(player.getWorld(), sample.pos().getX(), sample.pos().getY(), sample.pos().getZ()),
                    dir.normalize(), distance, FluidCollisionMode.NEVER, true);
            if (result == null || result.getHitBlock() == null || result.getHitBlock().equals(block)) {
                anyVisible = true;
                break;
            }
        }
        return !anyVisible;
    }

    private void handle(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cutoff = now - config.violationDecaySeconds() * 1000L;

        Deque<Long> list = violations.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        while (!list.isEmpty() && list.peekFirst() < cutoff) {
            list.pollFirst();
        }
        list.addLast(now);
        if (list.size() < config.violationsBeforeAction()) {
            return;
        }

        Long lastAct = lastAction.get(uuid);
        long cooldown = config.twActionCooldownSeconds() * 1000L;
        if (lastAct != null && now - lastAct < cooldown) {
            return;
        }
        lastAction.put(uuid, now);
        list.clear();

        enforcement.handleThroughWall(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastCheck.remove(uuid);
        lastAction.remove(uuid);
        violations.remove(uuid);
        recentEyes.remove(uuid);
    }
}

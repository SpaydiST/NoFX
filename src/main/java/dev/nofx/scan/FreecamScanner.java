package dev.nofx.scan;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import dev.nofx.NoFXConfig;
import dev.nofx.enforce.Enforcement;
import dev.nofx.mod.ModEnforcement;
import dev.nofx.store.TrackingStore;
import dev.nofx.util.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FreecamScanner implements Listener {

    private static final int MAX_PROBE_LINES = 3;
    private static final String CONTROL_TOKEN_PREFIX = "[NoFX:";
    // Оребфускатор (Orebfuscator) прячет невидимые игроку блоки/блок-сущности на
    // уровне пакетов. Наш знак-проверка ставится за спиной/в стене, то есть для
    // Orebfuscator он "невидим" и был бы заменён на камень, и клиент не получил бы
    // редактор. Поэтому на время скана цели временно выдаётся bypass-право.
    private static final String OREBFUSCATOR_BYPASS_PERMISSION = "orebfuscator.bypass";
    private static final int[][] OFFSETS = {
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {2, 1, 0}, {-2, 1, 0}, {0, 1, 2}, {0, 1, -2}
    };

    private final JavaPlugin plugin;
    private final NoFXConfig config;
    private final Enforcement enforcement;
    private final TrackingStore tracking;
    private final ModEnforcement modEnforcement;
    private final Map<UUID, ActiveScan> activeScans = new HashMap<>();
    private final Map<UUID, Long> lastAutoScan = new HashMap<>();

    public FreecamScanner(JavaPlugin plugin, NoFXConfig config, Enforcement enforcement, TrackingStore tracking,
                          ModEnforcement modEnforcement) {
        this.plugin = plugin;
        this.config = config;
        this.enforcement = enforcement;
        this.tracking = tracking;
        this.modEnforcement = modEnforcement;
    }

    public boolean isScanning(UUID uuid) {
        return activeScans.containsKey(uuid);
    }

    public boolean isScanLocation(UUID uuid, Location loc) {
        ActiveScan scan = activeScans.get(uuid);
        if (scan == null || scan.signLocation == null) {
            return false;
        }
        Location sl = scan.signLocation;
        return sl.getWorld() == loc.getWorld()
                && sl.getBlockX() == loc.getBlockX()
                && sl.getBlockY() == loc.getBlockY()
                && sl.getBlockZ() == loc.getBlockZ();
    }

    public boolean canAutoScan(UUID uuid) {
        long now = System.currentTimeMillis();
        long cooldownMs = config.cooldownSeconds() * 1000L;
        lastAutoScan.entrySet().removeIf(e -> now - e.getValue() >= cooldownMs);
        return now - lastAutoScan.getOrDefault(uuid, 0L) >= cooldownMs;
    }

    public boolean shouldAutoScan(UUID uuid) {
        return tracking.isFlagged(uuid) || canAutoScan(uuid);
    }

    public void startScan(Player target, Player initiator, boolean auto) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> startScan(target, initiator, auto));
            return;
        }
        if (!config.scanEnabled()) {
            if (initiator != null) {
                Msg.send(initiator, config.prefix(), config.msg("scan-disabled"), Map.of());
            }
            return;
        }
        UUID uuid = target.getUniqueId();
        if (activeScans.containsKey(uuid)) {
            if (initiator != null) {
                Msg.send(initiator, config.prefix(), config.msg("scan-running"),
                        Map.of("player", target.getName()));
            }
            return;
        }
        List<Probe> probes = config.probes();
        if (probes.isEmpty()) {
            plugin.getLogger().warning("[NoFX] No probes configured; scan aborted for " + target.getName());
            return;
        }
        if (target.hasPermission("nofx.bypass")) {
            if (initiator != null) {
                Msg.send(initiator, config.prefix(), config.msg("scan-bypass"),
                        Map.of("player", target.getName()));
            }
            return;
        }
        if (config.modSkipScan() && modEnforcement != null && modEnforcement.hasMod(uuid)) {
            if (initiator != null) {
                Msg.send(initiator, config.prefix(), config.msg("scan-skip-mod"),
                        Map.of("player", target.getName()));
            }
            plugin.getLogger().info("[NoFX] Skipping scan of " + target.getName()
                    + ": NoFC mod present (mod detects freecam itself).");
            return;
        }
        if (target.getOpenInventory().getType() != InventoryType.CRAFTING) {
            if (initiator != null) {
                String skipMsg = config.msg("scan-skip-inventory");
                if (skipMsg != null && !skipMsg.isBlank()) {
                    Msg.send(initiator, config.prefix(), skipMsg,
                            Map.of("player", target.getName()));
                }
            }
            plugin.getLogger().info("[NoFX] Skipping scan of " + target.getName()
                    + ": real inventory open (would close it).");
            return;
        }
        if (auto) {
            lastAutoScan.put(uuid, System.currentTimeMillis());
        }

        List<List<Probe>> batches = new ArrayList<>();
        for (int i = 0; i < probes.size(); i += MAX_PROBE_LINES) {
            batches.add(probes.subList(i, Math.min(i + MAX_PROBE_LINES, probes.size())));
        }

        ActiveScan scan = new ActiveScan(uuid, initiator != null ? initiator.getUniqueId() : null,
                auto, target.getName(), probes, batches);
        activeScans.put(uuid, scan);

        try {
            scan.bypassAttachment = target.addAttachment(plugin, OREBFUSCATOR_BYPASS_PERMISSION, true);
        } catch (Exception e) {
            scan.bypassAttachment = null;
        }

        if (initiator != null) {
            Msg.send(initiator, config.prefix(), config.msg("scan-started"),
                    Map.of("player", target.getName()));
        }
        plugin.getLogger().info("[NoFX] Scanning " + target.getName()
                + " for Freecam (" + batches.size() + " sign(s), " + probes.size() + " probe(s))...");

        processBatch(target, scan);
    }

    public void handleResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        ActiveScan scan = activeScans.get(uuid);
        if (scan == null) {
            return;
        }
        String controlLine = lines.length > 3 && lines[3] != null ? lines[3].strip() : "";
        String marker = controlToken(scan.currentBatchToken);
        if (scan.currentBatchToken == null || !controlLine.endsWith(marker)) {
            plugin.getLogger().info("[NoFX] " + target.getName() + ": ignored stale or invalid scan response");
            return;
        }
        plugin.getLogger().info("[NoFX] " + target.getName() + ": echo received (batch "
                + scan.currentBatch + "): [" + String.join(" | ", lines) + "]");
        if (scan.timeoutTask != null) {
            scan.timeoutTask.cancel();
            scan.timeoutTask = null;
        }
        restoreSign(scan);

        List<Probe> batch = scan.batches.get(scan.currentBatch);
        String controlResp = controlLine.substring(0, controlLine.length() - marker.length()).strip();
        boolean controlMissing = controlResp.isEmpty();
        boolean exploitPreventer = ScanLogic.isExploitPreventer(controlResp, config.controlKeybind());

        for (int i = 0; i < batch.size(); i++) {
            String resp = i < lines.length ? lines[i].strip() : "";
            ScanResult result = resp.isEmpty()
                    ? ScanResult.SKIPPED
                    : ScanLogic.evaluate(batch.get(i), resp);
            if (result != ScanResult.DETECTED && controlMissing) {
                // Без контрольного ответа нельзя отличить чистый клиент от того,
                // который обрезал или подменил строки ответа.
                result = ScanResult.SKIPPED;
            } else if (exploitPreventer) {
                // Клиент блокирует уязвимость (неразрезолвленная контрольная
                // клавиша). "Чистый" ответ в таком батче недостоверен — PROTECTED.
                if (result == ScanResult.NOT_DETECTED) {
                    result = ScanResult.PROTECTED;
                }
            } else if (result == ScanResult.PROTECTED) {
                // Контрольная клавиша разрезолвилась, значит клиент не блокирует:
                // возврат "сырого" ключа — это не защита, а неверно настроенный
                // зонд. Понижаем до SKIPPED, чтобы не сработал ложный PROTECTED.
                result = ScanResult.SKIPPED;
            }
            scan.results.put(batch.get(i).key(), result);
            plugin.getLogger().info("[NoFX] " + target.getName() + ": " + batch.get(i).key()
                    + " -> " + result + " (resp='" + resp + "', ctrl='" + controlResp + "')");
        }

        scheduleNext(scan);
    }

    public void handleQuit(UUID uuid) {
        ActiveScan scan = activeScans.remove(uuid);
        if (scan == null) {
            return;
        }
        if (scan.timeoutTask != null) {
            scan.timeoutTask.cancel();
        }
        restoreSign(scan);
        removeBypass(scan);
        if (!scan.auto) {
            plugin.getLogger().info("[NoFX] Scan of " + scan.playerName + " aborted: player quit.");
        }
    }

    public void cleanup() {
        for (ActiveScan scan : activeScans.values()) {
            if (scan.timeoutTask != null) {
                scan.timeoutTask.cancel();
            }
            restoreSign(scan);
            removeBypass(scan);
        }
        activeScans.clear();
    }

    private void processBatch(Player target, ActiveScan scan) {
        UUID uuid = scan.uuid;
        restoreSign(scan);
        if (scan.signLocation != null || scan.originalState != null || scan.barrierLocation != null) {
            plugin.getLogger().warning("[NoFX] Could not restore temporary scan blocks for " + scan.playerName
                    + "; aborting remaining batches.");
            markBatchSkipped(scan);
            finishScan(uuid);
            return;
        }
        List<Probe> batch = scan.batches.get(scan.currentBatch);

        Location signLoc = findSignLocation(target);
        if (signLoc == null) {
            // Нет скрытого места для таблички (открытая равнина и т.п.) — это НЕ
            // значит, что клиент что-то блокирует. Проверка просто не проведена:
            // батч помечается SKIPPED, к PROTECTED и наказанию это не приводит.
            plugin.getLogger().info("[NoFX] " + target.getName() + ": no hidden block found, marking batch SKIPPED");
            markBatchSkipped(scan);
            scheduleNext(scan);
            return;
        }

        Block block = signLoc.getBlock();
        BlockState original = block.getState();

        Location belowLoc = signLoc.clone().subtract(0, 1, 0);
        Block below = belowLoc.getBlock();
        if (below.getType().isAir()) {
            scan.barrierOriginalState = below.getState();
            below.setType(Material.BARRIER, false);
            scan.barrierLocation = belowLoc;
        }

        block.setType(Material.OAK_SIGN, false);
        BlockState fresh = block.getState();
        if (!(fresh instanceof Sign sign)) {
            if (!original.update(true, false)) {
                plugin.getLogger().warning("[NoFX] restore failed after sign placement for " + target.getName());
            }
            restoreBarrier(scan);
            plugin.getLogger().info("[NoFX] " + target.getName() + ": sign placement failed, marking batch SKIPPED");
            markBatchSkipped(scan);
            scheduleNext(scan);
            return;
        }

        SignSide front = sign.getSide(Side.FRONT);
        scan.currentBatchToken = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        for (int i = 0; i < MAX_PROBE_LINES; i++) {
            front.line(i, i < batch.size() ? probeComponent(batch.get(i)) : Component.empty());
        }
        front.line(MAX_PROBE_LINES, Component.keybind(config.controlKeybind())
                .append(Component.text(controlToken(scan.currentBatchToken))));

        // Знак обязан оставаться в мире до прихода ответа: при приёме
        // ServerboundSignUpdatePacket сервер ищет реальный BlockEntity на позиции
        // (instanceof SignBlockEntity + allowedPlayerEditor == uuid игрока), иначе
        // эхо-ответ молча игнорируется и скан уходит в PROTECTED. Откатываем после
        // ответа/таймаута в restoreSign.
        sign.update(true, false);

        scan.signLocation = signLoc;
        scan.signSupportLocation = belowLoc;
        scan.originalState = original;

        target.sendBlockUpdate(signLoc, sign);
        plugin.getLogger().info("[NoFX] " + target.getName() + ": sign placed at "
                + signLoc.getBlockX() + "," + signLoc.getBlockY() + "," + signLoc.getBlockZ());

        // Знак ставится реальным блоком в мире и был бы виден ВСЕМ рядом.
        // Скрываем его от всех, кроме сканируемого, НЕМЕДЛЕННО (в этот же тик)
        // пакетным BlockChange с оригинальными данными блока (для остальных мир
        // выглядит как до скана). Реальный блок в мире остаётся — он нужен
        // серверу для эхо-механизма. После скана restoreSign вернёт оригинальный
        // блок, и мировое обновление дойдёт до этих игроков автоматически.
        maskSignForOthers(scan, signLoc);
        // Повторная маскировка на следующем тике и при загрузке чанка покрывает
        // игроков, которые получили реальное состояние мира позже.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeScans.get(uuid) != scan || scan.signLocation == null) {
                return;
            }
            maskSignForOthers(scan, scan.signLocation);
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeScans.get(uuid) != scan || scan.signLocation == null) {
                return;
            }
            try {
                // Дублируем block-entity ПРЯМО перед открытием редактора: данные знака
                // в очереди клиента гарантированно стоят перед пакетом открытия, и
                // ClientPacketListener успевает зарегистрировать SignBlockEntity.
                target.sendBlockUpdate(signLoc, sign);
                // Открываем редактор таблички и в ТОМ ЖЕ тике мгновенно закрываем
                // экран. Клиент шлёт эхо-ответ (ServerboundSignUpdatePacket) в
                // SignEditScreen.removed() — при закрытии экрана через container-close
                // (setScreen(null)). Оба пакета уходят одной пачкой, экран не успевает
                // отрисоваться ни одного кадра — для игрока всё незаметно.
                target.openSign(sign, Side.FRONT);
                if (config.invisibleClose()) {
                    target.closeInventory();
                }
                // Прячем знак и от самого игрока, показывая оригинальные данные
                // блока (знак спрятан ВНУТРИ твёрдого блока — воздух дал бы «дыру»).
                BlockData originalData = scan.originalState != null
                        ? scan.originalState.getBlockData() : Material.AIR.createBlockData();
                target.sendBlockChange(signLoc, originalData);
            } catch (Exception e) {
                plugin.getLogger().warning("[NoFX] openSign failed for " + target.getName() + ": " + e.getMessage());
            }
        }, 2L);

        scan.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeScans.get(uuid) != scan) {
                return;
            }
            boolean signPresent = hasProbeSign(scan);
            restoreSign(scan);
            if (signPresent) {
                markBatchProtected(scan);
                plugin.getLogger().info("[NoFX] " + target.getName()
                        + ": no response, marking batch PROTECTED");
            } else {
                markBatchSkipped(scan);
                plugin.getLogger().info("[NoFX] " + target.getName()
                        + ": probe sign disappeared, marking batch SKIPPED");
            }
            scheduleNext(scan);
        }, config.timeoutTicks());
    }

    private void scheduleNext(ActiveScan scan) {
        scan.currentBatch++;
        if (scan.currentBatch >= scan.batches.size()) {
            finishScan(scan.uuid);
            return;
        }
        Player target = Bukkit.getPlayer(scan.uuid);
        if (target == null || !target.isOnline()) {
            finishScan(scan.uuid);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeScans.get(scan.uuid) == scan) {
                Player currentTarget = Bukkit.getPlayer(scan.uuid);
                if (currentTarget != null && currentTarget.isOnline()) {
                    processBatch(currentTarget, scan);
                }
            }
        }, config.betweenSignTicks());
    }

    private Component probeComponent(Probe probe) {
        if (probe.type() == Probe.Type.TRANSLATE && probe.fallback() != null && !probe.fallback().isEmpty()) {
            return Component.translatable(probe.key(), probe.fallback());
        }
        if (probe.type() == Probe.Type.TRANSLATE) {
            return Component.translatable(probe.key());
        }
        return Component.keybind(probe.key());
    }

    private String controlToken(String token) {
        return token == null ? "" : CONTROL_TOKEN_PREFIX + token + "]";
    }

    private boolean hasProbeSign(ActiveScan scan) {
        if (scan.signLocation == null) {
            return false;
        }
        Block block = scan.signLocation.getBlock();
        return block.getType() == Material.OAK_SIGN && block.getState() instanceof Sign;
    }

    private Location findSignLocation(Player target) {
        List<Location> candidates = new ArrayList<>();
        if (config.placementBehind()) {
            candidates.addAll(behindCandidates(target));
        } else {
            candidates.addAll(aboveCandidates(target));
        }
        // Запасной поиск: земля/скала прямо ПОД игроком (1-4 блока вниз). Игрок
        // стоит на твёрдом блоке почти всегда — так табличка найдёт скрытое
        // место даже на открытой равнине, где за спиной сплошной воздух.
        candidates.addAll(groundCandidates(target));
        for (Location loc : candidates) {
            if (isHiddenBlock(loc)) {
                return loc;
            }
        }
        return null;
    }

    // При placement "above" табличка прячется ВНУТРИ твёрдого непрозрачного
    // блока над головой (например, в потолке/полке), чтобы её НЕ было видно
    // другим игрокам даже при загрузке чанка. Если подходящего блока рядом
    // нет — батч помечается SKIPPED (проверка не проведена, а не «клиент
    // блокирует»).
    private List<Location> aboveCandidates(Player target) {
        List<Location> list = new ArrayList<>();
        Location base = target.getEyeLocation().clone();
        for (int dy = 1; dy <= 6; dy++) {
            list.add(base.clone().add(0, dy, 0));
        }
        for (int[] off : OFFSETS) {
            list.add(base.clone().add(off[0], off[1], off[2]));
        }
        return list;
    }

    // Табличка прячется ЗА СПИНОЙ игрока (вне поля зрения). Ставим её ТОЛЬКО
    // внутри твёрдого непрозрачного блока (стены/земли): тогда модель таблички
    // физически не видна никому. Если ни одна кандидатная позиция не подошла —
    // батч помечается SKIPPED, но табличка НИКОГДА не ставится в открытом
    // воздухе.
    private List<Location> behindCandidates(Player target) {
        List<Location> list = new ArrayList<>();
        Location eye = target.getEyeLocation().clone();
        double yaw = Math.toRadians(eye.getYaw());
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        int dist = config.signDistance();
        for (int dy = 1; dy >= -1; dy--) {
            for (int extra = 1; extra <= 3; extra++) {
                list.add(eye.clone().add(-dx * (dist + extra), dy, -dz * (dist + extra)));
            }
        }
        return list;
    }

    // Запасной поиск: земля/скала ПОД игроком. Ставим на 2-4 блока НИЖЕ игрока
    // (НИКОГДА не в блоке опоры прямо под ногами: временная замена опоры на
    // не-твёрдую табличку заставила бы игрока «провалиться»). Так табличка
    // найдёт скрытое место даже на открытой равнине.
    private List<Location> groundCandidates(Player target) {
        List<Location> list = new ArrayList<>();
        Location base = target.getLocation().clone();
        for (int dy = -2; dy >= -4; dy--) {
            list.add(base.clone().add(0, dy, 0));
        }
        return list;
    }

    // Блок подходит для скрытой постановки: полностью непрозрачный, НЕ воздух и
    // НЕ блок-сущность (не ломаем ложный tile entity, чтобы ничего не потерять).
    private boolean isHiddenBlock(Location loc) {
        Block b = loc.getBlock();
        return !b.getType().isAir() && b.getType().isOccluding()
                && !(b.getState() instanceof TileState);
    }

    private void markBatchSkipped(ActiveScan scan) {
        for (Probe probe : scan.batches.get(scan.currentBatch)) {
            scan.results.put(probe.key(), ScanResult.SKIPPED);
        }
    }

    private void markBatchProtected(ActiveScan scan) {
        for (Probe probe : scan.batches.get(scan.currentBatch)) {
            scan.results.put(probe.key(), ScanResult.PROTECTED);
        }
    }

    private void finishScan(UUID uuid) {
        ActiveScan scan = activeScans.remove(uuid);
        if (scan == null) {
            return;
        }
        restoreSign(scan);
        removeBypass(scan);

        boolean anyDetected = false;
        boolean anyProtected = false;
        boolean anySkipped = false;
        List<String> detectedProbes = new ArrayList<>();

        for (Probe probe : scan.allProbes) {
            ScanResult r = scan.results.getOrDefault(probe.key(), ScanResult.SKIPPED);
            if (r == ScanResult.DETECTED) {
                anyDetected = true;
                detectedProbes.add(probe.key());
            } else if (r == ScanResult.PROTECTED) {
                anyProtected = true;
            } else if (r == ScanResult.SKIPPED) {
                anySkipped = true;
            }
        }

        Player target = Bukkit.getPlayer(uuid);
        ScanResult verdict = anyDetected
                ? ScanResult.DETECTED
                : (anyProtected ? ScanResult.PROTECTED
                        : (anySkipped ? ScanResult.SKIPPED : ScanResult.NOT_DETECTED));

        if (verdict == ScanResult.DETECTED) {
            tracking.recordDetection(uuid, scan.playerName);
        } else if (verdict == ScanResult.PROTECTED) {
            tracking.recordProtected(uuid, scan.playerName);
            if (config.lockdownOnProtected()) {
                tracking.setLocked(uuid, true);
            }
        } else if (verdict == ScanResult.NOT_DETECTED) {
            tracking.markClean(uuid);
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", scan.playerName);
        placeholders.put("probes", String.join(", ", detectedProbes));

        String resultMsg = switch (verdict) {
            case DETECTED -> config.msg("scan-result-detected");
            case PROTECTED -> config.msg("scan-result-protected");
            case SKIPPED -> config.msg("scan-result-skipped");
            default -> config.msg("scan-result-clean");
        };

        if (scan.initiator != null) {
            Player initiator = Bukkit.getPlayer(scan.initiator);
            if (initiator != null && initiator.isOnline()) {
                Msg.send(initiator, config.prefix(), resultMsg, placeholders);
            }
        }

        if (verdict == ScanResult.DETECTED) {
            plugin.getLogger().warning("[NoFX] " + scan.playerName
                    + " detected using Freecam: " + detectedProbes);
        }

        if (target != null && target.isOnline()) {
            enforcement.handleScanResult(target, verdict, detectedProbes);
        }
    }

    private void restoreSign(ActiveScan scan) {
        if (scan.signLocation != null && scan.originalState != null) {
            try {
                // Не затираем изменение, сделанное другим плагином во время скана.
                Material currentType = scan.signLocation.getBlock().getType();
                if (currentType == Material.OAK_SIGN || currentType.isAir()) {
                    if (!scan.originalState.update(true, false)) {
                        plugin.getLogger().warning("[NoFX] restore sign returned false for " + scan.playerName);
                    } else {
                        scan.originalState = null;
                    }
                } else {
                    scan.originalState = null;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[NoFX] restore sign block: " + e.getMessage());
            }
        }
        restoreBarrier(scan);
        if (scan.signLocation != null && scan.originalState == null) {
            Player target = Bukkit.getPlayer(scan.uuid);
            if (target != null && target.isOnline()) {
                try {
                    target.sendBlockChange(scan.signLocation, scan.signLocation.getBlock().getBlockData());
                } catch (Exception e) {
                    plugin.getLogger().warning("[NoFX] restore sign: " + e.getMessage());
                }
            }
            scan.signLocation = null;
            scan.signSupportLocation = null;
        }
    }

    private void restoreBarrier(ActiveScan scan) {
        if (scan.barrierLocation == null) {
            return;
        }
        try {
            Material currentType = scan.barrierLocation.getBlock().getType();
            if (scan.barrierOriginalState != null
                    && (currentType == Material.BARRIER || currentType.isAir())
                    && !scan.barrierOriginalState.update(true, false)) {
                plugin.getLogger().warning("[NoFX] restore barrier returned false for " + scan.playerName);
                return;
            }
            // BlockState сохраняет точный тип воздуха (включая CAVE_AIR и VOID_AIR).
            // Если блок уже изменил другой плагин, оставляем новое состояние.
            scan.barrierOriginalState = null;
            scan.barrierLocation = null;
        } catch (Exception e) {
            plugin.getLogger().warning("[NoFX] restore barrier: " + e.getMessage());
        }
    }

    // Скрывает знак (и подставный barrier) от всех онлайн-игроков, кроме
    // сканируемого. Маскируем ОРИГИНАЛЬНЫМИ данными блока (для остальных мир
    // выглядит как до скана) — не воздухом, чтобы не было видимых «дыр».
    private void maskSignForOthers(ActiveScan scan, Location signLoc) {
        BlockData signHide = scan.originalState != null
                ? scan.originalState.getBlockData() : Material.AIR.createBlockData();
        BlockData barrierHide = scan.barrierOriginalState != null
                ? scan.barrierOriginalState.getBlockData() : Material.AIR.createBlockData();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (scan.uuid.equals(p.getUniqueId())) {
                continue;
            }
            p.sendBlockChange(signLoc, signHide);
            if (scan.barrierLocation != null) {
                p.sendBlockChange(scan.barrierLocation, barrierHide);
            }
        }
    }

    // Если чанк со спрятанной табличкой перезагрузился (игрок зашёл в радиус
    // отрисовки после маскировки), клиенты получат реальное состояние мира и
    // увидят знак. Пере-маскируем его для всех, кроме сканируемого.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (activeScans.isEmpty()) {
            return;
        }
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (ActiveScan scan : activeScans.values()) {
            if (scan.signLocation == null || scan.signLocation.getWorld() != event.getWorld()) {
                continue;
            }
            if (scan.signLocation.getBlockX() >> 4 == cx && scan.signLocation.getBlockZ() >> 4 == cz) {
                maskSignForOthers(scan, scan.signLocation);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTemporaryScanBlockBreak(BlockBreakEvent event) {
        if (isTemporaryScanBlock(event.getBlock())) {
            event.setDropItems(false);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTemporaryScanBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isTemporaryScanBlock);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTemporaryScanBlockExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isTemporaryScanBlock);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTemporaryScanBlockPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isTemporaryScanBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTemporaryScanBlockPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isTemporaryScanBlock)) {
            event.setCancelled(true);
        }
    }

    private boolean isTemporaryScanBlock(Block block) {
        Location location = block.getLocation();
        for (ActiveScan scan : activeScans.values()) {
            if (sameBlock(scan.signLocation, location)
                    || sameBlock(scan.barrierLocation, location)
                    || sameBlock(scan.signSupportLocation, location)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameBlock(Location first, Location second) {
        return first != null && second != null
                && first.getWorld() == second.getWorld()
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void removeBypass(ActiveScan scan) {
        if (scan.bypassAttachment == null) {
            return;
        }
        try {
            Player p = Bukkit.getPlayer(scan.uuid);
            if (p != null) {
                p.removeAttachment(scan.bypassAttachment);
            }
        } catch (IllegalArgumentException ignored) {
        }
        scan.bypassAttachment = null;
    }

    public static class ActiveScan {
        public final UUID uuid;
        public final UUID initiator;
        public final boolean auto;
        public final String playerName;
        public final List<Probe> allProbes;
        public final List<List<Probe>> batches;
        public final Map<String, ScanResult> results = new HashMap<>();
        public int currentBatch;
        public Location signLocation;
        public Location signSupportLocation;
        public BlockState originalState;
        public Location barrierLocation;
        public BlockState barrierOriginalState;
        public PermissionAttachment bypassAttachment;
        public BukkitTask timeoutTask;
        public String currentBatchToken;

        public ActiveScan(UUID uuid, UUID initiator, boolean auto, String playerName,
                          List<Probe> allProbes, List<List<Probe>> batches) {
            this.uuid = uuid;
            this.initiator = initiator;
            this.auto = auto;
            this.playerName = playerName;
            this.allProbes = allProbes;
            this.batches = batches;
        }
    }
}

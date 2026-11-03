package dev.nofx.protect;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import dev.nofx.NoFXConfig;
import dev.nofx.store.TrackingStore;
import dev.nofx.util.Msg;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class InteractionLockdown implements Listener {

    private final NoFXConfig config;
    private final TrackingStore tracking;
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    public InteractionLockdown(NoFXConfig config, TrackingStore tracking) {
        this.config = config;
        this.tracking = tracking;
    }

    private boolean blocked(Player player) {
        if (!config.lockdownOnProtected()) {
            return false;
        }
        if (player.hasPermission("nofx.bypass")) {
            return false;
        }
        if (!tracking.isLocked(player.getUniqueId())) {
            return false;
        }
        long now = System.currentTimeMillis();
        long cooldown = config.lockdownWarnCooldownSeconds() * 1000L;
        if (now - lastWarn.getOrDefault(player.getUniqueId(), 0L) >= cooldown) {
            lastWarn.put(player.getUniqueId(), now);
            // Сообщение о локдауне показывается в action bar, НЕ в чат.
            player.sendActionBar(Msg.parse(config.msg("lockdown-blocked"), null));
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastWarn.remove(event.getPlayer().getUniqueId());
    }
}

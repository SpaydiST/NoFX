package dev.nofx.scan;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import dev.nofx.NoFXConfig;

public class JoinListener implements Listener {

    private final JavaPlugin plugin;
    private final NoFXConfig config;
    private final FreecamScanner scanner;

    public JoinListener(JavaPlugin plugin, NoFXConfig config, FreecamScanner scanner) {
        this.plugin = plugin;
        this.config = config;
        this.scanner = scanner;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.scanEnabled() || !config.onJoinEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("nofx.bypass")) {
            return;
        }
        if (config.firstJoinOnly() && player.hasPlayedBefore()) {
            return;
        }
        if (!scanner.shouldAutoScan(player.getUniqueId())) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            scanner.startScan(player, null, true);
        }, config.onJoinDelayTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        scanner.handleQuit(event.getPlayer().getUniqueId());
    }
}

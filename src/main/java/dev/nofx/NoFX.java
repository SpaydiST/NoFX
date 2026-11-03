package dev.nofx;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import dev.nofx.command.NoFxCommand;
import dev.nofx.enforce.Enforcement;
import dev.nofx.mod.ModEnforcement;
import dev.nofx.protect.InteractionLockdown;
import dev.nofx.protect.ThroughWallProtection;
import dev.nofx.scan.FreecamScanner;
import dev.nofx.scan.JoinListener;
import dev.nofx.scan.SignListener;
import dev.nofx.store.TrackingStore;

public class NoFX extends JavaPlugin {

    private NoFXConfig noFXConfig;
    private FreecamScanner scanner;
    private TrackingStore tracking;
    private ModEnforcement modEnforcement;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        noFXConfig = new NoFXConfig(this);
        noFXConfig.load();

        tracking = new TrackingStore(this);
        tracking.load();

        Enforcement enforcement = new Enforcement(this, noFXConfig, tracking);
        modEnforcement = new ModEnforcement(this, noFXConfig, enforcement);
        scanner = new FreecamScanner(this, noFXConfig, enforcement, tracking, modEnforcement);
        modEnforcement.register();

        getServer().getPluginManager().registerEvents(new JoinListener(this, noFXConfig, scanner), this);
        getServer().getPluginManager().registerEvents(new SignListener(scanner), this);
        getServer().getPluginManager().registerEvents(scanner, this);
        getServer().getPluginManager().registerEvents(new ThroughWallProtection(noFXConfig, enforcement), this);
        getServer().getPluginManager().registerEvents(new InteractionLockdown(noFXConfig, tracking), this);

        LifecycleEventManager<Plugin> manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new NoFxCommand(noFXConfig, scanner, tracking).build()));

        getLogger().info("NoFX enabled. " + noFXConfig.probes().size() + " Freecam probe(s) loaded.");
    }

    @Override
    public void onDisable() {
        if (modEnforcement != null) {
            modEnforcement.unregister();
        }
        if (scanner != null) {
            scanner.cleanup();
        }
        if (tracking != null) {
            tracking.save();
        }
    }
}

package dev.nofx.scan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

public class SignListener implements Listener {

    private final FreecamScanner scanner;

    public SignListener(FreecamScanner scanner) {
        this.scanner = scanner;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!scanner.isScanning(player.getUniqueId())) {
            return;
        }
        // Эхо-ответ приходит только от фантомного знака-зонда. Реальное
        // редактирование таблички игроком во время скана не перехватываем.
        Location loc = event.getBlock().getLocation();
        if (!scanner.isScanLocation(player.getUniqueId(), loc)) {
            return;
        }

        event.setCancelled(true);

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            Component c = event.line(i);
            lines[i] = c != null ? PlainTextComponentSerializer.plainText().serialize(c) : "";
        }
        scanner.handleResponse(player, lines);
    }
}

package dev.nofx.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.Map;

public final class Msg {

    private Msg() {
    }

    public static Component parse(String template, Map<String, String> placeholders) {
        String text = template;
        if (text == null) {
            text = "";
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                if (e.getValue() != null) {
                    text = text.replace("%" + e.getKey() + "%", e.getValue());
                }
            }
        }
        return MiniMessage.miniMessage().deserialize(text);
    }

    public static void send(Player player, String prefix, String template, Map<String, String> placeholders) {
        player.sendMessage(parse(prefix, null).append(parse(template, placeholders)));
    }
}

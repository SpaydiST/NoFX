package dev.nofx;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigMigrationTest {

    /**
     * Регрессия: JavaPlugin.reloadConfig() вешает на конфиг дефолты из ресурса
     * (config.setDefaults(...)), из-за чего contains() через дефолты считает новые
     * ключи уже существующими. Миграция должна добавлять только действительно
     * отсутствующие в ФАЙЛЕ ключи (getKeys(false)) и сохранять пользовательские.
     */
    @Test
    void mergeAddsNewKeysEvenWhenDefaultsAttached() throws Exception {
        FileConfiguration target;
        try (InputStream in = getClass().getResourceAsStream("/old-config.yml")) {
            target = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        FileConfiguration defaults;
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        target.setDefaults(defaults);

        NoFXConfig.mergeSection(defaults, target);
        target.set("config-version", 7);

        assertEquals(7, target.getInt("config-version"));
        assertEquals(5, target.getInt("through-wall.action-cooldown-seconds", -1));
        assertEquals(5, target.getInt("enforcement.lockdown-warn-cooldown-seconds", -1));
        assertFalse(target.getBoolean("mod.require", true));
    }
}
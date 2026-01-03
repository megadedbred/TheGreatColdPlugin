package me.megadedbeb.thegreatcold.config;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.freeze.FreezeStage;
import me.megadedbeb.thegreatcold.heat.HeatSourceType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class ConfigManager {
    private final TheGreatColdPlugin plugin;
    private final FileConfiguration config;

    public ConfigManager(TheGreatColdPlugin plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
    }

    public long getStageDurationInMillis(int stage) {
        int seconds = config.getInt("stage_durations." + stage, 3600);
        return seconds * 1000L;
    }

    public boolean isAutoStageEnabled() {
        return config.getBoolean("auto_stage", false);
    }

    public void setStageDuration(int stage, long millis) {
        config.set("stage_durations." + stage, (int) (millis / 1000L));
        plugin.saveConfig();
    }

    // Возвращает радиус тепла для заданного типа источника тепла
    public int getHeatRadius(HeatSourceType type) {
        String name = type.name();
        return config.getInt("heat_radius." + name, 2);
    }

    // Freeze/обморожение
    public long getStageTimeToNext(FreezeStage stage) {
        // Длительность между получениями стадий (по умолчанию миллисекунды)
        return config.getLong("freeze_stages." + stage.id() + ".duration", 60000);
    }

    public int getStageDamage(FreezeStage stage) {
        return config.getInt("freeze_stages." + stage.id() + ".damage", 1);
    }

    public int getStageDamageInterval(FreezeStage stage) {
        return config.getInt("freeze_stages." + stage.id() + ".damage_interval", 5);
    }

    /**
     * Возвращает время (в миллисекундах), которое игрок должен находиться в зоне тепла,
     * чтобы снять текущую стадию обморожения. Если значение отрицательное — сброс невозможен.
     * Значение берётся из config: freeze_stages.<id>.required_heat_sec (в секундах).
     */
    public long getRequiredHeatMillis(FreezeStage stage) {
        long sec = config.getLong("freeze_stages." + stage.id() + ".required_heat_sec", -1L);
        if (sec < 0) return -1L;
        return sec * 1000L;
    }

    public int getHeatResetTime() {
        return 30_000;
    }

    public long getInitialProgressTime(FreezeStage stage, int globalStage) {
        // Можно детализировать по конфигу, либо просто использовать фикс:
        if (stage == FreezeStage.NONE) {
            if (globalStage == 1) return 120_000;
            if (globalStage == 2) return 60_000;
            if (globalStage == 3) return 20_000;
        }
        return 60000;
    }

    // Эффекты стадий - нужно расширить если в config добавлены дополнительные эффекты
    public void applyStageEffects(Player p, FreezeStage st) {
        if (st == FreezeStage.NONE) return;
        int slow = config.getInt("freeze_stages." + st.id() + ".slowness", st.id()-1);
        int hunger = config.getInt("freeze_stages." + st.id() + ".hunger", 0);
        int weakness = config.getInt("freeze_stages." + st.id() + ".weakness", 0);
        int fatigue = config.getInt("freeze_stages." + st.id() + ".fatigue", 0);
        int blindness = config.getInt("freeze_stages." + st.id() + ".blindness", 0);

        if (slow > 0)
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 220, slow - 1, true, false));
        if (hunger > 0)
            p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 220, hunger - 1, true, false));
        if (weakness > 0)
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 220, weakness - 1, true, false));
        if (fatigue > 0)
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 220, fatigue - 1, true, false));
        if (blindness > 0)
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 220, blindness - 1, true, false));
    }

    public void clearAllEffects(Player p) {
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.HUNGER);
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    public TheGreatColdPlugin getPlugin() {
        return plugin;
    }

    /**
     * Возвращает сообщение из секции messages.<key>. Поддерживает форматирование через String.format.
     * Пример использования: getMessage("freeze.warmed", stageId)
     */
    public String getMessage(String key, Object... args) {
        String path = "messages." + key;
        String msg = plugin.getConfig().getString(path);
        if (msg == null) return "";
        if (args != null && args.length > 0) {
            try {
                return String.format(msg, args);
            } catch (Exception ignored) {
                return msg;
            }
        }
        return msg;
    }
}
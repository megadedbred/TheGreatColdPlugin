package me.megadedbeb.thegreatcold.freeze;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.config.ConfigManager;
import me.megadedbeb.thegreatcold.data.DataManager;
import me.megadedbeb.thegreatcold.data.PlayerFreezeData;
import me.megadedbeb.thegreatcold.heat.CustomHeatManager;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.stage.StageManager;
import me.megadedbeb.thegreatcold.util.Messaging;
import me.megadedbeb.thegreatcold.util.NmsHelper;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public class FreezeManager {
    private final TheGreatColdPlugin plugin;
    private final ConfigManager config;
    private final DataManager dataManager;
    private final HeatSourceManager heatManager;
    private final StageManager stageManager;
    private final CustomHeatManager customHeatManager;
    private BukkitTask freezeTask;

    public FreezeManager(TheGreatColdPlugin plugin, ConfigManager config, DataManager dataManager,
                         HeatSourceManager heatManager, StageManager stageManager) {
        this.plugin = plugin;
        this.config = config;
        this.dataManager = dataManager;
        this.heatManager = heatManager;
        this.stageManager = stageManager;
        this.customHeatManager = plugin.getCustomHeatManager();
        // тик каждую секунду
        freezeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                handlePlayer(player);
            } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка в FreezeManager.tick для " + player.getName() + ": " + t);
            }
        }
    }

    private void handlePlayer(Player player) {
        if (!player.getWorld().getName().equals("world")) return;

        // Игроки в креативе или в spectator полностью иммунны (без урона, эффектов)
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            // чистим эффекты, чтобы не накапливались
            clearAllEffects(player);
            return;
        }

        UUID uuid = player.getUniqueId();
        PlayerFreezeData fd = dataManager.getPlayerData(uuid);

        int globalStageId = stageManager.getCurrentStage().id();

        // Определяем, считается ли игрок находящимся в зоне тепла.
        boolean rawInHeat = heatManager.isPlayerInHeat(player);
        // Если игрок в зоне кастомного источника — считаем его нагретым независимо от открытости неба
        boolean inCustomHeat = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(player.getLocation());

        boolean openToSky = NmsHelper.isOpenToSky(player.getLocation().getBlock());
        boolean inHeat;
        if (inCustomHeat) {
            inHeat = true;
        } else {
            inHeat = rawInHeat && !(globalStageId >= 2 && openToSky);
        }

        // сохраняем флаг
        if (!fd.isInHeat() && inHeat) {
            fd.setTimeInHeat(0L);
            fd.setHeatResetApplied(false);
        }
        fd.setInHeat(inHeat);

        // (остальная логика без изменений...)
        if (!inHeat) {
            // Вне источника тепла
            fd.setTimeInHeat(0L);
            fd.setTimeWithoutHeat(fd.getTimeWithoutHeat() + 1000L);

            FreezeStage desired = computeDesiredStageByTimeout(fd.getTimeWithoutHeat(), globalStageId, fd.isUndergroundMode());
            if (fd.isUndergroundMode() && fd.getFreezeStage().id() >= FreezeStage.STAGE_2.id()) {
                desired = fd.getFreezeStage();
            }

            if (desired.id() > fd.getFreezeStage().id()) {
                fd.setFreezeStage(desired);
                fd.setDamageAccumulatorMs(0L);
                fd.setTimeWithoutHeat(0L); // отсчёт следующей стадии с начала этой стадии
                fd.setTimeInHeat(0L);
                fd.setHeatResetApplied(false);
                Messaging.notifyFreezeStage(player, desired);
                applyStageEffects(player, desired);
            } else {
                if (globalStageId >= 1 && fd.getTimeWithoutHeat() > 0L && fd.getFreezeStage() != FreezeStage.STAGE_4) {
                    var loc = player.getLocation().add(0.0, 1.0, 0.0);
                    player.getWorld().spawnParticle(Particle.SNOWFLAKE, loc, 4, 0.25, 0.5, 0.25, 0.01);
                }
            }
        } else {
            // В зоне тепла
            fd.setTimeInHeat(fd.getTimeInHeat() + 1000L);

            if (!fd.isHeatResetApplied() && fd.getTimeInHeat() >= 30_000L) {
                fd.setTimeWithoutHeat(0L);
                fd.setHeatResetApplied(true);
            }

            if (fd.getFreezeStage() != FreezeStage.NONE) {
                long required = getRemovalMillis(fd.getFreezeStage());
                if (required >= 0 && fd.getTimeInHeat() >= required) {
                    clearFreezeInternal(player, fd);
                    Messaging.notifyUnfreeze(player);
                    return;
                } else {
                    if (fd.getTimeInHeat() > 0L) {
                        var loc = player.getLocation().add(0.0, 1.0, 0.0);
                        player.getWorld().spawnParticle(Particle.DRIPPING_WATER, loc, 4, 0.2, 0.5, 0.2, 0.01);
                    }
                }
            }
        }

        // Урон и эффекты
        tickEffects(player, fd);
    }

    /**
     * Новая версия: учитывает подземный режим (underground). В подземном режиме допустимые стадии
     * ограничены (максимум STAGE_2), а временные пороги отличаются (см. требования).
     */
    private FreezeStage computeDesiredStageByTimeout(long timeWithoutHeatMs, int globalStageId, boolean underground) {
        if (globalStageId <= 0) return FreezeStage.NONE;

        if (!underground) {
            // Существующая (обычная) логика
            if (globalStageId == 1) {
                if (timeWithoutHeatMs >= 6 * 60_000L) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= 3 * 60_000L) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            } else if (globalStageId == 2) {
                if (timeWithoutHeatMs >= 7 * 60_000L) return FreezeStage.STAGE_3;
                if (timeWithoutHeatMs >= 5 * 60_000L) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= 2 * 60_000L) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            } else { // globalStageId >= 3
                if (timeWithoutHeatMs >= 5 * 60_000L) return FreezeStage.STAGE_4;
                if (timeWithoutHeatMs >= 4 * 60_000L) return FreezeStage.STAGE_3;
                if (timeWithoutHeatMs >= 2 * 60_000L) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= 1 * 60_000L) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            }
        } else {
            // Подземные пороги (ограничение: максимум STAGE_2)
            if (globalStageId == 1) {
                if (timeWithoutHeatMs >= 40 * 60_000L) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= 25 * 60_000L) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            } else if (globalStageId == 2) {
                if (timeWithoutHeatMs >= 30 * 60_000L) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= 15 * 60_000L) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            } else { // globalStageId >= 3
                if (timeWithoutHeatMs >= 20 * 60_000L) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= 10 * 60_000L) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            }
        }
    }

    private void tickEffects(Player player, PlayerFreezeData fd) {
        FreezeStage stage = fd.getFreezeStage();

        if (stage == FreezeStage.NONE) {
            clearAllEffects(player);
            fd.setDamageAccumulatorMs(0L);
            restoreWalkSpeedIfNeeded(player, fd);
            return;
        }

        applyStageEffects(player, stage);

        if (fd.isInHeat() && stage != FreezeStage.STAGE_4) {
            fd.setDamageAccumulatorMs(0L);
            return;
        }

        long acc = fd.getDamageAccumulatorMs() + 1000L;

        long intervalMs = switch (stage) {
            case STAGE_1 -> 10_000L; // каждые 10 секунд
            case STAGE_2 -> 7_000L;  // каждые 7 секунд
            case STAGE_3 -> 5_000L;  // каждые 5 секунд
            case STAGE_4 -> 1_000L;  // каждые 1 секунду
            default -> 3_000L;
        };

        int damage = 2; // во всех стадиях урон 2 единицы согласно последнего запроса

        while (intervalMs > 0 && acc >= intervalMs) {
            player.damage(damage);
            acc -= intervalMs;
        }
        fd.setDamageAccumulatorMs(acc);
    }

    private long getRemovalMillis(FreezeStage stage) {
        return switch (stage) {
            case STAGE_1 -> 30_000L;
            case STAGE_2 -> 60_000L;
            case STAGE_3 -> 150_000L;
            case STAGE_4 -> Long.MAX_VALUE;
            default -> -1L;
        };
    }

    private void applyStageEffects(Player player, FreezeStage stage) {
        int slownessLevel = 0;
        int hungerLevel = 0;
        int miningFatigueLevel = 0;
        int weaknessLevel = 0;
        int blindnessLevel = 0;

        switch (stage) {
            case STAGE_1 -> {
                slownessLevel = 1;
                hungerLevel = 1;
            }
            case STAGE_2 -> {
                slownessLevel = 2;
                hungerLevel = 2;
                miningFatigueLevel = 1;
            }
            case STAGE_3 -> {
                slownessLevel = 3;
                hungerLevel = 3;
                miningFatigueLevel = 3;
                weaknessLevel = 1;
            }
            case STAGE_4 -> {
                slownessLevel = 10; // сильное замедление для полной неподвижности
                hungerLevel = 5;
                miningFatigueLevel = 4;
                weaknessLevel = 2;
                blindnessLevel = 1;
                PlayerFreezeData fd = dataManager.getPlayerData(player.getUniqueId());
                if (!fd.isWalkSpeedModified()) {
                    try {
                        fd.setStoredWalkSpeed(player.getWalkSpeed());
                        fd.setWalkSpeedModified(true);
                        player.setWalkSpeed(0.0f);
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Применяем эффекты с длительностью 5 секунд (обновляются каждую секунду)
        if (slownessLevel > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5 * 20, Math.max(0, slownessLevel - 1), false, false, true));
        } else {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        if (hungerLevel > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 5 * 20, Math.max(0, hungerLevel - 1), false, false, true));
        } else {
            player.removePotionEffect(PotionEffectType.HUNGER);
        }
        if (miningFatigueLevel > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 5 * 20, Math.max(0, miningFatigueLevel - 1), false, false, true));
        } else {
            player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        }
        if (weaknessLevel > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 5 * 20, Math.max(0, weaknessLevel - 1), false, false, true));
        } else {
            player.removePotionEffect(PotionEffectType.WEAKNESS);
        }
        if (blindnessLevel > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 5 * 20, Math.max(0, blindnessLevel - 1), false, false, true));
        } else {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    private void clearAllEffects(Player player) {
        try {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.removePotionEffect(PotionEffectType.HUNGER);
            player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
            player.removePotionEffect(PotionEffectType.WEAKNESS);
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            PlayerFreezeData fd = dataManager.getPlayerData(player.getUniqueId());
            restoreWalkSpeedIfNeeded(player, fd);
        } catch (Throwable ignored) {}
    }

    private void restoreWalkSpeedIfNeeded(Player player, PlayerFreezeData fd) {
        if (fd != null && fd.isWalkSpeedModified()) {
            try {
                Float stored = fd.getStoredWalkSpeed();
                if (stored != null) player.setWalkSpeed(stored);
            } catch (Throwable ignored) {}
            fd.setWalkSpeedModified(false);
            fd.setStoredWalkSpeed(null);
        }
    }

    public void setStage(Player player, FreezeStage stage) {
        PlayerFreezeData fd = dataManager.getPlayerData(player.getUniqueId());
        fd.setFreezeStage(stage);
        fd.setTimeInHeat(0L);
        fd.setTimeWithoutHeat(0L);
        fd.setDamageAccumulatorMs(0L);
        applyStageEffects(player, stage);
        Messaging.notifyManualFreeze(player, stage);
    }

    public void clearFreeze(Player player) {
        PlayerFreezeData fd = dataManager.getPlayerData(player.getUniqueId());
        clearFreezeInternal(player, fd);
        Messaging.notifyUnfreeze(player);
    }

    private void clearFreezeInternal(Player player, PlayerFreezeData fd) {
        if (fd == null) return;
        fd.reset();
        clearAllEffects(player);
    }

    public void onDeath(UUID uuid) {
        PlayerFreezeData fd = dataManager.getPlayerData(uuid);
        if (fd == null) return;

        // По требованию: обычная смерть НЕ должна сбрасывать обморожение.
        // Только если игрок был на 4-й стадии — по смерти понижать до 3-й стадии.
        if (fd.getFreezeStage() == FreezeStage.STAGE_4) {
            fd.setFreezeStage(FreezeStage.STAGE_3);
            fd.setTimeInHeat(0L);
            fd.setTimeWithoutHeat(0L);
            fd.setDamageAccumulatorMs(0L);
            // Пытаемся восстановить ходовую скорость, если нужно, у онлайн-игрока
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                restoreWalkSpeedIfNeeded(p, fd);
                // Применим эффекты новой стадии сразу
                applyStageEffects(p, FreezeStage.STAGE_3);
            } else {
                // игрок оффлайн/нет в данный момент — оставим storedWalkSpeed, но снимем флаг модификации,
                // чтобы при следующем заходе/тики не было "заблокированной" скорости.
                fd.setWalkSpeedModified(false);
            }
        }
        // иначе ничего не делаем — обморожение остаётся на той же стадии
    }

    public void onDisable() {
        if (freezeTask != null) {
            Bukkit.getScheduler().cancelTask(freezeTask.getTaskId());
        }
        dataManager.saveAll();
    }

    /**
     * Восстановленный публичный метод для совместимости — некоторые классы вызывают его:
     * applyEffects(player, stage, announce)
     */
    public void applyEffects(Player player, FreezeStage stage, boolean announce) {
        applyStageEffects(player, stage);
        if (announce) {
            Messaging.notifyFreezeStage(player, stage);
        }
    }
}
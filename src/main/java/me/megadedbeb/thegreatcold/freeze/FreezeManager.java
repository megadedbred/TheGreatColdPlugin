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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;

import java.util.*;

/**
 * FreezeManager — основной тикер обморожения.
 * Поддержка "Шапки с подогревом": инициализация, накопление ношения, применение эффекта,
 * уменьшение кастомной "прочности" шапки и удаление при поломке с воспроизведением звука.
 */
public class FreezeManager {
    private final TheGreatColdPlugin plugin;
    private final ConfigManager config;
    private final DataManager dataManager;
    private final HeatSourceManager heatManager;
    private final StageManager stageManager;
    private final CustomHeatManager customHeatManager;
    private BukkitTask freezeTask;

    // thresholds
    private static final int UNDERGROUND_Y_THRESHOLD = 20;
    private static final long UNDERGROUND_DELAY_MS = 60_000L;

    // villagers / animals accumulators
    private final Map<UUID, Long> villagerTimeWithoutHeat = new HashMap<>();
    private final Map<UUID, Long> villagerDamageAccumulator = new HashMap<>();
    private final Map<UUID, Long> animalTimeWithoutHeat = new HashMap<>();
    private final Map<UUID, Long> animalDamageAccumulator = new HashMap<>();

    // Heated hat tracking
    private final Map<UUID, Long> hatWornSince = new HashMap<>();           // when player started wearing (consecutive)
    private final Map<UUID, Boolean> hatEffectApplied = new HashMap<>();    // whether >60s worn and effect active
    private final Map<UUID, Long> hatRemovedSince = new HashMap<>();        // when removed, to enforce 1s absence
    private final Map<UUID, Long> hatDurAccum = new HashMap<>();            // accum ms towards next durability drain

    private final NamespacedKey hatKey;
    private final NamespacedKey hatDurKey;

    public FreezeManager(TheGreatColdPlugin plugin, ConfigManager config, DataManager dataManager,
                         HeatSourceManager heatManager, StageManager stageManager) {
        this.plugin = plugin;
        this.config = config;
        this.dataManager = dataManager;
        this.heatManager = heatManager;
        this.stageManager = stageManager;
        this.customHeatManager = plugin.getCustomHeatManager();

        this.hatKey = plugin.getHeatedHatKey();
        this.hatDurKey = plugin.getHeatedHatDurKey();

        // run tick every second on main thread
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

        try { handleVillagers(); } catch (Throwable t) { plugin.getLogger().warning("Ошибка в FreezeManager.tick (villagers): " + t); }
        try { handleAnimals(); } catch (Throwable t) { plugin.getLogger().warning("Ошибка в FreezeManager.tick (animals): " + t); }
    }

    private void handlePlayer(Player player) {
        if (!player.getWorld().getName().equals("world")) return;

        // players in creative/spectator are immune
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            clearAllEffects(player);
            return;
        }

        UUID uuid = player.getUniqueId();
        PlayerFreezeData fd = dataManager.getPlayerData(uuid);
        int globalStageId = stageManager.getCurrentStage().id();
        long now = System.currentTimeMillis();

        // --- Heated Hat logic ---
        ItemStack helm = player.getInventory().getHelmet();
        boolean wearingHeatedHat = isHeatedHat(helm);

        if (wearingHeatedHat) {
            // ensure item has PDC/lore if it came from other plugin / give
            ensureHatInitialized(helm);

            // reset removal-tracking
            hatRemovedSince.remove(uuid);

            // mark start wearing if absent
            hatWornSince.putIfAbsent(uuid, now);

            boolean applied = hatEffectApplied.getOrDefault(uuid, false);
            long wornSince = hatWornSince.getOrDefault(uuid, now);
            long woreMs = now - wornSince;
            if (!applied && woreMs >= 60_000L) {
                // activate effect
                hatEffectApplied.put(uuid, true);
                // per spec: after >60s, timers updated to new values — reset timeWithoutHeat/timeInHeat/damageAccumulator
                try {
                    fd.setTimeWithoutHeat(0L);
                    fd.setTimeInHeat(0L);
                    fd.setDamageAccumulatorMs(0L);
                } catch (Throwable ignored) {}
                player.sendMessage(ChatColor.GOLD + "Тепло шапки начинает согревать вас!");
            }

            // durability drain: only when player is outside effective heat
            boolean rawInHeat = heatManager.isPlayerInHeat(player);
            boolean inCustom = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(player.getLocation());
            boolean openToSky = NmsHelper.isOpenToSky(player.getLocation().getBlock());
            boolean effectiveInHeat = inCustom || (rawInHeat && !(globalStageId >= 2 && openToSky));

            if (!effectiveInHeat) {
                long acc = hatDurAccum.getOrDefault(uuid, 0L) + 1000L;
                if (acc >= 60_000L) { // every minute
                    int currentDur = getHatDurability(helm);
                    int newDur = currentDur - 2; // 2 units per minute
                    if (newDur <= 0) {
                        // break: remove helmet, notify, play sound
                        player.getInventory().setHelmet(null);
                        hatWornSince.remove(uuid);
                        hatEffectApplied.remove(uuid);
                        hatDurAccum.remove(uuid);
                        hatRemovedSince.put(uuid, now);
                        player.sendMessage(ChatColor.GOLD + "Тепло шапки больше не согревает вас!");
                        try {
                            player.getWorld().playSound(player.getLocation(), Sound.ITEM_WOLF_ARMOR_BREAK, 1.0f, 1.0f);
                        } catch (Throwable ignored) {}
                    } else {
                        setHatDurability(helm, newDur);
                        updateHatLore(helm, newDur, TheGreatColdPlugin.HEATED_HAT_MAX_DUR);
                        player.getInventory().setHelmet(helm); // update displayed item
                        acc -= 60_000L;
                        hatDurAccum.put(uuid, acc);
                    }
                } else {
                    hatDurAccum.put(uuid, acc);
                }
            } else {
                // in heat -> do not accumulate durability drain
                hatDurAccum.remove(uuid);
            }

        } else {
            // not wearing heated hat
            hatWornSince.remove(uuid);
            hatDurAccum.remove(uuid);

            if (hatEffectApplied.getOrDefault(uuid, false)) {
                // mark removal moment and after >=1s fully remove effect & reset timers
                hatRemovedSince.putIfAbsent(uuid, now);
                long removedAt = hatRemovedSince.getOrDefault(uuid, now);
                if (now - removedAt >= 1000L) {
                    hatEffectApplied.remove(uuid);
                    try {
                        fd.setTimeWithoutHeat(0L);
                        fd.setTimeInHeat(0L);
                        fd.setDamageAccumulatorMs(0L);
                    } catch (Throwable ignored) {}
                    player.sendMessage(ChatColor.GOLD + "Тепло шапки больше не согревает вас!");
                    hatRemovedSince.remove(uuid);
                }
            } else {
                hatRemovedSince.remove(uuid);
            }
        }

        // --- existing freeze logic follows ---

        // update Y counters for underground mode
        double py = player.getLocation().getY();
        if (py < UNDERGROUND_Y_THRESHOLD) {
            fd.setTimeBelowY(fd.getTimeBelowY() + 1000L);
            fd.setTimeAboveY(0L);
        } else {
            fd.setTimeAboveY(fd.getTimeAboveY() + 1000L);
            fd.setTimeBelowY(0L);
        }

        // enter underground mode after 60s below Y
        if (!fd.isUndergroundMode() && fd.getTimeBelowY() >= UNDERGROUND_DELAY_MS) {
            fd.setUndergroundMode(true);
            fd.setTimeWithoutHeat(0L);
            fd.setTimeInHeat(0L);
            fd.setHeatResetApplied(false);
            if (globalStageId != 0) {
                String msg = config.getMessage("heat.underground_enter");
                if (msg == null || msg.isEmpty()) msg = "§eНа глубине намного теплее.";
                player.sendMessage(msg);
            }
        }

        // leave underground mode after 60s above Y
        if (fd.isUndergroundMode() && fd.getTimeAboveY() >= UNDERGROUND_DELAY_MS) {
            fd.setUndergroundMode(false);
            fd.setTimeWithoutHeat(0L);
            fd.setTimeInHeat(0L);
            fd.setHeatResetApplied(false);
            if (globalStageId != 0) {
                String msg = config.getMessage("heat.underground_exit");
                if (msg == null || msg.isEmpty()) msg = "§eЧем ближе к поверхности, тем больше вы чувствуете холод.";
                player.sendMessage(msg);
            }
        }

        // determine whether player is in heat
        boolean rawInHeat = heatManager.isPlayerInHeat(player);
        boolean inCustomHeat = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(player.getLocation());
        boolean openToSky = NmsHelper.isOpenToSky(player.getLocation().getBlock());
        boolean inHeat;
        if (inCustomHeat) inHeat = true;
        else inHeat = rawInHeat && !(globalStageId >= 2 && openToSky);

        if (!fd.isInHeat() && inHeat) {
            fd.setTimeInHeat(0L);
            fd.setHeatResetApplied(false);
        }
        fd.setInHeat(inHeat);

        if (!inHeat) {
            fd.setTimeInHeat(0L);
            fd.setTimeWithoutHeat(fd.getTimeWithoutHeat() + 1000L);

            boolean hatActive = hatEffectApplied.getOrDefault(uuid, false);
            FreezeStage desired = computeDesiredStageByTimeout(fd.getTimeWithoutHeat(), globalStageId, fd.isUndergroundMode(), hatActive);

            // underground mode behavior: do not raise above STAGE_2 when underground (preserve earlier logic)
            if (fd.isUndergroundMode() && fd.getFreezeStage().id() >= FreezeStage.STAGE_2.id()) {
                desired = fd.getFreezeStage();
            }

            // if hat active, cap increases at STAGE_2 (but do NOT lower existing stage 3/4)
            if (hatActive && fd.getFreezeStage().id() < FreezeStage.STAGE_3.id()) {
                if (desired.id() > FreezeStage.STAGE_2.id()) desired = FreezeStage.STAGE_2;
            }

            if (desired.id() > fd.getFreezeStage().id()) {
                fd.setFreezeStage(desired);
                fd.setDamageAccumulatorMs(0L);
                fd.setTimeWithoutHeat(0L);
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

        // apply damage/effects for players
        tickEffects(player, fd);
    }

    private FreezeStage computeDesiredStageByTimeout(long timeWithoutHeatMs, int globalStageId, boolean underground, boolean hatActive) {
        if (globalStageId <= 0) return FreezeStage.NONE;
        double mul = hatActive ? 3.0 : 1.0;

        if (!underground) {
            if (globalStageId == 1) {
                if (timeWithoutHeatMs >= (long)(6 * 60_000L * mul)) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= (long)(3 * 60_000L * mul)) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            } else if (globalStageId == 2) {
                if (timeWithoutHeatMs >= (long)(7 * 60_000L * mul)) return FreezeStage.STAGE_3;
                if (timeWithoutHeatMs >= (long)(5 * 60_000L * mul)) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= (long)(2 * 60_000L * mul)) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            } else {
                if (timeWithoutHeatMs >= (long)(5 * 60_000L * mul)) return FreezeStage.STAGE_4;
                if (timeWithoutHeatMs >= (long)(4 * 60_000L * mul)) return FreezeStage.STAGE_3;
                if (timeWithoutHeatMs >= (long)(2 * 60_000L * mul)) return FreezeStage.STAGE_2;
                if (timeWithoutHeatMs >= (long)(1 * 60_000L * mul)) return FreezeStage.STAGE_1;
                return FreezeStage.NONE;
            }
        } else {
            if (timeWithoutHeatMs >= (long)(20 * 60_000L * mul)) return FreezeStage.STAGE_2;
            if (timeWithoutHeatMs >= (long)(10 * 60_000L * mul)) return FreezeStage.STAGE_1;
            return FreezeStage.NONE;
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
            case STAGE_1 -> 10_000L;
            case STAGE_2 -> 7_000L;
            case STAGE_3 -> 5_000L;
            case STAGE_4 -> 1_000L;
            default -> 3_000L;
        };

        int damage = 2;
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
        int slownessLevel = 0, hungerLevel = 0, miningFatigueLevel = 0, weaknessLevel = 0, blindnessLevel = 0;
        switch (stage) {
            case STAGE_1 -> { slownessLevel = 1; hungerLevel = 1; }
            case STAGE_2 -> { slownessLevel = 2; hungerLevel = 2; miningFatigueLevel = 1; }
            case STAGE_3 -> { slownessLevel = 3; hungerLevel = 3; miningFatigueLevel = 3; weaknessLevel = 1; }
            case STAGE_4 -> {
                slownessLevel = 10; hungerLevel = 5; miningFatigueLevel = 4; weaknessLevel = 2; blindnessLevel = 1;
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

        if (slownessLevel > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5*20, Math.max(0, slownessLevel-1), false, false, true));
        else player.removePotionEffect(PotionEffectType.SLOWNESS);
        if (hungerLevel > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 5*20, Math.max(0, hungerLevel-1), false, false, true));
        else player.removePotionEffect(PotionEffectType.HUNGER);
        if (miningFatigueLevel > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 5*20, Math.max(0, miningFatigueLevel-1), false, false, true));
        else player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        if (weaknessLevel > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 5*20, Math.max(0, weaknessLevel-1), false, false, true));
        else player.removePotionEffect(PotionEffectType.WEAKNESS);
        if (blindnessLevel > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 5*20, Math.max(0, blindnessLevel-1), false, false, true));
        else player.removePotionEffect(PotionEffectType.BLINDNESS);
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
        if (fd.getFreezeStage() == FreezeStage.STAGE_4) {
            fd.setFreezeStage(FreezeStage.STAGE_3);
            fd.setTimeInHeat(0L);
            fd.setTimeWithoutHeat(0L);
            fd.setDamageAccumulatorMs(0L);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                restoreWalkSpeedIfNeeded(p, fd);
                applyStageEffects(p, FreezeStage.STAGE_3);
            } else {
                fd.setWalkSpeedModified(false);
            }
        }
    }

    private void handleVillagers() {
        int globalStageId = stageManager.getCurrentStage().id();
        if (globalStageId != 3) {
            villagerTimeWithoutHeat.clear();
            villagerDamageAccumulator.clear();
            return;
        }

        World w = Bukkit.getWorld("world");
        if (w == null) return;

        for (Entity e : w.getEntities()) {
            if (!(e instanceof Villager)) continue;
            Villager v = (Villager) e;
            if (v.isDead()) continue;
            UUID id = v.getUniqueId();

            boolean rawInHeat = heatManager.isLocationInHeat(v.getLocation());
            boolean inCustom = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(v.getLocation());
            boolean openToSky = NmsHelper.isOpenToSky(v.getLocation().getBlock());
            boolean effectiveInHeat = inCustom || (rawInHeat && !(globalStageId >= 2 && openToSky));

            if (!effectiveInHeat && openToSky) {
                long tw = villagerTimeWithoutHeat.getOrDefault(id, 0L) + 1000L;
                villagerTimeWithoutHeat.put(id, tw);

                if (tw >= 30_000L) {
                    long acc = villagerDamageAccumulator.getOrDefault(id, 0L) + 1000L;
                    if (acc >= 5_000L) {
                        try { if (v instanceof LivingEntity le) le.damage(2.0); } catch (Throwable ignored) {}
                        acc -= 5_000L;
                    }
                    villagerDamageAccumulator.put(id, acc);
                } else {
                    villagerDamageAccumulator.put(id, 0L);
                }
            } else {
                villagerTimeWithoutHeat.remove(id);
                villagerDamageAccumulator.remove(id);
            }
        }
    }

    private void handleAnimals() {
        int globalStageId = stageManager.getCurrentStage().id();
        if (globalStageId != 3) {
            animalTimeWithoutHeat.clear();
            animalDamageAccumulator.clear();
            return;
        }

        World w = Bukkit.getWorld("world");
        if (w == null) return;

        for (Entity e : w.getEntities()) {
            if (!(e instanceof Cow) && !(e instanceof Pig)) continue;
            if (e.isDead()) continue;
            UUID id = e.getUniqueId();

            boolean rawInHeat = heatManager.isLocationInHeat(e.getLocation());
            boolean inCustom = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(e.getLocation());
            boolean openToSky = NmsHelper.isOpenToSky(e.getLocation().getBlock());
            boolean effectiveInHeat = inCustom || (rawInHeat && !(globalStageId >= 2 && openToSky));

            if (!effectiveInHeat && openToSky) {
                long tw = animalTimeWithoutHeat.getOrDefault(id, 0L) + 1000L;
                animalTimeWithoutHeat.put(id, tw);

                if (tw >= 60_000L) {
                    long acc = animalDamageAccumulator.getOrDefault(id, 0L) + 1000L;
                    if (acc >= 5_000L) {
                        try { if (e instanceof LivingEntity le) le.damage(2.0); } catch (Throwable ignored) {}
                        acc -= 5_000L;
                    }
                    animalDamageAccumulator.put(id, acc);
                } else {
                    animalDamageAccumulator.put(id, 0L);
                }
            } else {
                animalTimeWithoutHeat.remove(id);
                animalDamageAccumulator.remove(id);
            }
        }
    }

    public void onDisable() {
        if (freezeTask != null) {
            Bukkit.getScheduler().cancelTask(freezeTask.getTaskId());
        }
        dataManager.saveAll();
    }

    public void applyEffects(Player player, FreezeStage stage, boolean announce) {
        applyStageEffects(player, stage);
        if (announce) Messaging.notifyFreezeStage(player, stage);
    }

    // ---------------- Heated hat helpers ----------------

    private boolean isHeatedHat(ItemStack s) {
        if (s == null) return false;
        ItemMeta m = s.getItemMeta();
        if (m == null) return false;
        String v = m.getPersistentDataContainer().get(hatKey, PersistentDataType.STRING);
        return v != null && v.equals("true");
    }

    private int getHatDurability(ItemStack s) {
        if (s == null) return 0;
        ItemMeta m = s.getItemMeta();
        if (m == null) return 0;
        Integer v = m.getPersistentDataContainer().get(hatDurKey, PersistentDataType.INTEGER);
        return v == null ? TheGreatColdPlugin.HEATED_HAT_MAX_DUR : v.intValue();
    }

    private void setHatDurability(ItemStack s, int dur) {
        if (s == null) return;
        ItemMeta m = s.getItemMeta();
        if (m == null) return;
        m.getPersistentDataContainer().set(hatDurKey, PersistentDataType.INTEGER, Integer.valueOf(dur));
        s.setItemMeta(m);
    }

    private void ensureHatInitialized(ItemStack s) {
        if (s == null) return;
        ItemMeta m = s.getItemMeta();
        if (m == null) return;
        // initialize PDC and lore/unbreakable if missing
        if (m.getPersistentDataContainer().get(hatKey, PersistentDataType.STRING) == null) {
            m.getPersistentDataContainer().set(hatKey, PersistentDataType.STRING, "true");
            m.getPersistentDataContainer().set(hatDurKey, PersistentDataType.INTEGER, Integer.valueOf(TheGreatColdPlugin.HEATED_HAT_MAX_DUR));
            m.setUnbreakable(true);
            updateHatLoreFromMeta(m, TheGreatColdPlugin.HEATED_HAT_MAX_DUR);
            s.setItemMeta(m);
        } else {
            Integer v = m.getPersistentDataContainer().get(hatDurKey, PersistentDataType.INTEGER);
            int dur = (v == null) ? TheGreatColdPlugin.HEATED_HAT_MAX_DUR : v.intValue();
            updateHatLore(s, dur, TheGreatColdPlugin.HEATED_HAT_MAX_DUR);
        }
    }

    private void updateHatLore(ItemStack s, int dur, int max) {
        if (s == null) return;
        ItemMeta m = s.getItemMeta();
        if (m == null) return;
        updateHatLoreFromMeta(m, dur);
        s.setItemMeta(m);
    }

    private void updateHatLoreFromMeta(ItemMeta m, int dur) {
        List<String> lore = new ArrayList<>();
        lore.add("Шапка с подогревом позволяет в несколько раз дольше находиться на холоде!");
        lore.add(ChatColor.GOLD + "Прочность: " + dur + "/" + TheGreatColdPlugin.HEATED_HAT_MAX_DUR);
        m.setLore(lore);
    }
}
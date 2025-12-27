package me.megadedbeb.thegreatcold.stage;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.config.ConfigManager;
import me.megadedbeb.thegreatcold.data.DataManager;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.util.NmsHelper;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Управляет текущим этапом холода и побочными задачами (фиксация погоды/времени,
 * тушение костров на небу, плавная трансформация лавы и т.д.).
 *
 * Механика выпадения листвы удалена (по запросу) — теперь планируется только превращение лавы,
 * если она под открытым небом во время этапа 3.
 */
public class StageManager {
    private final TheGreatColdPlugin plugin;
    private final ConfigManager config;
    private final DataManager data;
    private final HeatSourceManager heat;

    private ColdStage currentStage = ColdStage.COLD_0;
    private BukkitTask autoStageTask;
    private BukkitTask enforceStageTask; // задача для принудительного поддержания погоды/времени
    private BukkitTask campfireTask;
    private boolean isAutoStage = false;
    private long stageEndMillis = 0;
    private boolean stageInfinite = false;

    // задачи для плавной трансформации лавы — чтобы отменять при выходе из этапа
    private final List<BukkitTask> scheduledDecayTasks = Collections.synchronizedList(new ArrayList<>());

    public StageManager(TheGreatColdPlugin plugin, ConfigManager config, DataManager data, HeatSourceManager heat) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.heat = heat;

        loadStageState();
        heat.scanWorldForHeatSources();
        startCampfireTask(); // always running

        // Регистрируем слушатель загрузки чанков — чтобы при загрузке чанка в этапе 3 планировать трансформацию лавы
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onChunkLoad(ChunkLoadEvent e) {
                try {
                    if (currentStage != null && currentStage.id() == 3) {
                        scheduleLavaDecayForChunk(e.getChunk());
                    }
                } catch (Throwable ignored) {}
            }
        }, plugin);

        // Восстанавливаем enforce-задачи и поведение этапа после перезапуска
        if (currentStage != null) {
            startEnforceTaskForStage(currentStage.id());
            if (currentStage.id() == 3) {
                // Если на этапе 3 — восстановим планирование превращения лавы для уже загруженных чанков
                scheduleLavaDecayForLoadedChunks();
            }
        }
    }

    public void startStage(int stageId, boolean broadcast) {
        try {
            ColdStage newStage = ColdStage.fromId(stageId);
            if (newStage == null) return;

            // отменяем эффекты старого этапа
            if (currentStage != null) manageStageEndEffects(currentStage.id());

            currentStage = newStage;

            World w = Bukkit.getWorld("world");
            if (w != null) {
                switch (stageId) {
                    case 0 -> {
                        w.setStorm(false);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                    }
                    case 1 -> {
                        w.setStorm(true);
                        w.setThundering(false);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                    }
                    case 2 -> {
                        w.setStorm(true);
                        w.setThundering(true);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                    }
                    case 3 -> {
                        w.setStorm(true);
                        w.setThundering(true);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        w.setTime(18000); // полночь
                        try { w.setGameRuleValue("doDaylightCycle", "false"); } catch (Throwable ignored) {}
                    }
                }

                // установить max_snow_accumulation_height в соответствии с этапом (не критично)
                int snowHeight = switch (stageId) {
                    case 0 -> 2;
                    case 1 -> 3;
                    case 2 -> 4;
                    case 3 -> 8;
                    default -> 2;
                };
                try {
                    w.setGameRuleValue("max_snow_accumulation_height", String.valueOf(snowHeight));
                } catch (Exception ignored) {}
            }

            long duration = config.getStageDurationInMillis(stageId);
            if (stageInfinite) {
                this.stageEndMillis = Long.MAX_VALUE;
            } else {
                this.stageEndMillis = System.currentTimeMillis() + duration;
            }
            saveStageState();

            // Запуск специфичных эффектов для этапа (ранее здесь был decay для листвы; удалено)
            if (stageId == 3) {
                // Только планирование превращения лавы для загруженных чанков
                scheduleLavaDecayForLoadedChunks();
            }

            // Запускаем/обновляем задачу поддержания погоды/времени для нового этапа
            startEnforceTaskForStage(stageId);

            if (broadcast) {
                Bukkit.broadcastMessage("§b[Великий холод] Этап " + stageId + " начался!");
            }

            heat.scanWorldForHeatSources(); // рескан источников тепла
        } catch (Throwable t) {
            plugin.getLogger().severe("Ошибка при смене этапа холода: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Отменить эффекты старого этапа (например отменить запланированные задачи).
     */
    private void manageStageEndEffects(int oldStageId) {
        if (oldStageId == 3) {
            // Отменяем все запланированные задачи для трансформации лавы
            synchronized (scheduledDecayTasks) {
                for (BukkitTask t : scheduledDecayTasks) {
                    try { t.cancel(); } catch (Exception ignored) {}
                }
                scheduledDecayTasks.clear();
            }
        }
        // при выходе из этапа отменяем enforce-задачу и восстанавливаем daylight cycle
        stopEnforceTask();
        try { World w = Bukkit.getWorld("world"); if (w != null) w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
    }

    // -- Костры под открытым небом тухнут через 15 сек ТОЛЬКО на этапах 2 и 3
    private void startCampfireTask() {
        if (campfireTask != null) return;
        campfireTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World w = Bukkit.getWorld("world");
            if (w == null) return;
            int current = (currentStage == null) ? 0 : currentStage.id();
            // если текущий этап не 2 и не 3 — не гасим костры
            if (!(current == 2 || current == 3)) return;

            for (Chunk chunk : w.getLoadedChunks()) {
                for (int y = w.getMinHeight(); y < w.getMaxHeight(); y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            Block block = chunk.getBlock(x, y, z);
                            if ((block.getType() == Material.CAMPFIRE || block.getType() == Material.SOUL_CAMPFIRE)
                                    && NmsHelper.isOpenToSky(block)) {
                                var campfire = block.getBlockData();
                                if (campfire instanceof Campfire cf && cf.isLit()) {
                                    // планируем тушение через 15 секунд — ещё раз проверим стадию и состояние
                                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                        try {
                                            int nowStage = (currentStage == null) ? 0 : currentStage.id();
                                            if (!(nowStage == 2 || nowStage == 3)) return;
                                            if (!block.getChunk().isLoaded()) return;
                                            var maybe = block.getBlockData();
                                            if (maybe instanceof Campfire cf2 && cf2.isLit() && NmsHelper.isOpenToSky(block)) {
                                                cf2.setLit(false);
                                                block.setBlockData(cf2, false);
                                                block.getWorld().playSound(block.getLocation(),
                                                        Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
                                                heat.removeHeatSource(block);
                                            }
                                        } catch (Throwable ignored) {}
                                    }, 15 * 20L); // 15 секунд
                                }
                            }
                        }
                    }
                }
            }
        }, 40L, 600L);
    }

    /**
     * Планирует преобразование лавы -> обсидиан для ВСЕХ загруженных чанков в мире.
     */
    private void scheduleLavaDecayForLoadedChunks() {
        int totalScheduled = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                totalScheduled += scheduleLavaDecayForChunk(chunk);
            }
        }
        plugin.getLogger().info("[TheGreatCold] Scheduled lava-transform tasks for loaded chunks: " + totalScheduled + " tasks.");
    }

    /**
     * Планирует преобразование лавы для одного чанка. Возвращает число запланированных задач.
     *
     * Временные диапазоны (по вашему предыдущему запросу):
     * - Лава: 7..15 минут (420..900 сек)
     */
    private int scheduleLavaDecayForChunk(Chunk chunk) {
        int scheduled = 0;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World world = chunk.getWorld();
        if (world == null) return 0;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block b = chunk.getBlock(x, y, z);
                    try {
                        if (b.getType() == Material.LAVA && NmsHelper.isOpenToSky(b)) {
                            // задержка 7..15 минут -> 420..900 секунд
                            long delaySeconds = 420L + rnd.nextLong(0, 481L); // 420 + [0..480] = 420..900
                            long delayTicks = delaySeconds * 20L;
                            BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                try {
                                    if (!b.getChunk().isLoaded()) return;
                                    if (b.getType() == Material.LAVA && NmsHelper.isOpenToSky(b)) {
                                        b.setType(Material.OBSIDIAN, false);
                                    }
                                } catch (Throwable ignored) {}
                            }, delayTicks);
                            scheduledDecayTasks.add(t);
                            scheduled++;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (scheduled > 0) {
            plugin.getLogger().info("[TheGreatCold] chunk " + chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ()
                    + " -> scheduled " + scheduled + " lava-transform tasks.");
        }
        return scheduled;
    }

    private void loadStageState() {
        int stageId = data.getCurrentStageId();
        this.currentStage = ColdStage.fromId(stageId);
        this.stageEndMillis = data.getStageEndMillis();
        this.isAutoStage = data.getAutoStageFlag();
        this.stageInfinite = data.getStageInfiniteFlag();
        if (stageInfinite) this.stageEndMillis = Long.MAX_VALUE;
    }

    private void saveStageState() {
        data.setCurrentStage(currentStage.id());
        data.setStageEndMillis(stageEndMillis);
        data.setAutoStageFlag(isAutoStage);
        data.setStageInfiniteFlag(stageInfinite);
        data.saveAll();
    }

    public void startAutoStageIfEnabled() {
        if (config.isAutoStageEnabled()) {
            startAutoStage();
        }
    }

    public void startAutoStage() {
        if (isAutoStage) return;
        isAutoStage = true;
        autoStageTask = Bukkit.getScheduler().runTaskTimer(plugin, this::autoStageTick, 20L, 20L);
    }

    private void autoStageTick() {
        if (!stageInfinite && System.currentTimeMillis() >= stageEndMillis) {
            int next = (currentStage.ordinal() + 1) % 4;
            startStage(next, true);
        }
    }

    public void stopAutoStage() {
        isAutoStage = false;
        if (autoStageTask != null) {
            autoStageTask.cancel();
            autoStageTask = null;
        }
    }

    /**
     * Запускает задачу, которая каждые N секунд принудительно поддерживает погоду/время
     * в зависимости от этапа (0..3). Это нужно, чтобы при рестарте/сне/внешних командах
     * погода не менялась от ожидаемого поведения этапа.
     */
    private void startEnforceTaskForStage(int stageId) {
        stopEnforceTask();

        enforceStageTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                World w = Bukkit.getWorld("world");
                if (w == null) return;

                switch (stageId) {
                    case 0 -> {
                        if (w.hasStorm()) w.setStorm(false);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                    }
                    case 1 -> {
                        if (!w.hasStorm()) w.setStorm(true);
                        if (w.isThundering()) w.setThundering(false);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                    }
                    case 2 -> {
                        if (!w.hasStorm()) w.setStorm(true);
                        if (!w.isThundering()) w.setThundering(true);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                    }
                    case 3 -> {
                        if (!w.hasStorm()) w.setStorm(true);
                        if (!w.isThundering()) w.setThundering(true);
                        w.setWeatherDuration(Integer.MAX_VALUE);
                        w.setTime(18000L);
                        try { w.setGameRuleValue("doDaylightCycle", "false"); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка в enforceStageTask: " + t);
            }
        }, 1L, 20L * 5L); // каждые 5 секунд
    }

    private void stopEnforceTask() {
        if (enforceStageTask != null) {
            try { enforceStageTask.cancel(); } catch (Throwable ignored) {}
            enforceStageTask = null;
        }
    }

    // Остальные геттеры/утилиты

    public ColdStage getCurrentStage() { return currentStage; }
    public long getStageEndMillis() { return stageEndMillis; }
    public boolean isAutoStage() { return isAutoStage; }
    public boolean isStageSet() { return currentStage != null; }
    public TheGreatColdPlugin getPlugin() { return plugin; }

    public void setStageDuration(int stage, long millis, boolean makeInfiniteIfRequested) {
        config.setStageDuration(stage, millis);
        saveStageState();
    }

    public void setStageInfinite(boolean infinite) {
        this.stageInfinite = infinite;
        if (infinite) this.stageEndMillis = Long.MAX_VALUE;
        else this.stageEndMillis = System.currentTimeMillis() + config.getStageDurationInMillis(currentStage.id());
        saveStageState();
    }

    public boolean isStageInfinite() {
        return stageInfinite;
    }

    public void onDisable() {
        saveStageState();
        stopAutoStage();
        stopEnforceTask();
        manageStageEndEffects(currentStage.id());
        if (campfireTask != null) { campfireTask.cancel(); campfireTask = null; }
    }
}
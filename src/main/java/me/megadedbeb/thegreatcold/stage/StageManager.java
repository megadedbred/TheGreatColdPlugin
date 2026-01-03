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
 * StageManager — как раньше, с исправленным и оптимизированным startCampfireTask.
 */
public class StageManager {
    private final TheGreatColdPlugin plugin;
    private final ConfigManager config;
    private final DataManager data;
    private final HeatSourceManager heat;

    private ColdStage currentStage = ColdStage.COLD_0;
    private BukkitTask autoStageTask;
    private BukkitTask enforceStageTask;
    private BukkitTask campfireTask;
    private boolean isAutoStage = false;
    private long stageEndMillis = 0;
    private boolean stageInfinite = false;

    private final List<BukkitTask> scheduledDecayTasks = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> scheduledLavaChunks = Collections.synchronizedSet(new HashSet<>());
    private BukkitTask lavaSchedulerTask = null;

    // pending: key = world:x:y:z -> scheduledTimeMillis
    private final Map<String, Long> pendingExtinguish = Collections.synchronizedMap(new HashMap<>());

    public StageManager(TheGreatColdPlugin plugin, ConfigManager config, DataManager data, HeatSourceManager heat) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.heat = heat;

        loadStageState();
        heat.scanWorldForHeatSources();
        startCampfireTask();

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onChunkLoad(ChunkLoadEvent e) {
                try {
                    if (currentStage != null && currentStage.id() == 3) {
                        if (isChunkNearAnyPlayer(e.getChunk(), 1)) {
                            int scheduled = scheduleLavaDecayForChunk(e.getChunk());
                            if (scheduled > 0) {
                                String ck = chunkKeyFor(e.getChunk());
                                scheduledLavaChunks.add(ck);
                                plugin.getLogger().info("[TheGreatCold] Scheduled lava-transform tasks in chunk " + ck + " -> " + scheduled + " tasks.");
                            } else {
                                String ck = chunkKeyFor(e.getChunk());
                                scheduledLavaChunks.add(ck);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }, plugin);

        if (currentStage != null) {
            applyStageWeather(currentStage.id());
            startEnforceTaskForStage(currentStage.id());
            if (currentStage.id() == 3) startLavaScheduler();
        }
    }

    public void startStage(int stageId, boolean broadcast) {
        try {
            ColdStage newStage = ColdStage.fromId(stageId);
            if (newStage == null) return;

            if (currentStage != null) manageStageEndEffects(currentStage.id());

            currentStage = newStage;
            applyStageWeather(stageId);

            long duration = config.getStageDurationInMillis(stageId);
            if (stageInfinite) this.stageEndMillis = Long.MAX_VALUE;
            else this.stageEndMillis = System.currentTimeMillis() + duration;
            saveStageState();

            if (stageId == 3) startLavaScheduler();
            startEnforceTaskForStage(stageId);

            if (broadcast) {
                Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("greatcold.admin"))
                        .forEach(p -> p.sendMessage("§b[Великий холод] Этап " + stageId + " начался!"));
            }

            heat.scanWorldForHeatSources();
        } catch (Throwable t) {
            plugin.getLogger().severe("Ошибка при смене этапа холода: " + t);
            t.printStackTrace();
        }
    }

    private void applyStageWeather(int stageId) {
        World w = Bukkit.getWorld("world");
        if (w == null) return;
        try {
            switch (stageId) {
                case 0 -> {
                    if (w.hasStorm()) w.setStorm(false);
                    if (w.isThundering()) w.setThundering(false);
                    w.setWeatherDuration(Integer.MAX_VALUE);
                    try { w.setGameRuleValue("doWeatherCycle", "true"); } catch (Throwable ignored) {}
                    try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                }
                case 1 -> {
                    if (!w.hasStorm()) w.setStorm(true);
                    if (w.isThundering()) w.setThundering(false);
                    w.setWeatherDuration(Integer.MAX_VALUE);
                    try { w.setGameRuleValue("doWeatherCycle", "false"); } catch (Throwable ignored) {}
                    try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                }
                case 2 -> {
                    if (!w.hasStorm()) w.setStorm(true);
                    if (!w.isThundering()) w.setThundering(true);
                    w.setWeatherDuration(Integer.MAX_VALUE);
                    try { w.setGameRuleValue("doWeatherCycle", "false"); } catch (Throwable ignored) {}
                    try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                }
                case 3 -> {
                    if (!w.hasStorm()) w.setStorm(true);
                    if (!w.isThundering()) w.setThundering(true);
                    w.setTime(18000);
                    w.setWeatherDuration(Integer.MAX_VALUE);
                    try { w.setGameRuleValue("doWeatherCycle", "false"); } catch (Throwable ignored) {}
                    try { w.setGameRuleValue("doDaylightCycle", "false"); } catch (Throwable ignored) {}
                }
                default -> {
                    if (w.hasStorm()) w.setStorm(false);
                    if (w.isThundering()) w.setThundering(false);
                    try { w.setGameRuleValue("doWeatherCycle", "true"); } catch (Throwable ignored) {}
                    try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("applyStageWeather error: " + t);
        }
    }

    private void startEnforceTaskForStage(int stageId) {
        stopEnforceTask();
        applyStageWeather(stageId);
        enforceStageTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { applyStageWeather(stageId); } catch (Throwable t) { plugin.getLogger().warning("Ошибка в enforceStageTask: " + t); }
        }, 1L, 20L * 5L);
    }

    private void stopEnforceTask() {
        if (enforceStageTask != null) {
            try { enforceStageTask.cancel(); } catch (Throwable ignored) {}
            enforceStageTask = null;
        }
    }

    private void manageStageEndEffects(int oldStageId) {
        if (oldStageId == 3) {
            synchronized (scheduledDecayTasks) {
                for (BukkitTask t : scheduledDecayTasks) {
                    try { t.cancel(); } catch (Exception ignored) {}
                }
                scheduledDecayTasks.clear();
            }
            scheduledLavaChunks.clear();
            stopLavaScheduler();
        }
        stopEnforceTask();
        try {
            World w = Bukkit.getWorld("world");
            if (w != null) {
                try { w.setGameRuleValue("doDaylightCycle", "true"); } catch (Throwable ignored) {}
                try { w.setGameRuleValue("doWeatherCycle", "true"); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Исправленный/надёжный оптимизированный startCampfireTask.
     * Проверяем небольшой вертикальный диапазон {topY, topY-1, topY-2} для надёжного обнаружения костров.
     */
    private void startCampfireTask() {
        if (campfireTask != null) return;

        campfireTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                World w = Bukkit.getWorld("world");
                if (w == null) return;
                int current = (currentStage == null) ? 0 : currentStage.id();
                if (!(current == 2 || current == 3)) {
                    pendingExtinguish.clear();
                    return;
                }

                long now = System.currentTimeMillis();

                for (Chunk chunk : w.getLoadedChunks()) {
                    int baseX = chunk.getX() << 4;
                    int baseZ = chunk.getZ() << 4;

                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int worldX = baseX + lx;
                            int worldZ = baseZ + lz;
                            int topY = w.getHighestBlockYAt(worldX, worldZ);
                            // Проверяем небольшой набор слоёв: topY, topY-1, topY-2
                            boolean foundCampfireInColumn = false;
                            for (int dy = 0; dy >= -2; dy--) {
                                int checkY = topY + dy;
                                if (checkY < w.getMinHeight() || checkY > w.getMaxHeight()) continue;
                                Block b = w.getBlockAt(worldX, checkY, worldZ);
                                if (b == null) continue;
                                Material mt = b.getType();
                                String key = b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();

                                if (mt != Material.CAMPFIRE && mt != Material.SOUL_CAMPFIRE) {
                                    // если запись для этой позиции есть — убираем (значит костра здесь сейчас нет)
                                    pendingExtinguish.remove(key);
                                    continue;
                                }

                                // у нас найден candidate campfire на позиции b
                                foundCampfireInColumn = true;

                                boolean isLit = false;
                                try {
                                    if (b.getBlockData() instanceof Campfire cf && cf.isLit()) isLit = true;
                                } catch (Throwable ignored) {}

                                if (!isLit) {
                                    pendingExtinguish.remove(key);
                                    continue;
                                }

                                if (!NmsHelper.isOpenToSky(b)) {
                                    pendingExtinguish.remove(key);
                                    continue;
                                }

                                Long scheduledAt = pendingExtinguish.get(key);
                                if (scheduledAt == null) {
                                    pendingExtinguish.put(key, now + 15_000L);
                                } else if (now >= scheduledAt) {
                                    if (currentStage != null && (currentStage.id() == 2 || currentStage.id() == 3)) {
                                        if (b.getChunk().isLoaded()) {
                                            var bd = b.getBlockData();
                                            if (bd instanceof Campfire cf2 && cf2.isLit() && NmsHelper.isOpenToSky(b)) {
                                                try {
                                                    cf2.setLit(false);
                                                    b.setBlockData(cf2, false);
                                                    b.getWorld().playSound(b.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
                                                    heat.removeHeatSource(b);
                                                } catch (Throwable ignored) {}
                                            }
                                        }
                                    }
                                    pendingExtinguish.remove(key);
                                }
                            } // end dy loop

                            // Если в столбце не найден костёр (в проверенных слоях), — можно (по желанию) ничего не делать.
                            // Мы не пытаемся чистить pendingExtinguish по "другим" ключам тут, т.к. ключи основаны на конкретных позициях.
                        }
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка в optimized campfireTask: " + t);
            }
        }, 40L, 20L * 10L);
    }

    private void startLavaScheduler() {
        if (lavaSchedulerTask != null) return;
        lavaSchedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (currentStage == null || currentStage.id() != 3) return;
                scheduleDecayForChunksNearPlayers(1);
            } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка в lavaSchedulerTask: " + t);
            }
        }, 60L, 20L * 20L);
    }

    private void stopLavaScheduler() {
        if (lavaSchedulerTask != null) {
            try { lavaSchedulerTask.cancel(); } catch (Throwable ignored) {}
            lavaSchedulerTask = null;
        }
    }

    private void scheduleDecayForChunksNearPlayers(int chunkRadius) {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Chunk pc = p.getLocation().getChunk();
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    int cx = pc.getX() + dx;
                    int cz = pc.getZ() + dz;
                    String ck = pc.getWorld().getName() + ":" + cx + ":" + cz;
                    if (scheduledLavaChunks.contains(ck)) continue;
                    Chunk chunk = pc.getWorld().getChunkAt(cx, cz);
                    if (!chunk.isLoaded()) continue;
                    int scheduled = scheduleLavaDecayForChunk(chunk);
                    if (scheduled > 0) {
                        scheduledLavaChunks.add(ck);
                        plugin.getLogger().info("[TheGreatCold] Scheduled lava-transform tasks in chunk " + ck + " -> " + scheduled + " tasks.");
                    } else {
                        scheduledLavaChunks.add(ck);
                    }
                }
            }
        }
    }

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
                            long delaySeconds = 420L + rnd.nextLong(0, 481L); // 420..900
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
                            if (scheduled >= 1024) break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
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
        if (config.isAutoStageEnabled()) startAutoStage();
    }

    public void startAutoStage() {
        if (isAutoStage) return;
        isAutoStage = true;
        autoStageTask = Bukkit.getScheduler().runTaskTimer(plugin, this::autoStageTick, 20L, 20L);
    }

    private void autoStageTick() {
        if (!stageInfinite && System.currentTimeMillis() >= stageEndMillis) {
            int next = (currentStage.ordinal() + 1) % ColdStage.values().length;
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

    public boolean isStageInfinite() { return stageInfinite; }

    public void onDisable() {
        saveStageState();
        stopAutoStage();
        stopEnforceTask();
        manageStageEndEffects(currentStage.id());
        if (campfireTask != null) { campfireTask.cancel(); campfireTask = null; }
    }

    private boolean isChunkNearAnyPlayer(Chunk chunk, int radius) {
        if (chunk == null || chunk.getWorld() == null) return false;
        String worldName = chunk.getWorld().getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().getName().equals(worldName)) continue;
            Chunk pc = p.getLocation().getChunk();
            int dx = Math.abs(pc.getX() - cx);
            int dz = Math.abs(pc.getZ() - cz);
            if (dx <= radius && dz <= radius) return true;
        }
        return false;
    }

    private String chunkKeyFor(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ();
    }
}
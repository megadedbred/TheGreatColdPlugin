package me.megadedbeb.thegreatcold.heat;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.data.DataManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class HeatSourceManager implements Listener {
    private static final List<Material> HEAT_SOURCES = List.of(
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.LAVA, Material.MAGMA_BLOCK,
            Material.FIRE, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER
    );

    private final TheGreatColdPlugin plugin;
    private final DataManager dataManager;
    private final CustomHeatManager customHeatManager; // integration for custom sources

    // sourceKey -> region (sourceKey = world:x:y:z)
    private final Map<String, HeatSourceRegion> activeRegions = new HashMap<>();

    // chunkKey -> set of sourceKeys in that chunk (chunkKey = world:chunkX:chunkZ)
    private final Map<String, Set<String>> chunkIndex = new HashMap<>();

    // временные задачи инкрементного сканирования чанка (чтобы не нагружать сервер при загрузке мира)
    // chunkKey -> BukkitTask
    private final Map<String, BukkitTask> chunkScanTasks = new HashMap<>();

    // validation interval (ticks). 600 ticks = 30s. Можно изменить при необходимости.
    private static final long VALIDATION_INTERVAL_TICKS = 600L;

    // Сколько колонн (x,z) чанка обрабатывать за тик при полном сканировании.
    // 16 -> закончить скан одного чанка за ~16 тиков.
    private static final int COLUMNS_PER_TICK = 16;

    public HeatSourceManager(TheGreatColdPlugin plugin, DataManager dataManager, CustomHeatManager customHeatManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.customHeatManager = customHeatManager;

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Мы больше не сохраняем natural heat sources в data.yml.
        // Очистим возможные старые записи в data.yml, чтобы не оставлять устаревшие записи.
        try {
            dataManager.clearSavedHeatSources();
            dataManager.saveAll();
        } catch (Throwable ignored) {}

        // Редкая проверка «здоровья» активных регионов, но только рядом с игроками
        startValidationTask();
    }

    public TheGreatColdPlugin getPlugin() { return this.plugin; }

    public void scanWorldForHeatSources() {
        activeRegions.clear();
        chunkIndex.clear();

        // Запускаем инкрементное сканирование загруженных чанков (чтобы найти природные источники, лаву и т.п.)
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkForHeat(chunk);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        scanChunkForHeat(e.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        // Отменяем фоновую задачу сканирования, если она есть
        String ck = chunkKeyFor(e.getChunk());
        BukkitTask t = chunkScanTasks.remove(ck);
        if (t != null) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        for (Chunk c : e.getWorld().getLoadedChunks()) scanChunkForHeat(c);
    }

    /**
     * Сканирует один чанк. Для минимизации пикового нагрузки:
     * - Сначала делаем быструю проверку tile-entities и верхних слоёв (surface/near-surface).
     * - Затем планируем полноск чанка, разбитый на небольшие порции (несколько колонн в тик).
     */
    private void scanChunkForHeat(Chunk chunk) {
        if (chunk == null || !chunk.isLoaded()) return;
        String chunkKey = chunkKeyFor(chunk);
        Set<String> existing = chunkIndex.get(chunkKey);
        if (existing != null && !existing.isEmpty()) {
            quickScanChunk(chunk);
            return;
        }

        if (chunkScanTasks.containsKey(chunkKey)) return;

        quickScanChunk(chunk);

        BukkitRunnable task = new BukkitRunnable() {
            private int colIndex = 0; // 0..255 -> each maps to (x,z)
            private final World world = chunk.getWorld();
            private final int minY = (world != null) ? world.getMinHeight() : chunk.getWorld().getMinHeight();
            private final int maxY = (world != null) ? world.getMaxHeight() : chunk.getWorld().getMaxHeight();

            @Override
            public void run() {
                try {
                    if (!chunk.isLoaded()) {
                        cancel();
                        chunkScanTasks.remove(chunkKey);
                        return;
                    }
                    int processed = 0;
                    while (processed < COLUMNS_PER_TICK && colIndex < 256) {
                        int cx = colIndex % 16;
                        int cz = colIndex / 16;
                        colIndex++;
                        processed++;

                        for (int y = minY; y < maxY; y++) {
                            try {
                                Block block = chunk.getBlock(cx, y, cz);
                                Material mat = block.getType();
                                if (HEAT_SOURCES.contains(mat)) {
                                    if (providesHeat(block)) {
                                        registerHeatSource(block);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (colIndex >= 256) {
                        cancel();
                        chunkScanTasks.remove(chunkKey);
                    }
                } catch (Throwable t) {
                    try { cancel(); } catch (Throwable ignored) {}
                    chunkScanTasks.remove(chunkKey);
                }
            }
        };

        BukkitTask bt = task.runTaskTimer(plugin, 0L, 1L);
        chunkScanTasks.put(chunkKey, bt);
    }

    private void quickScanChunk(Chunk chunk) {
        World world = chunk.getWorld();
        if (world == null) return;

        try {
            for (BlockState tile : chunk.getTileEntities()) {
                try {
                    Block b = tile.getBlock();
                    if (b == null) continue;
                    Material m = b.getType();
                    if (HEAT_SOURCES.contains(m) && providesHeat(b)) {
                        registerHeatSource(b);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int bx = chunk.getX() * 16 + x;
                    int bz = chunk.getZ() * 16 + z;
                    int topY = world.getHighestBlockYAt(bx, bz);
                    int fromY = Math.max(world.getMinHeight(), topY - 64);
                    for (int y = topY; y >= fromY; y--) {
                        try {
                            Block b = chunk.getBlock(x, y, z);
                            if (HEAT_SOURCES.contains(b.getType()) && providesHeat(b)) {
                                registerHeatSource(b);
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void startValidationTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                validateRegionsNearPlayers();
            }
        }.runTaskTimer(plugin, VALIDATION_INTERVAL_TICKS, VALIDATION_INTERVAL_TICKS);
    }

    private void validateRegionsNearPlayers() {
        Set<String> chunksToCheck = new HashSet<>();
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Chunk c = p.getLocation().getChunk();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunksToCheck.add(c.getWorld().getName() + ":" + (c.getX() + dx) + ":" + (c.getZ() + dz));
                }
            }
        }
        Set<String> sourcesToCheck = new HashSet<>();
        for (String chunkKey : chunksToCheck) {
            Set<String> s = chunkIndex.get(chunkKey);
            if (s != null) sourcesToCheck.addAll(s);
        }
        List<String> toRemove = new ArrayList<>();
        for (String sourceKey : sourcesToCheck) {
            HeatSourceRegion region = activeRegions.get(sourceKey);
            if (region == null) continue;
            Block block = region.getCenter().getBlock();
            if (!HEAT_SOURCES.contains(block.getType()) || !providesHeat(block)) {
                toRemove.add(sourceKey);
            }
        }
        for (String key : toRemove) {
            HeatSourceRegion removed = activeRegions.remove(key);
            if (removed != null) {
                // do NOT persist or remove persisted data for natural sources — we don't persist them
                String ck = chunkKeyFor(removed.getCenter().getChunk());
                Set<String> set = chunkIndex.get(ck);
                if (set != null) {
                    set.remove(key);
                    if (set.isEmpty()) chunkIndex.remove(ck);
                }
            }
        }
    }

    public boolean providesHeat(Block block) {
        Material mat = block.getType();
        if (mat == Material.CAMPFIRE || mat == Material.SOUL_CAMPFIRE) {
            try {
                var camp = (org.bukkit.block.data.type.Campfire) block.getBlockData();
                return camp.isLit();
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (mat == Material.FURNACE || mat == Material.SMOKER || mat == Material.BLAST_FURNACE) {
            BlockState state = block.getState();
            try {
                try {
                    var m = state.getClass().getMethod("getBurnTime");
                    Object res = m.invoke(state);
                    if (res instanceof Number) {
                        if (((Number) res).intValue() > 0) return true;
                    }
                } catch (NoSuchMethodException ignored) {}
                try {
                    int blockLight = block.getLightLevel() - block.getLightFromSky();
                    if (blockLight > 0) return true;
                } catch (Throwable ignored) {}
                return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return mat == Material.LAVA || mat == Material.MAGMA_BLOCK || mat == Material.FIRE;
    }

    public void registerHeatSource(Block block) {
        if (!HEAT_SOURCES.contains(block.getType())) return;
        if (!providesHeat(block)) {
            removeHeatSource(block);
            return;
        }
        String sourceKey = keyFor(block.getLocation());
        if (activeRegions.containsKey(sourceKey)) return;

        HeatSourceType type = HeatSourceType.fromMaterial(block.getType());
        int radius = plugin.getConfigManager().getHeatRadius(type);
        HeatSourceRegion region = new HeatSourceRegion(block.getLocation(), type, radius);
        activeRegions.put(sourceKey, region);

        String chunkKey = chunkKeyFor(block.getLocation().getChunk());
        chunkIndex.computeIfAbsent(chunkKey, k -> new HashSet<>()).add(sourceKey);

        // IMPORTANT: do NOT persist natural heat blocks to data.yml (they are discovered dynamically)
    }

    public void removeHeatSource(Block block) {
        String sourceKey = keyFor(block.getLocation());
        HeatSourceRegion removed = activeRegions.remove(sourceKey);
        if (removed != null) {
            String ck = chunkKeyFor(removed.getCenter().getChunk());
            Set<String> set = chunkIndex.get(ck);
            if (set != null) {
                set.remove(sourceKey);
                if (set.isEmpty()) chunkIndex.remove(ck);
            }
            // we intentionally do not touch persistent storage for natural sources
        } else {
            // nothing persisted for natural sources -> nothing to remove
        }
    }

    public boolean isPlayerInHeat(org.bukkit.entity.Player player) {
        return isLocationInHeat(player.getLocation());
    }

    /**
     * Проверяет, находится ли данная локация в зоне любого зарегистрированного источника тепла.
     */
    public boolean isLocationInHeat(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        // First, check custom heat sources (they have larger zones and apply even when their block isn't loaded)
        if (customHeatManager != null && customHeatManager.isLocationInCustomHeat(loc)) return true;

        Chunk chunk = loc.getChunk();
        int chunkRadius = 1; // смотрим соседние чанки
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                String ck = loc.getWorld().getName() + ":" + (chunk.getX() + dx) + ":" + (chunk.getZ() + dz);
                Set<String> set = chunkIndex.get(ck);
                if (set == null || set.isEmpty()) continue;
                for (String sourceKey : set) {
                    HeatSourceRegion region = activeRegions.get(sourceKey);
                    if (region != null && region.isInside(loc)) return true;
                }
            }
        }
        return false;
    }

    private String keyFor(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private String chunkKeyFor(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ();
    }
}
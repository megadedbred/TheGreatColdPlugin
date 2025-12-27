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

import java.util.*;

public class HeatSourceManager implements Listener {
    private static final List<Material> HEAT_SOURCES = List.of(
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.LAVA, Material.MAGMA_BLOCK,
            Material.FIRE, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER
    );

    private final TheGreatColdPlugin plugin;
    private final DataManager dataManager;

    // sourceKey -> region (sourceKey = world:x:y:z)
    private final Map<String, HeatSourceRegion> activeRegions = new HashMap<>();

    // chunkKey -> set of sourceKeys in that chunk (chunkKey = world:chunkX:chunkZ)
    private final Map<String, Set<String>> chunkIndex = new HashMap<>();

    // validation interval (ticks). 600 ticks = 30s. Можно изменить при необходимости.
    private static final long VALIDATION_INTERVAL_TICKS = 600L;

    public HeatSourceManager(TheGreatColdPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Восстанавливаем сохранённые источники (из data.yml)
        restoreSavedHeatSources();

        // Редкая проверка «здоровья» активных регионов, но только рядом с игроками
        startValidationTask();
    }

    public TheGreatColdPlugin getPlugin() { return this.plugin; }

    public void scanWorldForHeatSources() {
        activeRegions.clear();
        chunkIndex.clear();
        restoreSavedHeatSources();
    }

    private void restoreSavedHeatSources() {
        Collection<HeatSourceRegion> saved = new ArrayList<>(dataManager.getSavedHeatSources());
        List<Location> toRemove = new ArrayList<>();
        for (HeatSourceRegion r : saved) {
            Location loc = r.getCenter();
            try {
                Block block = loc.getBlock();
                if (HEAT_SOURCES.contains(block.getType()) && providesHeat(block)) {
                    String sourceKey = keyFor(loc);
                    activeRegions.put(sourceKey, r);
                    String chunkKey = chunkKeyFor(loc.getChunk());
                    chunkIndex.computeIfAbsent(chunkKey, k -> new HashSet<>()).add(sourceKey);
                } else {
                    toRemove.add(loc);
                }
            } catch (Exception ignored) {
                toRemove.add(r.getCenter());
            }
        }
        // Удаляем невалидные записи из персистентного хранилища
        for (Location loc : toRemove) {
            dataManager.removeSavedHeatSourceByLocation(loc);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        scanChunkForHeat(e.getChunk());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        for (Chunk c : e.getWorld().getLoadedChunks()) scanChunkForHeat(c);
    }

    private void scanChunkForHeat(Chunk chunk) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = Math.min(world.getMaxHeight(), world.getSeaLevel() + 256);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    Material mat = block.getType();
                    if (HEAT_SOURCES.contains(mat) && providesHeat(block)) {
                        registerHeatSource(block);
                    }
                }
            }
        }
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
                dataManager.removeSavedHeatSourceByLocation(removed.getCenter());
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
                if (state instanceof org.bukkit.block.Furnace f) {
                    if (f.getBurnTime() > 0) return true;
                    try {
                        FurnaceInventory inv = (FurnaceInventory) f.getInventory();
                        if (inv != null) {
                            if (inv.getFuel() != null) return true;
                            if (inv.getSmelting() != null) return true;
                        }
                    } catch (Throwable ignored) {}
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            return false;
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

        dataManager.addSavedHeatSource(region);
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
            dataManager.removeSavedHeatSourceByLocation(removed.getCenter());
        } else {
            dataManager.removeSavedHeatSourceByLocation(block.getLocation());
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
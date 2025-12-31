package me.megadedbeb.thegreatcold.heat;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.data.DataManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.ChatColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;

/**
 * CustomHeatManager — управляет кастомными источниками тепла (small_heater и sea_heater).
 *
 * Изменения:
 * - Морской обогреватель теперь тратит топливо независимо от загрузки чанка (как и небольшой).
 *   Визуалы/таяние по-прежнему требуют загруженного чанка/воды для отображения/эффекта.
 *
 * Остальная логика: восстановление/сохранение, голограммы, частицы, BFS-таяние — сохранена.
 */
public class CustomHeatManager implements Listener {
    private final TheGreatColdPlugin plugin;
    private final DataManager dataManager;

    // key = world:x:y:z  -> CustomHeatSource
    private final Map<String, CustomHeatSource> sources = new ConcurrentHashMap<>();

    private BukkitTask tickTask;

    private final NamespacedKey pdcKey;     // для рецепта/Item
    private final NamespacedKey holoKey;    // для пометки ArmorStand'ов hologram

    // Throttling & intervals
    private long lastSaveMillis = 0L;
    private static final long SAVE_INTERVAL_MS = 60_000L;
    private long tickCounter = 0L;
    private static final int MELT_INTERVAL_TICKS = 5;

    public CustomHeatManager(TheGreatColdPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.pdcKey = new NamespacedKey(plugin, "heater_type");
        this.holoKey = new NamespacedKey(plugin, "thegreatcold_hologram");

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Load all saved entries into memory (preserve fuelMillis). Visuals spawn later when chunks load.
        loadAllSavedIntoMemory();

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 20L);
    }

    private String keyFor(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    // ----------------- Public API helpers (Block overloads) -----------------
    public boolean hasSourceAt(Block b) { return b != null && hasSourceAt(b.getLocation()); }
    public void createSourceAt(Block b, String type) { if (b != null) createSourceAt(b.getLocation(), type); }
    public void removeSourceAt(Block b) { if (b != null) removeSourceAt(b.getLocation()); }

    // ----------------- Loading / restoring -----------------

    private void loadAllSavedIntoMemory() {
        var saved = dataManager.getSavedCustomSources();
        if (saved == null || saved.isEmpty()) return;

        List<Map<String,String>> toRemove = new ArrayList<>();

        for (var dto : saved) {
            try {
                String type = dto.get("type");
                String world = dto.get("world");
                int x = Integer.parseInt(dto.get("x"));
                int y = Integer.parseInt(dto.get("y"));
                int z = Integer.parseInt(dto.get("z"));
                long fuel = Long.parseLong(dto.getOrDefault("fuelMillis", "0"));

                World w = Bukkit.getWorld(world);
                if (w == null) {
                    toRemove.add(dto);
                    continue;
                }

                Location loc = new Location(w, x, y, z);
                String key = keyFor(loc);
                if (sources.containsKey(key)) {
                    CustomHeatSource existing = sources.get(key);
                    if (existing != null && existing.getFuelMillis() != fuel) existing.setFuelMillis(fuel);
                    continue;
                }

                if (CustomHeatSource.TYPE_SMALL_HEATER.equals(type)) {
                    long max = 15L * 60L * 60L * 1000L; // 15 hours
                    CustomHeatSource s = new CustomHeatSource(type, loc, 15, max, fuel);
                    sources.put(key, s);
                } else if (CustomHeatSource.TYPE_SEA_HEATER.equals(type)) {
                    long max = 8L * 60L * 60L * 1000L; // 8 hours
                    // radius previously set elsewhere; keep stored radius when creating source — default used here is 41 in other code paths
                    CustomHeatSource s = new CustomHeatSource(type, loc, 41, max, fuel);
                    sources.put(key, s);
                } else {
                    toRemove.add(dto);
                }
            } catch (Throwable t) {
                try { toRemove.add(dto); } catch (Throwable ignored) {}
            }
        }

        if (!toRemove.isEmpty()) {
            for (var dto : toRemove) {
                try {
                    String world = dto.get("world");
                    int x = Integer.parseInt(dto.get("x"));
                    int y = Integer.parseInt(dto.get("y"));
                    int z = Integer.parseInt(dto.get("z"));
                    World w = Bukkit.getWorld(world);
                    if (w == null) continue;
                    Location loc = new Location(w, x, y, z);
                    try { dataManager.removeSavedCustomSource(loc); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
            try { dataManager.saveAll(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Public method: возвращает коллекцию всех in-memory источников.
     */
    public Collection<CustomHeatSource> getAllSources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    // ----------------- Chunk events -----------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent ev) {
        Chunk chunk = ev.getChunk();
        World w = chunk.getWorld();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        // Process in-memory sources that are inside this chunk
        List<CustomHeatSource> inChunk = new ArrayList<>();
        for (CustomHeatSource s : sources.values()) {
            Location loc = s.getBlockLocation();
            if (!loc.getWorld().equals(w)) continue;
            int scx = Math.floorDiv(loc.getBlockX(), 16);
            int scz = Math.floorDiv(loc.getBlockZ(), 16);
            if (scx == cx && scz == cz) inChunk.add(s);
        }

        for (CustomHeatSource s : inChunk) {
            Location loc = s.getBlockLocation();
            Block b = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            boolean valid = false;
            if (CustomHeatSource.TYPE_SMALL_HEATER.equals(s.getType())) {
                if (b != null && (b.getType() == Material.COAL_BLOCK || b.getType() == Material.SHROOMLIGHT)) valid = true;
            } else if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
                if (b != null && (b.getType() == Material.COAL_BLOCK || b.getType() == Material.SEA_LANTERN)) valid = true;
            }

            if (!valid) {
                removeSourceAt(loc);
            } else {
                updateVisualOnStateChange(s);
                spawnHologramsIfNeeded(s);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent ev) {
        Chunk chunk = ev.getChunk();
        World w = chunk.getWorld();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        for (CustomHeatSource s : sources.values()) {
            Location loc = s.getBlockLocation();
            if (!loc.getWorld().equals(w)) continue;
            int scx = Math.floorDiv(loc.getBlockX(), 16);
            int scz = Math.floorDiv(loc.getBlockZ(), 16);
            if (scx == cx && scz == cz) {
                s.setNameLineEntity(null);
                s.setFuelLineEntity(null);
            }
        }
    }

    // ----------------- API create/remove/has (Location) -----------------

    public boolean hasSourceAt(Location loc) {
        return sources.containsKey(keyFor(loc));
    }

    public CustomHeatSource getSourceAt(Location loc) {
        return sources.get(keyFor(loc));
    }

    public void createSourceAt(Location blockLoc, String type) {
        if (blockLoc == null) return;
        if (!blockLoc.getWorld().getName().equals("world")) return;
        String key = keyFor(blockLoc);
        if (sources.containsKey(key)) return;

        if (CustomHeatSource.TYPE_SMALL_HEATER.equals(type)) {
            long max = 15L * 60L * 60L * 1000L;
            CustomHeatSource s = new CustomHeatSource(type, blockLoc, 15, max, 0L);
            sources.put(key, s);
            purgeSavedCustomSourcesAt(blockLoc);
            dataManager.addSavedCustomSource(s);
            Block b = getSourceBlock(s);
            if (b != null) b.setType(Material.COAL_BLOCK, false);
            spawnHologramsIfNeeded(s);
            dataManager.saveAll();
        } else if (CustomHeatSource.TYPE_SEA_HEATER.equals(type)) {
            long max = 8L * 60L * 60L * 1000L;
            // radius set to 41 as requested
            CustomHeatSource s = new CustomHeatSource(type, blockLoc, 41, max, 0L);
            sources.put(key, s);
            purgeSavedCustomSourcesAt(blockLoc);
            dataManager.addSavedCustomSource(s);
            Block b = getSourceBlock(s);
            if (b != null) b.setType(Material.COAL_BLOCK, false);
            spawnHologramsIfNeeded(s);
            dataManager.saveAll();
        }
    }

    public void removeSourceAt(Location blockLoc) {
        if (blockLoc == null) return;
        String key = keyFor(blockLoc);
        CustomHeatSource s = sources.remove(key);
        if (s != null) {
            despawnHolograms(s);
            purgeSavedCustomSourcesAt(s.getBlockLocation());
            try { dataManager.removeSavedCustomSource(s.getBlockLocation()); } catch (Throwable ignored) {}
            Block b = getSourceBlock(s);
            if (b != null) {
                try { b.setType(Material.AIR, false); } catch (Throwable ignored) {}
            }
            try { dataManager.saveAll(); } catch (Throwable ignored) {}
        } else {
            purgeSavedCustomSourcesAt(blockLoc);
            try { dataManager.removeSavedCustomSource(blockLoc); } catch (Throwable ignored) {}
            try { dataManager.saveAll(); } catch (Throwable ignored) {}
        }
    }

    private void purgeSavedCustomSourcesAt(Location blockLoc) {
        try {
            var saved = dataManager.getSavedCustomSources();
            if (saved == null || saved.isEmpty()) return;
            String worldName = blockLoc.getWorld().getName();
            String sx = String.valueOf(blockLoc.getBlockX());
            String sy = String.valueOf(blockLoc.getBlockY());
            String sz = String.valueOf(blockLoc.getBlockZ());

            List<Map<String,String>> toRemove = new ArrayList<>();
            for (var dto : saved) {
                try {
                    String dw = dto.get("world");
                    String dx = dto.get("x");
                    String dy = dto.get("y");
                    String dz = dto.get("z");
                    if (worldName.equals(dw) && sx.equals(dx) && sy.equals(dy) && sz.equals(dz)) {
                        toRemove.add(dto);
                    }
                } catch (Throwable ignored) {}
            }

            for (var dto : toRemove) {
                try {
                    String dw = dto.get("world");
                    int dx = Integer.parseInt(dto.get("x"));
                    int dy = Integer.parseInt(dto.get("y"));
                    int dz = Integer.parseInt(dto.get("z"));
                    World w = Bukkit.getWorld(dw);
                    if (w == null) continue;
                    Location loc = new Location(w, dx, dy, dz);
                    try { dataManager.removeSavedCustomSource(loc); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ----------------- tick / particles / melt -----------------

    /**
     * Важно: теперь морской обогреватель тратит топливо независимо от загрузки чанка (как и небольшой).
     * Визуалы и таяние по-прежнему выполняются только для загруженных чанков / если условия выполнены.
     */
    private void tickAll() {
        tickCounter++;
        long dt = 1000L;
        boolean anyChanged = false;

        for (CustomHeatSource s : sources.values()) {
            long beforeFuel = s.getFuelMillis();
            boolean stateChanged = false;

            // Always attempt to consume fuel if any (for both types).
            // s.tick will reduce fuelMillis and return whether active/inactive changed.
            try {
                stateChanged = s.tick(dt);
            } catch (Throwable ignored) {}

            long afterFuel = s.getFuelMillis();

            // Spawn particles for active sources (for sea_heater require water above AND fuel)
            if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
                Block block = getSourceBlock(s);
                boolean effectiveActive = false;
                if (block != null && s.isActive()) {
                    Block above = block.getRelative(0,1,0);
                    effectiveActive = (above != null && above.getType() == Material.WATER);
                }
                if (effectiveActive) spawnSeaParticles(s);
            } else {
                if (s.isActive()) spawnParticles(s);
            }

            // melt snow/ice occasionally (only for sources that are effectively active)
            boolean effectiveActiveForMelt;
            if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
                Block block = getSourceBlock(s);
                if (block == null) effectiveActiveForMelt = false;
                else {
                    Block above = block.getRelative(0,1,0);
                    effectiveActiveForMelt = (s.isActive() && above != null && above.getType() == Material.WATER);
                }
            } else {
                effectiveActiveForMelt = s.isActive();
            }
            if (effectiveActiveForMelt && (tickCounter % MELT_INTERVAL_TICKS == 0)) {
                try { meltSnowAndIceInLoadedChunks(s); } catch (Throwable t) { plugin.getLogger().warning("Ошибка melt: " + t); }
            }

            // Update holograms if needed
            if (stateChanged || s.getNameLineEntity() != null || s.getFuelLineEntity() != null) {
                try { spawnOrUpdateHolograms(s); } catch (Throwable ignored) {}
            }

            // Persist in-memory DTO whenever fuel changed
            if (stateChanged || afterFuel != beforeFuel) {
                try {
                    purgeSavedCustomSourcesAt(s.getBlockLocation());
                    dataManager.addSavedCustomSource(s); // update in-memory map
                } catch (Throwable ignored) {}
                anyChanged = true;
            }

            // Also ensure visuals reflect effective active state (sea heater might have fuel but no water -> inactive visual)
            try { updateVisuals(s); } catch (Throwable ignored) {}
        }

        long now = System.currentTimeMillis();
        if (anyChanged || (now - lastSaveMillis >= SAVE_INTERVAL_MS)) {
            try {
                dataManager.saveAll();
                lastSaveMillis = now;
            } catch (Throwable t) { plugin.getLogger().warning("Ошибка saveAll: " + t); }
        }
    }

    // Возвращает Block источника (null если chunk не загружен)
    private Block getSourceBlock(CustomHeatSource s) {
        Location blockLoc = s.getBlockLocation();
        World w = blockLoc.getWorld();
        if (w == null) return null;
        int cx = Math.floorDiv(blockLoc.getBlockX(), 16);
        int cz = Math.floorDiv(blockLoc.getBlockZ(), 16);
        if (!w.isChunkLoaded(cx, cz)) return null;
        return w.getBlockAt(blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ());
    }

    private void updateVisualOnStateChange(CustomHeatSource s) {
        Block b = getSourceBlock(s);
        if (b == null) return;
        try {
            if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
                Block above = b.getRelative(0,1,0);
                boolean activeEffective = (s.isActive() && above != null && above.getType() == Material.WATER);
                if (activeEffective) b.setType(Material.SEA_LANTERN, false);
                else b.setType(Material.COAL_BLOCK, false);
            } else {
                if (s.isActive()) b.setType(Material.SHROOMLIGHT, false);
                else b.setType(Material.COAL_BLOCK, false);
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- holograms / particles / melt ----------------

    private void spawnHologramsIfNeeded(CustomHeatSource s) {
        Block block = getSourceBlock(s);
        if (block == null) return;
        spawnOrUpdateHolograms(s);
    }

    private void spawnOrUpdateHolograms(CustomHeatSource s) {
        Block block = getSourceBlock(s);
        if (block == null) return;

        if (s.getNameLineEntity() != null && !s.getNameLineEntity().isValid()) s.setNameLineEntity(null);
        if (s.getFuelLineEntity() != null && !s.getFuelLineEntity().isValid()) s.setFuelLineEntity(null);

        double nameY = block.getY() + 1.00;
        double fuelY = block.getY() + 0.65;
        World w = block.getWorld();
        String sourceKey = keyFor(block.getLocation());

        String displayName;
        if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
            Block above = block.getRelative(0,1,0);
            boolean effectiveActive = (s.isActive() && above != null && above.getType() == Material.WATER);
            displayName = (effectiveActive ? (ChatColor.BLUE.toString() + "Морской обогреватель") : (ChatColor.AQUA.toString() + "Морской обогреватель"));
        } else {
            displayName = (s.isActive() ? (ChatColor.GOLD.toString() + "Небольшой обогреватель") : (ChatColor.AQUA.toString() + "Небольшой обогреватель"));
        }

        ArmorStand nameAS = s.getNameLineEntity();
        if (nameAS == null || !nameAS.isValid()) {
            Collection<Entity> nearby = w.getNearbyEntities(new Location(w, block.getX() + 0.5, nameY, block.getZ() + 0.5), 0.6, 0.6, 0.6);
            ArmorStand found = null;
            List<ArmorStand> extras = new ArrayList<>();
            for (Entity e : nearby) {
                if (!(e instanceof ArmorStand as)) continue;
                if (!as.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) continue;
                String v = as.getPersistentDataContainer().get(holoKey, PersistentDataType.STRING);
                if ((sourceKey + "|name").equals(v)) {
                    if (found == null) found = as;
                    else extras.add(as);
                }
            }
            for (ArmorStand ex : extras) try { ex.remove(); } catch (Throwable ignored) {}
            if (found != null) {
                nameAS = found;
                s.setNameLineEntity(nameAS);
            } else {
                Location nameLoc = new Location(w, block.getX() + 0.5, nameY, block.getZ() + 0.5);
                Entity e = w.spawn(nameLoc, ArmorStand.class, as -> {
                    as.setVisible(false);
                    as.setMarker(true);
                    as.setGravity(false);
                    as.setCustomNameVisible(true);
                    as.setCanPickupItems(false);
                    as.setCustomName(displayName);
                    as.setCollidable(false);
                    as.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, sourceKey + "|name");
                });
                if (e instanceof ArmorStand as2) {
                    nameAS = as2;
                    s.setNameLineEntity(nameAS);
                }
            }
        } else {
            try {
                nameAS.setCustomName(displayName);
                nameAS.teleport(new Location(w, block.getX() + 0.5, nameY, block.getZ() + 0.5));
            } catch (Throwable ignored) {}
        }

        ArmorStand fuelAS = s.getFuelLineEntity();
        if (fuelAS == null || !fuelAS.isValid()) {
            Collection<Entity> nearby = w.getNearbyEntities(new Location(w, block.getX() + 0.5, fuelY, block.getZ() + 0.5), 0.6, 0.6, 0.6);
            ArmorStand found = null;
            List<ArmorStand> extras = new ArrayList<>();
            for (Entity e : nearby) {
                if (!(e instanceof ArmorStand as)) continue;
                if (!as.getPersistentDataContainer().has(holoKey, PersistentDataType.STRING)) continue;
                String v = as.getPersistentDataContainer().get(holoKey, PersistentDataType.STRING);
                if ((sourceKey + "|fuel").equals(v)) {
                    if (found == null) found = as;
                    else extras.add(as);
                }
            }
            for (ArmorStand ex : extras) try { ex.remove(); } catch (Throwable ignored) {}
            if (found != null) {
                fuelAS = found;
                s.setFuelLineEntity(fuelAS);
            } else {
                Location fuelLoc = new Location(w, block.getX() + 0.5, fuelY, block.getZ() + 0.5);
                Entity e2 = w.spawn(fuelLoc, ArmorStand.class, as -> {
                    as.setVisible(false);
                    as.setMarker(true);
                    as.setGravity(false);
                    as.setCustomNameVisible(true);
                    as.setCanPickupItems(false);
                    as.setCustomName(s.getDisplayFuelLine());
                    as.setCollidable(false);
                    as.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, sourceKey + "|fuel");
                });
                if (e2 instanceof ArmorStand as2) {
                    fuelAS = as2;
                    s.setFuelLineEntity(fuelAS);
                }
            }
        } else {
            try {
                fuelAS.setCustomName(s.getDisplayFuelLine());
                fuelAS.teleport(new Location(w, block.getX() + 0.5, fuelY, block.getZ() + 0.5));
            } catch (Throwable ignored) {}
        }

        try {
            if (nameAS != null) nameAS.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, sourceKey + "|name");
            if (fuelAS != null) fuelAS.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, sourceKey + "|fuel");
        } catch (Throwable ignored) {}
    }

    private void despawnHolograms(CustomHeatSource s) {
        try {
            ArmorStand n = s.getNameLineEntity();
            ArmorStand f = s.getFuelLineEntity();
            if (n != null && n.isValid()) n.remove();
            if (f != null && f.isValid()) f.remove();
        } catch (Throwable ignored) {}
        s.setNameLineEntity(null);
        s.setFuelLineEntity(null);
    }

    private void spawnParticles(CustomHeatSource s) {
        try {
            Block b = getSourceBlock(s);
            if (b == null) return;
            Location c = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.9, b.getZ() + 0.5);
            b.getWorld().spawnParticle(Particle.FLAME, c, 6, 0.3, 0.2, 0.3, 0.01);
        } catch (Throwable ignored) {}
    }

    private void spawnSeaParticles(CustomHeatSource s) {
        try {
            Block b = getSourceBlock(s);
            if (b == null) return;
            World w = b.getWorld();
            Location center = new Location(w, b.getX() + 0.5, b.getY() + 0.9, b.getZ() + 0.5);
            w.spawnParticle(Particle.BUBBLE_POP, center, 12, 1.2, 0.6, 1.2, 0.02);
            Location top = new Location(w, b.getX() + 0.5, b.getY() + 1.6, b.getZ() + 0.5);
            w.spawnParticle(Particle.POOF, top, 4, 0.2, 0.2, 0.2, 0.01);
        } catch (Throwable ignored) {}
    }

    /**
     * Центрированный BFS melt с адаптивными лимитами.
     * Использует s.getRadius() (для sea_heater значение задаётся при создании).
     */
    private void meltSnowAndIceInLoadedChunks(CustomHeatSource s) {
        Block src = getSourceBlock(s);
        if (src == null) return;
        World w = src.getWorld();
        int cx = src.getX();
        int cy = src.getY();
        int cz = src.getZ();
        int r = s.getRadius();

        final int MAX_SEARCH_NODES_SEA = 200_000;
        final int MAX_SEARCH_NODES_SMALL = 16_384;
        final int MAX_MELTS_SEA = 256;
        final int MAX_MELTS_SMALL = 64;

        final int MAX_SEARCH_NODES = CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType()) ? MAX_SEARCH_NODES_SEA : MAX_SEARCH_NODES_SMALL;
        final int localMaxMelt = CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType()) ? MAX_MELTS_SEA : MAX_MELTS_SMALL;

        int melted = 0;

        ArrayDeque<int[]> q = new ArrayDeque<>();
        HashSet<Long> visited = new HashSet<>();

        final int OFFSET = 1 << 20;
        q.add(new int[]{cx, cy, cz});
        visited.add((((long)(cx + OFFSET)) << 42) | (((long)(cy + OFFSET)) << 21) | ((long)(cz + OFFSET)));

        while (!q.isEmpty() && melted < localMaxMelt && visited.size() < MAX_SEARCH_NODES) {
            int[] pos = q.poll();
            int x = pos[0], y = pos[1], z = pos[2];

            if (Math.abs(x - cx) > r || Math.abs(y - cy) > r || Math.abs(z - cz) > r) continue;

            int chunkX = Math.floorDiv(x, 16);
            int chunkZ = Math.floorDiv(z, 16);
            if (!w.isChunkLoaded(chunkX, chunkZ)) continue;

            try {
                Block b = w.getBlockAt(x, y, z);
                Material mt = b.getType();
                if (mt == Material.SNOW || mt == Material.SNOW_BLOCK) {
                    b.setType(Material.AIR, false);
                    melted++;
                } else if (mt == Material.ICE) {
                    b.setType(Material.WATER, false);
                    melted++;
                }
            } catch (Throwable ignored) {}

            if (melted >= localMaxMelt) break;

            int[][] neigh = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for (int[] d : neigh) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (Math.abs(nx - cx) > r || Math.abs(ny - cy) > r || Math.abs(nz - cz) > r) continue;
                long key = (((long)(nx + OFFSET)) << 42) | (((long)(ny + OFFSET)) << 21) | ((long)(nz + OFFSET));
                if (visited.contains(key)) continue;
                visited.add(key);
                q.add(new int[]{nx, ny, nz});
            }
        }

        if (melted < localMaxMelt && visited.size() >= MAX_SEARCH_NODES) {
            int minX = cx - r;
            int maxX = cx + r;
            int minZ = cz - r;
            int maxZ = cz + r;

            int minChunkX = Math.floorDiv(minX, 16);
            int maxChunkX = Math.floorDiv(maxX, 16);
            int minChunkZ = Math.floorDiv(minZ, 16);
            int maxChunkZ = Math.floorDiv(maxZ, 16);

            outer:
            for (int ccx = minChunkX; ccx <= maxChunkX; ccx++) {
                for (int ccz = minChunkZ; ccz <= maxChunkZ; ccz++) {
                    if (!w.isChunkLoaded(ccx, ccz)) continue;
                    int startX = Math.max(minX, ccx * 16);
                    int endX = Math.min(maxX, ccx * 16 + 15);
                    int startZ = Math.max(minZ, ccz * 16);
                    int endZ = Math.min(maxZ, ccz * 16 + 15);
                    for (int x = startX; x <= endX; x++) {
                        for (int z = startZ; z <= endZ; z++) {
                            int topY = w.getHighestBlockYAt(x, z);
                            int fromY = Math.max(w.getMinHeight(), topY - 64);
                            for (int y = topY; y >= fromY; y--) {
                                if (Math.abs(x - cx) > r || Math.abs(y - cy) > r || Math.abs(z - cz) > r) continue;
                                try {
                                    Block b = w.getBlockAt(x, y, z);
                                    Material mt = b.getType();
                                    if (mt == Material.SNOW || mt == Material.SNOW_BLOCK) {
                                        b.setType(Material.AIR, false);
                                        melted++;
                                        if (melted >= localMaxMelt) break outer;
                                    } else if (mt == Material.ICE) {
                                        b.setType(Material.WATER, false);
                                        melted++;
                                        if (melted >= localMaxMelt) break outer;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- block events / interaction ----------------

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent ev) {
        ItemStack item = ev.getItemInHand();
        if (item == null) return;
        var meta = item.getItemMeta();
        if (meta == null) return;
        if (!meta.getPersistentDataContainer().has(pdcKey, PersistentDataType.STRING)) return;
        String val = meta.getPersistentDataContainer().get(pdcKey, PersistentDataType.STRING);
        if (val == null) return;
        Location loc = ev.getBlockPlaced().getLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { ev.getBlockPlaced().setType(Material.COAL_BLOCK, false); } catch (Throwable ignored) {}
            createSourceAt(loc, val);
        }, 0L);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent ev) {
        Block b = ev.getBlock();
        if (b == null) return;
        if (hasSourceAt(b.getLocation())) ev.setCancelled(true);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent ev) {
        ev.blockList().removeIf(bl -> hasSourceAt(bl.getLocation()));
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent ev) {
        ev.blockList().removeIf(bl -> hasSourceAt(bl.getLocation()));
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent ev) {
        if (hasSourceAt(ev.getBlock().getLocation())) ev.setCancelled(true);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent ev) {
        for (Block b : ev.getBlocks()) if (hasSourceAt(b.getLocation())) ev.setCancelled(true);
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent ev) {
        for (Block b : ev.getBlocks()) if (hasSourceAt(b.getLocation())) ev.setCancelled(true);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND) return;
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (ev.getClickedBlock() == null) return;
        Block clicked = ev.getClickedBlock();
        String key = keyFor(clicked.getLocation());
        CustomHeatSource s = sources.get(key);
        if (s == null) return;

        Player p = ev.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();

        if (inHand != null && inHand.getType() == Material.BRUSH && p.hasPermission("greatcold.admin")) {
            ev.setCancelled(true);
            removeSourceAt(clicked.getLocation());
            p.sendMessage("§aИсточник тепла удалён.");
            return;
        }

        if (inHand == null || inHand.getType() == Material.AIR) return;

        Integer minutes = minutesForFuel(inHand.getType(), inHand, s);
        if (minutes == null) {
            p.sendMessage("§cЭтот предмет не является топливом для этого обогревателя.");
            return;
        }
        ev.setCancelled(true);

        long perItemMillis = minutes * 60L * 1000L;
        long spaceLeft = s.getMaxFuelMillis() - s.getFuelMillis();

        if (spaceLeft <= 0) {
            p.sendMessage("§cТопливо больше не вмещается!");
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.2f);
            return;
        }

        boolean sneak = p.isSneaking();
        int requested = sneak ? inHand.getAmount() : 1;

        int maxItemsFit = (int) (spaceLeft / perItemMillis);
        boolean allowPartial = (spaceLeft > 0 && maxItemsFit == 0);

        if (!sneak) {
            if (maxItemsFit >= 1) {
                s.addFuelMillis(perItemMillis);
                consumePlayerItems(p, 1);
            } else if (allowPartial) {
                s.addFuelMillis(spaceLeft);
                consumePlayerItems(p, 1);
            } else {
                p.sendMessage("§cТопливо больше не вмещается!");
                return;
            }
        } else {
            if (maxItemsFit <= 0) {
                if (allowPartial) {
                    s.addFuelMillis(spaceLeft);
                    consumePlayerItems(p, 1);
                } else {
                    p.sendMessage("§cТопливо больше не вмещается!");
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.2f);
                    return;
                }
            } else {
                int toConsume = Math.min(requested, maxItemsFit);
                s.addFuelMillis(perItemMillis * toConsume);
                consumePlayerItems(p, toConsume);
            }
        }

        updateVisuals(s);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.0f);
        p.sendMessage("§aТопливо добавлено. Текущее: " + s.getFuelPercent() + "%");
    }

    private void consumePlayerItems(Player p, int count) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null) return;
        int left = hand.getAmount() - count;
        if (left <= 0) p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else hand.setAmount(left);
    }

    private Integer minutesForFuel(Material mat, ItemStack stack, CustomHeatSource s) {
        String name = mat.name();

        if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
            if (mat == Material.MAGMA_BLOCK) return 10;
            if (mat == Material.COAL_BLOCK) return 40;
            if (mat == Material.COAL || mat == Material.CHARCOAL) return 4;
            if (name.endsWith("_LOG") || name.endsWith("_WOOD") || (name.startsWith("STRIPPED_") && (name.contains("_LOG") || name.contains("_WOOD")))) return 4;
            if (mat == Material.CRAFTING_TABLE) return 4;
            if (name.endsWith("_DOOR") && mat != Material.IRON_DOOR && mat != Material.COPPER_DOOR) return 3;
            if (name.endsWith("_SIGN")) return 1;
            if (name.endsWith("_PLANKS")) return 1;
            for (String w : new String[]{"OAK","SPRUCE","BIRCH","JUNGLE","ACACIA","DARK_OAK","MANGROVE","CHERRY"}) {
                if (name.equals(w + "_SLAB")) return 1;
            }
            if (mat == Material.STICK) return 1;
            return null;
        }

        Integer base = null;
        if (mat == Material.COAL_BLOCK) base = 81;
        else if (mat == Material.COAL || mat == Material.CHARCOAL) base = 9;
        else if (name.endsWith("_LOG") || name.endsWith("_WOOD") || (name.startsWith("STRIPPED_") && (name.contains("_LOG") || name.contains("_WOOD")))) base = 9;
        else if (mat == Material.CRAFTING_TABLE) base = 8;
        else if (name.endsWith("_DOOR") && mat != Material.IRON_DOOR && mat != Material.COPPER_DOOR) base = 6;
        else if (name.endsWith("_SIGN")) base = 4;
        else if (name.endsWith("_PLANKS")) base = 2;
        else {
            for (String w : new String[]{"OAK","SPRUCE","BIRCH","JUNGLE","ACACIA","DARK_OAK","MANGROVE","CHERRY"}) {
                if (name.equals(w + "_SLAB")) { base = 1; break; }
            }
            if (mat == Material.STICK) base = 1;
        }

        if (base == null) return null;
        int reduced = (int) Math.floor(base * 0.75);
        reduced = Math.max(1, reduced);
        return reduced;
    }

    public boolean isLocationInCustomHeat(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        for (CustomHeatSource s : sources.values()) {
            if (!s.isActive()) continue;
            Block cBlock = getSourceBlock(s);
            if (cBlock == null) continue;
            Location c = cBlock.getLocation();
            if (!c.getWorld().equals(loc.getWorld())) continue;

            if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
                Block above = cBlock.getRelative(0,1,0);
                if (above == null || above.getType() != Material.WATER) continue;
            }

            int dx = Math.abs(loc.getBlockX() - c.getBlockX());
            int dy = Math.abs(loc.getBlockY() - c.getBlockY());
            int dz = Math.abs(loc.getBlockZ() - c.getBlockZ());
            if (dx <= s.getRadius() && dy <= s.getRadius() && dz <= s.getRadius()) return true;
        }
        return false;
    }

    private void updateVisuals(CustomHeatSource s) {
        Block b = getSourceBlock(s);
        if (b != null) {
            try {
                if (CustomHeatSource.TYPE_SEA_HEATER.equals(s.getType())) {
                    Block above = b.getRelative(0,1,0);
                    boolean activeEffective = (s.isActive() && above != null && above.getType() == Material.WATER);
                    if (activeEffective) b.setType(Material.SEA_LANTERN, false);
                    else b.setType(Material.COAL_BLOCK, false);
                } else {
                    if (s.isActive()) b.setType(Material.SHROOMLIGHT, false);
                    else b.setType(Material.COAL_BLOCK, false);
                }
            } catch (Throwable ignored) {}
        }
        spawnOrUpdateHolograms(s);
        try { purgeSavedCustomSourcesAt(s.getBlockLocation()); } catch (Throwable ignored) {}
        dataManager.addSavedCustomSource(s);
    }

    public void onDisable() {
        if (tickTask != null) {
            try { Bukkit.getScheduler().cancelTask(tickTask.getTaskId()); } catch (Throwable ignored) {}
            tickTask = null;
        }

        try {
            for (CustomHeatSource s : sources.values()) {
                try { purgeSavedCustomSourcesAt(s.getBlockLocation()); } catch (Throwable ignored) {}
            }
            for (CustomHeatSource s : sources.values()) {
                try { dataManager.addSavedCustomSource(s); } catch (Throwable ignored) {}
            }
            dataManager.saveAll();
        } catch (Throwable t) {
            plugin.getLogger().warning("Ошибка при сохранении данных CustomHeatManager в onDisable: " + t);
        }

        for (CustomHeatSource s : sources.values()) {
            try { despawnHolograms(s); } catch (Throwable ignored) {}
        }
    }
}
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CustomHeatManager — управляет кастомными источниками тепла.
 *
 * Исправления и улучшения:
 * - Устранение дублирования hologram'ов (использован PDC-ключ для пометки ArmorStand)
 * - При попытке класть топливо в полный источник — сообщение и топливо не тратится
 * - Shift+клик — расходуется ровно столько предметов, сколько помещается до 100%
 * - Стабильная замена блока SHROOMLIGHT <-> COAL_BLOCK по координате блока
 * - Немедленное сохранение при удалении (чтобы не восстанавливались после рестарта)
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
    private static final int MAX_MELTS_PER_RUN = 64;

    public CustomHeatManager(TheGreatColdPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.pdcKey = new NamespacedKey(plugin, "heater_type");
        this.holoKey = new NamespacedKey(plugin, "thegreatcold_hologram");

        Bukkit.getPluginManager().registerEvents(this, plugin);

        loadFromDataManager();

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 20L);
    }

    private String keyFor(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void loadFromDataManager() {
        var saved = dataManager.getSavedCustomSources();
        for (var dto : saved) {
            try {
                String type = dto.get("type");
                String world = dto.get("world");
                int x = Integer.parseInt(dto.get("x"));
                int y = Integer.parseInt(dto.get("y"));
                int z = Integer.parseInt(dto.get("z"));
                long fuel = Long.parseLong(dto.getOrDefault("fuelMillis", "0"));
                World w = Bukkit.getWorld(world);
                if (w == null) continue;
                Location blockLoc = new Location(w, x, y, z);
                if (CustomHeatSource.TYPE_SMALL_HEATER.equals(type)) {
                    long max = 20L * 60L * 60L * 1000L;
                    CustomHeatSource s = new CustomHeatSource(type, blockLoc, 15, max, fuel);
                    sources.put(keyFor(blockLoc), s);
                    // ensure block state corresponds to active/inactive if chunk loaded
                    Block b = getSourceBlock(s);
                    if (b != null) {
                        if (s.isActive()) b.setType(Material.SHROOMLIGHT, false);
                        else b.setType(Material.COAL_BLOCK, false);
                    }
                    spawnHologramsIfNeeded(s);
                }
            } catch (Throwable ignored) {}
        }
    }

    public Collection<CustomHeatSource> getAllSources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    public boolean hasSourceAt(Location loc) {
        return sources.containsKey(keyFor(loc));
    }

    public CustomHeatSource getSourceAt(Location loc) {
        return sources.get(keyFor(loc));
    }

    public void createSourceAt(Location blockLoc, String type) {
        if (!blockLoc.getWorld().getName().equals("world")) return;
        if (hasSourceAt(blockLoc)) return;
        if (CustomHeatSource.TYPE_SMALL_HEATER.equals(type)) {
            long max = 20L * 60L * 60L * 1000L;
            CustomHeatSource s = new CustomHeatSource(type, blockLoc, 15, max, 0L);
            sources.put(keyFor(blockLoc), s);
            dataManager.addSavedCustomSource(s);
            Block b = getSourceBlock(s);
            if (b != null) b.setType(Material.COAL_BLOCK, false);
            spawnHologramsIfNeeded(s);
            dataManager.saveAll(); // persist creation immediately (infrequent)
        }
    }

    public void removeSourceAt(Location blockLoc) {
        String key = keyFor(blockLoc);
        CustomHeatSource s = sources.remove(key);
        if (s != null) {
            despawnHolograms(s);
            dataManager.removeSavedCustomSource(s.getBlockLocation());
            Block b = getSourceBlock(s);
            if (b != null) {
                try { b.setType(Material.AIR, false); } catch (Throwable ignored) {}
            }
            dataManager.saveAll(); // persist deletion immediately to avoid restore after restart
        }
    }

    private void tickAll() {
        tickCounter++;
        long dt = 1000L;
        boolean anyChanged = false;

        for (CustomHeatSource s : sources.values()) {
            boolean stateChanged = s.tick(dt);
            if (stateChanged) {
                anyChanged = true;
                try { updateVisualOnStateChange(s); } catch (Throwable ignored) {}
            }

            if (s.isActive()) spawnParticles(s);

            if (s.isActive() && (tickCounter % MELT_INTERVAL_TICKS == 0)) {
                try { meltSnowAndIceInLoadedChunks(s); } catch (Throwable t) { plugin.getLogger().warning("Ошибка melt: " + t); }
            }

            if (stateChanged || s.getNameLineEntity() != null || s.getFuelLineEntity() != null) {
                try { spawnOrUpdateHolograms(s); } catch (Throwable ignored) {}
            }

            if (stateChanged) {
                try { dataManager.addSavedCustomSource(s); } catch (Throwable ignored) {}
            }
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
            if (s.isActive()) b.setType(Material.SHROOMLIGHT, false);
            else b.setType(Material.COAL_BLOCK, false);
        } catch (Throwable ignored) {}
    }

    // ---------------- Holograms (no duplication) ----------------
    private void spawnHologramsIfNeeded(CustomHeatSource s) {
        Block block = getSourceBlock(s);
        if (block == null) return;
        spawnOrUpdateHolograms(s);
    }

    /**
     * Создаёт/переиспользует ArmorStand'ы для name и fuel.
     * Решение дублирования: помечаем сущности в PDC (holoKey). Ищем nearby и переиспользуем,
     * удаляя лишние.
     */
    private void spawnOrUpdateHolograms(CustomHeatSource s) {
        Block block = getSourceBlock(s);
        if (block == null) return;

        // cleanup invalid refs
        if (s.getNameLineEntity() != null && !s.getNameLineEntity().isValid()) s.setNameLineEntity(null);
        if (s.getFuelLineEntity() != null && !s.getFuelLineEntity().isValid()) s.setFuelLineEntity(null);

        // compute positions
        double nameY = block.getY() + 1.00;
        double fuelY = block.getY() + 0.65;
        World w = block.getWorld();
        String sourceKey = keyFor(block.getLocation());

        // --- find or create name ArmorStand ---
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
                    as.setCustomName(s.getDisplayName());
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
                nameAS.setCustomName(s.getDisplayName());
                nameAS.teleport(new Location(w, block.getX() + 0.5, nameY, block.getZ() + 0.5));
            } catch (Throwable ignored) {}
        }

        // --- find or create fuel ArmorStand ---
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

        // ensure tags
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

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent ev) {
        for (CustomHeatSource s : sources.values()) {
            Block b = getSourceBlock(s);
            if (b == null) continue;
            if (b.getChunk().equals(ev.getChunk())) {
                updateVisualOnStateChange(s);
                spawnHologramsIfNeeded(s);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent ev) {
        for (CustomHeatSource s : sources.values()) {
            Block b = getSourceBlock(s);
            if (b == null) continue;
            if (b.getChunk().equals(ev.getChunk())) {
                s.setNameLineEntity(null);
                s.setFuelLineEntity(null);
            }
        }
    }

    private void spawnParticles(CustomHeatSource s) {
        try {
            Block b = getSourceBlock(s);
            if (b == null) return;
            Location c = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.9, b.getZ() + 0.5);
            b.getWorld().spawnParticle(Particle.FLAME, c, 6, 0.3, 0.2, 0.3, 0.01);
        } catch (Throwable ignored) {}
    }

    private void meltSnowAndIceInLoadedChunks(CustomHeatSource s) {
        Block src = getSourceBlock(s);
        if (src == null) return;
        Location center = src.getLocation();
        int r = s.getRadius();
        World w = center.getWorld();
        int minX = center.getBlockX() - r;
        int maxX = center.getBlockX() + r;
        int minZ = center.getBlockZ() - r;
        int maxZ = center.getBlockZ() + r;
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);

        int melted = 0;

        outer:
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!w.isChunkLoaded(cx, cz)) continue;
                for (int lx = 0; lx < 16; lx++) {
                    int worldX = cx * 16 + lx;
                    if (worldX < minX || worldX > maxX) continue;
                    for (int lz = 0; lz < 16; lz++) {
                        int worldZ = cz * 16 + lz;
                        if (worldZ < minZ || worldZ > maxZ) continue;
                        int topY = w.getHighestBlockYAt(worldX, worldZ);
                        int fromY = Math.max(w.getMinHeight(), topY - 64);
                        for (int y = topY; y >= fromY; y--) {
                            Block b = w.getBlockAt(worldX, y, worldZ);
                            Material mt = b.getType();
                            if (mt == Material.SNOW || mt == Material.SNOW_BLOCK) {
                                b.setType(Material.AIR, false);
                                melted++;
                                break;
                            } else if (mt == Material.ICE) {
                                b.setType(Material.WATER, false);
                                melted++;
                                break;
                            }
                        }
                        if (melted >= MAX_MELTS_PER_RUN) break outer;
                    }
                }
            }
        }
    }

    // ---------------- Placement and interaction ----------------
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

        Integer minutes = minutesForFuel(inHand.getType(), inHand);
        if (minutes == null) return;
        ev.setCancelled(true);

        long perItemMillis = minutes * 60L * 1000L;
        long spaceLeft = s.getMaxFuelMillis() - s.getFuelMillis();

        // If already full -> message and do not consume
        if (spaceLeft <= 0) {
            p.sendMessage("§cТопливо больше не вмещается!");
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.2f);
            return;
        }

        boolean sneak = p.isSneaking();
        int requested = sneak ? inHand.getAmount() : 1;

        // compute how many whole items fit
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

        // update visuals and persist (throttled by tickAll, but we update in-memory and visuals now)
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

    private Integer minutesForFuel(Material mat, ItemStack stack) {
        String name = mat.name();
        if (mat == Material.COAL_BLOCK) return 81;
        if (mat == Material.COAL || mat == Material.CHARCOAL) return 9;
        if (name.endsWith("_LOG") || name.endsWith("_WOOD") || (name.startsWith("STRIPPED_") && (name.contains("_LOG") || name.contains("_WOOD")))) return 9;
        if (mat == Material.CRAFTING_TABLE) return 8;
        if (name.endsWith("_DOOR") && mat != Material.IRON_DOOR && mat != Material.COPPER_DOOR) return 6;
        if (name.endsWith("_SIGN")) return 4;
        if (name.endsWith("_PLANKS")) return 2;
        String[] woods = {"OAK","SPRUCE","BIRCH","JUNGLE","ACACIA","DARK_OAK","MANGROVE","CHERRY"};
        if (name.endsWith("_SLAB")) { for (String w : woods) if (name.startsWith(w + "_")) return 1; }
        if (mat == Material.STICK) return 1;
        return null;
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

    public boolean isLocationInCustomHeat(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        for (CustomHeatSource s : sources.values()) {
            if (!s.isActive()) continue;
            Block cBlock = getSourceBlock(s);
            if (cBlock == null) continue;
            Location c = cBlock.getLocation();
            if (!c.getWorld().equals(loc.getWorld())) continue;
            int dx = Math.abs(loc.getBlockX() - c.getBlockX());
            int dy = Math.abs(loc.getBlockY() - c.getBlockY());
            int dz = Math.abs(loc.getBlockZ() - c.getBlockZ());
            if (dx <= s.getRadius() && dy <= s.getRadius() && dz <= s.getRadius()) return true;
        }
        return false;
    }

    // Обновление визуалов и запись в память (не обязательно немедленное сохранение на диск)
    private void updateVisuals(CustomHeatSource s) {
        Block b = getSourceBlock(s);
        if (b != null) {
            try {
                if (s.isActive()) b.setType(Material.SHROOMLIGHT, false);
                else b.setType(Material.COAL_BLOCK, false);
            } catch (Throwable ignored) {}
        }
        // обновить holograms
        spawnOrUpdateHolograms(s);
        // обновить in-memory DTO
        dataManager.addSavedCustomSource(s);
    }
}
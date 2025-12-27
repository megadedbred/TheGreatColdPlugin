package me.megadedbeb.thegreatcold.data;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.heat.HeatSourceRegion;
import me.megadedbeb.thegreatcold.heat.HeatSourceType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class DataManager {
    private final TheGreatColdPlugin plugin;
    private final File dataFile;
    private final YamlConfiguration config;
    private int currentStageId = 0;
    private long stageEndMillis = 0;
    private boolean autoStageFlag = false;
    private boolean stageInfiniteFlag = false;
    private final Map<UUID, PlayerFreezeData> playerData = new HashMap<>();

    // сохранённые источники тепла (ключ world:x:y:z)
    private final Map<String, HeatSourceRegion> savedHeatSources = new HashMap<>();

    public DataManager(TheGreatColdPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException ignored) {}
        }
        this.config = YamlConfiguration.loadConfiguration(dataFile);
        loadAll();
    }

    public void loadAll() {
        playerData.clear();
        savedHeatSources.clear();

        currentStageId = config.getInt("stage.id", 0);
        stageEndMillis = config.getLong("stage.endMillis", 0);
        autoStageFlag = config.getBoolean("stage.auto", false);
        stageInfiniteFlag = config.getBoolean("stage.infinite", false);

        if (config.isConfigurationSection("players")) {
            for (String s : config.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(s);
                    int stage = config.getInt("players." + s + ".freezeStage", 0);
                    long inHeat = config.getLong("players." + s + ".timeInHeat", 0L);
                    long withoutHeat = config.getLong("players." + s + ".timeWithoutHeat", 0L);
                    long dmgAcc = config.getLong("players." + s + ".damageAccumulatorMs", 0L);
                    boolean heat = config.getBoolean("players." + s + ".inHeat", false);
                    PlayerFreezeData data = new PlayerFreezeData(uuid);
                    data.setFreezeStage(me.megadedbeb.thegreatcold.freeze.FreezeStage.fromId(stage));
                    data.setTimeInHeat(inHeat);
                    data.setTimeWithoutHeat(withoutHeat);
                    data.setDamageAccumulatorMs(dmgAcc);
                    data.setInHeat(heat);
                    playerData.put(uuid, data);
                } catch (Exception ignored) {}
            }
        }

        if (config.isConfigurationSection("heat_sources")) {
            for (String key : config.getConfigurationSection("heat_sources").getKeys(false)) {
                try {
                    String world = config.getString("heat_sources." + key + ".world");
                    int x = config.getInt("heat_sources." + key + ".x");
                    int y = config.getInt("heat_sources." + key + ".y");
                    int z = config.getInt("heat_sources." + key + ".z");
                    String typeName = config.getString("heat_sources." + key + ".type");
                    int radius = config.getInt("heat_sources." + key + ".radius", 1);
                    if (world == null) continue;
                    var w = Bukkit.getWorld(world);
                    if (w == null) continue;
                    Location loc = new Location(w, x, y, z);
                    HeatSourceType t = HeatSourceType.valueOf(typeName);
                    HeatSourceRegion region = new HeatSourceRegion(loc, t, radius);
                    savedHeatSources.put(key, region);
                } catch (Exception ignored) {}
            }
        }
    }

    public void saveAll() {
        config.set("stage.id", currentStageId);
        config.set("stage.endMillis", stageEndMillis);
        config.set("stage.auto", autoStageFlag);
        config.set("stage.infinite", stageInfiniteFlag);

        if (playerData.isEmpty()) {
            config.set("players", null);
        } else {
            for (Map.Entry<UUID, PlayerFreezeData> e : playerData.entrySet()) {
                String k = e.getKey().toString();
                PlayerFreezeData d = e.getValue();
                config.set("players." + k + ".freezeStage", d.getFreezeStage().id());
                config.set("players." + k + ".timeInHeat", d.getTimeInHeat());
                config.set("players." + k + ".timeWithoutHeat", d.getTimeWithoutHeat());
                config.set("players." + k + ".damageAccumulatorMs", d.getDamageAccumulatorMs());
                config.set("players." + k + ".inHeat", d.isInHeat());
            }
        }

        if (savedHeatSources.isEmpty()) {
            config.set("heat_sources", null);
        } else {
            for (Map.Entry<String, HeatSourceRegion> en : savedHeatSources.entrySet()) {
                String key = en.getKey();
                HeatSourceRegion r = en.getValue();
                Location c = r.getCenter();
                config.set("heat_sources." + key + ".world", c.getWorld().getName());
                config.set("heat_sources." + key + ".x", c.getBlockX());
                config.set("heat_sources." + key + ".y", c.getBlockY());
                config.set("heat_sources." + key + ".z", c.getBlockZ());
                config.set("heat_sources." + key + ".type", r.getType().name());
                config.set("heat_sources." + key + ".radius", r.getRadius());
            }
        }

        try {
            config.save(dataFile);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public int getCurrentStageId() { return currentStageId; }
    public long getStageEndMillis() { return stageEndMillis; }
    public boolean getAutoStageFlag() { return autoStageFlag; }
    public boolean getStageInfiniteFlag() { return stageInfiniteFlag; }
    public void setCurrentStage(int i) { this.currentStageId = i; }
    public void setStageEndMillis(long ms) { this.stageEndMillis = ms; }
    public void setAutoStageFlag(boolean flag) { this.autoStageFlag = flag; }
    public void setStageInfiniteFlag(boolean flag) { this.stageInfiniteFlag = flag; }

    public PlayerFreezeData getPlayerData(UUID uuid) {
        return playerData.computeIfAbsent(uuid, PlayerFreezeData::new);
    }
    public void setPlayerData(UUID uuid, PlayerFreezeData data) {
        playerData.put(uuid, data);
    }
    public Map<UUID, PlayerFreezeData> getAllPlayerData() { return playerData; }

    // heat sources persistence API
    public Collection<HeatSourceRegion> getSavedHeatSources() {
        return Collections.unmodifiableCollection(savedHeatSources.values());
    }

    public void addSavedHeatSource(HeatSourceRegion region) {
        String key = makeKey(region.getCenter());
        savedHeatSources.put(key, region);
    }

    public void removeSavedHeatSourceByLocation(Location loc) {
        String key = makeKey(loc);
        savedHeatSources.remove(key);
    }

    public void clearSavedHeatSources() {
        savedHeatSources.clear();
    }

    private String makeKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
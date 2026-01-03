package me.megadedbeb.thegreatcold.data;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.heat.HeatSourceRegion;
import me.megadedbeb.thegreatcold.heat.HeatSourceType;
import me.megadedbeb.thegreatcold.heat.CustomHeatSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.World;

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
    // NOTE: natural heat_sources are no longer persisted; this map kept for in-memory usage if needed
    private final Map<String, HeatSourceRegion> savedHeatSources = new HashMap<>();

    // сохранённые кастомные источники: key -> map(type, world, x, y, z, fuelMillis)
    private final Map<String, Map<String, String>> savedCustomSources = new HashMap<>();

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
        savedCustomSources.clear();

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

                    // Новые поля для подземной механики
                    long timeBelowY = config.getLong("players." + s + ".timeBelowY", 0L);
                    long timeAboveY = config.getLong("players." + s + ".timeAboveY", 0L);
                    boolean underground = config.getBoolean("players." + s + ".undergroundMode", false);

                    PlayerFreezeData data = new PlayerFreezeData(uuid);
                    data.setFreezeStage(me.megadedbeb.thegreatcold.freeze.FreezeStage.fromId(stage));
                    data.setTimeInHeat(inHeat);
                    data.setTimeWithoutHeat(withoutHeat);
                    data.setDamageAccumulatorMs(dmgAcc);
                    data.setInHeat(heat);

                    // применяем загруженные подземные данные
                    data.setTimeBelowY(timeBelowY);
                    data.setTimeAboveY(timeAboveY);
                    data.setUndergroundMode(underground);

                    playerData.put(uuid, data);
                } catch (Exception ignored) {}
            }
        }

        // NOTE: we intentionally do NOT read "heat_sources" from config any more.
        // Natural heat sources are discovered at runtime (dynamic), only custom_sources are persisted.

        if (config.isConfigurationSection("custom_sources")) {
            for (String key : config.getConfigurationSection("custom_sources").getKeys(false)) {
                try {
                    String type = config.getString("custom_sources." + key + ".type");
                    String world = config.getString("custom_sources." + key + ".world");
                    int x = config.getInt("custom_sources." + key + ".x");
                    int y = config.getInt("custom_sources." + key + ".y");
                    int z = config.getInt("custom_sources." + key + ".z");
                    long fuel = config.getLong("custom_sources." + key + ".fuelMillis", 0L);
                    if (type == null || world == null) continue;
                    Map<String, String> map = new HashMap<>();
                    map.put("type", type);
                    map.put("world", world);
                    map.put("x", String.valueOf(x));
                    map.put("y", String.valueOf(y));
                    map.put("z", String.valueOf(z));
                    map.put("fuelMillis", String.valueOf(fuel));
                    savedCustomSources.put(key, map);
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

                // Сохраняем новые поля для подземной логики
                config.set("players." + k + ".timeBelowY", d.getTimeBelowY());
                config.set("players." + k + ".timeAboveY", d.getTimeAboveY());
                config.set("players." + k + ".undergroundMode", d.isUndergroundMode());
            }
        }

        // Important: do NOT persist natural heat_sources. Keep only custom_sources persisted.
        config.set("heat_sources", null);

        if (savedCustomSources.isEmpty()) {
            config.set("custom_sources", null);
        } else {
            for (Map.Entry<String, Map<String, String>> en : savedCustomSources.entrySet()) {
                String key = en.getKey();
                Map<String, String> m = en.getValue();
                config.set("custom_sources." + key + ".type", m.get("type"));
                config.set("custom_sources." + key + ".world", m.get("world"));
                config.set("custom_sources." + key + ".x", Integer.parseInt(m.get("x")));
                config.set("custom_sources." + key + ".y", Integer.parseInt(m.get("y")));
                config.set("custom_sources." + key + ".z", Integer.parseInt(m.get("z")));
                config.set("custom_sources." + key + ".fuelMillis", Long.parseLong(m.getOrDefault("fuelMillis","0")));
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

    // heat sources persistence API (kept for compatibility but natural sources are intentionally not persisted)
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

    // custom sources persistence API
    public Collection<Map<String, String>> getSavedCustomSources() {
        return Collections.unmodifiableCollection(savedCustomSources.values());
    }

    public void addSavedCustomSource(CustomHeatSource s) {
        Location c = s.getBlockLocation(); // use exact block location
        String key = makeKey(c);
        Map<String, String> m = new HashMap<>();
        m.put("type", s.getType());
        m.put("world", c.getWorld().getName());
        m.put("x", String.valueOf(c.getBlockX()));
        m.put("y", String.valueOf(c.getBlockY()));
        m.put("z", String.valueOf(c.getBlockZ()));
        m.put("fuelMillis", String.valueOf(s.getFuelMillis()));
        savedCustomSources.put(key, m);
    }

    public void removeSavedCustomSource(Location loc) {
        String key = makeKey(loc);
        savedCustomSources.remove(key);
    }

    public void clearSavedCustomSources() {
        savedCustomSources.clear();
    }

    private String makeKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
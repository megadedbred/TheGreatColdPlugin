package me.megadedbeb.thegreatcold;

import me.megadedbeb.thegreatcold.command.GreatColdCommandExecutor;
import me.megadedbeb.thegreatcold.command.GreatColdTabCompleter;
import me.megadedbeb.thegreatcold.config.ConfigManager;
import me.megadedbeb.thegreatcold.data.DataManager;
import me.megadedbeb.thegreatcold.freeze.FreezeManager;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.heat.CustomHeatManager;
import me.megadedbeb.thegreatcold.stage.StageManager;
import me.megadedbeb.thegreatcold.listener.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class TheGreatColdPlugin extends JavaPlugin {
    private static TheGreatColdPlugin instance;
    private ConfigManager configManager;
    private DataManager dataManager;
    private StageManager stageManager;
    private FreezeManager freezeManager;
    private HeatSourceManager heatSourceManager;
    private CustomHeatManager customHeatManager;

    @Override
    public void onEnable() {
        instance = this;

        // Менеджеры
        configManager = new ConfigManager(this);
        dataManager = new DataManager(this);

        // custom heat manager first (it registers its own listeners)
        customHeatManager = new CustomHeatManager(this, dataManager);

        heatSourceManager = new HeatSourceManager(this, dataManager, customHeatManager);
        stageManager = new StageManager(this, configManager, dataManager, heatSourceManager);
        freezeManager = new FreezeManager(this, configManager, dataManager, heatSourceManager, stageManager);

        // Лисенеры — регистрируем все используемые слушатели
        // CustomHeatManager & HeatSourceManager уже регистрируются внутри своих конструкторов
        Bukkit.getPluginManager().registerEvents(new EntitySpawnListener(stageManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerMoveListener(freezeManager, heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new BedTradeListener(heatSourceManager, freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new BlockHeatListener(heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new CropListener(heatSourceManager), this); // обработка роста культур и костной муки

        // SleepListener будит спящих на этапе 3, но не отменяет сон глобально
        Bukkit.getPluginManager().registerEvents(new SleepListener(heatSourceManager), this);

        // Новая логика: запрет создания порталов
        Bukkit.getPluginManager().registerEvents(new me.megadedbeb.thegreatcold.listener.PortalCreateListener(), this);

        // Команды
        GreatColdCommandExecutor commandExecutor = new GreatColdCommandExecutor(stageManager, freezeManager, configManager, dataManager);
        if (getCommand("greatcold") != null) {
            getCommand("greatcold").setExecutor(commandExecutor);
            getCommand("greatcold").setTabCompleter(new GreatColdTabCompleter());
        } else {
            getLogger().warning("Команда greatcold не зарегистрирована в plugin.yml");
        }

        // Register recipe for small heater
        registerSmallHeaterRecipe();

        // Автостарт 0 этапа если требуется (если этап не задан в данных)
        if (!stageManager.isStageSet()) {
            stageManager.startStage(0, false);
        }

        stageManager.startAutoStageIfEnabled();
    }

    private void registerSmallHeaterRecipe() {
        ItemStack result = new ItemStack(Material.SHROOMLIGHT);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            NamespacedKey key = new NamespacedKey(this, "heater_type");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "small_heater");
            meta.setDisplayName(org.bukkit.ChatColor.GOLD + "Небольшой обогреватель");
            meta.setLore(List.of("§7Обогревает небольшую зону, но и требует не много энергии.", "§7Неразрушаемый."));
            result.setItemMeta(meta);
        }

        NamespacedKey rkey = new NamespacedKey(this, "small_heater");
        ShapedRecipe recipe = new ShapedRecipe(rkey, result);
        recipe.shape("AAA", "BFB", "CMC");
        recipe.setIngredient('A', Material.AMETHYST_SHARD);
        recipe.setIngredient('B', Material.BRICK);
        recipe.setIngredient('F', Material.BLAST_FURNACE);
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('M', Material.MAGMA_BLOCK);

        Bukkit.addRecipe(recipe);
    }

    @Override
    public void onDisable() {
        // безопасные проверки: некоторые менеджеры могут быть null, если onEnable прервался
        if (dataManager != null) dataManager.saveAll();
        if (stageManager != null) stageManager.onDisable();
        if (freezeManager != null) freezeManager.onDisable();
    }

    public static TheGreatColdPlugin getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public DataManager getDataManager() { return dataManager; }
    public StageManager getStageManager() { return stageManager; }
    public FreezeManager getFreezeManager() { return freezeManager; }
    public HeatSourceManager getHeatSourceManager() { return heatSourceManager; }
    public CustomHeatManager getCustomHeatManager() { return customHeatManager; }
}

package me.megadedbeb.thegreatcold;

import me.megadedbeb.thegreatcold.command.GreatColdCommandExecutor;
import me.megadedbeb.thegreatcold.command.GreatColdTabCompleter;
import me.megadedbeb.thegreatcold.config.ConfigManager;
import me.megadedbeb.thegreatcold.data.DataManager;
import me.megadedbeb.thegreatcold.freeze.FreezeManager;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.stage.StageManager;
import me.megadedbeb.thegreatcold.listener.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class TheGreatColdPlugin extends JavaPlugin {
    private static TheGreatColdPlugin instance;
    private ConfigManager configManager;
    private DataManager dataManager;
    private StageManager stageManager;
    private FreezeManager freezeManager;
    private HeatSourceManager heatSourceManager;

    @Override
    public void onEnable() {
        instance = this;

        // Менеджеры
        configManager = new ConfigManager(this);
        dataManager = new DataManager(this);
        heatSourceManager = new HeatSourceManager(this, dataManager);
        stageManager = new StageManager(this, configManager, dataManager, heatSourceManager);
        freezeManager = new FreezeManager(this, configManager, dataManager, heatSourceManager, stageManager);

        // Лисенеры — регистрируем все используемые слушатели
        // HeatSourceManager уже регистрируется внутри своего конструктора
        Bukkit.getPluginManager().registerEvents(new EntitySpawnListener(stageManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerMoveListener(freezeManager, heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new BedTradeListener(heatSourceManager, freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new BlockHeatListener(heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new CropListener(heatSourceManager), this); // обработка роста культур и костной муки

        // SleepListener будит спящих на этапе 3, но не отменяет сон глобально
        Bukkit.getPluginManager().registerEvents(new SleepListener(heatSourceManager), this);

        // Команды
        GreatColdCommandExecutor commandExecutor = new GreatColdCommandExecutor(stageManager, freezeManager, configManager, dataManager);
        if (getCommand("greatcold") != null) {
            getCommand("greatcold").setExecutor(commandExecutor);
            getCommand("greatcold").setTabCompleter(new GreatColdTabCompleter());
        } else {
            getLogger().warning("Команда greatcold не зарегистрирована в plugin.yml");
        }

        // Автостарт 0 этапа если требуется (если этап не задан в данных)
        if (!stageManager.isStageSet()) {
            stageManager.startStage(0, false);
        }

        stageManager.startAutoStageIfEnabled();
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
}
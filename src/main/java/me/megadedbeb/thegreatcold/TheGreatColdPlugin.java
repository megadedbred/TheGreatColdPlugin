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
import me.megadedbeb.thegreatcold.listener.HeatedHatListener;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public class TheGreatColdPlugin extends JavaPlugin {
    private static TheGreatColdPlugin instance;
    private ConfigManager configManager;
    private DataManager dataManager;
    private StageManager stageManager;
    private FreezeManager freezeManager;
    private HeatSourceManager heatSourceManager;
    private CustomHeatManager customHeatManager;

    // key to mark Heated Hat
    private NamespacedKey heatedHatKey;
    private NamespacedKey heatedHatDurKey;

    public static final int HEATED_HAT_MAX_DUR = 200; // final durability

    @Override
    public void onEnable() {
        instance = this;

        heatedHatKey = new NamespacedKey(this, "heated_hat");
        heatedHatDurKey = new NamespacedKey(this, "heated_hat_dur");

        configManager = new ConfigManager(this);
        dataManager = new DataManager(this);

        customHeatManager = new CustomHeatManager(this, dataManager);

        heatSourceManager = new HeatSourceManager(this, dataManager, customHeatManager);
        stageManager = new StageManager(this, configManager, dataManager, heatSourceManager);
        // create freeze manager after stage manager (order)
        this.freezeManager = new FreezeManager(this, configManager, dataManager, heatSourceManager, stageManager);

        // register listeners
        Bukkit.getPluginManager().registerEvents(new EntitySpawnListener(stageManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerMoveListener(freezeManager, heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new BedTradeListener(heatSourceManager, freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new BlockHeatListener(heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new CropListener(heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new SleepListener(heatSourceManager), this);
        Bukkit.getPluginManager().registerEvents(new me.megadedbeb.thegreatcold.listener.PortalCreateListener(), this);
        Bukkit.getPluginManager().registerEvents(new AnimalBreedListener(), this);

        // heated hat listener
        Bukkit.getPluginManager().registerEvents(new HeatedHatListener(this), this);

        // commands
        GreatColdCommandExecutor commandExecutor = new GreatColdCommandExecutor(stageManager, freezeManager, configManager, dataManager, customHeatManager);
        if (getCommand("greatcold") != null) {
            getCommand("greatcold").setExecutor(commandExecutor);
            getCommand("greatcold").setTabCompleter(new GreatColdTabCompleter());
        } else {
            getLogger().warning("Команда greatcold не зарегистрирована в plugin.yml");
        }

        // recipes
        registerSmallHeaterRecipe();
        registerSeaHeaterRecipe();
        registerMegaFurnaceRecipe();
        registerHeatedHatRecipe();

        if (!stageManager.isStageSet()) stageManager.startStage(0, false);
        stageManager.startAutoStageIfEnabled();
    }

    private void registerSmallHeaterRecipe() {
        ItemStack result = new ItemStack(Material.SHROOMLIGHT);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            NamespacedKey key = new NamespacedKey(this, "heater_type");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "small_heater");
            meta.setDisplayName(ChatColor.GOLD + "Небольшой обогреватель");
            meta.setLore(List.of("Обогревает небольшую зону, но и требует " + ChatColor.GOLD + "немного топлива" + ChatColor.RESET + ".",
                    "Нельзя сломать."));
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

    private void registerSeaHeaterRecipe() {
        ItemStack result = new ItemStack(Material.SEA_LANTERN);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            NamespacedKey key = new NamespacedKey(this, "heater_type");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "sea_heater");
            meta.setDisplayName(ChatColor.BLUE + "Морской обогреватель");
            String line1 = "Использует силу " + ChatColor.BLUE + "Морского" + ChatColor.RESET + " " + ChatColor.BLUE + "источника" + ChatColor.RESET + " и создает теплый пар на большой территории,";
            String line2 = "вмещает мало, но " + ChatColor.GOLD + "расходует много топлива" + ChatColor.RESET + ". Нельзя сломать.";
            meta.setLore(List.of(line1, line2));
            result.setItemMeta(meta);
        }
        NamespacedKey rkey = new NamespacedKey(this, "sea_heater");
        ShapedRecipe recipe = new ShapedRecipe(rkey, result);
        recipe.shape("ABA", "CDC", "EFE");
        recipe.setIngredient('A', Material.PRISMARINE_SHARD);
        recipe.setIngredient('B', Material.BUCKET);
        recipe.setIngredient('C', Material.PRISMARINE_CRYSTALS);
        recipe.setIngredient('D', Material.CONDUIT);
        recipe.setIngredient('E', Material.TURTLE_SCUTE);
        recipe.setIngredient('F', Material.MAGMA_BLOCK);
        Bukkit.addRecipe(recipe);
    }

    private void registerMegaFurnaceRecipe() {
        ItemStack result = new ItemStack(Material.MAGMA_BLOCK);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            NamespacedKey key = new NamespacedKey(this, "heater_type");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "mega_furnace");
            meta.setDisplayName(ChatColor.RED + "Мегапечь");
            String line1 = "Распространяет " + ChatColor.RED + "адский жар" + ChatColor.RESET + " на огромную территорию,";
            String line2 = "вмещает и требует " + ChatColor.GOLD + "много топлива" + ChatColor.RESET + ". Нельзя сломать.";
            meta.setLore(List.of(line1, line2));
            result.setItemMeta(meta);
        }
        NamespacedKey rkey = new NamespacedKey(this, "mega_furnace");
        ShapedRecipe recipe = new ShapedRecipe(rkey, result);
        recipe.shape("AMA", "DND", "BEB");
        recipe.setIngredient('A', Material.MAGMA_CREAM);
        recipe.setIngredient('M', Material.GOLD_INGOT);
        recipe.setIngredient('D', Material.DIAMOND_BLOCK);
        recipe.setIngredient('N', Material.NETHER_STAR);
        recipe.setIngredient('B', Material.BLAZE_POWDER);
        recipe.setIngredient('E', Material.LAVA_BUCKET);
        Bukkit.addRecipe(recipe);
    }

    private void registerHeatedHatRecipe() {
        ItemStack result = new ItemStack(Material.COPPER_HELMET);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Шапка с подогревом");
            List<String> lore = new ArrayList<>();
            lore.add("Шапка с подогревом позволяет в несколько раз дольше находиться на холоде!");
            lore.add(ChatColor.GOLD + "Прочность: " + HEATED_HAT_MAX_DUR + "/" + HEATED_HAT_MAX_DUR);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(heatedHatKey, PersistentDataType.STRING, "true");
            meta.getPersistentDataContainer().set(heatedHatDurKey, PersistentDataType.INTEGER, Integer.valueOf(HEATED_HAT_MAX_DUR));
            meta.setUnbreakable(true);
            result.setItemMeta(meta);
        }

        NamespacedKey key = new NamespacedKey(this, "heated_hat");
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(" A ", "ABA", " C ");
        recipe.setIngredient('A', Material.RABBIT_HIDE);
        recipe.setIngredient('B', Material.MAGMA_BLOCK);
        recipe.setIngredient('C', Material.COPPER_HELMET);
        Bukkit.addRecipe(recipe);
    }

    @Override
    public void onDisable() {
        if (customHeatManager != null) {
            try { customHeatManager.onDisable(); } catch (Throwable ignored) {}
        }
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
    public NamespacedKey getHeatedHatKey() { return heatedHatKey; }
    public NamespacedKey getHeatedHatDurKey() { return heatedHatDurKey; }
}
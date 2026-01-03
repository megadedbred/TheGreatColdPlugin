package me.megadedbeb.thegreatcold.command;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.config.ConfigManager;
import me.megadedbeb.thegreatcold.data.DataManager;
import me.megadedbeb.thegreatcold.freeze.FreezeManager;
import me.megadedbeb.thegreatcold.freeze.FreezeStage;
import me.megadedbeb.thegreatcold.stage.StageManager;
import me.megadedbeb.thegreatcold.heat.CustomHeatManager;
import me.megadedbeb.thegreatcold.heat.CustomHeatSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.ChatColor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class GreatColdCommandExecutor implements CommandExecutor {
    private final StageManager stageManager;
    private final FreezeManager freezeManager;
    private final ConfigManager configManager;
    private final DataManager dataManager;
    private final CustomHeatManager customHeatManager;

    public GreatColdCommandExecutor(StageManager s, FreezeManager f, ConfigManager c, DataManager d, CustomHeatManager ch) {
        this.stageManager = s;
        this.freezeManager = f;
        this.configManager = c;
        this.dataManager = d;
        this.customHeatManager = ch;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String lab, String[] args) {
        if (!sender.hasPermission("greatcold.admin")) return true;
        if (args.length == 0) {
            sender.sendMessage("§7Доступные подкоманды: stage, autostage, stageinfo, setperiod, freeze, unfreeze, listheaters, giveheater");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "stage" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /greatcold stage <id>");
                    return true;
                }
                int stage = Integer.parseInt(args[1]);
                stageManager.startStage(stage, true);
                sender.sendMessage("§aУстановлен этап "+stage);
            }
            case "autostage" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /greatcold autostage <on|off>");
                    return true;
                }
                if ("on".equalsIgnoreCase(args[1])) {
                    stageManager.startAutoStage();
                    sender.sendMessage("§aАвтоматическая смена этапов включена");
                } else {
                    stageManager.stopAutoStage();
                    sender.sendMessage("§eАвтоматическая смена этапов отключена");
                }
            }
            case "stageinfo" -> {
                long remain = stageManager.getStageEndMillis() == Long.MAX_VALUE ? -1
                        : (stageManager.getStageEndMillis() - System.currentTimeMillis())/1000;
                sender.sendMessage("§bТекущий этап: " + stageManager.getCurrentStage().id());
                sender.sendMessage("§bДо смены: " + (remain < 0 ? "бесконечно" : remain + " сек."));
                sender.sendMessage("§bБесконечный этап: " + (stageManager.isStageInfinite() ? "да" : "нет"));
            }
            case "setperiod" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cИспользование: /greatcold setperiod <stage> <minutes|infinite>");
                    return true;
                }
                int st = Integer.parseInt(args[1]);
                String val = args[2];
                if ("infinite".equalsIgnoreCase(val) || "inf".equalsIgnoreCase(val)) {
                    if (stageManager.getCurrentStage().id() == st) {
                        stageManager.setStageInfinite(true);
                        sender.sendMessage("§aТекущий этап " + st + " сделан бесконечным.");
                    }
                    dataManager.setStageInfiniteFlag(true);
                    dataManager.saveAll();
                } else {
                    long mins = Long.parseLong(val);
                    long millis = mins * 60L * 1000L;
                    configManager.setStageDuration(st, millis);
                    sender.sendMessage("§aВремя этапа "+st+" теперь "+mins+" мин.");
                    if (stageManager.getCurrentStage().id() == st && !stageManager.isStageInfinite()) {
                        stageManager.setStageInfinite(false);
                        sender.sendMessage("§aОбновлён таймер текущего этапа.");
                    }
                }
            }
            case "freeze" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cИспользование: /greatcold freeze <player> <stageId>");
                    return true;
                }
                Player p = Bukkit.getPlayer(args[1]);
                if (p != null) {
                    int st = Integer.parseInt(args[2]);
                    freezeManager.setStage(p, FreezeStage.fromId(st));
                    sender.sendMessage("§aИгроку "+p.getName()+" выдана стадия "+st);
                } else sender.sendMessage("§cИгрок не найден.");
            }
            case "unfreeze" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /greatcold unfreeze <player>");
                    return true;
                }
                Player p = Bukkit.getPlayer(args[1]);
                if (p != null) {
                    freezeManager.clearFreeze(p);
                    sender.sendMessage("§aОбморожение снято с "+p.getName());
                } else sender.sendMessage("§cИгрок не найден.");
            }
            case "listheaters" -> {
                Collection<CustomHeatSource> list = customHeatManager.getAllSources();
                if (list.isEmpty()) {
                    sender.sendMessage("§eСохранённых источников тепла не найдено.");
                } else {
                    sender.sendMessage("§bСохранённые источники тепла (" + list.size() + "):");
                    int idx = 1;
                    for (CustomHeatSource s : list) {
                        Location loc = s.getBlockLocation();
                        String world = (loc.getWorld() != null) ? loc.getWorld().getName() : "unknown";
                        long fuelMin = s.getFuelMillis() / 60000L;
                        sender.sendMessage("§7" + idx + ") §f" + s.getType()
                                + " §7at §f" + world + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ()
                                + " §7active=" + (s.isActive() ? "yes" : "no")
                                + " §7fuel=" + s.getFuelPercent() + "% (" + fuelMin + " min)");
                        idx++;
                    }
                }
            }
            case "giveheater" -> {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cOnly players can receive items.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /greatcold giveheater <small_heater|sea_heater|mega_furnace>");
                    return true;
                }
                String type = args[1].toLowerCase();
                ItemStack item;
                ItemMeta meta;
                NamespacedKey key = new NamespacedKey(TheGreatColdPlugin.getInstance(), "heater_type");
                if ("small_heater".equals(type) || "small".equals(type)) {
                    item = new ItemStack(Material.SHROOMLIGHT);
                    meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColor.GOLD + "Небольшой обогреватель");
                        meta.setLore(List.of("Обогревает небольшую зону, но и требует " + ChatColor.GOLD + "немного топлива" + ChatColor.RESET + ".",
                                "Нельзя сломать."));
                        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, CustomHeatSource.TYPE_SMALL_HEATER);
                        item.setItemMeta(meta);
                    }
                } else if ("sea_heater".equals(type) || "sea".equals(type)) {
                    item = new ItemStack(Material.SEA_LANTERN);
                    meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColor.BLUE + "Морской обогреватель");
                        meta.setLore(List.of("Использует силу " + ChatColor.BLUE + "Морского" + ChatColor.RESET + " источника, вмещает мало топлива."));
                        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, CustomHeatSource.TYPE_SEA_HEATER);
                        item.setItemMeta(meta);
                    }
                } else if ("mega_furnace".equals(type) || "mega".equals(type)) {
                    item = new ItemStack(Material.MAGMA_BLOCK);
                    meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColor.RED + "Мегапечь");
                        meta.setLore(List.of("Мощная печь, вмещает много топлива."));
                        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, CustomHeatSource.TYPE_MEGA_FURNACE);
                        item.setItemMeta(meta);
                    }
                } else {
                    sender.sendMessage("§cНеизвестный тип: " + type);
                    return true;
                }
                Player p = (Player) sender;
                Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), item);
                }
                p.sendMessage("§aПредмет выдан: " + type);
            }
            default -> sender.sendMessage("§cНеизвестная подкоманда.");
        }
        return true;
    }
}
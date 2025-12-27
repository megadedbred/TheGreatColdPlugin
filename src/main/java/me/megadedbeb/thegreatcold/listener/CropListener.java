package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.util.NmsHelper;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.block.Action;

public class CropListener implements Listener {
    private final HeatSourceManager heatManager;

    public CropListener(HeatSourceManager heatManager) {
        this.heatManager = heatManager;
    }

    private boolean isColdStageActive() {
        int globalStageId = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();
        return (globalStageId == 2 || globalStageId == 3);
    }

    // Натуральный рост
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockGrow(BlockGrowEvent event) {
        if (!isColdStageActive()) return;
        Block b = event.getBlock();
        if (b == null) return;

        // Разрешаем свекле (BEETROOTS) расти всегда
        if (b.getType() == Material.BEETROOTS) return;

        boolean inHeat = heatManager.isLocationInHeat(b.getLocation());
        if (!inHeat) {
            event.setCancelled(true);
        }
    }

    // Рост деревьев (костная мука на саженце -> StructureGrowEvent)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!isColdStageActive()) return;
        if (event.getLocation() == null) return;
        Block b = event.getLocation().getBlock();
        if (b == null) return;

        // Если это свёкла (маловероятно для StructureGrow, но на всякий) — разрешаем.
        if (b.getType() == Material.BEETROOTS) return;

        boolean inHeat = heatManager.isLocationInHeat(b.getLocation());
        if (!inHeat) {
            event.setCancelled(true);
        }
    }

    // Нажатие костной муки (правый клик) — предотвращаем применение (за исключением свеклы)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isColdStageActive()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() == null) return;
        if (event.getItem().getType() != Material.BONE_MEAL) return;
        Block b = event.getClickedBlock();
        if (b == null) return;

        // Если это свёкла — разрешаем
        if (b.getType() == Material.BEETROOTS) return;

        boolean inHeat = heatManager.isLocationInHeat(b.getLocation());
        if (!inHeat) {
            event.setCancelled(true);
            if (event.getPlayer() != null)
                event.getPlayer().sendMessage("§cКультура не растёт — слишком холодно!");
        }
    }

    // Paper/Bukkit событие удобрения — перехватываем "автоматическое" применение костной муки
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        if (!isColdStageActive()) return;
        Block b = event.getBlock();
        if (b == null) return;

        // Если это свёкла — разрешаем
        if (b.getType() == Material.BEETROOTS) return;

        boolean inHeat = heatManager.isLocationInHeat(b.getLocation());
        if (!inHeat) {
            event.setCancelled(true);
        }
    }
}
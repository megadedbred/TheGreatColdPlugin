package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.freeze.FreezeManager;
import me.megadedbeb.thegreatcold.heat.CustomHeatManager;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.util.NmsHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Listener для сна/установки точки респауна и (частично) торговли.
 * Сообщение при попытке установить спавн на этапе 3/2 больше не отправляется игроку (по запросу).
 */
public class BedTradeListener implements Listener {
    private final HeatSourceManager heatManager;
    private final FreezeManager freezeManager;
    private final CustomHeatManager customHeatManager;

    public BedTradeListener(HeatSourceManager heatManager, FreezeManager freezeManager) {
        this.heatManager = heatManager;
        this.freezeManager = freeze_manager_fix(freezeManager);
        this.customHeatManager = TheGreatColdPlugin.getInstance().getCustomHeatManager();
    }

    private FreezeManager freeze_manager_fix(FreezeManager f) { return f; }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals("world")) return;

        int globalStageId = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();

        // Full deny: on stage 3 players cannot sleep at all.
        if (globalStageId == 3) {
            event.setCancelled(true);
            player.sendMessage(TheGreatColdPlugin.getInstance().getConfigManager().getMessage("bed.cant_sleep"));
            return;
        }

        // For stage 2: require heat (older behavior)
        if (globalStageId < 2) return;

        boolean rawInHeat = heatManager.isPlayerInHeat(player);
        boolean inCustom = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(player.getLocation());
        boolean openToSky = NmsHelper.isOpenToSky(player.getLocation().getBlock());
        boolean effectiveInHeat = inCustom || (rawInHeat && !(globalStageId >= 2 && openToSky));

        if (!effectiveInHeat) {
            event.setCancelled(true);
            player.sendMessage(TheGreatColdPlugin.getInstance().getConfigManager().getMessage("bed.cant_sleep"));
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var player = event.getPlayer();
        if (!player.getWorld().getName().equals("world")) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        // Запрет установки точки возрождения (правый клик по кровати)
        if (clicked.getType().name().endsWith("_BED")) {
            int globalStageId = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();

            // Full deny setting spawn on stage 3
            if (globalStageId == 3) {
                event.setCancelled(true);
                // previously sent cant_set_spawn — now silent per request
                return;
            }

            if (globalStageId < 2) return;

            boolean rawInHeat = heatManager.isPlayerInHeat(player);
            boolean inCustom = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(player.getLocation());
            boolean openToSky = NmsHelper.isOpenToSky(player.getLocation().getBlock());
            boolean effectiveInHeat = inCustom || (rawInHeat && !(globalStageId >= 2 && openToSky));

            if (globalStageId >= 2 && !effectiveInHeat) {
                event.setCancelled(true);
                // previously sent cant_set_spawn — now silent per request
            }
        }

        // Торговля: handled elsewhere via PlayerInteractEntityEvent (no change needed here)
    }
}
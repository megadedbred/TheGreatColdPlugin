package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.freeze.FreezeManager;
import me.megadedbeb.thegreatcold.heat.CustomHeatManager;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import me.megadedbeb.thegreatcold.util.NmsHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

public class PlayerMoveListener implements Listener {
    private final FreezeManager freezeManager;
    private final HeatSourceManager heatManager;
    private final Map<UUID, Boolean> playerHeatStates = new HashMap<>();
    private final CustomHeatManager customHeatManager;

    public PlayerMoveListener(FreezeManager freezeManager, HeatSourceManager heatManager) {
        this.freezeManager = freezeManager;
        this.heatManager = heatManager;
        this.customHeatManager = TheGreatColdPlugin.getInstance().getCustomHeatManager();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals("world")) return;

        boolean rawInHeat = heatManager.isPlayerInHeat(player);
        boolean inCustomHeat = (customHeatManager != null) && customHeatManager.isLocationInCustomHeat(player.getLocation());
        int globalStageId = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();
        boolean openToSky = NmsHelper.isOpenToSky(player.getLocation().getBlock());
        boolean effectiveInHeat;
        if (inCustomHeat) {
            effectiveInHeat = true;
        } else {
            effectiveInHeat = rawInHeat && !(globalStageId >= 2 && openToSky);
        }

        Boolean previous = playerHeatStates.get(player.getUniqueId());
        if (previous == null || effectiveInHeat != previous) {
            // Do not send enter/leave messages on stage 0
            if (globalStageId != 0) {
                if (effectiveInHeat) {
                    player.sendMessage(TheGreatColdPlugin.getInstance().getConfigManager().getMessage("heat.enter"));
                } else {
                    player.sendMessage(TheGreatColdPlugin.getInstance().getConfigManager().getMessage("heat.leave"));
                }
            }
            playerHeatStates.put(player.getUniqueId(), effectiveInHeat);
        }
    }
}
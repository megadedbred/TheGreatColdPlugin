package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.freeze.FreezeManager;
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

    public PlayerMoveListener(FreezeManager freezeManager, HeatSourceManager heatManager) {
        this.freezeManager = freezeManager;
        this.heatManager = heatManager;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals("world")) return;

        boolean rawInHeat = heatManager.isPlayerInHeat(player);
        int globalStageId = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();
        boolean openToSky = NmsHelper.isOpenToSky(player.getLocation().getBlock());
        boolean effectiveInHeat = rawInHeat && !(globalStageId >= 2 && openToSky);

        Boolean previous = playerHeatStates.get(player.getUniqueId());
        if (previous == null || effectiveInHeat != previous) {
            if (effectiveInHeat) {
                player.sendMessage("§eВы вошли в зону тепла!");
            } else {
                player.sendMessage("§bВы покинули зону тепла!");
            }
            playerHeatStates.put(player.getUniqueId(), effectiveInHeat);
        }
    }
}
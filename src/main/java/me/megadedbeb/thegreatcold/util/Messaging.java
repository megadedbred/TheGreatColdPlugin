package me.megadedbeb.thegreatcold.util;

import me.megadedbeb.thegreatcold.freeze.FreezeStage;
import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import org.bukkit.entity.Player;

public class Messaging {
    public static void notifyFreezeStage(Player p, FreezeStage st) {
        var cfg = TheGreatColdPlugin.getInstance().getConfigManager();
        switch (st) {
            case STAGE_1 -> p.sendMessage(cfg.getMessage("freeze.stage_1"));
            case STAGE_2 -> p.sendMessage(cfg.getMessage("freeze.stage_2"));
            case STAGE_3 -> p.sendMessage(cfg.getMessage("freeze.stage_3"));
            case STAGE_4 -> p.sendMessage(cfg.getMessage("freeze.stage_4"));
            default -> {
                // Previously we sent "freeze.none" for NONE — per request, do NOT send anything for NONE.
            }
        }
    }

    public static void notifyWarmed(Player p, FreezeStage stage) {
        String msg = TheGreatColdPlugin.getInstance().getConfigManager().getMessage("freeze.warmed", stage.id());
        p.sendMessage(msg);
    }

    public static void notifyManualFreeze(Player p, FreezeStage stage) {
        String msg = TheGreatColdPlugin.getInstance().getConfigManager().getMessage("freeze.manual_freeze", stage.id());
        p.sendMessage(msg);
    }

    public static void notifyUnfreeze(Player p) {
        p.sendMessage(TheGreatColdPlugin.getInstance().getConfigManager().getMessage("freeze.unfreeze"));
    }
}
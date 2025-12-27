package me.megadedbeb.thegreatcold.util;

import me.megadedbeb.thegreatcold.freeze.FreezeStage;
import org.bukkit.entity.Player;

public class Messaging {
    public static void notifyFreezeStage(Player p, FreezeStage st) {
        switch (st) {
            case STAGE_1 -> p.sendMessage("§bВы получили 1 стадию обморожения!");
            case STAGE_2 -> p.sendMessage("§bВы получили 2 стадию обморожения!");
            case STAGE_3 -> p.sendMessage("§c3 стадия обморожения!");
            case STAGE_4 -> p.sendMessage("§4Вам очень холодно, вы не можете двигаться!");
            default -> p.sendMessage("§aВы больше не заморожены!");
        }
    }

    public static void notifyWarmed(Player p, FreezeStage stage) {
        p.sendMessage("§eВы согрелись, прогресс стадии обморожения (" + stage.id() + ") сброшен.");
    }

    public static void notifyManualFreeze(Player p, FreezeStage stage) {
        p.sendMessage("§7Установлена стадия обморожения " + stage.id());
    }

    public static void notifyUnfreeze(Player p) {
        p.sendMessage("§aОбморожение снято");
    }
}
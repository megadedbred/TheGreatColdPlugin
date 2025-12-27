package me.megadedbeb.thegreatcold.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GreatColdTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String a, String[] args) {
        if (args.length == 1)
            return Arrays.asList("stage", "autostage", "stageinfo", "setperiod", "freeze", "unfreeze", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("autostage"))
            return Arrays.asList("on", "off");
        return Collections.emptyList();
    }
}

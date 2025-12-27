package me.megadedbeb.thegreatcold.listener;

import me.megadedbeb.thegreatcold.TheGreatColdPlugin;
import me.megadedbeb.thegreatcold.heat.HeatSourceManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;

import java.lang.reflect.Method;

/**
 * Позволяет игрокам ложиться в кровать, но на этапе 3 (Великий Холод)
 * вскоре после этого автоматически будит всех спящих, а StageManager
 * гарантирует что ночь и гроза сохранятся.
 *
 * Важно: прямой вызов Player.wakeUp() может отсутствовать в подключённой версии API,
 * поэтому используем рефлексию для вызова метода с любой сигнатурой.
 */
public class SleepListener implements Listener {
    private final HeatSourceManager heatManager;

    public SleepListener(HeatSourceManager heatManager) {
        this.heatManager = heatManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        // Не отменяем событие — оставляем вашу логику отмены сна для игроков вне зоны тепла.
        // Но если этап == 3 — через небольшую задержку просыпаем всех спящих.
        int stage = TheGreatColdPlugin.getInstance().getStageManager().getCurrentStage().id();
        if (stage != 3) return;

        // Небольшая задержка, чтобы игра успела пометить игрока как "спящего"
        Bukkit.getScheduler().runTaskLater(TheGreatColdPlugin.getInstance(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    if (!p.isSleeping()) continue;

                    // Попытка вызвать метод wakeUp через рефлексию (ищем метод с именем "wakeUp")
                    Method target = null;
                    for (Method m : p.getClass().getMethods()) {
                        if (m.getName().equals("wakeUp")) {
                            target = m;
                            break;
                        }
                    }
                    if (target != null) {
                        try {
                            Class<?>[] pts = target.getParameterTypes();
                            if (pts.length == 0) {
                                target.invoke(p);
                            } else if (pts.length == 1) {
                                // часто сигнатура wakeUp(boolean setSpawn)
                                target.invoke(p, false);
                            } else if (pts.length == 2) {
                                // иногда wakeUp(boolean setSpawn, boolean updatePlayersSleeping)
                                target.invoke(p, false, false);
                            } else {
                                // непредвиденная сигнатура — пытаемся вызвать без аргументов
                                target.invoke(p);
                            }
                        } catch (Throwable ignored) {
                            // если рефлексия не сработала — пробуем грубый fallback: телепортировать игрока на то же место
                            // иногда это прерывает состояние сна в некоторых версиях/сборках
                            try { p.teleport(p.getLocation()); } catch (Throwable ignored2) {}
                        }
                    } else {
                        // метод wakeUp не найден — используем fallback
                        try { p.teleport(p.getLocation()); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        }, 2L); // 2 тика — небольшой безопасный запас
    }
}
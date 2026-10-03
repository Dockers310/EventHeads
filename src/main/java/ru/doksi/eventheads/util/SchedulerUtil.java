package ru.doksi.eventheads.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler abstraction shared by Paper and Folia.
 *
 * GlobalRegionScheduler is used for plugin-wide state, RegionScheduler for world/block/entity
 * work, and EntityScheduler for operations owned by one player/entity. The same API is present
 * on Paper, so no runtime fork is needed in the plugin code.
 */
public final class SchedulerUtil {
    private SchedulerUtil() {}

    public static ScheduledTask runGlobal(Plugin plugin, Runnable task) {
        return plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    public static ScheduledTask runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        return plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), Math.max(0L, delayTicks));
    }

    public static ScheduledTask runGlobalRepeating(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    public static ScheduledTask runRegion(Plugin plugin, Location location, Runnable task) {
        if (location == null || location.getWorld() == null) return null;
        return plugin.getServer().getRegionScheduler().run(plugin, location, ignored -> task.run());
    }

    public static ScheduledTask runRegionLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        if (location == null || location.getWorld() == null) return null;
        return plugin.getServer().getRegionScheduler().runDelayed(plugin, location, ignored -> task.run(), Math.max(0L, delayTicks));
    }

    /**
     * RU: Повторяющаяся задача внутри региона. Подходит для коротких эффектов,
     * которые двигают сущность только в пределах одного Folia-региона.
     * EN: Repeating task pinned to one Folia region.
     */
    public static ScheduledTask runRegionRepeating(Plugin plugin, Location location, Runnable task, long delayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null) return null;
        return plugin.getServer().getRegionScheduler().runAtFixedRate(
                plugin,
                location,
                ignored -> task.run(),
                Math.max(0L, delayTicks),
                Math.max(1L, periodTicks)
        );
    }

    public static ScheduledTask runEntity(Plugin plugin, Entity entity, Runnable task) {
        if (entity == null) return null;
        return entity.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    public static ScheduledTask runEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (entity == null) return null;
        return entity.getScheduler().runDelayed(plugin, ignored -> task.run(), null, Math.max(0L, delayTicks));
    }

    public static ScheduledTask runEntityRepeating(Plugin plugin, Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (entity == null) return null;
        return entity.getScheduler().runAtFixedRate(plugin, ignored -> task.run(), null, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    public static ScheduledTask runPlayer(Plugin plugin, Player player, Runnable task) {
        return runEntity(plugin, player, task);
    }
}

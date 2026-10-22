package ru.doksi.eventheads.services;

// RU: Подсветка точек частицами, пока сотрудник держит инструмент точек.
// EN: Highlights spawn points with particles while staff hold a point tool.

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.SpawnPoint;
import ru.doksi.eventheads.roles.Perm;
import ru.doksi.eventheads.util.SchedulerUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * RU: Пока в руке шкурка (постановка точек), подсвечиваются точки её событий. Пока в руке лапка
 * (чёрный список) — точки её события и запреты, у общей лапки — общие запреты.
 * Частицы отправляются только самому игроку с правом eventheads.points: остальные их не видят,
 * поэтому подсветка не выдаёт точки обычным игрокам.
 */
public final class PointPreviewService {
    /** Точка события, к которому привязана шкурка. */
    private static final Particle.DustOptions POINT = dust(0x55FF55);
    /** Точка, которую лапка удалит кликом. */
    private static final Particle.DustOptions REMOVABLE_POINT = dust(0xFFAA00);
    /** Запрет спавна для одного события. */
    private static final Particle.DustOptions BLOCKED = dust(0xFF5555);
    /** Общий запрет для всех событий. */
    private static final Particle.DustOptions GLOBAL_BLOCKED = dust(0xAA00AA);

    private final EventHeadsPlugin plugin;

    public PointPreviewService(EventHeadsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Период задаётся при запуске, остальные настройки перечитываются на каждом проходе. */
    public void start() {
        long period = Math.max(2L, Math.min(100L, plugin.getConfig().getLong("points.preview.period-ticks", 10L)));
        SchedulerUtil.runGlobalRepeating(plugin, this::tick, period, period);
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("points.preview.enabled", true)) return;
        // Folia: инвентарь и частицы игрока трогаем только в его собственном планировщике.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            SchedulerUtil.runPlayer(plugin, player, () -> show(player));
        }
    }

    private void show(Player player) {
        if (!player.isOnline()) return;
        ItemService items = plugin.items();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isTool(items, tool)) tool = player.getInventory().getItemInOffHand();
        if (!isTool(items, tool)) return;
        if (!plugin.access().has(player, Perm.POINTS)) return;

        World world = player.getWorld();
        Location center = player.getLocation();
        double radius = Math.max(4.0D, Math.min(256.0D, plugin.getConfig().getDouble("points.preview.radius", 48.0D)));
        int maxPoints = Math.max(1, Math.min(1000, plugin.getConfig().getInt("points.preview.max-points", 150)));

        List<Marker> markers = new ArrayList<>();
        try {
            if (items.isWand(tool)) {
                for (String id : items.wandEvents(tool)) {
                    EventDefinition event = plugin.events().get(id);
                    if (event != null) addPoints(markers, event.points, POINT, world, center, radius);
                }
            } else {
                String target = items.removeToolEvent(tool);
                if ("*".equals(target)) {
                    addLocations(markers, plugin.data().globalBlockedLocations(), GLOBAL_BLOCKED, world, center, radius);
                } else {
                    EventDefinition event = target == null ? null : plugin.events().get(target);
                    if (event != null) {
                        addPoints(markers, event.points, REMOVABLE_POINT, world, center, radius);
                        addLocations(markers, event.blockedLocations, BLOCKED, world, center, radius);
                    }
                }
            }
        } catch (RuntimeException ex) {
            // Список точек могли менять в этот момент в другом регионе: подсветка просто пропустит проход.
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("[point-preview] " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            return;
        }
        if (markers.isEmpty()) return;

        if (markers.size() > maxPoints) {
            markers.sort(Comparator.comparingDouble(Marker::distanceSquared));
            markers = markers.subList(0, maxPoints);
        }
        for (Marker marker : markers) {
            // Столбик пыли в центре блока точки. force=true: видно дальше 32 блоков и при «уменьшенных» частицах.
            player.spawnParticle(Particle.DUST, marker.x(), marker.y(), marker.z(), 5, 0.05D, 0.25D, 0.05D, 0.0D, marker.color(), true);
        }
    }

    private static boolean isTool(ItemService items, ItemStack item) {
        return items.isWand(item) || items.isRemoveTool(item);
    }

    private static void addPoints(List<Marker> out, List<SpawnPoint> points, Particle.DustOptions color, World world, Location center, double radius) {
        // Копия через toArray: исходный список может меняться, пока мы его читаем.
        for (Object raw : points.toArray()) {
            if (raw instanceof SpawnPoint point) add(out, point.location, color, world, center, radius);
        }
    }

    private static void addLocations(List<Marker> out, List<Location> locations, Particle.DustOptions color, World world, Location center, double radius) {
        for (Object raw : locations.toArray()) {
            if (raw instanceof Location location) add(out, location, color, world, center, radius);
        }
    }

    private static void add(List<Marker> out, Location location, Particle.DustOptions color, World world, Location center, double radius) {
        if (location == null || location.getWorld() == null) return;
        UUID worldId = location.getWorld().getUID();
        if (!worldId.equals(world.getUID())) return;
        double x = location.getBlockX() + 0.5D;
        double y = location.getBlockY() + 0.6D;
        double z = location.getBlockZ() + 0.5D;
        double dx = x - center.getX(), dy = y - center.getY(), dz = z - center.getZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > radius * radius) return;
        out.add(new Marker(x, y, z, distanceSquared, color));
    }

    private static Particle.DustOptions dust(int rgb) {
        return new Particle.DustOptions(Color.fromRGB(rgb), 1.3F);
    }

    private record Marker(double x, double y, double z, double distanceSquared, Particle.DustOptions color) {}
}

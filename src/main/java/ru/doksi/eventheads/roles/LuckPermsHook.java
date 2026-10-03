package ru.doksi.eventheads.roles;

import ru.doksi.eventheads.EventHeadsPlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.group.Group;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * RU: Нативная интеграция EventHeads с LuckPerms 5.5.
 * EN: Native EventHeads integration with LuckPerms 5.5.
 *
 * EventHeads never writes LuckPerms data automatically. It only reads the cached
 * permission state and optionally maps LuckPerms group names to EventHeads roles.
 */
public final class LuckPermsHook {
    private final EventHeadsPlugin plugin;
    private LuckPerms api;

    public LuckPermsHook(EventHeadsPlugin plugin) { this.plugin = plugin; }

    public void reload() {
        api = null;
        if (!plugin.getConfig().getBoolean("luckperms.enabled", true)) return;
        if (plugin.getServer().getPluginManager().getPlugin("LuckPerms") == null) return;
        try { api = LuckPermsProvider.get(); }
        catch (Throwable ex) { plugin.getLogger().warning("LuckPerms found, but API is unavailable: " + ex.getMessage()); }
    }

    public boolean available() { return api != null; }
    public LuckPerms api() { return api; }

    public boolean has(Player player, String node) {
        if (api == null) return player.hasPermission(node);
        try {
            User user = api.getPlayerAdapter(Player.class).getUser(player);
            return user.getCachedData().getPermissionData().checkPermission(node).asBoolean();
        } catch (Throwable ignored) {
            return player.hasPermission(node);
        }
    }

    public String primaryGroup(Player player) {
        if (api == null) return null;
        try { return api.getPlayerAdapter(Player.class).getUser(player).getPrimaryGroup(); }
        catch (Throwable ignored) { return null; }
    }

    /** Returns the highest mapped EventHeads role from the player's inherited LP groups. */
    public String mappedRole(Player player) {
        if (api == null) return null;
        try {
            User user = api.getPlayerAdapter(Player.class).getUser(player);
            Map<String, String> mapping = groupRoles();
            String best = null;
            int bestWeight = Integer.MIN_VALUE;
            for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
                String role = mapping.get(group.getName().toLowerCase(Locale.ROOT));
                if (role == null) continue;
                int weight = plugin.access().weight(role);
                if (weight > bestWeight) { bestWeight = weight; best = role; }
            }
            String primary = user.getPrimaryGroup();
            String primaryRole = mapping.get(primary.toLowerCase(Locale.ROOT));
            if (primaryRole != null && plugin.access().weight(primaryRole) > bestWeight) best = primaryRole;
            return best;
        } catch (Throwable ignored) { return null; }
    }

    private Map<String,String> groupRoles() {
        Map<String,String> out = new LinkedHashMap<>();
        var section = plugin.getConfig().getConfigurationSection("luckperms.group-roles");
        if (section != null) for (String key : section.getKeys(false)) {
            String role = section.getString(key);
            if (role != null && !role.isBlank()) out.put(key.toLowerCase(Locale.ROOT), role.toUpperCase(Locale.ROOT));
        }
        return out;
    }
}

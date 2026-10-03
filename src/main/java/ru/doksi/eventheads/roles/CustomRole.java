package ru.doksi.eventheads.roles;

// RU: Модель кастомной роли, создаваемой администратором.
// EN: Model for administrator-created custom roles.

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record CustomRole(String id, String displayName, int weight, Set<String> permissions, String icon, int maxPoints, double pointRadius) {
    public CustomRole {
        
        LinkedHashSet<String> normalizedPermissions = new LinkedHashSet<>();
        if (permissions != null) for (String permission : permissions) {
            if (permission == null || permission.isBlank()) continue;
            normalizedPermissions.add(permission.trim().toLowerCase(Locale.ROOT));
        }
        permissions = Set.copyOf(normalizedPermissions);
        icon = (icon == null || icon.isBlank()) ? "WRITABLE_BOOK" : icon.toUpperCase(Locale.ROOT);
        maxPoints = maxPoints < -1 ? -1 : maxPoints;
        pointRadius = Double.isFinite(pointRadius) && pointRadius >= 0.0D ? pointRadius : -1.0D;
    }
    public CustomRole(String id, String displayName, int weight, Set<String> permissions) {
        this(id, displayName, weight, permissions, "WRITABLE_BOOK", -1, -1.0D);
    }
    public CustomRole(String id, String displayName, int weight, Set<String> permissions, String icon) {
        this(id, displayName, weight, permissions, icon, -1, -1.0D);
    }
    public boolean allows(String permission) { return permission != null && (permissions.contains("*") || permissions.contains(permission.trim().toLowerCase(Locale.ROOT))); }
    public boolean hasPointRestrictions() { return maxPoints >= 0 || pointRadius >= 0.0D; }
}

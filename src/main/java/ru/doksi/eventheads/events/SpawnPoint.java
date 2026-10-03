package ru.doksi.eventheads.events;

import org.bukkit.Location;
import java.util.UUID;

/** RU: Сохранённая ручная точка. Мир может быть временно не загружен: raw worldName/worldUuid сохраняются до восстановления. */
public final class SpawnPoint {
    public final String id;
    public final Location location;
    public final String worldName;
    public final UUID worldUuid;
    public double chance;
    public final boolean enabled;
    /** UUID игрока, создавшего точку. null = старая точка/владелец неизвестен. */
    public final UUID addedBy;

    /** Backward-compatible constructor: also records the player who created the point. */
    public SpawnPoint(String id, Location location, double chance, boolean enabled, UUID addedBy){
        this(id, location, chance, enabled, location!=null && location.getWorld()!=null ? location.getWorld().getName() : null, location!=null && location.getWorld()!=null ? location.getWorld().getUID() : null, addedBy);
    }

    public SpawnPoint(String id, Location location, double chance, boolean enabled){
        this(id, location, chance, enabled, location!=null && location.getWorld()!=null ? location.getWorld().getName() : null, location!=null && location.getWorld()!=null ? location.getWorld().getUID() : null, null);
    }

    public SpawnPoint(String id, Location location, double chance, boolean enabled, String worldName, UUID worldUuid){
        this(id, location, chance, enabled, worldName, worldUuid, null);
    }

    public SpawnPoint(String id, Location location, double chance, boolean enabled, String worldName, UUID worldUuid, UUID addedBy){
        this.id=id;
        this.location=location==null?new Location(null,0,0,0):location.clone();
        this.worldName=worldName;
        this.worldUuid=worldUuid;
        this.chance=chance;
        this.enabled=enabled;
        this.addedBy=addedBy;
    }

    public boolean resolved(){ return location.getWorld()!=null; }

    public SpawnPoint resolve(Location resolved){
        return new SpawnPoint(id, resolved, chance, enabled,
                resolved!=null && resolved.getWorld()!=null ? resolved.getWorld().getName() : worldName,
                resolved!=null && resolved.getWorld()!=null ? resolved.getWorld().getUID() : worldUuid, addedBy);
    }
}

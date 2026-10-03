
package ru.doksi.eventheads.events;

// RU: Один реально заспавненный экземпляр EventHead.
// EN: One live spawned EventHead instance.

import org.bukkit.Location;
import java.util.UUID;

public final class EventInstance {
    public final UUID instanceId;
    public final String eventId;
    public final Location location;
    public final float yaw;
    public final long spawnedAt;
    public long expiresAt;
    public boolean collected;
    public EventInstance(UUID instanceId,String eventId,Location location,float yaw,long spawnedAt,long expiresAt){this.instanceId=instanceId;this.eventId=eventId;this.location=location.clone();this.yaw=yaw;this.spawnedAt=spawnedAt;this.expiresAt=expiresAt;}
}

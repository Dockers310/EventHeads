
package ru.doksi.eventheads.events;

// RU: Полная конфигурация одного шаблона EventHeads.
// EN: Complete configuration model for one EventHeads template.

import ru.doksi.eventheads.catalog.ParticleSettings;
import org.bukkit.inventory.ItemStack;
import java.time.Instant;
import java.util.*;

public final class EventDefinition {
    public enum SpawnMode { RANDOM, POINTS, MIXED }
    public final String id;
    public String adminName;
    public String playerName;
    /** Short description shown in the EventHeads GUI. */
    public String description="";
    public String authorUuid;
    public String authorName;
    public long createdAt;
    public ItemStack visual;
    public int rewardMin=1, rewardMax=5;
    public SpawnMode spawnMode=SpawnMode.RANDOM;
    public int maxActive=30, regionMaxActive=30;
    public double minDistance=8.0;
    /** Minimum horizontal distance from any online player for automatic/random spawns. 0 = disabled. */
    public double minPlayerDistance=0.0D;
    public int minY=-64, maxY=320;
    public boolean requireSolidGround=true, allowFloating=false, allowWater=false, allowLava=false;
    public long minDelaySeconds=15, maxDelaySeconds=60, minLifetimeSeconds=30, maxLifetimeSeconds=120;
    public double spawnChance=100.0;
    public boolean enabled=true;
    public Instant startAt=null, endAt=null;
    /** Weekly recurring schedule. If enabled it takes precedence over absolute start/end. */
    public boolean weeklyScheduleEnabled=false;
    public int weeklyDayOfWeek=6; // 1=Monday .. 7=Sunday; 6=Saturday
    public int weeklyTimeMinutes=20*60; // local server time
    public long weeklyDurationSeconds=3600;
    public boolean dailyScheduleEnabled=false;
    public int dailyTimeMinutes=20*60;
    public long dailyDurationSeconds=3600;
    public String worldGuardRegion=null, worldGuardWorld=null;
    public final List<String> worldGuardRegions=new ArrayList<>(); public int areaMinX,areaMinY,areaMinZ,areaMaxX,areaMaxY,areaMaxZ; public boolean hasArea=false;
    public boolean randomRotation=true;
    /** Whether the temporary ArmorStand becomes small while the collection animation is playing. */
    public boolean animationSmall=false;
    /** Small vertical offset used to place the visible head slightly above the spawn block. */
    public double visualOffsetY=0.15D;
    /** Per-event animation speed multiplier. 1.0 is normal speed. */
    public double animationSpeed=1.0D;
    /** Per-event collection particle count. -1 means use global config. */
    public int collectParticleCount=-1;
    /** Per-event collection particle color in #RRGGBB. Used for colored dust particles. */
    public String collectParticleColor="#FFFFFF";
    /** Particle type used for collection animation. Blank means use global animation.particle. */
    public String collectParticle="";
    /** Legacy/custom collection sound. Empty = use global animation sound when soundEnabled=true. */
    public String sound="";
    /** Multiple custom sounds. One of them is chosen for each collection. Empty means use legacy sound/global sound. */
    public final List<String> sounds=new ArrayList<>();
    /** True = play configured/global sound; false = completely mute this event. */
    public boolean soundEnabled=true;
    public float soundVolume=1.0F;
    public float soundPitch=1.0F;
    /** Multiple simultaneously enabled collection particles. Empty = legacy single particle. */
    public final List<String> collectParticles=new ArrayList<>();
    /** Multiple HEX colors used for DUST particles. Empty means use collectParticleColor. */
    public final List<String> collectParticleColors=new ArrayList<>();
    public final Map<String,ParticleSettings> particleSettings=new LinkedHashMap<>();
    public EventConditions conditions=new EventConditions();
    public final List<SpawnPoint> points=new ArrayList<>();
    /** True when points.yml contains unresolved locations; do not overwrite it with incomplete data. */
    public transient boolean pointsIncomplete=false;
    /** Exact block locations forbidden for RANDOM/MIXED spawning. */
    public final List<org.bukkit.Location> blockedLocations=new ArrayList<>();
    public EventDefinition(String id){this.id=id; this.adminName=id; this.playerName="Ивентовый предмет"; this.createdAt=System.currentTimeMillis();}
    public void addRegion(String region){ if(region!=null&&!region.isBlank()&&!worldGuardRegions.stream().anyMatch(x->x.equalsIgnoreCase(region))){worldGuardRegions.add(region); if(worldGuardRegion==null)worldGuardRegion=region;} }
    public boolean currentlyScheduled(){
        if(!enabled) return false;
        java.time.ZonedDateTime now=java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault());
        if(dailyScheduleEnabled){
            long duration=Math.max(1,dailyDurationSeconds);
            int startMinutes=Math.floorMod(dailyTimeMinutes,1440);
            for(int daysBack=0;daysBack<2;daysBack++){
                java.time.LocalDate date=now.toLocalDate().minusDays(daysBack);
                java.time.ZonedDateTime start=date.atStartOfDay(now.getZone()).plusMinutes(startMinutes);
                java.time.ZonedDateTime end=start.plusSeconds(duration);
                if(!now.isBefore(start) && now.isBefore(end)) return true;
            }
            return false;
        }
        if(weeklyScheduleEnabled){
            long duration=Math.max(1,weeklyDurationSeconds);
            java.time.DayOfWeek targetDay=java.time.DayOfWeek.of(Math.max(1,Math.min(7,weeklyDayOfWeek)));
            int startMinutes=Math.floorMod(weeklyTimeMinutes,1440);
            for(int daysBack=0;daysBack<7;daysBack++){
                java.time.LocalDate date=now.toLocalDate().minusDays(daysBack);
                if(date.getDayOfWeek()!=targetDay) continue;
                java.time.ZonedDateTime start=date.atStartOfDay(now.getZone()).plusMinutes(startMinutes);
                java.time.ZonedDateTime end=start.plusSeconds(duration);
                if(!now.isBefore(start) && now.isBefore(end)) return true;
            }
            return false;
        }
        java.time.Instant current=now.toInstant();
        return (startAt==null || !current.isBefore(startAt)) && (endAt==null || current.isBefore(endAt));
    }
    public int randomReward(Random r){return rewardMin==rewardMax?rewardMin:rewardMin+r.nextInt(rewardMax-rewardMin+1);}
}

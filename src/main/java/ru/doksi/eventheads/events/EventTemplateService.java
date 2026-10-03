package ru.doksi.eventheads.events;

import ru.doksi.eventheads.EventHeadsPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Built-in event templates and safe in-memory duplication. */
public final class EventTemplateService {
    private final EventHeadsPlugin plugin;
    public EventTemplateService(EventHeadsPlugin plugin){this.plugin=plugin;}

    public EventDefinition create(Player creator, String template, String id){
        EventDefinition e=new EventDefinition(id);
        e.authorUuid=creator.getUniqueId().toString(); e.authorName=creator.getName();
        e.adminName=templateName(template); e.playerName=templateName(template); e.description=templateDescription(template);
        e.maxActive=plugin.getConfig().getInt("spawn.default-max-active",30);
        e.minDistance=plugin.getConfig().getDouble("spawn.default-min-distance",8.0);
        e.minPlayerDistance=plugin.getConfig().getDouble("spawn.default-min-player-distance",0.0D);
        e.minDelaySeconds=plugin.getConfig().getLong("spawn.default-min-delay-seconds",15);
        e.maxDelaySeconds=plugin.getConfig().getLong("spawn.default-max-delay-seconds",60);
        long life=plugin.getConfig().getLong("spawn.default-lifetime-seconds",120);
        e.minLifetimeSeconds=Math.max(1,life); e.maxLifetimeSeconds=Math.max(1,life);
        e.requireSolidGround=plugin.getConfig().getBoolean("spawn.require-solid-ground",true);
        e.allowWater=plugin.getConfig().getBoolean("spawn.allow-water",false);
        e.allowLava=plugin.getConfig().getBoolean("spawn.allow-lava",false);
        e.allowFloating=plugin.getConfig().getBoolean("spawn.allow-floating",false);
        e.enabled=false;
        e.visual=new ItemStack(Material.PLAYER_HEAD);
        e.collectParticleCount=6;
        switch(template.toLowerCase(Locale.ROOT)){
            case "easter" -> { e.rewardMin=5; e.rewardMax=12; e.collectParticles.add("HEART"); e.collectParticles.add("HAPPY_VILLAGER"); e.animationSpeed=1.1; e.sound="ENTITY_PLAYER_LEVELUP"; e.sounds.add(e.sound); e.spawnMode=EventDefinition.SpawnMode.POINTS; }
            case "winter" -> { e.rewardMin=8; e.rewardMax=18; e.collectParticles.add("SNOWFLAKE"); e.collectParticles.add("WHITE_ASH"); e.animationSpeed=0.9; e.sound="BLOCK_AMETHYST_BLOCK_CHIME"; e.sounds.add(e.sound); e.spawnMode=EventDefinition.SpawnMode.POINTS; }
            case "fair" -> { e.rewardMin=10; e.rewardMax=25; e.collectParticles.add("GLOW"); e.collectParticles.add("FIREWORK"); e.animationSpeed=1.0; e.sound="ENTITY_PLAYER_LEVELUP"; e.sounds.add(e.sound); e.spawnMode=EventDefinition.SpawnMode.POINTS; }
            case "search" -> { e.rewardMin=2; e.rewardMax=8; e.collectParticles.add("END_ROD"); e.collectParticles.add("NOTE"); e.animationSpeed=1.2; e.sound="BLOCK_NOTE_BLOCK_PLING"; e.sounds.add(e.sound); e.spawnMode=EventDefinition.SpawnMode.POINTS; }
            default -> { e.rewardMin=1; e.rewardMax=5; e.collectParticles.add("END_ROD"); e.sound="ENTITY_ALLAY_ITEM_GIVEN"; e.sounds.add(e.sound); }
        }
        e.collectParticle=e.collectParticles.isEmpty()?"END_ROD":e.collectParticles.get(0);
        if(template.toLowerCase(Locale.ROOT).equals("search")) {
            e.conditions.enabled=true; e.conditions.requiredHelmetMaterial="PUMPKIN"; e.conditions.failMessage="§cВы не можете собрать: §fвы не надели тыкву§c.";
        }
        applyConfiguredTemplate(e, template);
        // A template gets one starter point at the creator's current location only when the template is point-based.
        if(e.spawnMode==EventDefinition.SpawnMode.POINTS && creator.getLocation().getWorld()!=null){
            Location l=creator.getLocation().getBlock().getLocation().add(0,1,0);
            e.points.add(new SpawnPoint(UUID.randomUUID().toString(),l,100,true,creator.getUniqueId()));
        }
        return e;
    }

    public EventDefinition duplicate(EventDefinition src, String id, Player creator){
        EventDefinition e=new EventDefinition(id);
        e.adminName=src.adminName+" копия"; e.playerName=src.playerName+" копия"; e.description=src.description;
        e.authorUuid=creator.getUniqueId().toString(); e.authorName=creator.getName(); e.createdAt=System.currentTimeMillis();
        e.rewardMin=src.rewardMin; e.rewardMax=src.rewardMax; e.spawnMode=src.spawnMode; e.maxActive=src.maxActive; e.regionMaxActive=src.regionMaxActive;
        e.minDistance=src.minDistance; e.minPlayerDistance=src.minPlayerDistance; e.minY=src.minY; e.maxY=src.maxY; e.requireSolidGround=src.requireSolidGround; e.allowFloating=src.allowFloating; e.allowWater=src.allowWater; e.allowLava=src.allowLava;
        e.minDelaySeconds=src.minDelaySeconds; e.maxDelaySeconds=src.maxDelaySeconds; e.minLifetimeSeconds=src.minLifetimeSeconds; e.maxLifetimeSeconds=src.maxLifetimeSeconds; e.spawnChance=src.spawnChance; e.enabled=src.enabled;
        e.startAt=src.startAt; e.endAt=src.endAt; e.weeklyScheduleEnabled=src.weeklyScheduleEnabled; e.weeklyDayOfWeek=src.weeklyDayOfWeek; e.weeklyTimeMinutes=src.weeklyTimeMinutes; e.weeklyDurationSeconds=src.weeklyDurationSeconds; e.dailyScheduleEnabled=src.dailyScheduleEnabled; e.dailyTimeMinutes=src.dailyTimeMinutes; e.dailyDurationSeconds=src.dailyDurationSeconds;
        e.worldGuardRegion=src.worldGuardRegion; e.worldGuardWorld=src.worldGuardWorld; e.worldGuardRegions.addAll(src.worldGuardRegions);
        e.hasArea=src.hasArea; e.areaMinX=src.areaMinX; e.areaMinY=src.areaMinY; e.areaMinZ=src.areaMinZ; e.areaMaxX=src.areaMaxX; e.areaMaxY=src.areaMaxY; e.areaMaxZ=src.areaMaxZ;
        e.randomRotation=src.randomRotation; e.animationSmall=src.animationSmall; e.visualOffsetY=src.visualOffsetY; e.animationSpeed=src.animationSpeed;
        e.collectParticleCount=src.collectParticleCount; e.collectParticleColor=src.collectParticleColor; e.collectParticle=src.collectParticle; e.collectParticleColors.addAll(src.collectParticleColors); e.collectParticles.addAll(src.collectParticles);
        src.particleSettings.forEach((k,v)->e.particleSettings.put(k,v.copy())); e.conditions=src.conditions.copy(); e.sound=src.sound; e.sounds.addAll(src.sounds); if(e.sounds.isEmpty()&&!e.sound.isBlank())e.sounds.add(e.sound); e.soundEnabled=src.soundEnabled; e.soundVolume=src.soundVolume; e.soundPitch=src.soundPitch;
        if(src.visual!=null)e.visual=src.visual.clone();
        for(SpawnPoint p:src.points) e.points.add(new SpawnPoint(p.id, p.location, p.chance, p.enabled, p.worldName, p.worldUuid, creator.getUniqueId()));
        for(Location l:src.blockedLocations)e.blockedLocations.add(l.clone());
        return e;
    }

    private void applyConfiguredTemplate(EventDefinition e,String template){
        String base="templates."+template.toLowerCase(Locale.ROOT)+".";
        org.bukkit.configuration.file.FileConfiguration c=plugin.getConfig();
        if(c.contains(base+"reward-min")) e.rewardMin=c.getInt(base+"reward-min",e.rewardMin);
        if(c.contains(base+"reward-max")) e.rewardMax=c.getInt(base+"reward-max",e.rewardMax);
        if(c.contains(base+"max-active")) e.maxActive=c.getInt(base+"max-active",e.maxActive);
        if(c.contains(base+"region-max")) e.regionMaxActive=c.getInt(base+"region-max",e.regionMaxActive);
        if(c.contains(base+"spawn-mode")) try{e.spawnMode=EventDefinition.SpawnMode.valueOf(c.getString(base+"spawn-mode",e.spawnMode.name()).toUpperCase(Locale.ROOT));}catch(Exception ignored){}
        if(c.contains(base+"min-distance")) e.minDistance=c.getDouble(base+"min-distance",e.minDistance);
        if(c.contains(base+"min-player-distance")) e.minPlayerDistance=c.getDouble(base+"min-player-distance",e.minPlayerDistance);
        if(c.contains(base+"min-delay")) e.minDelaySeconds=Math.max(0,c.getLong(base+"min-delay",e.minDelaySeconds));
        if(c.contains(base+"max-delay")) e.maxDelaySeconds=Math.max(e.minDelaySeconds,c.getLong(base+"max-delay",e.maxDelaySeconds));
        if(c.contains(base+"min-lifetime")) e.minLifetimeSeconds=Math.max(1,c.getLong(base+"min-lifetime",e.minLifetimeSeconds));
        if(c.contains(base+"max-lifetime")) e.maxLifetimeSeconds=Math.max(e.minLifetimeSeconds,c.getLong(base+"max-lifetime",e.maxLifetimeSeconds));
        if(c.contains(base+"require-solid-ground")) e.requireSolidGround=c.getBoolean(base+"require-solid-ground",e.requireSolidGround);
        if(c.contains(base+"allow-water")) e.allowWater=c.getBoolean(base+"allow-water",e.allowWater);
        if(c.contains(base+"allow-lava")) e.allowLava=c.getBoolean(base+"allow-lava",e.allowLava);
        if(c.contains(base+"allow-floating")) e.allowFloating=c.getBoolean(base+"allow-floating",e.allowFloating);
        if(c.contains(base+"particle-count")) e.collectParticleCount=c.getInt(base+"particle-count",e.collectParticleCount);
        if(c.contains(base+"particles")){e.collectParticles.clear(); e.collectParticles.addAll(c.getStringList(base+"particles"));}
        if(c.contains(base+"sounds")){e.sounds.clear(); e.sounds.addAll(c.getStringList(base+"sounds")); e.sound=e.sounds.isEmpty()?"":e.sounds.get(0);}
        if(c.contains(base+"sound-enabled")) e.soundEnabled=c.getBoolean(base+"sound-enabled",e.soundEnabled);
        if(c.contains(base+"sound-volume")) e.soundVolume=(float)Math.max(0,c.getDouble(base+"sound-volume",e.soundVolume));
        if(c.contains(base+"sound-pitch")) e.soundPitch=(float)Math.max(0,c.getDouble(base+"sound-pitch",e.soundPitch));
        if(c.contains(base+"animation-speed")) e.animationSpeed=Math.max(.05,c.getDouble(base+"animation-speed",e.animationSpeed));
        if(c.contains(base+"animation-small")) e.animationSmall=c.getBoolean(base+"animation-small",e.animationSmall);
        if(c.contains(base+"random-rotation")) e.randomRotation=c.getBoolean(base+"random-rotation",e.randomRotation);
        if(c.contains(base+"helmet")) e.conditions.requiredHelmetMaterial=c.getString(base+"helmet","");
        if(c.contains(base+"inventory-item")) e.conditions.requiredInventoryMaterial=c.getString(base+"inventory-item","");
        if(c.contains(base+"conditions-enabled")) e.conditions.enabled=c.getBoolean(base+"conditions-enabled",e.conditions.enabled);
        else if(!e.conditions.requiredHelmetMaterial.isBlank()||!e.conditions.requiredInventoryMaterial.isBlank()) e.conditions.enabled=true;
        if(c.contains(base+"fail-message")) e.conditions.failMessage=c.getString(base+"fail-message",e.conditions.failMessage);
        if(c.contains(base+"fail-penalty")) e.conditions.failPenalty=c.getInt(base+"fail-penalty",e.conditions.failPenalty);
        if(c.contains(base+"player-name")) e.playerName=c.getString(base+"player-name",e.playerName);
        if(c.contains(base+"admin-name")) e.adminName=c.getString(base+"admin-name",e.adminName);
        if(c.contains(base+"description")) e.description=c.getString(base+"description",e.description);
        e.collectParticle=e.collectParticles.isEmpty()?"END_ROD":e.collectParticles.get(0);
        if(c.contains(base+"weekly.enabled")){ e.weeklyScheduleEnabled=c.getBoolean(base+"weekly.enabled",false); e.weeklyDayOfWeek=c.getInt(base+"weekly.day",e.weeklyDayOfWeek); e.weeklyTimeMinutes=c.getInt(base+"weekly.time",e.weeklyTimeMinutes); e.weeklyDurationSeconds=Math.max(1,c.getLong(base+"weekly.duration",e.weeklyDurationSeconds)); if(e.weeklyScheduleEnabled)e.dailyScheduleEnabled=false; }
        if(c.contains(base+"daily.enabled")){ e.dailyScheduleEnabled=c.getBoolean(base+"daily.enabled",false); e.dailyTimeMinutes=c.getInt(base+"daily.time",e.dailyTimeMinutes); e.dailyDurationSeconds=Math.max(1,c.getLong(base+"daily.duration",e.dailyDurationSeconds)); if(e.dailyScheduleEnabled)e.weeklyScheduleEnabled=false; }
    }

    public void saveTemplateSetting(String template,String path,Object value){
        String base="templates."+template.toLowerCase(Locale.ROOT)+".";
        plugin.getConfig().set(base+path,value);
        plugin.saveConfig();
    }

    public String templateName(String id){return switch(id.toLowerCase(Locale.ROOT)){case "easter"->"Пасхальный ивент";case "winter"->"Зимний ивент";case "fair"->"Ярмарка";case "search"->"Поиск предметов";default->"Свой ивент";};}
    public String templateDescription(String id){return switch(id.toLowerCase(Locale.ROOT)){case "easter"->"Готовый пасхальный шаблон.";case "winter"->"Готовый зимний шаблон.";case "fair"->"Шаблон ярмарочного ивента.";case "search"->"Поиск предмета с требованием экипировки.";default->"Пустой собственный шаблон.";};}
}

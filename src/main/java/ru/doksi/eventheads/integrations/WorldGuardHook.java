package ru.doksi.eventheads.integrations;

// RU: Необязательная интеграция с WorldGuard для проверки и поиска регионов.
// EN: Optional WorldGuard integration for region validation and location search.

import ru.doksi.eventheads.EventHeadsPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldGuardHook {
    private final EventHeadsPlugin plugin;
    private boolean available;
    public WorldGuardHook(EventHeadsPlugin plugin){this.plugin=plugin; reload();}
    public void reload(){available=plugin.getConfig().getBoolean("worldguard.enabled",true) && plugin.getServer().getPluginManager().getPlugin("WorldGuard")!=null;}
    public boolean available(){return available;}

    private Object platform(){
        try{Object wg=Class.forName("com.sk89q.worldguard.WorldGuard").getMethod("getInstance").invoke(null);return wg.getClass().getMethod("getPlatform").invoke(wg);}catch(Throwable t){return null;}
    }
    private Object regionManager(World world){
        try{Object platform=platform(); if(platform==null)return null; Object container=platform.getClass().getMethod("getRegionContainer").invoke(platform); Object bWorld=Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter").getMethod("adapt",World.class).invoke(null,world); return container.getClass().getMethod("get",bWorld.getClass().getInterfaces().length>0?bWorld.getClass().getInterfaces()[0]:bWorld.getClass()).invoke(container,bWorld);}catch(Throwable t){
            try{Object platform=platform(); if(platform==null)return null; Object container=platform.getClass().getMethod("getRegionContainer").invoke(platform); Object bWorld=Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter").getMethod("adapt",World.class).invoke(null,world); for(Method m:container.getClass().getMethods()) if(m.getName().equals("get")&&m.getParameterCount()==1)return m.invoke(container,bWorld);}catch(Throwable ignored){} return null;
        }
    }
    public boolean contains(Location loc,String regionName){
        if(!available||regionName==null||regionName.isBlank())return true;
        try{
            Object rm=regionManager(loc.getWorld()); if(rm==null)return false;
            Object region=rm.getClass().getMethod("get",String.class).invoke(rm,regionName); if(region==null)return false;
            try {
                Object result=region.getClass().getMethod("contains",int.class,int.class,int.class).invoke(region,loc.getBlockX(),loc.getBlockY(),loc.getBlockZ());
                return Boolean.TRUE.equals(result);
            } catch(NoSuchMethodException ignored) {
                Object min=region.getClass().getMethod("getMinimumPoint").invoke(region);Object max=region.getClass().getMethod("getMaximumPoint").invoke(region);
                int minX=(int)min.getClass().getMethod("x").invoke(min),minY=(int)min.getClass().getMethod("y").invoke(min),minZ=(int)min.getClass().getMethod("z").invoke(min);
                int maxX=(int)max.getClass().getMethod("x").invoke(max),maxY=(int)max.getClass().getMethod("y").invoke(max),maxZ=(int)max.getClass().getMethod("z").invoke(max);
                return loc.getBlockX()>=minX&&loc.getBlockX()<=maxX&&loc.getBlockY()>=minY&&loc.getBlockY()<=maxY&&loc.getBlockZ()>=minZ&&loc.getBlockZ()<=maxZ;
            }
        }catch(Throwable t){plugin.getLogger().fine("WorldGuard contains failed: "+t.getMessage());return false;}
    }
    /** Returns a random coordinate inside the region bounds without touching terrain. */
    public Location randomCandidate(String regionName,World world){
        if(!available||regionName==null||regionName.isBlank()||world==null)return null;
        try{
            Object rm=regionManager(world);if(rm==null)return null;Object region=rm.getClass().getMethod("get",String.class).invoke(rm,regionName);if(region==null)return null;
            Object min=region.getClass().getMethod("getMinimumPoint").invoke(region);Object max=region.getClass().getMethod("getMaximumPoint").invoke(region);
            int minX=(int)min.getClass().getMethod("x").invoke(min),minY=(int)min.getClass().getMethod("y").invoke(min),minZ=(int)min.getClass().getMethod("z").invoke(min);
            int maxX=(int)max.getClass().getMethod("x").invoke(max),maxY=(int)max.getClass().getMethod("y").invoke(max),maxZ=(int)max.getClass().getMethod("z").invoke(max);
            int x=ThreadLocalRandom.current().nextInt(Math.min(minX,maxX),Math.max(minX,maxX)+1);
            int y=ThreadLocalRandom.current().nextInt(Math.min(minY,maxY),Math.max(minY,maxY)+1);
            int z=ThreadLocalRandom.current().nextInt(Math.min(minZ,maxZ),Math.max(minZ,maxZ)+1);
            return new Location(world,x,y,z);
        }catch(Throwable t){plugin.getLogger().fine("WorldGuard random candidate failed: "+t.getMessage());return null;}
    }

    public Location randomLocation(String regionName,World world){
        for(int i=0;i<Math.max(10,plugin.getConfig().getInt("spawn.region-location-attempts",50));i++){
            Location candidate=randomCandidate(regionName,world); if(candidate==null)return null;
            int y=world.getHighestBlockYAt(candidate.getBlockX(),candidate.getBlockZ())+1;
            candidate.setY(y);
            if(contains(candidate,regionName)||contains(candidate.clone().add(0,-1,0),regionName))return candidate;
        }
        return null;
    }
}

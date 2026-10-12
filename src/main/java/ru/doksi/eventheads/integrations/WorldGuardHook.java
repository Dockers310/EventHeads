package ru.doksi.eventheads.integrations;

// RU: Необязательная интеграция с WorldGuard для проверки и поиска регионов.
// EN: Optional WorldGuard integration for region validation and location search.

import ru.doksi.eventheads.EventHeadsPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldGuardHook {
    private final EventHeadsPlugin plugin;
    private volatile boolean available;
    // RU: Методы WorldGuard находятся один раз при включении. Если API не распознан, интеграция
    // выключается с предупреждением в консоли, а не молча отвечает «вне региона» на каждый запрос.
    private Method wgInstance,wgPlatform,platformContainer,adaptWorld,containerGet,managerRegion,regionContains,regionMin,regionMax,vectorX,vectorY,vectorZ;
    public WorldGuardHook(EventHeadsPlugin plugin){this.plugin=plugin; reload();}
    public void reload(){
        available=false;
        if(!plugin.getConfig().getBoolean("worldguard.enabled",true) || plugin.getServer().getPluginManager().getPlugin("WorldGuard")==null) return;
        try{
            resolveApi();
            available=true;
        }catch(Throwable t){
            plugin.getLogger().warning("WorldGuard найден, но его API не распознан ("+t+"). Регионы WorldGuard в событиях работать не будут.");
        }
    }
    public boolean available(){return available;}

    private void resolveApi() throws ReflectiveOperationException{
        // Классы только ищутся, без инициализации: её выполняют сами WorldGuard и WorldEdit.
        ClassLoader loader=getClass().getClassLoader();
        Class<?> worldGuard=Class.forName("com.sk89q.worldguard.WorldGuard",false,loader);
        wgInstance=worldGuard.getMethod("getInstance");
        wgPlatform=worldGuard.getMethod("getPlatform");
        platformContainer=wgPlatform.getReturnType().getMethod("getRegionContainer");
        // BukkitAdapter находится в WorldEdit; класса com.sk89q.worldguard.bukkit.BukkitAdapter в WorldGuard 7 нет.
        adaptWorld=Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter",false,loader).getMethod("adapt",World.class);
        containerGet=platformContainer.getReturnType().getMethod("get",adaptWorld.getReturnType());
        managerRegion=containerGet.getReturnType().getMethod("getRegion",String.class);
        Class<?> region=managerRegion.getReturnType();
        regionContains=region.getMethod("contains",int.class,int.class,int.class);
        regionMin=region.getMethod("getMinimumPoint");
        regionMax=region.getMethod("getMaximumPoint");
        Class<?> vector=regionMin.getReturnType();
        vectorX=coordinate(vector,"x","getX","getBlockX");
        vectorY=coordinate(vector,"y","getY","getBlockY");
        vectorZ=coordinate(vector,"z","getZ","getBlockZ");
    }
    /** В разных версиях WorldEdit координата вектора называется x(), getX() или getBlockX(). */
    static Method coordinate(Class<?> vector,String... names) throws NoSuchMethodException{
        for(String name:names){try{return vector.getMethod(name);}catch(NoSuchMethodException ignored){}}
        throw new NoSuchMethodException(vector.getName()+"."+names[0]);
    }

    /** Регион WorldGuard по имени или null. Данные регионов можно читать из любого потока. */
    private Object region(World world,String regionName) throws ReflectiveOperationException{
        Object platform=wgPlatform.invoke(wgInstance.invoke(null)); if(platform==null)return null;
        Object container=platformContainer.invoke(platform); if(container==null)return null;
        Object manager=containerGet.invoke(container,adaptWorld.invoke(null,world)); if(manager==null)return null;
        return managerRegion.invoke(manager,regionName);
    }
    private boolean inside(Object region,int x,int y,int z) throws ReflectiveOperationException{
        return Boolean.TRUE.equals(regionContains.invoke(region,x,y,z));
    }
    /** Границы региона: {minX,minY,minZ,maxX,maxY,maxZ}. */
    private int[] bounds(Object region) throws ReflectiveOperationException{
        Object min=regionMin.invoke(region),max=regionMax.invoke(region);
        return new int[]{((Number)vectorX.invoke(min)).intValue(),((Number)vectorY.invoke(min)).intValue(),((Number)vectorZ.invoke(min)).intValue(),
                ((Number)vectorX.invoke(max)).intValue(),((Number)vectorY.invoke(max)).intValue(),((Number)vectorZ.invoke(max)).intValue()};
    }

    public boolean contains(Location loc,String regionName){
        if(!available||regionName==null||regionName.isBlank())return true;
        try{
            Object region=region(loc.getWorld(),regionName);
            return region!=null && inside(region,loc.getBlockX(),loc.getBlockY(),loc.getBlockZ());
        }catch(Throwable t){plugin.getLogger().fine("WorldGuard contains failed: "+t.getMessage());return false;}
    }
    /** Returns a random coordinate inside the region bounds without touching terrain. */
    public Location randomCandidate(String regionName,World world){
        if(!available||regionName==null||regionName.isBlank()||world==null)return null;
        try{
            Object region=region(world,regionName);if(region==null)return null;
            int[] b=bounds(region);
            int x=ThreadLocalRandom.current().nextInt(Math.min(b[0],b[3]),Math.max(b[0],b[3])+1);
            int y=ThreadLocalRandom.current().nextInt(Math.min(b[1],b[4]),Math.max(b[1],b[4])+1);
            int z=ThreadLocalRandom.current().nextInt(Math.min(b[2],b[5]),Math.max(b[2],b[5])+1);
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

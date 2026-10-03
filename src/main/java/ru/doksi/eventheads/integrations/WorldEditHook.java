package ru.doksi.eventheads.integrations;

// RU: Необязательная интеграция с WorldEdit через безопасный reflection hook.
// EN: Optional WorldEdit integration using a reflection-based hook.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.events.EventDefinition;
import org.bukkit.entity.Player;
import java.lang.reflect.Method;

public final class WorldEditHook {
    private final EventHeadsPlugin plugin; private boolean available;
    public WorldEditHook(EventHeadsPlugin plugin){this.plugin=plugin;reload();}
    public void reload(){available=plugin.getConfig().getBoolean("worldedit.enabled",true) && plugin.getServer().getPluginManager().getPlugin("WorldEdit")!=null;}
    public boolean available(){return available;}
    public boolean captureSelection(Player player,EventDefinition event){
        if(!available)return false;
        try{
            Object we=Class.forName("com.sk89q.worldedit.bukkit.WorldEditPlugin").cast(plugin.getServer().getPluginManager().getPlugin("WorldEdit"));
            Object sel=we.getClass().getMethod("getSelection",Player.class).invoke(we,player);if(sel==null)return false;
            Object min=sel.getClass().getMethod("getMinimumPoint").invoke(sel);Object max=sel.getClass().getMethod("getMaximumPoint").invoke(sel);
            Method getX=min.getClass().getMethod("getX"),getY=min.getClass().getMethod("getY"),getZ=min.getClass().getMethod("getZ");
            event.areaMinX=((Number)getX.invoke(min)).intValue();event.areaMinY=((Number)getY.invoke(min)).intValue();event.areaMinZ=((Number)getZ.invoke(min)).intValue();
            event.areaMaxX=((Number)max.getClass().getMethod("getX").invoke(max)).intValue();event.areaMaxY=((Number)max.getClass().getMethod("getY").invoke(max)).intValue();event.areaMaxZ=((Number)max.getClass().getMethod("getZ").invoke(max)).intValue();event.hasArea=true;event.worldGuardWorld=player.getWorld().getName();return true;
        }catch(Throwable t){plugin.getLogger().fine("WorldEdit selection unavailable: "+t.getMessage());return false;}
    }
}

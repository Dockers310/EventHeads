package ru.doksi.eventheads.integrations;

// RU: Необязательная интеграция с WorldEdit через безопасный reflection hook.
// EN: Optional WorldEdit integration using a reflection-based hook.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.events.EventDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.lang.reflect.Method;

public final class WorldEditHook {
    private final EventHeadsPlugin plugin; private volatile boolean available;
    // RU: В WorldEdit 7 выделение берётся из сессии игрока: WorldEditPlugin.getSelection(Player) там нет.
    private Method pluginSession,sessionWorld,sessionSelection,worldName,regionMin,regionMax,vectorX,vectorY,vectorZ;
    public WorldEditHook(EventHeadsPlugin plugin){this.plugin=plugin;reload();}
    public void reload(){
        available=false;
        if(!plugin.getConfig().getBoolean("worldedit.enabled",true) || plugin.getServer().getPluginManager().getPlugin("WorldEdit")==null) return;
        try{
            resolveApi();
            available=true;
        }catch(Throwable t){
            plugin.getLogger().warning("WorldEdit найден, но его API не распознан ("+t+"). Сохранение выделения в событиях работать не будет.");
        }
    }
    public boolean available(){return available;}

    private void resolveApi() throws ReflectiveOperationException{
        // Класс только ищется, без инициализации: её выполняет сам WorldEdit.
        pluginSession=Class.forName("com.sk89q.worldedit.bukkit.WorldEditPlugin",false,getClass().getClassLoader()).getMethod("getSession",Player.class);
        Class<?> session=pluginSession.getReturnType();
        sessionWorld=session.getMethod("getSelectionWorld");
        sessionSelection=session.getMethod("getSelection",sessionWorld.getReturnType());
        worldName=sessionWorld.getReturnType().getMethod("getName");
        Class<?> region=sessionSelection.getReturnType();
        regionMin=region.getMethod("getMinimumPoint");
        regionMax=region.getMethod("getMaximumPoint");
        Class<?> vector=regionMin.getReturnType();
        vectorX=WorldGuardHook.coordinate(vector,"x","getX","getBlockX");
        vectorY=WorldGuardHook.coordinate(vector,"y","getY","getBlockY");
        vectorZ=WorldGuardHook.coordinate(vector,"z","getZ","getBlockZ");
    }

    public boolean captureSelection(Player player,EventDefinition event){
        if(!available||player==null||event==null)return false;
        try{
            Object worldEdit=plugin.getServer().getPluginManager().getPlugin("WorldEdit");
            if(worldEdit==null)return false;
            return applySelection(pluginSession.invoke(worldEdit,player),event,player.getWorld().getName());
        }catch(Throwable t){plugin.getLogger().fine("WorldEdit selection unavailable: "+t.getMessage());return false;}
    }

    /** Записывает выделение из сессии WorldEdit в событие. false — выделения нет или оно неполное. */
    private boolean applySelection(Object session,EventDefinition event,String fallbackWorld){
        try{
            if(session==null)return false;
            Object world=sessionWorld.invoke(session);
            if(world==null)return false;
            Object selection=sessionSelection.invoke(session,world);
            if(selection==null)return false;
            Object min=regionMin.invoke(selection),max=regionMax.invoke(selection);
            event.areaMinX=((Number)vectorX.invoke(min)).intValue();event.areaMinY=((Number)vectorY.invoke(min)).intValue();event.areaMinZ=((Number)vectorZ.invoke(min)).intValue();
            event.areaMaxX=((Number)vectorX.invoke(max)).intValue();event.areaMaxY=((Number)vectorY.invoke(max)).intValue();event.areaMaxZ=((Number)vectorZ.invoke(max)).intValue();
            event.hasArea=true;
            // Выделение может быть сделано в другом мире, чем тот, где сейчас стоит сотрудник.
            String selected=String.valueOf(worldName.invoke(world));
            event.worldGuardWorld=Bukkit.getWorld(selected)!=null?selected:fallbackWorld;
            return true;
        }catch(Throwable t){
            // Неполное выделение WorldEdit сообщает исключением IncompleteRegionException.
            plugin.getLogger().fine("WorldEdit selection unavailable: "+t);
            return false;
        }
    }
}

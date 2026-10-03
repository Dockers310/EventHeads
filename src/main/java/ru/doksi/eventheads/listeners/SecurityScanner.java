package ru.doksi.eventheads.listeners;

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.roles.Perm;
import ru.doksi.eventheads.util.SchedulerUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Folia-safe protection of EventHeads items in player inventories and containers. */
public final class SecurityScanner implements Listener {
    private final EventHeadsPlugin plugin;
    private final Map<UUID, ScheduledTask> playerTasks=new ConcurrentHashMap<>();
    private final java.util.Set<ChunkKey> trackedChunks=ConcurrentHashMap.newKeySet();
    public SecurityScanner(EventHeadsPlugin p){plugin=p;}

    private record ChunkKey(UUID world,int x,int z) {}

    public void startPlayerScan(Player p){
        if(p==null)return;
        stopPlayerScan(p);
        ScheduledTask task=SchedulerUtil.runEntityRepeating(plugin,p,()->cleanIfUnauthorized(p),20L,20L);
        if(task!=null)playerTasks.put(p.getUniqueId(),task);
    }
    public void stopPlayerScan(Player p){
        if(p==null)return;
        ScheduledTask task=playerTasks.remove(p.getUniqueId());
        if(task!=null)task.cancel();
    }
    public void trackChunk(Chunk chunk){if(chunk!=null&&chunk.getWorld()!=null)trackedChunks.add(new ChunkKey(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ()));}
    public void untrackChunk(Chunk chunk){if(chunk!=null&&chunk.getWorld()!=null)trackedChunks.remove(new ChunkKey(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ()));}

    @EventHandler public void held(PlayerItemHeldEvent e){scheduleClean(e.getPlayer());}
    @EventHandler public void world(PlayerChangedWorldEvent e){scheduleClean(e.getPlayer());}
    @EventHandler public void respawn(PlayerRespawnEvent e){scheduleClean(e.getPlayer());}
    @EventHandler public void open(InventoryOpenEvent e){
        if(e.getPlayer() instanceof Player p && !allowed(p)) SchedulerUtil.runEntity(plugin,p,()->{cleanPlayerInventories(p);clean(e.getInventory());});
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(e.getWhoClicked() instanceof Player p && !allowed(p)){
            SchedulerUtil.runEntity(plugin,p,()->cleanPlayerInventories(p));
            if(e.getClickedInventory()!=null) SchedulerUtil.runEntity(plugin,p,()->clean(e.getClickedInventory()));
        }
    }
    @EventHandler public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p && !allowed(p))SchedulerUtil.runEntity(plugin,p,()->cleanPlayerInventories(p));}
    @EventHandler public void close(InventoryCloseEvent e){if(e.getPlayer() instanceof Player p && !allowed(p))scheduleClean(p);}
    @EventHandler public void drop(PlayerDropItemEvent e){
        if(!allowed(e.getPlayer()) && protectedItem(e.getItemDrop().getItemStack())){e.setCancelled(true);e.getItemDrop().remove();scheduleClean(e.getPlayer());}
    }
    @EventHandler public void pickup(EntityPickupItemEvent e){
        if(!(e.getEntity() instanceof Player p)||allowed(p)||!protectedItem(e.getItem().getItemStack()))return;
        e.setCancelled(true);e.getItem().remove();
    }
    private void scheduleClean(Player p){SchedulerUtil.runEntity(plugin,p,()->cleanIfUnauthorized(p));}
    private void cleanIfUnauthorized(Player p){if(!allowed(p))cleanPlayerInventories(p);}
    private void cleanPlayerInventories(Player p){
        if(!plugin.getConfig().getBoolean("security.remove-from-unauthorized-inventory",true))return;
        clean(p.getInventory());if(plugin.getConfig().getBoolean("security.remove-from-ender-chests",true))clean(p.getEnderChest());
    }
    public void scanPlayers(){for(Player p:plugin.getServer().getOnlinePlayers())scheduleClean(p);}
    /** Scans only chunks that have actually announced themselves as loaded. Each chunk is handled by its own region. */
    public void scan(){
        if(!plugin.getConfig().getBoolean("security.remove-from-containers",true))return;
        for(ChunkKey key:new java.util.ArrayList<>(trackedChunks)){
            World w=Bukkit.getWorld(key.world());
            if(w==null)continue;
            org.bukkit.Location anchor=new org.bukkit.Location(w,key.x()*16+0.5D,Math.max(w.getMinHeight(),64),key.z()*16+0.5D);
            SchedulerUtil.runRegion(plugin,anchor,()->{
                if(!w.isChunkLoaded(key.x(),key.z())){trackedChunks.remove(key);return;}
                Chunk chunk=w.getChunkAt(key.x(),key.z());
                for(BlockState state:chunk.getTileEntities(false))if(state instanceof Container c)clean(c.getInventory());
            });
        }
    }
    private boolean allowed(Player p){return plugin.access().has(p,Perm.GIVE)||plugin.access().has(p,Perm.EDIT)||plugin.access().has(p,Perm.CREATE)||plugin.access().has(p,Perm.POINTS);}
    private boolean protectedItem(org.bukkit.inventory.ItemStack item){return plugin.items().isEventItem(item)||plugin.items().isWand(item)||plugin.items().isRemoveTool(item);}
    private void clean(Inventory inv){if(inv==null)return;for(int i=0;i<inv.getSize();i++)if(protectedItem(inv.getItem(i)))inv.setItem(i,null);}
}

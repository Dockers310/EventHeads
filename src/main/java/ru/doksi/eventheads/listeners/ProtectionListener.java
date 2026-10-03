package ru.doksi.eventheads.listeners;

// RU: Защита ивентовых предметов от несанкционированного перемещения.
// EN: Protects EventHeads items from unauthorized movement and duplication.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.roles.Perm;
import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.entity.*;import org.bukkit.event.inventory.*;import org.bukkit.event.player.*;import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;

public final class ProtectionListener implements Listener {
 private final EventHeadsPlugin plugin; public ProtectionListener(EventHeadsPlugin p){plugin=p;}
 private boolean allowed(Player p){return plugin.access().has(p,Perm.GIVE)||plugin.access().has(p,Perm.EDIT)||plugin.access().has(p,Perm.CREATE);}
 private boolean is(ItemStack i){return plugin.items().isEventItem(i)||plugin.items().isWand(i)||plugin.items().isRemoveTool(i);}
 private void cleanPlayerEnder(Player p){if(!allowed(p))for(int i=0;i<p.getEnderChest().getSize();i++)if(is(p.getEnderChest().getItem(i)))p.getEnderChest().setItem(i,null);}
 @EventHandler public void open(InventoryOpenEvent e){if(!(e.getPlayer() instanceof Player p)||allowed(p))return;Inventory inv=e.getInventory();for(int i=0;i<inv.getSize();i++)if(is(inv.getItem(i)))inv.setItem(i,null);cleanPlayerEnder(p);}
 @EventHandler public void click(InventoryClickEvent e){if(!(e.getWhoClicked() instanceof Player p)||!plugin.getConfig().getBoolean("security.protect-event-items",true))return;ItemStack cur=e.getCurrentItem(), cursor=e.getCursor();if((is(cur)||is(cursor))&&!allowed(p)){e.setCancelled(true);if(is(cur))e.setCurrentItem(null);if(is(cursor))e.setCursor(null);}}
 @EventHandler public void drag(InventoryDragEvent e){if(!(e.getWhoClicked() instanceof Player p)||allowed(p))return;if(is(e.getOldCursor()))e.setCancelled(true);}
 @EventHandler public void pickup(EntityPickupItemEvent e){if(!(e.getEntity() instanceof Player p)||!is(e.getItem().getItemStack()))return;if(!allowed(p)){e.getItem().remove();e.setCancelled(true);}}
 @EventHandler public void drop(PlayerDropItemEvent e){if(is(e.getItemDrop().getItemStack())&&!allowed(e.getPlayer())){e.setCancelled(true);e.getItemDrop().remove();}}
 @EventHandler public void death(PlayerDeathEvent e){e.getDrops().removeIf(this::is);}
 @EventHandler public void move(InventoryMoveItemEvent e){if(is(e.getItem()))e.setCancelled(true);}
 @EventHandler public void pickupContainer(InventoryPickupItemEvent e){if(is(e.getItem().getItemStack())){e.setCancelled(true);e.getItem().remove();}}
 @EventHandler public void prepare(PrepareInventoryResultEvent e){ItemStack r=e.getResult();if(is(r))e.setResult(null);}
 @EventHandler public void creative(InventoryCreativeEvent e){if(!(e.getWhoClicked() instanceof Player p)||allowed(p)||!plugin.getConfig().getBoolean("security.block-creative-cloning",true))return;if(is(e.getCursor())||is(e.getCurrentItem()))e.setCancelled(true);}
 @EventHandler public void place(org.bukkit.event.block.BlockPlaceEvent e){ItemStack item=e.getItemInHand();if(plugin.items().isEventItem(item)&&!allowed(e.getPlayer())){e.setCancelled(true);if(e.getHand()==org.bukkit.inventory.EquipmentSlot.OFF_HAND)e.getPlayer().getInventory().setItemInOffHand(null);else e.getPlayer().getInventory().setItemInMainHand(null);}}
 @EventHandler public void swap(PlayerSwapHandItemsEvent e){if(!allowed(e.getPlayer())&&(is(e.getMainHandItem())||is(e.getOffHandItem())))e.setCancelled(true);}
}

package ru.doksi.eventheads.gui;

// RU: Маркер GUI, позволяющий отличать окна EventHeads от обычных инвентарей.
// EN: GUI holder marker used to distinguish EventHeads inventories from normal containers.
import org.bukkit.inventory.Inventory;import org.bukkit.inventory.InventoryHolder;
public final class EventMenuHolder implements InventoryHolder { public final String type; public final String eventId; public final int page; public EventMenuHolder(String t,String id){this(t,id,1);} public EventMenuHolder(String t,String id,int page){type=t;eventId=id;this.page=Math.max(1,page);} private Inventory inv; public void inventory(Inventory i){inv=i;} @Override public Inventory getInventory(){return inv;} }

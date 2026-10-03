package ru.doksi.eventheads.listeners;

// RU: Обработка установки точек, сбора и работы специальных инструментов EventHeads.
// EN: Handles point placement, collection and special EventHeads tools.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.EventInstance;
import ru.doksi.eventheads.events.SpawnPoint;
import ru.doksi.eventheads.players.PlayerProxy;
import ru.doksi.eventheads.roles.Perm;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class InteractionListener implements Listener {
    private final EventHeadsPlugin plugin;
    private final Map<UUID,String> pointMode = new java.util.concurrent.ConcurrentHashMap<>();
    /** Prevents one physical click from firing both interaction/damage handlers repeatedly. */
    private final Map<UUID,Long> instanceActionLock = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey instanceKey,eventIdKey,decoyKey,tierKey;

    public InteractionListener(EventHeadsPlugin p){
        plugin=p;
        instanceKey=new NamespacedKey(p,"instance");
        eventIdKey=new NamespacedKey(p,"event_id"); decoyKey=new NamespacedKey(p,"decoy");tierKey=new NamespacedKey(p,"decoy_tier");
    }

    /**
     * Запоминает события для режима постановки точек. Сам физический инструмент
     * также содержит эти ID в PDC, поэтому его можно передать другому сотруднику
     * и он всё равно будет знать, к каким событиям относится.
     */
    public void setPointMode(Player p,String id){pointMode.put(p.getUniqueId(),id);} public void setPointMode(Player p,java.util.Collection<String> ids){pointMode.put(p.getUniqueId(),String.join(",",ids));}
    public void cancel(Player p){pointMode.remove(p.getUniqueId());}

    private boolean sameBlock(Location a,Location b){
        return a!=null&&b!=null&&a.getWorld()!=null&&b.getWorld()!=null&&a.getWorld().getUID().equals(b.getWorld().getUID())&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();
    }

    @EventHandler
    /** Обрабатывает оба клика по блоку: обычный режим точек и служебную лапку блокировок. */
    public void block(PlayerInteractEvent e){
        Player p=e.getPlayer();
        if(e.getHand()!=EquipmentSlot.HAND)return;
        ItemStack hand=e.getItem();
        // Защита служебных инструментов: обычная rabbit hide/foot не блокируется.
        if((plugin.items().isWand(hand)||plugin.items().isRemoveTool(hand)) && !plugin.access().has(p,Perm.POINTS)){
            if(plugin.getConfig().getBoolean("security.remove-unauthorized-tools",true)){
                e.setCancelled(true); if(e.getAction()!=Action.PHYSICAL) p.getInventory().setItemInMainHand(null);
                plugin.lang().send(p, "§cЭто служебный инструмент EventHeads. У вас нет права eventheads.points."); return;
            }
        }

        // Кроличья лапка: удаление конкретной точки/ивента или глобальная блокировка.
        if(hand!=null&&plugin.items().isRemoveTool(hand)&&plugin.access().has(p,Perm.POINTS)){
            if(e.getClickedBlock()!=null&&(e.getAction()==Action.LEFT_CLICK_BLOCK||e.getAction()==Action.RIGHT_CLICK_BLOCK)){
                Location clicked=e.getClickedBlock().getLocation();
                Location target=clicked.clone().add(0,1,0);
                String targetEvent=plugin.items().removeToolEvent(hand);

                if("*".equals(targetEvent)){
                    if(p.isSneaking() && plugin.data().removeGlobalBlocked(target)){plugin.data().log("blocked.global.remove",p.getName(),target.getBlockX()+","+target.getBlockY()+","+target.getBlockZ());
                        plugin.lang().send(p, "§aОбщий запрет спавна снят с этого места.");
                    } else if(!plugin.data().isGlobalBlocked(target)) {
                        plugin.data().addGlobalBlocked(target); plugin.data().log("blocked.global.add",p.getName(),target.getBlockX()+","+target.getBlockY()+","+target.getBlockZ());
                        for(EventInstance inst:new ArrayList<>(plugin.spawner().instances().values()))
                            if(sameBlock(inst.location,target)||sameBlock(inst.location,clicked)) plugin.spawner().breakByModerator(inst.instanceId);
                        plugin.lang().send(p, "§aМесто добавлено в общий чёрный список EventHeads. Здесь не спавнится ни один ивентовый предмет.");
                    } else plugin.lang().send(p, "§eЗдесь уже установлен общий запрет. Shift+ЛКМ/ПКМ — снять его.");
                    e.setCancelled(true);return;
                }

                EventDefinition selected=targetEvent==null?null:plugin.events().get(targetEvent);
                if(selected==null){plugin.lang().send(p, "§cИвент для этой кроличьей лапки не найден.");e.setCancelled(true);return;}
                SpawnPoint found=null;
                for(SpawnPoint sp:selected.points){if(sameBlock(sp.location,target)||sameBlock(sp.location,clicked)){found=sp;break;}}
                if(found!=null){if(!plugin.access().canEditPoint(p.getUniqueId(),found)){plugin.lang().send(p, plugin.access().pointEditDenied());e.setCancelled(true);return;}selected.points.remove(found);plugin.data().saveEvent(selected);plugin.data().log("point.remove",p.getName(),selected.id+" #"+(selected.points.size()+1)+" at "+found.location.getBlockX()+","+found.location.getBlockY()+","+found.location.getBlockZ());plugin.lang().send(p, "§aТочка §f"+selected.id+" §aудалена.");}
                else if(p.isSneaking() && selected.blockedLocations.removeIf(x->sameBlock(x,target))){plugin.data().saveEvent(selected);plugin.data().log("blocked.remove",p.getName(),selected.id+" -> "+target.getBlockX()+","+target.getBlockY()+","+target.getBlockZ());plugin.lang().send(p, "§aЗапрет спавна для §f"+selected.id+" §aснят.");}
                else if(selected.blockedLocations.stream().noneMatch(x->sameBlock(x,target))){
                    selected.blockedLocations.add(target);plugin.data().saveEvent(selected);plugin.data().log("blocked.add",p.getName(),selected.id+" -> "+target.getBlockX()+","+target.getBlockY()+","+target.getBlockZ());
                    for(EventInstance inst:new ArrayList<>(plugin.spawner().instances().values()))
                        if(inst.eventId.equalsIgnoreCase(selected.id)&&(sameBlock(inst.location,target)||sameBlock(inst.location,clicked))) plugin.spawner().breakByModerator(inst.instanceId);
                    plugin.lang().send(p, "§aМесто добавлено в чёрный список спавна для §f"+selected.id+"§a.");
                } else plugin.lang().send(p, "§eНа этом месте уже установлен запрет спавна для §f"+selected.id+"§e.");
                e.setCancelled(true);return;
            }
        }

        // Кроличья шкурка: постоянный режим добавления точек.
        if(hand!=null&&plugin.items().isWand(hand)&&e.getClickedBlock()!=null&&
                (e.getAction()==Action.LEFT_CLICK_BLOCK||e.getAction()==Action.RIGHT_CLICK_BLOCK)&&pointMode.containsKey(p.getUniqueId())){
            String ids=String.join(",",plugin.items().wandEvents(hand));
            if(ids.isBlank()) ids=pointMode.get(p.getUniqueId());
            if(ids==null||ids.isBlank()){ e.setCancelled(true); return; }
            Location loc=e.getClickedBlock().getLocation().add(0,1,0);loc.setYaw(p.getLocation().getYaw());loc.setPitch(0);
            List<String> added=new ArrayList<>();List<String> failed=new ArrayList<>();
            for(String rawId:ids.split(",")){String id=rawId.trim();EventDefinition ev=plugin.events().get(id);if(ev==null)continue;String pointLimitReason=plugin.access().pointPlacementReason(p.getUniqueId(),loc);if(pointLimitReason!=null){failed.add(ev.playerName+" — "+pointLimitReason);continue;}String reason=plugin.spawner().validationReasonForPoint(ev,loc);if(reason!=null){failed.add(ev.playerName+" — "+reason);continue;}if(ev.points.stream().anyMatch(sp->sameBlock(sp.location,loc))){failed.add(ev.playerName+" — такая точка уже существует");continue;}SpawnPoint addedPoint=new SpawnPoint(UUID.randomUUID().toString(),loc.clone(),100,true,p.getUniqueId());ev.points.add(addedPoint);plugin.data().saveEvent(ev);plugin.data().log("point.add",p.getName(),ev.id+" #"+ev.points.size()+" at "+loc.getBlockX()+","+loc.getBlockY()+","+loc.getBlockZ());added.add(ev.playerName+" #"+ev.points.size());}
            if(!added.isEmpty())plugin.lang().send(p, "§a✓ Точка добавлена: §f"+String.join("§7, §f",added));
            if(!failed.isEmpty())plugin.lang().send(p, "§eНекоторые события пропущены: §7"+String.join(" §8| §7", failed));
            e.setCancelled(true);return;
        }

        // Совместимость со статическими головами, если они существуют в старых данных.
        if(e.getAction()==Action.RIGHT_CLICK_BLOCK||e.getAction()==Action.LEFT_CLICK_BLOCK){
            Block b=e.getClickedBlock();
            if(b!=null&&b.getState() instanceof Skull skull){
                String id=skull.getPersistentDataContainer().get(eventIdKey,PersistentDataType.STRING);
                if(id!=null){
                    e.setCancelled(true);
                    if(plugin.access().has(p,Perm.BREAK)&&e.getAction()==Action.LEFT_CLICK_BLOCK){
                        b.setType(Material.AIR);
                        plugin.lang().send(p, "§aИвентовая точка удалена. Предмет не выпал.");
                    }
                }
            }
        }
    }

    @EventHandler
    public void place(BlockPlaceEvent e){
        ItemStack item=e.getItemInHand();
        Player p=e.getPlayer();
        if(plugin.items().isEventItem(item) && !plugin.access().has(p,Perm.GIVE)){e.setCancelled(true);p.getInventory().setItemInMainHand(null);plugin.lang().send(p, "§cЭтот ивентовый предмет нельзя размещать без права eventheads.give.");return;}
        if(!plugin.items().isConfiguredHead(item)) return;
        if(!plugin.access().has(p,Perm.GIVE)){e.setCancelled(true);return;}
        String id=plugin.items().id(item);EventDefinition def=plugin.events().get(id);
        if(def==null){e.setCancelled(true);return;}
        // Это не настоящая декоративная голова: предмет служит маркером точки.
        e.setCancelled(true);
        Location loc=e.getBlockPlaced().getLocation();loc.setYaw(p.getLocation().getYaw());
        String pointLimitReason=plugin.access().pointPlacementReason(p.getUniqueId(),loc);
        if(pointLimitReason!=null){plugin.lang().send(p, "§cТочка не добавлена: §f"+pointLimitReason);return;}
        String reason=plugin.spawner().validationReason(def,loc,true);
        if(reason!=null){plugin.lang().send(p, "§cТочка не добавлена: §f"+reason);return;}
        if(def.points.stream().anyMatch(sp->sameBlock(sp.location,loc))){plugin.lang().send(p, "§eТакая точка уже существует.");return;}
        def.points.add(new SpawnPoint(UUID.randomUUID().toString(),loc,100,true,p.getUniqueId()));plugin.data().saveEvent(def);plugin.data().log("point.add",p.getName(),def.id+" #"+def.points.size()+" at "+loc.getBlockX()+","+loc.getBlockY()+","+loc.getBlockZ());
        plugin.lang().send(p, "§aНастроенная голова превращена в точку спавна события §f"+(def.playerName==null?id:def.playerName)+"§a.");
        if(item.getAmount()<=1)p.getInventory().setItemInMainHand(null);else item.setAmount(item.getAmount()-1);
    }

    private boolean isBuriedDecoy(ArmorStand as){
        String tier=as.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING);
        return tier==null || tier.equalsIgnoreCase("buried");
    }

    private boolean locked(UUID instanceId){
        long now=System.currentTimeMillis();
        Long until=instanceActionLock.get(instanceId);
        if(until!=null&&until>now)return true;
        instanceActionLock.put(instanceId,now+350L);
        instanceActionLock.entrySet().removeIf(x->x.getValue()<now-5000L);
        return false;
    }

    /**
     * Paper/Folia: отдельный обработчик взмаха основной рукой для ЛКМ.
     * На некоторых версиях/настройках invulnerable ArmorStand обычный
     * EntityDamageByEntityEvent может не дойти до плагина, поэтому ЛКМ
     * для обычного игрока обрабатываем напрямую по целевой сущности.
     * Административное поведение не меняем: для ролей с BREAK остаётся
     * существующая обработка EntityDamageByEntityEvent.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void armSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e){
        if(e.getHand()!=EquipmentSlot.HAND)return;
        Player p=e.getPlayer();
        if(plugin.access().has(p,Perm.BREAK))return;

        org.bukkit.entity.Entity target=p.getTargetEntity(6);
        if(!(target instanceof ArmorStand))return;
        ArmorStand as=(ArmorStand)target;

        if(as.getPersistentDataContainer().has(decoyKey,PersistentDataType.STRING))return;
        if(as.getPersistentDataContainer().has(new NamespacedKey(plugin,"eventheads_animation"),PersistentDataType.BYTE))return;

        String raw=as.getPersistentDataContainer().get(instanceKey,PersistentDataType.STRING);
        if(raw==null)return;

        try{
            UUID id=UUID.fromString(raw);
            if(locked(id))return;
            if(plugin.spawner().instances().get(id)==null){
                as.remove();
                return;
            }

            // ЛКМ обычного игрока = обычный сбор, те же условия, что и для ПКМ.
            plugin.spawner().collect(id,new PlayerProxy(p));
        }catch(Exception ex){
            if(plugin.getConfig().getBoolean("debug",false)){
                plugin.getLogger().warning("Некорректный PDC EventHeads при ЛКМ: "+raw);
            }
        }
    }

    @EventHandler
    public void entity(PlayerInteractEntityEvent e){
        if(!(e.getRightClicked() instanceof ArmorStand as))return;
        if(as.getPersistentDataContainer().has(decoyKey,PersistentDataType.STRING)){
            e.setCancelled(true);
            if(e.getHand()==EquipmentSlot.HAND) plugin.antiEspReport().trigger(e.getPlayer(),as.getLocation(),"interact",isBuriedDecoy(as));
            as.remove(); return;
        }
        String raw=as.getPersistentDataContainer().get(instanceKey,PersistentDataType.STRING);if(raw==null)return;
        if(as.getPersistentDataContainer().has(new NamespacedKey(plugin,"eventheads_animation"),PersistentDataType.BYTE)){
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if(e.getHand()!=EquipmentSlot.HAND)return;
        try{
            UUID id=UUID.fromString(raw);
            if(locked(id))return;
            if(plugin.spawner().instances().get(id)==null){ as.remove(); return; }
            Player p=e.getPlayer();
            ItemStack hand=p.getInventory().getItemInMainHand();
            if(plugin.items().isRemoveTool(hand)&&plugin.access().has(p,Perm.POINTS)){
                EventInstance inst=plugin.spawner().instances().get(id);String target=plugin.items().removeToolEvent(hand);
                if(inst!=null&&(target.equals("*")||target.equalsIgnoreCase(inst.eventId))){
                    if(target.equals("*"))plugin.data().addGlobalBlocked(inst.location.clone());
                    else {EventDefinition d=plugin.events().get(inst.eventId);if(d!=null && d.blockedLocations.stream().noneMatch(x->sameBlock(x,inst.location))){d.blockedLocations.add(inst.location.clone());plugin.data().saveEvent(d);}}
                    plugin.spawner().breakByModerator(id);
                    plugin.lang().send(p, target.equals("*")?"§aИвентовый предмет удалён. Место заблокировано для всех событий.":"§aИвентовый предмет удалён. Место заблокировано для этого события.");
                }
                return;
            }
            plugin.spawner().collect(id,new PlayerProxy(p));
            // Не удаляем ArmorStand здесь. На Folia тот же существующий EventHead
            // используется AnimationService для анимации через EntityScheduler.
        }catch(Exception ex){
            as.remove();
            if(plugin.getConfig().getBoolean("debug",false)) plugin.getLogger().warning("Некорректный PDC EventHeads на ArmorStand: "+raw);
        }
    }

    @EventHandler
    public void damage(EntityDamageByEntityEvent e){
        if(!(e.getEntity() instanceof ArmorStand as)||!(e.getDamager() instanceof Player p))return;
        if(as.getPersistentDataContainer().has(decoyKey,PersistentDataType.STRING)){
            e.setCancelled(true);
            if(e.getCause()==org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) plugin.antiEspReport().trigger(p,as.getLocation(),"attack",isBuriedDecoy(as));
            as.remove(); return;
        }
        String raw=as.getPersistentDataContainer().get(instanceKey,PersistentDataType.STRING);if(raw==null)return;
        if(as.getPersistentDataContainer().has(new NamespacedKey(plugin,"eventheads_animation"),PersistentDataType.BYTE)){
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        try{
            UUID id=UUID.fromString(raw);
            if(locked(id))return;
            if(plugin.spawner().instances().get(id)==null){ as.remove(); return; }
            ItemStack hand=p.getInventory().getItemInMainHand();
            if(plugin.items().isRemoveTool(hand)&&plugin.access().has(p,Perm.POINTS)){
                EventInstance inst=plugin.spawner().instances().get(id);String target=plugin.items().removeToolEvent(hand);
                if(inst!=null&&(target.equals("*")||target.equalsIgnoreCase(inst.eventId))){
                    if(target.equals("*"))plugin.data().addGlobalBlocked(inst.location.clone());
                    else {EventDefinition d=plugin.events().get(inst.eventId);if(d!=null && d.blockedLocations.stream().noneMatch(x->sameBlock(x,inst.location))){d.blockedLocations.add(inst.location.clone());plugin.data().saveEvent(d);}}
                    plugin.spawner().breakByModerator(id);
                    plugin.lang().send(p, target.equals("*")?"§aИвентовый предмет удалён. Место заблокировано для всех событий.":"§aИвентовый предмет удалён. Место заблокировано для этого события.");
                }
            } else if(plugin.access().has(p,Perm.BREAK)){
                if(plugin.spawner().breakByModerator(id)) plugin.lang().send(p, "§aИвентовый предмет удалён без выпадения.");
            } else {
                plugin.spawner().collect(id,new PlayerProxy(p));
            }
        }catch(Exception ex){
            as.remove();
            if(plugin.getConfig().getBoolean("debug",false)) plugin.getLogger().warning("Некорректный PDC EventHeads на ArmorStand: "+raw);
        }
    }
    @EventHandler
    public void armorStandManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent e){
        if(e.getRightClicked().getPersistentDataContainer().has(new NamespacedKey(plugin,"instance"),PersistentDataType.STRING) || e.getRightClicked().getPersistentDataContainer().has(new NamespacedKey(plugin,"decoy"),PersistentDataType.STRING)){
            e.setCancelled(true);
        }
    }

}

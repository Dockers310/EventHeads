package ru.doksi.eventheads.services;

// RU: Создание и проверка PDC-идентификаторов EventHeads предметов и инструментов.
// EN: Creates and verifies PDC identities for EventHeads items and tools.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.localization.ChatColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

public final class ItemService {
    private final EventHeadsPlugin plugin;
    private final NamespacedKey marker,idKey,instanceKey,wandKey,editorEventKey,removeToolKey,removeToolEventKey,wandEventsKey;
    public ItemService(EventHeadsPlugin p){
        plugin=p; marker=new NamespacedKey(p,"event_item"); idKey=new NamespacedKey(p,"event_id");
        instanceKey=new NamespacedKey(p,"event_instance"); wandKey=new NamespacedKey(p,"editor_wand"); editorEventKey=new NamespacedKey(p,"editor_event"); removeToolKey=new NamespacedKey(p,"point_remove_tool"); removeToolEventKey=new NamespacedKey(p,"point_remove_event"); wandEventsKey=new NamespacedKey(p,"point_events");
    }
    public ItemStack createInstance(String id,ItemStack visual){
        ItemStack item=visual==null?new ItemStack(Material.PLAYER_HEAD):visual.clone();
        ItemMeta m=item.getItemMeta(); if(m==null)return item;
        EventDefinition e=plugin.events().get(id);
        var pdc=m.getPersistentDataContainer(); pdc.set(marker,PersistentDataType.BYTE,(byte)1); pdc.set(idKey,PersistentDataType.STRING,id); pdc.set(instanceKey,PersistentDataType.STRING,UUID.randomUUID().toString());
        List<String> lore=m.hasLore()?new java.util.ArrayList<>(m.getLore()):new java.util.ArrayList<>();
        lore.add(ChatColorUtil.color("&8────────────"));
        lore.add(ChatColorUtil.color("&dИвентовый предмет: &f"+(e==null?id:e.playerName)));
        if(e!=null){
            lore.add(ChatColorUtil.color("&7Создал: &f"+(e.authorName==null?"неизвестно":e.authorName)));
            java.time.Instant now=java.time.Instant.now();
            String status; String color; String symbol;
            if(e.startAt!=null && now.isBefore(e.startAt)){status="НЕ НАЧАЛОСЬ";color="&e";symbol="◆";}
            else if(e.endAt!=null && !now.isBefore(e.endAt)){status="ЗАВЕРШЕНО";color="&c";symbol="■";}
            else if(e.enabled){status="АКТИВНО";color="&a";symbol="●";}
            else {status="ВЫКЛЮЧЕНО";color="&7";symbol="○";}
            lore.add(ChatColorUtil.color("&7Статус: "+color+symbol+" "+status));
        }
        m.setLore(lore); item.setItemMeta(m); return item;
    }

    public boolean isEventItem(ItemStack item){
        if(item==null||item.getType()==Material.AIR||!item.hasItemMeta())return false;
        var p=item.getItemMeta().getPersistentDataContainer();
        return p.has(marker,PersistentDataType.BYTE)&&p.has(idKey,PersistentDataType.STRING)&&p.has(instanceKey,PersistentDataType.STRING);
    }
    public String id(ItemStack item){return isEventItem(item)?item.getItemMeta().getPersistentDataContainer().get(idKey,PersistentDataType.STRING):null;}
    public UUID instance(ItemStack item){try{return UUID.fromString(item.getItemMeta().getPersistentDataContainer().get(instanceKey,PersistentDataType.STRING));}catch(Exception e){return null;}}
    public boolean isWand(ItemStack i){return i!=null&&i.getType()==Material.RABBIT_HIDE&&i.hasItemMeta()&&i.getItemMeta().getPersistentDataContainer().has(wandKey,PersistentDataType.BYTE);}
    public ItemStack makeWand(){ return makeWand(java.util.List.of("*")); }
    public ItemStack makeWand(Collection<String> eventIds){
        ItemStack i=new ItemStack(Material.RABBIT_HIDE); ItemMeta m=i.getItemMeta();
        m.setDisplayName(ChatColorUtil.color("&6EventHeads • Редактор точек"));
        List<String> names=new ArrayList<>(); for(String id:eventIds){EventDefinition e=plugin.events().get(id);names.add(e==null?id:e.playerName);}
        List<String> lore=new ArrayList<>(); lore.add(ChatColorUtil.color("&8Привязанные события:")); for(String n:names) lore.add(ChatColorUtil.color("&7• &f"+n));
        lore.add(ChatColorUtil.color("&7ЛКМ/ПКМ по блоку — поставить точку.")); lore.add(ChatColorUtil.color("&7Одна шкурка может обслуживать несколько событий."));
        lore.add(ChatColorUtil.color("&8Для каждого события точка сохраняется отдельно.")); lore.add(ChatColorUtil.color("&7Завершить: &f/eventheads point cancel")); lore.add(ChatColorUtil.color("&8Требуется: eventheads.points"));
        m.setLore(lore); var pdc=m.getPersistentDataContainer(); pdc.set(wandKey,PersistentDataType.BYTE,(byte)1); pdc.set(wandEventsKey,PersistentDataType.STRING,String.join(",",eventIds)); i.setItemMeta(m); return i;
    }
    public List<String> wandEvents(ItemStack i){if(!isWand(i))return List.of();String raw=i.getItemMeta().getPersistentDataContainer().get(wandEventsKey,PersistentDataType.STRING);if(raw==null||raw.isBlank()||raw.equals("*"))return new ArrayList<>(plugin.events().keySet());return Arrays.stream(raw.split(",")).map(String::trim).filter(x->!x.isBlank()&&plugin.events().containsKey(x)).distinct().toList();}
    public String describeWand(ItemStack i){List<String> ids=wandEvents(i);if(ids.isEmpty())return "не привязан";return String.join(", ",ids.stream().map(x->{EventDefinition e=plugin.events().get(x);return e==null?x:e.playerName;}).toList());}

    public boolean isRemoveTool(ItemStack i){return i!=null&&i.getType()==Material.RABBIT_FOOT&&i.hasItemMeta()&&i.getItemMeta().getPersistentDataContainer().has(removeToolKey,PersistentDataType.BYTE);}
    public ItemStack makeRemoveTool(String eventId){
        ItemStack i=new ItemStack(Material.RABBIT_FOOT); ItemMeta m=i.getItemMeta();
        m.setDisplayName(ChatColorUtil.color("&cEventHeads • Чёрный список"));
        String target=eventId.equals("*")?"все события":plugin.events().get(eventId)==null?eventId:plugin.events().get(eventId).playerName;
        m.setLore(java.util.List.of(ChatColorUtil.color("&7Привязка: &f"+target),ChatColorUtil.color("&7ЛКМ/ПКМ по блоку — удалить точку или заблокировать место."),ChatColorUtil.color("&7Shift+ЛКМ/ПКМ — снять запрет."),ChatColorUtil.color("&7ЛКМ/ПКМ по активному EventHead — удалить его и заблокировать место."),ChatColorUtil.color("&8Требуется: eventheads.points")));
        m.getPersistentDataContainer().set(removeToolKey,PersistentDataType.BYTE,(byte)1); m.getPersistentDataContainer().set(removeToolEventKey,PersistentDataType.STRING,eventId); i.setItemMeta(m); return i;
    }

    public String removeToolEvent(ItemStack i){if(!isRemoveTool(i))return null;return i.getItemMeta().getPersistentDataContainer().get(removeToolEventKey,PersistentDataType.STRING);}
    public ItemStack makeConfiguredHead(String id){
        EventDefinition e=plugin.events().get(id); if(e==null)return null;
        ItemStack i=createInstance(id,e.visual);
        ItemMeta m=i.getItemMeta();
        if(m!=null){
            m.getPersistentDataContainer().set(editorEventKey,PersistentDataType.STRING,id);
            List<String> lore=m.getLore()==null?new ArrayList<>():new ArrayList<>(m.getLore());
            String label="Ивент: "+(e.playerName==null||e.playerName.isBlank()?e.adminName:e.playerName);
            if(lore.stream().noneMatch(line->line!=null&&ChatColorUtil.strip(line).equalsIgnoreCase(label)))lore.add(ChatColorUtil.color("&7"+label));
            m.setLore(lore);
            i.setItemMeta(m);
        }
        return i;
    }
    public boolean isConfiguredHead(ItemStack item){return isEventItem(item)&&item.getItemMeta().getPersistentDataContainer().has(editorEventKey,PersistentDataType.STRING);}
    /** Пункт меню с текстурой головы. Значение берём из зашитого Base64, поэтому меню не зависит от игрока Steve. */
    public ItemStack makeMenuHead(String type){
        String texture=switch(type.toLowerCase(Locale.ROOT)){
            case "staff" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTNjNjk0YmQxNGQ1NDVjMzFjYzJiOGU0NGQ2OWExZjdjYmQwNTA0MzFiYThmMGViN2ZmZDQ4NGM1YmZlNCJ9fX0=";
            default -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWQ4OGE4NjQ0NWQ0MTE0YjE1MTcxYmI5ZWY4Mzc0NjA2MGNhZmZmMmNiMmFhNzUxMTU5NzQwY2ZmNDhkZjg5ZSJ9fX0=";
        };
        ItemStack head=new ItemStack(Material.PLAYER_HEAD);ItemMeta meta=head.getItemMeta();
        if(meta instanceof SkullMeta skull){try{com.destroystokyo.paper.profile.PlayerProfile profile=(com.destroystokyo.paper.profile.PlayerProfile) Bukkit.createProfile(UUID.nameUUIDFromBytes(("EventHeads:"+type).getBytes(java.nio.charset.StandardCharsets.UTF_8)));profile.setProperty(new ProfileProperty("textures",texture));skull.setPlayerProfile(profile);head.setItemMeta(skull);}catch(Throwable ignored){}}
        return head;
    }

}

package ru.doksi.eventheads.commands;

// RU: Центральная точка всех /eventheads и /eh команд и их коротких вариантов.
// EN: Central command router for /eventheads, /eh and all supported aliases.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.antiesp.AntiEspReport;
import ru.doksi.eventheads.catalog.ParticleSettings;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.SpawnPoint;
import ru.doksi.eventheads.roles.CustomRole;
import ru.doksi.eventheads.roles.Perm;
import ru.doksi.eventheads.roles.Role;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;import net.kyori.adventure.text.event.ClickEvent;import net.kyori.adventure.text.event.HoverEvent;
import java.util.*;

public final class Commands implements TabExecutor {
    // RU: Все короткие команды являются только алиасами длинных. Логика хранится в одном месте,
    // чтобы /eventheads и /eh никогда не расходились по поведению.

    private final EventHeadsPlugin plugin;
    private final Map<UUID,Long> pendingRoleDeletes=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,UUID> pendingMemberDeletes=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Long> pendingMemberDeleteAt=new java.util.concurrent.ConcurrentHashMap<>();
    public Commands(EventHeadsPlugin plugin){this.plugin=plugin;}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage("EventHeads: player only.");return true;}
        if(args.length==0){ if(plugin.access().has(p,Perm.VIEW)) plugin.gui().openMain(p); else plugin.gui().openHelp(p); return true; }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("help")||sub.equals("h")){plugin.gui().openHelp(p);return true;}
        return switch(sub){
            case "menu","m" -> simple(p,Perm.VIEW,()->plugin.gui().openMain(p));
            case "list","ls" -> simple(p,Perm.VIEW,()->plugin.gui().openEvents(p));
            case "create","new" -> create(p,args);
            case "item" -> item(p,args);
            case "edit" -> item(p,new String[]{"item","edit",args.length>1?args[1]:""});
            case "del" -> item(p,new String[]{"item","delete",args.length>1?args[1]:"",args.length>2?args[2]:""});
            case "point" -> point(p,args);
            case "add" -> point(p,new String[]{"point","add",args.length>1?args[1]:""});
            case "rp" -> point(p,new String[]{"point","remove",args.length>1?args[1]:"",args.length>2?args[2]:""});
            case "pc" -> point(p,new String[]{"point","cancel"});
            case "region" -> region(p,args);
            case "reg" -> region(p,args);
            case "spawn","test" -> spawn(p,args);
            case "stats","stat","statistics" -> stats(p,args);
            case "access","acl" -> access(p,args);
            case "role" -> role(p,args);
            case "wand","hand","editor" -> wand(p);
            case "tool" -> tool(p,args);
            case "give" -> tool(p,new String[]{"tool","give",args.length>1?args[1]:""});
            case "clear" -> tool(p,new String[]{"tool","clear"});
            case "remove","rem" -> { if(args.length<2){usage(p,"/eventheads remove <id>","Выдаёт специальную кроличью лапку EventHeads для удаления точки или блокировки места случайного спавна.");yield true;} yield tool(p,new String[]{"tool","give",args[1]}); }
            case "on","enable" -> item(p,new String[]{"item","enable",args.length>1?args[1]:""});
            case "off","disable" -> item(p,new String[]{"item","disable",args.length>1?args[1]:""});
            case "reload" -> simple(p,Perm.SETTINGS,()->{plugin.lang().send(p,"§7Перезагрузка EventHeads запущена…");plugin.reloadAll(()->ru.doksi.eventheads.util.SchedulerUtil.runEntity(plugin,p,()->plugin.lang().send(p,plugin.lang().tr(p,"reload"))));});
            case "cleanup","purge" -> simple(p,Perm.SETTINGS,()->{
                int r=8;
                if(args.length>1){try{r=Integer.parseInt(args[1]);}catch(NumberFormatException ignored){}}
                final int radius=Math.max(1,Math.min(12,r));
                plugin.lang().send(p,"§7Зачистка залипших стоек EventHeads в радиусе "+radius+" чанков…");
                plugin.spawner().purgeAroundPlayer(p,radius,count->plugin.lang().send(p,"§aУдалено лишних стоек: §e"+count));
            });
            case "debug" -> simple(p,Perm.SETTINGS,()->plugin.lang().send(p, plugin.debugText()));
            case "antiesp","anti-esp","esp" -> antiEsp(p,args);
            case "data","storage" -> data(p,args);
            case "state", "status" -> simple(p,Perm.VIEW,()->plugin.gui().openScheduleOverview(p));
            case "export" -> exportEvent(p,args);
            case "import" -> importEvent(p,args);
            case "hex" -> hex(p,args);
            default -> {plugin.lang().send(p, plugin.lang().tr(p,"unknown-command"));yield true;}
        };
    }
    private boolean antiEsp(Player p,String[] a){
        if(!plugin.access().has(p,Perm.SETTINGS)){unknown(p);return true;}
        String sub=a.length>1?a[1].toLowerCase(Locale.ROOT):"top";
        if(sub.equals("hide")||sub.equals("show")){
            if(!plugin.getConfig().getBoolean("security.anti-esp-personal-visibility-commands",true)){
                plugin.lang().send(p, "§cПерсональные команды отображения Anti-ESP отключены в конфигурации EventHeads.");
                return true;
            }
            String perm=plugin.getConfig().getString("security.anti-esp-personal-visibility-permission","eventheads.anti-esp.personal");
            if(!plugin.access().has(p,Perm.SETTINGS) && !p.hasPermission(perm)) { unknown(p); return true; }
            plugin.antiEspVisibility(p,sub.equals("show"));
            plugin.lang().send(p, sub.equals("show")?"§aФейковые предметы Anti-ESP снова отображаются для вас.":"§cФейковые предметы Anti-ESP скрыты для вас.");
            return true;
        }
        if(sub.equals("top")){
            int limit=10;
            if(a.length>2)try{limit=Math.max(1,Math.min(50,Integer.parseInt(a[2])));}catch(NumberFormatException ignored){}
            List<AntiEspReport.Entry> top=plugin.antiEspReport().top(limit);
            plugin.lang().send(p, "§5§lEventHeads §7• §dТоп срабатываний Anti-ESP");
            if(top.isEmpty()){plugin.lang().send(p, "§7Срабатываний пока нет.");return true;}
            java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("dd.MM HH:mm");
            int i=1;for(AntiEspReport.Entry e:top){plugin.lang().send(p, "§8"+(i++)+". §f"+e.name+" §7— всего §f"+e.total+" §8"+(e.last>0?f.format(new java.util.Date(e.last)):"-"));}
            return true;
        }
        if(sub.equals("reset")){
            if(a.length>2){OfflinePlayer target=Bukkit.getOfflinePlayer(a[2]);int n=plugin.antiEspReport().reset(target.getUniqueId());plugin.lang().send(p, n>0?"§aСтатистика §f"+target.getName()+"§a сброшена.":"§7Записи для этого игрока нет.");}
            else {int n=plugin.antiEspReport().reset(null);plugin.lang().send(p, "§aСброшено записей: §f"+n+"§a.");}
            plugin.data().log("anti-esp-reset",p.getName(),a.length>2?sanitize(a[2]):"ALL");
            return true;
        }
        if(sub.equals("on")||sub.equals("off")||sub.equals("clear")){
            if(sub.equals("clear")){int n=plugin.spawner().removeAllDecoys();plugin.lang().send(p, "§aУдалено ложных стоек: §f"+n+"§a.");return true;}
            boolean on=sub.equals("on");plugin.getConfig().set("security.anti-esp-decoys",on);plugin.saveConfig();int n=on?0:plugin.spawner().removeAllDecoys();plugin.data().log("anti-esp-toggle",p.getName(),on?"ON":"OFF");plugin.lang().send(p, on?"§aЛожные стойки Anti-ESP включены.":"§cЛожные стойки Anti-ESP выключены. Удалено: §f"+n);return true;
        }
        usage(p,"/eventheads antiesp top [N] | reset [игрок] | on | off | clear | hide | show","Топ, сброс статистики, управление ловушками и персональным отображением Anti-ESP.");
        return true;
    }
    private boolean safeGive(Player p,ItemStack item){item=plugin.lang().localizeItem(p,item);if(item==null||item.getType().isAir())return false;if(p.getInventory().getItemInMainHand().getType().isAir()){p.getInventory().setItemInMainHand(item);return true;}Map<Integer,ItemStack> left=p.getInventory().addItem(item);return left.isEmpty();}
    private String sanitize(String raw){return raw==null?"":raw.replaceAll("[;&|\\n\\r]","");}

    private boolean hex(Player p,String[] a){
        if(!plugin.access().has(p,Perm.EDIT)){unknown(p);return true;}
        if(a.length<4 || !a[1].equalsIgnoreCase("dust")){
            usage(p,"/eventheads hex dust <id> <#RRGGBB,#RRGGBB,...>","Задаёт несколько HEX-цветов для частиц DUST выбранного события. Цвета разделяются запятыми.");
            return true;
        }
        EventRef ref=resolveEventRef(a,2,null); String id=ref.id; EventDefinition e=plugin.events().get(id); if(e==null || ref.nextIndex>=a.length){notFound(p,id);return true;}
        String raw=String.join("",java.util.Arrays.copyOfRange(a,ref.nextIndex,a.length)).replace(" ","");
        String[] parts=raw.replace(';',',').split(",");
        java.util.ArrayList<String> colors=new java.util.ArrayList<>();
        for(String part:parts){String c=part.trim().toUpperCase(java.util.Locale.ROOT);if(!c.matches("#[0-9A-F]{6}")){plugin.lang().send(p, "§cНеверный HEX-цвет: §f"+part+"§c. Формат: §f#RRGGBB§c.");return true;}colors.add(c);}
        if(colors.isEmpty()){plugin.lang().send(p, "§cНе указан ни один HEX-цвет.");return true;}
        e.collectParticleColors.clear();e.collectParticleColors.addAll(colors);e.collectParticleColor=colors.get(0);e.collectParticle="DUST";if(e.collectParticles.stream().noneMatch(x->x.equalsIgnoreCase("DUST")))e.collectParticles.add("DUST");
        ParticleSettings dust=e.particleSettings.computeIfAbsent("DUST",k->new ParticleSettings()); dust.color="";
        ParticleSettings transition=e.particleSettings.get("DUST_COLOR_TRANSITION"); if(transition!=null)transition.color="";
        plugin.data().saveEvent(e);
        plugin.lang().send(p, "§a✓ Для события §f"+e.playerName+" §aустановлен DUST с цветами: §f"+String.join(", ",colors));
        return true;
    }

    private boolean data(Player p,String[] a){
        if(!plugin.access().has(p,Perm.SETTINGS)){unknown(p);return true;}
        if(a.length<2 || !a[1].equalsIgnoreCase("check")){usage(p,"/eventheads data check","Проверяет, созданы ли все рабочие YAML EventHeads и где они находятся.");return true;}
        plugin.lang().send(p, plugin.data().storageReport());
        return true;
    }
    private boolean simple(Player p,String perm,Runnable r){if(!plugin.access().has(p,perm)){unknown(p);return true;}r.run();return true;}
    private void unknown(Player p){plugin.lang().send(p, plugin.lang().tr(p,"unknown-command"));}
    private void usage(Player p,String usage,String desc){
        String shortUsage = usage
                .replace("/eventheads ","/eh ")
                .replace(" item edit "," edit ")
                .replace(" item delete "," del ")
                .replace(" item enable "," on ")
                .replace(" item disable "," off ")
                .replace(" item give "," item give ")
                .replace(" point add "," add ")
                .replace(" point remove "," rp ")
                .replace(" point cancel"," pc")
                .replace(" region "," reg ")
                .replace(" stats "," stat ")
                .replace(" access "," acl ")
                .replace(" role "," role ");
        plugin.lang().send(p, "§6§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        plugin.lang().send(p, "§cНеверный ввод.");
        plugin.lang().send(p, "§eПолная команда: §f"+usage);
        plugin.lang().send(p, "§bКороткая команда: §f"+shortUsage);
        plugin.lang().send(p, "§7Описание: §f"+desc);
        plugin.lang().send(p, "§6Пример: §f"+usage.replace("<id>","example").replace("<player>","Steve").replace("<roleId>","helperplus").replace("<permissions>","view,points"));
        plugin.lang().send(p, "§6§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private boolean create(Player p,String[] a){
        if(!plugin.access().has(p,Perm.CREATE)){unknown(p);return true;}
        if(a.length<2){usage(p,"/eventheads create <id>","Создаёт новый шаблон ивентового предмета и открывает его редактор.");return true;}
        String id=a[1].toLowerCase(Locale.ROOT);
        if(!id.matches("[a-z0-9_-]{1,32}")){usage(p,"/eventheads create <id>","ID: 1–32 символа, только a-z, 0-9, _ и -.");return true;}
        if(plugin.events().containsKey(id)){plugin.lang().send(p, "§cИвент с ID §f"+id+" §cуже существует.");return true;}
        EventDefinition e=new EventDefinition(id);
        // RU: Новые события получают значения по умолчанию из config.yml, чтобы настройки действительно работали.
        // EN: New events inherit their defaults from config.yml so the documented settings are actually applied.
        e.maxActive=plugin.getConfig().getInt("spawn.default-max-active",30);
        e.minDistance=plugin.getConfig().getDouble("spawn.default-min-distance",8.0);
        e.minDelaySeconds=plugin.getConfig().getLong("spawn.default-min-delay-seconds",15);
        e.maxDelaySeconds=plugin.getConfig().getLong("spawn.default-max-delay-seconds",60);
        long defaultLifetime=plugin.getConfig().getLong("spawn.default-lifetime-seconds",120);
        e.minLifetimeSeconds=Math.max(1,defaultLifetime); e.maxLifetimeSeconds=Math.max(e.minLifetimeSeconds,defaultLifetime);
        e.requireSolidGround=plugin.getConfig().getBoolean("spawn.require-solid-ground",true);
        e.allowWater=plugin.getConfig().getBoolean("spawn.allow-water",false);
        e.allowLava=plugin.getConfig().getBoolean("spawn.allow-lava",false);
        e.allowFloating=plugin.getConfig().getBoolean("spawn.allow-floating",false);
        try{e.spawnMode=EventDefinition.SpawnMode.valueOf(plugin.getConfig().getString("spawn.default-mode","RANDOM").toUpperCase(Locale.ROOT));}catch(Exception ignored){e.spawnMode=EventDefinition.SpawnMode.RANDOM;}
        e.enabled=plugin.getConfig().getBoolean("spawn.default-enabled",false);
        e.authorUuid=p.getUniqueId().toString();e.authorName=p.getName();e.visual=new org.bukkit.inventory.ItemStack(org.bukkit.Material.PLAYER_HEAD);
        plugin.events().put(id,e);plugin.data().saveEvent(e);plugin.gui().openEditor(p,id);return true;
    }

    private boolean item(Player p,String[] a){
        if(a.length<2){usage(p,"/eventheads item <edit|delete|enable|disable|give> ...","Управляет уже созданными ивентовыми предметами.");return true;}
        String op=a[1].toLowerCase(Locale.ROOT);
        if(op.equals("edit")){if(!plugin.access().has(p,Perm.EDIT)){unknown(p);return true;}if(a.length<3||a[2].isBlank()){plugin.lang().send(p, "§eВыберите существующий EventHeads из списка или введите ID. Пример: §f/eventheads item edit test1§e.");plugin.gui().openEvents(p);return true;}EventRef ref=resolveEventRef(a,2,null);if(!ref.found){notFound(p,String.join(" ",Arrays.copyOfRange(a,2,a.length)));return true;}plugin.gui().openEditor(p,ref.id);return true;}
        if(op.equals("delete")){
            if(!plugin.access().has(p,Perm.DELETE)){unknown(p);return true;}
            if(a.length<3){usage(p,"/eventheads item delete <id> confirm","Полностью удаляет событие, точки, активные экземпляры, настройки и связанную статистику. Название с пробелами тоже поддерживается: /eventheads item delete Новый ивент confirm.");return true;}
            EventRef ref=resolveEventRef(a,2,"confirm");
            if(!ref.found){notFound(p,String.join(" ",Arrays.copyOfRange(a,2,a.length)));return true;}
            String id=ref.id;
            if(ref.nextIndex>=a.length || !"confirm".equalsIgnoreCase(a[ref.nextIndex])){usage(p,"/eventheads item delete "+id+" confirm","Подтвердите полное удаление именно этого события.");return true;}
            plugin.spawner().removeByEvent(id);
            // Сначала удаляем событие из оперативной карты, чтобы общий autosave
            // не записал только что удалённое событие обратно в YAML.
            plugin.events().remove(id);
            boolean removed=plugin.data().removeEvent(id);
            plugin.data().saveAll();
            plugin.data().log("delete",p.getName(),id);
            plugin.lang().send(p, removed?"§a✓ Событие §f"+id+" §aполностью удалено.":"§cСобытие §f"+id+" §cне найдено в хранилище.");
            plugin.gui().openEvents(p);
            return true;
        }
        if(op.equals("enable")||op.equals("disable")){if(!plugin.access().has(p,Perm.SCHEDULE)){unknown(p);return true;}if(a.length<3){usage(p,"/eventheads item enable|disable <id>","Включает или выключает автоматический спавн, не удаляя настройки.");return true;}EventRef ref=resolveEventRef(a,2,null);if(!ref.found){notFound(p,String.join(" ",Arrays.copyOfRange(a,2,a.length)));return true;}EventDefinition e=plugin.events().get(ref.id);e.enabled=op.equals("enable");plugin.data().saveEvent(e);if(!e.enabled)plugin.spawner().removeByEvent(e.id);plugin.lang().send(p, plugin.lang().tr(p,e.enabled?"enabled":"disabled").replace("{id}",e.id));return true;}
        if(op.equals("give")){if(!plugin.access().has(p,Perm.GIVE)){unknown(p);return true;}if(a.length<4){usage(p,"/eventheads item give <player> <id>","Выдаёт настроенный EventHeads-предмет для установки администратором.");return true;}Player t=Bukkit.getPlayerExact(a[2]);EventRef ref=resolveEventRef(a,3,null);if(t==null||!ref.found){plugin.lang().send(p, "§cИгрок или ивент не найден.");return true;}give(p,t,ref.id);return true;}
        usage(p,"/eventheads item <edit|delete|enable|disable|give> ...","Выберите одну из доступных операций.");return true;
    }
    private void give(Player actor,Player target,String id){var i=plugin.items().makeConfiguredHead(id);if(i==null){notFound(actor,id);return;}ru.doksi.eventheads.util.SchedulerUtil.runEntity(plugin,target,()->{var left=target.getInventory().addItem(i);left.values().forEach(x->target.getWorld().dropItemNaturally(target.getLocation(),x));ru.doksi.eventheads.util.SchedulerUtil.runEntity(plugin,actor,()->plugin.lang().send(actor,"§aВыдан настроенный предмет §f"+id+" §aигроку §f"+target.getName()+"§a."));});}

    private boolean point(Player p,String[] a){
        if(!plugin.access().has(p,Perm.POINTS)){unknown(p);return true;}
        if(a.length<2){usage(p,"/eventheads point add <id>","Включает постоянный режим установки точек. После команды нажимайте кроличьей шкуркой по блокам; для завершения используйте /eventheads point cancel.");return true;}
        if(a.length<3 && !a[1].equalsIgnoreCase("cancel")){usage(p,"/eventheads point add <id>","Включает постоянный режим установки точек. После команды нажимайте кроличьей шкуркой по блокам; для завершения используйте /eventheads point cancel.");return true;}
        if(a[1].equalsIgnoreCase("add")){
            List<String> ids=new ArrayList<>();
            for(String rawId:a[2].split(",")){
                String id=findEventId(rawId.trim());
                if(id.isBlank()||ids.contains(id))continue;
                if(plugin.events().containsKey(id))ids.add(id);
            }
            if(ids.isEmpty()){
                usage(p,"/eventheads point add <id[,id2,...]>","Привязывает инструмент точек сразу к одному или нескольким событиям. Например: /eventheads point add halloween,summer,treasure");
                return true;
            }
            plugin.pointListener().setPointMode(p,ids);
            if(!safeGive(p,plugin.items().makeWand(ids))){plugin.lang().send(p, "§cСвободного места для инструмента нет. Ваши предметы не изменены.");return true;}
            plugin.lang().send(p, "§a✓ Инструмент точек привязан к событиям: §f"+String.join("§7, §f",ids)+"§a.");
            return true;
        }
        if(a[1].equalsIgnoreCase("cancel")){plugin.pointListener().cancel(p);plugin.lang().send(p, plugin.lang().tr(p,"point-mode-cancelled"));return true;}
        if(a[1].equalsIgnoreCase("remove")){if(a.length<4){usage(p,"/eventheads point remove <id> <number>","Удаляет точку по номеру из списка точек.");return true;}EventDefinition e=plugin.events().get(a[2]);if(e==null){notFound(p,a[2]);return true;}try{int idx=Integer.parseInt(a[3])-1;if(idx<0||idx>=e.points.size())throw new NumberFormatException();SpawnPoint point=e.points.get(idx);if(!plugin.access().canEditPoint(p.getUniqueId(),point)){plugin.lang().send(p, plugin.access().pointEditDenied());return true;}e.points.remove(idx);plugin.data().saveEvent(e);plugin.lang().send(p, plugin.lang().tr(p,"point-removed"));}catch(NumberFormatException ex){usage(p,"/eventheads point remove <id> <number>","Номер точки должен существовать в списке.");}return true;}
        usage(p,"/eventheads point add|remove ...","add — добавить точку; remove — удалить точку.");return true;
    }

    private boolean region(Player p,String[] a){
        if(!plugin.access().has(p,Perm.EDIT)){unknown(p);return true;}
        if(a.length<2){usage(p,"/eventheads region set <id> <region>","Привязывает EventHeads к существующему WorldGuard-региону. WorldGuard необязателен для работы плагина в целом.");return true;}
        if(a[1].equalsIgnoreCase("selection")){if(a.length<3){usage(p,"/eventheads region selection <id>","Сохраняет текущую выделенную область WorldEdit.");return true;}EventDefinition e=findEvent(a[2]);if(e==null){notFound(p,a[2]);return true;}if(!plugin.worldEdit().captureSelection(p,e)){plugin.lang().send(p, "§cWorldEdit не найден или выделение отсутствует.");return true;}plugin.data().saveEvent(e);plugin.lang().send(p, "§aВыделение WorldEdit сохранено.");return true;}
        if(a[1].equalsIgnoreCase("set")||a[1].equalsIgnoreCase("add")){if(a.length<4){usage(p,"/eventheads region set <id> <region>","Добавляет регион WorldGuard к ивентовому предмету.");return true;}EventDefinition e=findEvent(a[2]);if(e==null){notFound(p,a[2]);return true;}if(!plugin.worldGuard().available()){plugin.lang().send(p, "§cWorldGuard не установлен. Используйте точки или WorldEdit-область.");return true;}e.addRegion(a[3]);e.worldGuardWorld=p.getWorld().getName();plugin.data().saveEvent(e);plugin.lang().send(p, "§aРегион добавлен: §f"+a[3]);return true;}
        // short syntax: /eventheads region <region> <id>
        if(a.length>=3 && (a[1].equalsIgnoreCase("remove")||a[1].equalsIgnoreCase("del"))){EventDefinition e=findEvent(a[2]);if(e==null){notFound(p,a[2]);return true;}if(a.length<4){usage(p,"/eventheads region remove <id> <region>","Удаляет один WorldGuard-регион из ивента. Можно удалить даже если WorldGuard сейчас выключен.");return true;}String region=a[3];if(!e.worldGuardRegions.removeIf(x->x.equalsIgnoreCase(region))){plugin.lang().send(p, "§eРегион §f"+region+" §eне найден в ивенте §f"+e.id+"§e.");return true;}if(e.worldGuardRegion!=null&&e.worldGuardRegion.equalsIgnoreCase(region))e.worldGuardRegion=e.worldGuardRegions.isEmpty()?null:e.worldGuardRegions.get(0);plugin.data().saveEvent(e);plugin.lang().send(p, "§aРегион §f"+region+" §aудалён из §f"+e.id+"§a.");return true;}
        if(a.length>=3 && a[1].equalsIgnoreCase("clear")){EventDefinition e=plugin.events().get(a[2]);if(e==null){notFound(p,a[2]);return true;}int n=e.worldGuardRegions.size();e.worldGuardRegions.clear();e.worldGuardRegion=null;plugin.data().saveEvent(e);plugin.lang().send(p, "§aУ ивента §f"+e.id+" §aудалены все WorldGuard-регионы: §f"+n+"§a.");return true;}
        if(a.length>=3){String region=a[1],id=a[2];EventDefinition e=findEvent(id);if(e!=null){if(!plugin.worldGuard().available()){plugin.lang().send(p, "§cWorldGuard сейчас недоступен. Установите/включите его или используйте /eventheads region remove/clear.");return true;}e.addRegion(region);e.worldGuardWorld=p.getWorld().getName();plugin.data().saveEvent(e);plugin.lang().send(p, "§aРегион §f"+region+" §aдобавлен к §f"+id);return true;}}
        usage(p,"/eventheads region set|remove|clear <id> [region]","Добавляет или удаляет WorldGuard-регионы. remove/clear работают даже при отключённом WorldGuard.");return true;
    }

    private boolean spawn(Player p,String[] a){if(!plugin.access().has(p,Perm.TEST)){unknown(p);return true;}String id=a.length>=3?a[2]:a.length>=2?a[1]:"";id=id.isBlank()?id:findEventId(id);if(id.isBlank()){usage(p,"/eventheads spawn test <id>","Создаёт тестовый экземпляр в допустимом месте рядом с вами.");return true;}plugin.spawner().testSpawn(p,id,ok->{plugin.lang().send(p, ok?plugin.lang().tr(p,"test-spawn"):"§cТестовый спавн не удался: место не соответствует настройкам.");});return true;}
    private boolean stats(Player p,String[] a){if(!plugin.access().has(p,Perm.STATS)){unknown(p);return true;}if(a.length<2){plugin.gui().openStatsList(p);return true;}EventRef ref=resolveEventRef(a,1,null);if(!ref.found){notFound(p,String.join(" ",Arrays.copyOfRange(a,1,a.length)));return true;}plugin.gui().openStats(p,ref.id);return true;}
    private boolean access(Player p,String[] a){
        if(!plugin.access().has(p,Perm.ACCESS)){unknown(p);return true;}
        if(a.length<2||a[1].equalsIgnoreCase("list")){plugin.gui().openAccess(p);return true;}
        if(a[1].equalsIgnoreCase("remove")){if(a.length<3){usage(p,"/eventheads access remove <player> confirm","Удаляет роль игрока после двойного подтверждения.");return true;}OfflinePlayer t=Bukkit.getOfflinePlayer(a[2]);if(plugin.isSuperAdmin(t.getUniqueId())){plugin.lang().send(p, "§cСупер-администратор Dok_Si защищён и не может быть удалён из EventHeads.");return true;}if(!plugin.access().isAdmin(p.getUniqueId())){plugin.lang().send(p, "§cУдалять участников может только супер-администратор.");return true;}long now=System.currentTimeMillis();UUID prev=pendingMemberDeletes.get(p.getUniqueId());Long at=pendingMemberDeleteAt.get(p.getUniqueId());if(a.length<4||!a[3].equalsIgnoreCase("confirm")||!t.getUniqueId().equals(prev)||at==null||now-at>20000L){pendingMemberDeletes.put(p.getUniqueId(),t.getUniqueId());pendingMemberDeleteAt.put(p.getUniqueId(),now);plugin.lang().send(p, "§c⚠ Для удаления §f"+String.valueOf(t.getName())+"§c повторите команду с §fconfirm§c в течение 20 секунд.");return true;}pendingMemberDeletes.remove(p.getUniqueId());pendingMemberDeleteAt.remove(p.getUniqueId());plugin.access().remove(t);plugin.data().log("access.remove",p.getName(),String.valueOf(t.getName()));plugin.lang().send(p, plugin.lang().tr(p,"access-removed").replace("{player}",String.valueOf(t.getName())));return true;}
        if(a[1].equalsIgnoreCase("add")||a[1].equalsIgnoreCase("set")){if(a.length<4){usage(p,"/eventheads access add <player> <roleId>","Назначает игроку встроенную или созданную вами роль.");return true;}OfflinePlayer t=Bukkit.getOfflinePlayer(a[2]);String roleId=a[3];if(plugin.isSuperAdmin(t.getUniqueId())){plugin.lang().send(p, "§cСупер-администратор Dok_Si защищён и не участвует в ролях EventHeads.");return true;}if(t.getUniqueId().equals(p.getUniqueId())){plugin.lang().send(p, plugin.lang().tr(p,"access-self-denied"));return true;}if(plugin.access().weight(roleId)<=0){usage(p,"/eventheads access add <player> <roleId>","Роль не найдена. Сначала создайте её через /eventheads role create.");return true;}if(!plugin.access().canGrant(p,roleId)){plugin.lang().send(p, plugin.lang().tr(p,"role-too-high"));return true;}plugin.access().setRole(t,roleId);plugin.data().log("access",p.getName(),t.getName()+" -> "+roleId);plugin.lang().send(p, plugin.lang().tr(p,"access-added").replace("{player}",String.valueOf(t.getName())).replace("{role}",roleId));return true;}
        if(a[1].equalsIgnoreCase("grant")||a[1].equalsIgnoreCase("revoke")){if(a.length<4){usage(p,"/eventheads access grant|revoke <player> <permission>","Точечно разрешает или запрещает право игроку поверх его роли.");return true;}OfflinePlayer t=Bukkit.getOfflinePlayer(a[2]);if(!plugin.access().canGrant(p,plugin.access().roleId(t.getUniqueId()))){plugin.lang().send(p, plugin.lang().tr(p,"role-too-high"));return true;}String perm=a[3].toLowerCase(Locale.ROOT);plugin.access().setOverride(t,perm,a[1].equalsIgnoreCase("grant"));plugin.data().log("access.override",p.getName(),t.getName()+" -> "+perm+"="+a[1].equalsIgnoreCase("grant"));plugin.lang().send(p, "§aПраво §f"+perm+" §aдля §f"+t.getName()+" §a"+(a[1].equalsIgnoreCase("grant")?"разрешено":"запрещено")+".");return true;}
        usage(p,"/eventheads access add|remove|grant|revoke|list ...","Управляет ролями и индивидуальными правами.");return true;
    }
    private boolean tool(Player p,String[] a){
        if(!plugin.access().has(p,Perm.POINTS)){unknown(p);return true;}
        if(a.length<2){usage(p,"/eventheads tool give <id> | /eventheads tool take","give — выдать кроличью лапку EventHeads для управления точками/чёрным списком; take — удалить только специальную лапку из руки.");return true;}
        if(a[1].equalsIgnoreCase("give")||a[1].equalsIgnoreCase("remove")){
            if(a.length<3){usage(p,"/eventheads tool give <id|all>","Выдаёт кроличью лапку: <id> — только для одного события; all — общий чёрный список для всех событий.");return true;}
            if(!a[2].equalsIgnoreCase("all")&&plugin.events().get(a[2])==null){notFound(p,a[2]);return true;}
            ItemStack tool=plugin.items().makeRemoveTool(a[2].equalsIgnoreCase("all")?"*":a[2]);
            if(!safeGive(p,tool)){plugin.lang().send(p, "§cСвободного места для инструмента нет. Ваши предметы не изменены.");return true;}
            plugin.lang().send(p, a[2].equalsIgnoreCase("all")?"§aВыдана кроличья лапка для общего чёрного списка EventHeads.":"§aВыдана кроличья лапка для события §f"+a[2]+"§a.");return true;
        }
        if(a[1].equalsIgnoreCase("take")||a[1].equalsIgnoreCase("clear")){
            ItemStack hand=p.getInventory().getItemInMainHand();
            if(!plugin.items().isRemoveTool(hand)){plugin.lang().send(p, "§eВ руке нет специальной кроличьей лапки EventHeads. Обычный предмет не удалён.");return true;}
            p.getInventory().setItemInMainHand(null);plugin.lang().send(p, "§aСпециальная кроличья лапка EventHeads удалена из руки.");return true;
        }
        usage(p,"/eventheads tool give <id> | /eventheads tool take","give — получить инструмент; take — удалить его из руки.");return true;
    }
    private boolean role(Player p,String[] a){
        if(!plugin.access().has(p,Perm.ACCESS)){unknown(p);return true;}
        if(a.length<2){usage(p,"/eventheads role create <id> <name> <weight> <permissions...>","Создаёт кастомную роль. Пример: /eventheads role create helperplus Помощник 45 view,points,give,test");return true;}
        if(a[1].equalsIgnoreCase("create")){
            if(a.length<6){usage(p,"/eventheads role create <id> <name> <weight> <permissions>","permissions через запятую: view,create,edit,delete,spawn,points,stats,schedule,access,give,settings,test,break,item");return true;}
            try{int weight=Integer.parseInt(a[4]);if(weight>=plugin.access().weight(plugin.access().roleId(p.getUniqueId()))&&!plugin.access().isAdmin(p.getUniqueId())){plugin.lang().send(p, "§cВес новой роли должен быть ниже вашей роли.");return true;}List<String> perms=Arrays.stream(a[5].split(",")).map(String::trim).filter(x->!x.isBlank()).toList();
                if(!plugin.access().createRole(a[2],a[3],weight,perms)){plugin.lang().send(p, "§cТакая роль уже существует или ID некорректен.");return true;}plugin.lang().send(p, "§aРоль создана: §f"+a[2]+" §7(вес "+weight+")");return true;
            }catch(NumberFormatException ex){usage(p,"/eventheads role create <id> <name> <weight> <permissions>","Вес должен быть целым числом.");return true;}
        }
        if(a[1].equalsIgnoreCase("add")||a[1].equalsIgnoreCase("assign")){
            if(a.length<4){usage(p,"/eventheads role add <player> <roleId>","Добавляет игрока в уже существующую встроенную или кастомную роль.");return true;}
            OfflinePlayer target=Bukkit.getOfflinePlayer(a[2]); String roleId=a[3];
            if(plugin.isSuperAdmin(target.getUniqueId())){plugin.lang().send(p, "§cСупер-администратор Dok_Si защищён и не участвует в ролях EventHeads.");return true;}
            if(target.getUniqueId().equals(p.getUniqueId())){plugin.lang().send(p, plugin.lang().tr(p,"access-self-denied"));return true;}
            if(plugin.access().weight(roleId)<=0){plugin.lang().send(p, "§cРоль §f"+roleId+" §cне найдена. Используйте /eventheads role list.");return true;}
            if(!plugin.access().canGrant(p,roleId)){plugin.lang().send(p, plugin.lang().tr(p,"role-too-high"));return true;}
            plugin.access().setRole(target,roleId); plugin.data().log("role-assign",p.getName(),target.getName()+" -> "+roleId);
            plugin.lang().send(p, plugin.lang().tr(p,"access-added").replace("{player}",String.valueOf(target.getName())).replace("{role}",roleId)); return true;
        }
        if(a[1].equalsIgnoreCase("list")){plugin.lang().send(p, "§6Роли EventHeads:");for(Role r:Role.values())if(r!=Role.NONE)plugin.lang().send(p, "§e"+r.name()+" §7— вес "+r.weight());for(CustomRole r:plugin.access().customRoles().values())plugin.lang().send(p, "§e"+r.id()+" §7("+r.displayName()+") — вес "+r.weight()+"; права: "+String.join(",",r.permissions()));return true;}
        if(a[1].equalsIgnoreCase("delete")){if(!plugin.access().isAdmin(p.getUniqueId())){plugin.lang().send(p, "§cУдалять роли может только супер-администратор.");return true;}if(a.length<3){usage(p,"/eventheads role delete <id> confirm","Удаляет только кастомную роль и сбрасывает её у назначенных игроков.");return true;}if(plugin.access().isBuiltinRole(a[2])){plugin.lang().send(p, "§cВстроенные роли удалить нельзя.");return true;}long now=System.currentTimeMillis();Long first=pendingRoleDeletes.get(p.getUniqueId());if(a.length<4||!a[3].equalsIgnoreCase("confirm")||first==null||now-first>20000L){pendingRoleDeletes.put(p.getUniqueId(),now);plugin.lang().send(p, "§c⚠ Для удаления роли §f"+a[2]+"§c повторите команду с §fconfirm§c в течение 20 секунд.");return true;}pendingRoleDeletes.remove(p.getUniqueId());if(plugin.access().deleteRole(a[2])){plugin.data().log("role.delete",p.getName(),a[2]);plugin.lang().send(p, "§aРоль удалена: §f"+a[2]);}else plugin.lang().send(p, "§cКастомная роль не найдена.");return true;}
        usage(p,"/eventheads role create|delete ...","create — создать кастомную роль; delete — удалить её (только супер-администратор).");return true;
    }
    private boolean exportEvent(Player p,String[] a){
        if(!plugin.access().has(p,Perm.EXPORT) && !plugin.access().has(p,Perm.EDIT)){unknown(p);return true;}
        if(a.length<2){usage(p,"/eventheads export <id>","Сохраняет весь ивент, включая точки, блокировки, WorldGuard/WorldEdit, расписание и визуальный предмет, в отдельный YAML-файл для переноса на другой сервер.");return true;}
        String id=a[1]; if(plugin.events().get(id)==null){notFound(p,id);return true;}
        java.io.File f=new java.io.File(plugin.getDataFolder(),"exports/"+id+".yml");
        plugin.lang().send(p, plugin.data().exportEvent(id,f)?"§a✓ Экспортирован: §fexports/"+id+".yml":"§c✖ Не удалось экспортировать ивент."); return true;
    }
    private boolean importEvent(Player p,String[] a){
        if(!plugin.access().has(p,Perm.IMPORT) && !plugin.access().has(p,Perm.EDIT)){unknown(p);return true;}
        if(a.length<2){usage(p,"/eventheads import <id>","Импортирует exports/<id>.yml. Это переносит точки и остальные настройки ивента.");return true;}
        String id=a[1].toLowerCase(Locale.ROOT); if(!id.matches("[a-z0-9_-]{1,32}")){usage(p,"/eventheads import <id>","ID должен содержать только a-z, 0-9, _ и -.");return true;}
        java.io.File f=new java.io.File(plugin.getDataFolder(),"exports/"+id+".yml"); if(!f.isFile()){plugin.lang().send(p, "§c✖ Файл §fexports/"+id+".yml §cне найден.");return true;}
        if(!plugin.data().importEvent(id,f)){plugin.lang().send(p, "§c✖ Не удалось импортировать файл.");return true;}
        EventDefinition e=plugin.data().getEvent(id); if(e!=null)plugin.events().put(id,e); plugin.lang().send(p, "§a✓ Ивент §f"+id+" §aимпортирован. Точки: §f"+(e==null?0:e.points.size())); return true;
    }

    private boolean wand(Player p){if(!plugin.access().has(p,Perm.POINTS)){unknown(p);return true;}ItemStack tool=plugin.items().makeWand();Map<Integer,ItemStack> left=p.getInventory().addItem(tool);left.values().forEach(x->p.getWorld().dropItemNaturally(p.getLocation(),x));plugin.lang().send(p, plugin.lang().tr(p,"wand-given"));return true;}
    private void notFound(Player p,String id){plugin.lang().send(p, plugin.lang().tr(p,"not-found").replace("{value}",id));}

    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] a){
        if(!(sender instanceof Player p))return List.of();
        if(a.length==1){List<String> out=new ArrayList<>();if(!plugin.access().has(p,Perm.VIEW))return List.of();
            out.add("help");out.add("h");out.add("menu");out.add("m");out.add("list");out.add("ls");out.add("state");out.add("status");
            if(plugin.access().has(p,Perm.CREATE)){out.add("create");out.add("new");}
            if(plugin.access().has(p,Perm.EDIT))out.add("item");
            if(plugin.access().has(p,Perm.POINTS)){out.add("point");out.add("add");out.add("pc");out.add("wand");out.add("hand");out.add("editor");}
            if(plugin.access().has(p,Perm.EDIT))out.add("region");
            if(plugin.access().has(p,Perm.TEST))out.add("spawn");
            if(plugin.access().has(p,Perm.STATS)){out.add("stats");out.add("stat");}
            if(plugin.access().has(p,Perm.ACCESS)){out.add("access");out.add("acl");out.add("role");}
            if(plugin.access().has(p,Perm.POINTS)){out.add("tool");out.add("give");out.add("clear");out.add("remove");out.add("rem");}
            if(plugin.access().has(p,Perm.SCHEDULE)){out.add("on");out.add("off");}
            if(plugin.access().has(p,Perm.SETTINGS)){out.add("reload");out.add("debug");out.add("cleanup");out.add("antiesp");}
            if(plugin.access().has(p,Perm.EDIT))out.add("hex");
            if(plugin.access().has(p,Perm.EDIT)){out.add("export");out.add("import");}return filter(out,a[0]);}
        if(a.length==2&&(a[0].equalsIgnoreCase("antiesp")||a[0].equalsIgnoreCase("anti-esp"))){
            List<String> sub= new ArrayList<>(List.of("top","reset","on","off","clear"));
            if(plugin.getConfig().getBoolean("security.anti-esp-personal-visibility-commands",true)) sub.addAll(List.of("hide","show"));
            return filter(sub,a[1]);
        }
        if(a.length==2&&a[0].equalsIgnoreCase("item"))return filter(List.of("edit","delete","enable","disable","give"),a[1]);
        if(a.length==2&&a[0].equalsIgnoreCase("hex"))return filter(List.of("dust"),a[1]);
        if(a.length==3&&a[0].equalsIgnoreCase("hex")&&a[1].equalsIgnoreCase("dust"))return filter(eventSuggestions(),a[2]);
        if(a.length==3&&a[0].equalsIgnoreCase("item")&&a[1].equalsIgnoreCase("edit"))return filter(eventSuggestions(),a[2]);
        if(a.length==3&&a[0].equalsIgnoreCase("item")&&(a[1].equalsIgnoreCase("delete")||a[1].equalsIgnoreCase("enable")||a[1].equalsIgnoreCase("disable")))return filter(eventSuggestions(),a[2]);
        if(a.length==2&&(a[0].equalsIgnoreCase("stats")||a[0].equalsIgnoreCase("stat")||a[0].equalsIgnoreCase("statistics")))return filter(eventSuggestions(),a[1]);
        if(a.length==2&&a[0].equalsIgnoreCase("point"))return filter(List.of("add","remove","cancel"),a[1]);
        if(a.length==2&&a[0].equalsIgnoreCase("rp"))return filter(eventSuggestions(),a[1]);
        if(a.length==3&&a[0].equalsIgnoreCase("rp")){
            EventDefinition e=findEvent(a[1]);
            if(e==null)return List.of();
            List<String> numbers=new ArrayList<>();
            for(int i=1;i<=e.points.size();i++)numbers.add(Integer.toString(i));
            return filter(numbers,a[2]);
        }
        if(a.length==2&&a[0].equalsIgnoreCase("region"))return filter(List.of("set","add","remove","clear","selection"),a[1]);
        if(a.length==2&&a[0].equalsIgnoreCase("access"))return filter(List.of("add","set","remove","grant","revoke","list"),a[1]);
        if(a.length==4&&a[0].equalsIgnoreCase("access")&&(a[1].equalsIgnoreCase("add")||a[1].equalsIgnoreCase("set")))return filter(plugin.access().roleIds(),a[3]);
        if(a.length==2&&a[0].equalsIgnoreCase("tool"))return filter(List.of("give","remove","take","clear"),a[1]);
        if(a.length==2&&a[0].equalsIgnoreCase("role"))return filter(List.of("create","add","assign","list","delete"),a[1]);
        if(a.length==2&&(a[0].equalsIgnoreCase("data")||a[0].equalsIgnoreCase("storage")))return filter(List.of("check"),a[1]);
        if(a.length==4&&a[0].equalsIgnoreCase("role")&&(a[1].equalsIgnoreCase("add")||a[1].equalsIgnoreCase("assign")))return filter(plugin.access().roleIds(),a[3]);
        if(a.length==3&&a[0].equalsIgnoreCase("region")&&(a[1].equalsIgnoreCase("remove")||a[1].equalsIgnoreCase("clear")||a[1].equalsIgnoreCase("selection")||a[1].equalsIgnoreCase("set")||a[1].equalsIgnoreCase("add")))return filter(eventSuggestions(),a[2]);
        return List.of();
    }
    /** RU: результат разрешения ссылки на событие из нескольких токенов команды. */
    private static final class EventRef { final String id; final int nextIndex; final boolean found; EventRef(String id,int nextIndex,boolean found){this.id=id;this.nextIndex=nextIndex;this.found=found;} }

    /**
     * RU: разрешает ссылку на событие, начиная с токена {@code start}, даже если административное
     * название содержит пробелы (например "Новый ивент"). Сначала пробуется одно слово (обычный ID
     * без пробелов), затем — всё более длинные последовательности слов подряд. Если задано
     * {@code trailingKeyword} (например "confirm"), последнее слово команды в расчёт имени не
     * берётся, даже если оно случайно совпадёт с частью названия.
     */
    private EventRef resolveEventRef(String[] a,int start,String trailingKeyword){
        if(start>=a.length) return new EventRef("",start,false);
        int limit=a.length;
        if(trailingKeyword!=null && limit>start+1 && trailingKeyword.equalsIgnoreCase(a[limit-1])) limit--;
        String single=a[start];
        String singleId=findEventId(single);
        if(plugin.events().containsKey(singleId)) return new EventRef(singleId,start+1,true);
        for(int end=limit;end>start;end--){
            String joined=String.join(" ",Arrays.copyOfRange(a,start,end));
            String candidate=findEventId(joined);
            if(plugin.events().containsKey(candidate)) return new EventRef(candidate,end,true);
        }
        return new EventRef(singleId,start+1,false);
    }

    private String findEventId(String input){
        if(input==null||input.isBlank())return "";
        String raw=input.trim();
        if(plugin.events().containsKey(raw))return raw;
        for(String key:plugin.events().keySet()) if(key.equalsIgnoreCase(raw)) return key;
        String cleanRaw=stripFormatting(raw);
        for(EventDefinition e:plugin.events().values()){
            if(e.adminName!=null&&(e.adminName.equalsIgnoreCase(raw)||stripFormatting(e.adminName).equalsIgnoreCase(cleanRaw)))return e.id;
            if(e.playerName!=null&&(e.playerName.equalsIgnoreCase(raw)||stripFormatting(e.playerName).equalsIgnoreCase(cleanRaw)))return e.id;
        }
        return raw;
    }
    private String stripFormatting(String s){
        if(s==null)return "";
        String x=s.replaceAll("(?i)&x(?:&[0-9a-f]){6}","").replaceAll("(?i)&[0-9a-fk-or]","").replaceAll("§[0-9A-FK-ORk-or]","");
        return x.replaceAll("§x(?:§[0-9a-fA-F]){6}","").trim();
    }
    private EventDefinition findEvent(String input){String id=findEventId(input);return plugin.events().get(id);}
    private List<String> eventSuggestions(){
        // Autocomplete shows one friendly identifier per event.
        // Internal IDs are still accepted manually, but are intentionally hidden from the UX.
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(EventDefinition e:plugin.events().values()){
            String friendly=e.adminName!=null&&!e.adminName.isBlank()&&!e.adminName.toLowerCase(Locale.ROOT).startsWith("event_")
                    ?e.adminName:e.playerName;
            if(friendly!=null&&!friendly.isBlank()&&!friendly.toLowerCase(Locale.ROOT).startsWith("event_")) out.add(friendly);
        }
        return new ArrayList<>(out);
    }
    private List<String> filter(Collection<String> c,String prefix){return c.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();}
}

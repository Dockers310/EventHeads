package ru.doksi.eventheads.listeners;

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.catalog.ParticleCatalog;
import ru.doksi.eventheads.catalog.ParticleSettings;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.SpawnPoint;
import ru.doksi.eventheads.roles.CustomRole;
import ru.doksi.eventheads.roles.Perm;
import ru.doksi.eventheads.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Input parser used by the GUI editor.
 * RU: Здесь собран весь пользовательский ввод: числа, минимумы/максимумы, длительности и даты.
 * EN: This class centralizes all editor input: numbers, min/max values, durations and dates.
 */
public final class InputManager implements Listener {
    private final EventHeadsPlugin plugin;
    private final Map<UUID, String[]> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> pendingVisual = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingRoleIcon = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, RoleDraft> roleDrafts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> pendingRoleField = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> pendingRoleAssign = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> pendingAccessSearch = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> pendingPointToolBinding = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> pendingPointCenter = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Pattern RANGE = Pattern.compile("^\\s*(-?\\d+(?:[.,]\\d+)?(?:[smhdw])?)\\s*(?:[-–—]|\\.\\.|\\||:)\\s*(-?\\d+(?:[.,]\\d+)?(?:[smhdw])?)\\s*$|^\\s*(-?\\d+(?:[.,]\\d+)?(?:[smhdw])?)\\s+(-?\\d+(?:[.,]\\d+)?(?:[smhdw])?)\\s*$", Pattern.CASE_INSENSITIVE);

    public InputManager(EventHeadsPlugin p) { plugin = p; }

    /** Открывает ввод значения и сразу объясняет сотруднику нужный формат. */
    public void start(Player p, String event, String field) {
        pendingVisual.remove(p.getUniqueId());
        pending.put(p.getUniqueId(), new String[]{event, field});
        p.closeInventory();
        String message = switch (field) {
            case "name", "playerName", "region" -> plugin.lang().tr(p, "input-text");
            case "description" -> event.startsWith("__template__") ? "§bEventHeads §7• Текст шаблона\n§7Введите новый текст. §cотмена §7— отмена." : plugin.lang().tr(p, "input-text");
            case "height", "minHeight", "maxHeight" -> plugin.lang().tr(p, "input-height");
            case "delay", "lifetime", "minDelay", "maxDelay", "minLifetime", "maxLifetime" -> plugin.lang().tr(p, "input-duration");
            case "schedule" -> "§bEventHeads §7• Настройка расписания\n§7Формат даты: §f2026-09-10 18:00 | 2026-09-17 23:59\n§7Или относительный формат: §f2d | 1d §7= старт через 2 дня и длительность 1 день.\n§7Можно указать только начало. §cотмена §7— отмена.";
            case "particleCount" -> "§bEventHeads §7• Количество частиц\n§7Введите число §f0–100§7. Пример: §f12§7. §fglobal §7— использовать глобальную настройку. §cотмена §7— отмена.";
            case "particleColor" -> "§bEventHeads §7• Один HEX-цвет\n§7Формат: §f#RRGGBB§7. Пример: §f#c79923§7. §cотмена §7— отмена.";
            case "particleColors" -> "§bEventHeads §7• Несколько HEX-цветов\n§7Введите цвета через запятую: §f#c79923,#c73f23,#6b23c7§7. Тип частиц автоматически станет DUST. §cотмена §7— отмена.";
            case "particleColorsAdd" -> "§bEventHeads §7• Добавить свой HEX-цвет\n§7Введите один или несколько цветов через запятую: §f#c79923,#c73f23§7.\n§7Уже выбранные в палитре цвета §aне будут удалены§7 — этот цвет добавится к ним. §cотмена §7— отмена.";
            case "particleType" -> "§bEventHeads §7• Тип частиц\n§7Тип частиц выбирается кликом по предмету в меню. Вводить название в чат не нужно.";
            case "weeklySchedule" -> "§bEventHeads §7• Еженедельный запуск\n§7Формат: суббота 20:00 | 1h\n§7Например: каждую субботу в 20:00 на 1 час.\n§7Длительность: 30m, 1h, 2h и т.п.";
            case "dailySchedule" -> "§bEventHeads §7• Ежедневный запуск\n§7Формат: 20:00 | 1h\n§7Например: каждый день в 20:00 на 1 час.\n§7Длительность: 30m, 1h, 2h и т.п.";
            case "conditionsWorld" -> "§bEventHeads §7• Мир\n§7Введите имя мира или any.";
            case "conditionsTime" -> "§bEventHeads §7• Время мира\n§7Введите диапазон тиков, например 6000-18000.";
            case "conditionsHelmet" -> "§bEventHeads §7• Шлем\n§7Например PUMPKIN или CARVED_PUMPKIN. empty — снять требование.";
            case "conditionsItem" -> "§bEventHeads §7• Предмет\n§7Например GOLD_INGOT. empty — снять требование.";
            case "conditionsMessage" -> "§bEventHeads §7• Сообщение отказа\n§7Например: &cВы не можете собрать: наденьте тыкву.";
            case "conditionsPenalty" -> "§bEventHeads §7• Штраф\n§7Сколько монет снять при невыполненном условии. 0 — без штрафа.";
            case "soundCustom" -> "§bEventHeads §7• Звук\n§7Например ENTITY_PLAYER_LEVELUP. empty — глобальный звук.";
            case "soundVolume" -> "§bEventHeads §7• Громкость\n§70.0–10.0";
            case "soundPitch" -> "§bEventHeads §7• Тон\n§70.5–2.0";
            case "duplicateEvent" -> "§bEventHeads §7• Копия ивента\n§7Введите новый ID, например fair_2. Латиница, цифры, _ и - до 32 символов.";
            case "animationSpeed" -> "§bEventHeads §7• Скорость анимации\n§7Введите множитель от §f0.05 §7до §f10.0§7. Пример: §f1.5§7 = в 1.5 раза быстрее. §cотмена §7— отмена.";
            case "decoyCount" -> "§bEventHeads §7• Количество ложных стоек\n§7Введите диапазон, например §f2-5§7. §cотмена §7— отмена.";
            case "decoyRadius" -> "§bEventHeads §7• Радиус ложных стоек\n§7Введите диапазон в блоках, например §f3-10§7. §cотмена §7— отмена.";
            case "decoyDelay" -> "§bEventHeads §7• Задержка повторного спавна ложных стоек\n§7Пример: §f10-30s§7. §cотмена §7— отмена.";
            case "decoyLifetime" -> "§bEventHeads §7• Время жизни ложных стоек\n§7Пример: §f15-30s§7. §cотмена §7— отмена.";
            case "reward-min","reward-max" -> "§bEventHeads §7• Награда шаблона\n§7Введите сумму, например §f10§7 или §f-5§7. §cотмена §7— отмена.";
            case "max-active","region-max","min-delay","max-delay","min-lifetime","max-lifetime" -> "§bEventHeads §7• Настройка шаблона\n§7Введите значение. Для времени можно использовать §f10s, 5m, 1h, 2d§7. §cотмена§7 — отмена.";
            case "min-distance","min-player-distance","animation-speed","sound-volume","sound-pitch","particle-count","fail-penalty" -> "§bEventHeads §7• Настройка шаблона\n§7Введите числовое значение. §cотмена§7 — отмена.";
            case "particles" -> "§bEventHeads §7• Частицы шаблона\n§7Введите типы через запятую, например §fHEART,END_ROD,NOTE§7. §cотмена §7— отмена.";
            case "sounds" -> "§bEventHeads §7• Звуки шаблона\n§7Введите звуки через запятую. §cотмена §7— отмена.";
            case "helmet","inventory-item" -> "§bEventHeads §7• Требование шаблона\n§7Например §fPUMPKIN§7. §fempty§7 — снять требование.";
            case "fail-message","player-name","admin-name" -> "§bEventHeads §7• Текст шаблона\n§7Введите новый текст. §cотмена§7 — отмена.";
            case "weekly" -> "§bEventHeads §7• Расписание шаблона\n§7Формат: §fсуббота 20:00 | 1h§7. §cотмена§7 — отмена.";
            case "daily" -> "§bEventHeads §7• Ежедневное расписание шаблона\n§7Формат: §f20:00 | 1h§7. §cотмена§7 — отмена.";
            default -> field.startsWith("particleSetting:") ? particleSettingPrompt(field) : plugin.lang().tr(p, "input-number");
        };
        plugin.lang().send(p, message);
    }


    private String particleSettingPrompt(String field){
        String[] q=field.split(":",3);
        if(q.length!=3) return "§bEventHeads §7• Настройка частицы\n§7Введите значение.";
        return switch(q[2]){
            case "radius" -> "§bEventHeads §7• Радиус частицы\n§7Введите 0–128. Пример: §f0.5§7.";
            case "duration" -> "§bEventHeads §7• Длительность частицы\n§7Введите секунды или §f30s§7/§f2m§7. §fglobal§7 — до конца анимации.";
            case "count" -> "§bEventHeads §7• Количество частицы\n§7Введите 0–100000. §fglobal§7 — делить общий лимит между типами.";
            case "speed" -> "§bEventHeads §7• Скорость частицы\n§7Введите 0–100. Пример: §f0.02§7.";
            case "size" -> "§bEventHeads §7• Размер частицы\n§7Введите 0.01–10. Пример: §f1.5§7.";
            case "color" -> "§bEventHeads §7• Цвет частицы\n§7Введите §f#RRGGBB§7 или два цвета через запятую. §fGLOBAL§7 — наследовать палитру.";
            default -> "§bEventHeads §7• Настройка частицы\n§7Введите значение или §fотмена§7.";
        };
    }

    public void startVisualSelection(Player p, String event) {
        pending.remove(p.getUniqueId()); pendingRoleIcon.remove(p.getUniqueId());
        pendingVisual.put(p.getUniqueId(), event);
        p.closeInventory();
        plugin.lang().send(p, plugin.lang().tr(p, "visual-select"));
    }
    public void startRoleIconSelection(Player p){
        pending.remove(p.getUniqueId()); pendingVisual.remove(p.getUniqueId()); pendingRoleIcon.put(p.getUniqueId(),true);
        p.closeInventory();
        plugin.lang().send(p, "§bEventHeads §7• Выбор иконки роли");
        plugin.lang().send(p, "§7Закройте это сообщение и нажмите ЛКМ по предмету в своём инвентаре.");
        plugin.lang().send(p, "§8Берётся только Material предмета; сам предмет не расходуется. §cотмена §7— отмена.");
    }

    public void ensureRoleDraft(Player p,String id){ CustomRole r=plugin.access().customRoles().get(id.toLowerCase(Locale.ROOT)); if(r!=null) roleDrafts.putIfAbsent(p.getUniqueId(),new RoleDraft(r.id(),r.displayName(),r.weight(),new LinkedHashSet<>(r.permissions()),r.icon(),r.maxPoints(),r.pointRadius())); }
    public void beginRoleCreator(Player p){ roleDrafts.put(p.getUniqueId(),new RoleDraft("event_role", "Новая роль", 40,new LinkedHashSet<>(),"WRITABLE_BOOK",-1,-1.0D)); pendingRoleField.remove(p.getUniqueId()); plugin.gui().openRoleCreator(p); }
    public String roleDraftId(Player p,String fallback){ RoleDraft d=roleDrafts.get(p.getUniqueId()); return d==null?fallback:d.id; }
    public String roleDraftName(Player p,String fallback){ RoleDraft d=roleDrafts.get(p.getUniqueId()); return d==null?fallback:d.name; }
    public int roleDraftWeight(Player p,int fallback){ RoleDraft d=roleDrafts.get(p.getUniqueId()); return d==null?fallback:d.weight; }
    public int roleDraftMaxPoints(Player p,int fallback){ RoleDraft d=roleDrafts.get(p.getUniqueId()); return d==null?fallback:d.maxPoints; }
    public double roleDraftRadius(Player p,double fallback){ RoleDraft d=roleDrafts.get(p.getUniqueId()); return d==null?fallback:d.pointRadius; }
    public String roleDraftIcon(Player p,String fallback){ RoleDraft d=roleDrafts.get(p.getUniqueId()); return d==null?fallback:d.icon; }
    public boolean roleDraftHas(Player p,String perm){
        RoleDraft d=roleDrafts.computeIfAbsent(p.getUniqueId(),u->new RoleDraft("event_role","Новая роль",40,new LinkedHashSet<>(),"WRITABLE_BOOK",-1,-1.0D));
        String normalized=perm==null?"":perm.trim().toLowerCase(Locale.ROOT);
        return d.permissions.contains("*") || d.permissions.stream().anyMatch(x->x.equalsIgnoreCase(normalized));
    }
    public void toggleRoleDraft(Player p,String perm){
        RoleDraft d=roleDrafts.computeIfAbsent(p.getUniqueId(),u->new RoleDraft("event_role","Новая роль",40,new LinkedHashSet<>(),"WRITABLE_BOOK",-1,-1.0D));
        LinkedHashSet<String> set=new LinkedHashSet<>();
        for(String existing:d.permissions) if(existing!=null&&!existing.isBlank()) set.add(existing.trim().toLowerCase(Locale.ROOT));
        String normalized=perm==null?"":perm.trim().toLowerCase(Locale.ROOT);
        if(set.contains("*")){
            set.remove("*");
            set.addAll(List.of("view","create","edit","delete","spawn","points","points-all","stats","schedule","access","give","settings","test","break","item","export","import","anti-esp","audit","templates"));
        }
        if(!set.add(normalized)) set.remove(normalized);
        roleDrafts.put(p.getUniqueId(),new RoleDraft(d.id,d.name,d.weight,set,d.icon,d.maxPoints,d.pointRadius));
    }
    public void startRoleField(Player p,String field){ pendingRoleField.put(p.getUniqueId(),field); pending.clear(); p.closeInventory(); plugin.lang().send(p, "§bEventHeads §7• Введите значение для §f"+field+"§7. Напишите §cотмена §7для отмены."); }
    public void startRoleAssign(Player p,String role){ pendingRoleAssign.put(p.getUniqueId(),role); pending.clear(); p.closeInventory(); plugin.lang().send(p, "§bEventHeads §7• Введите ник игрока, которого назначить в роль §f"+role+"§7. §cотмена §7— отмена."); }
    public void startRoleEditorField(Player p,String role,String field){ pendingRoleField.put(p.getUniqueId(),"edit:"+role+":"+field); pending.clear(); p.closeInventory(); plugin.lang().send(p, "§bEventHeads §7• Введите новое значение для §f"+field+"§7. §cотмена §7— отмена."); }

    public void startRolePointSetting(Player p,String role,String field){
        if(!plugin.access().canEditRolePointSettings(p,role)){plugin.lang().send(p,"§cНедоступно: §7у вашей роли нет права изменять настройки точек этой роли.");return;}
        pendingRoleField.put(p.getUniqueId(),"point-settings:"+role+":"+field); pending.clear(); p.closeInventory();
        String message=switch(field){
            case "maxPoints" -> "§bEventHeads §7• Лимит точек роли\n§7Введите число §f0–1000000§7. §f-1§7 — без ограничения. §cотмена §7— отмена.";
            case "radius" -> "§bEventHeads §7• Радиус роли\n§7Введите расстояние в блоках §f0–1000000§7. §f-1§7 — без ограничения. §cотмена §7— отмена.";
            case "center" -> "§bEventHeads §7• Центр точек роли\n§7Введите два числа §fX Z§7. Пример: §f1250 -340§7. §fglobal§7 — вернуть глобальный центр. §cотмена §7— отмена.";
            default -> "§bEventHeads §7• Настройка точек роли\n§7Введите значение. §cотмена §7— отмена.";
        };
        plugin.lang().send(p,message);
    }

    public void startPointCenter(Player p){
        if(!plugin.access().has(p,Perm.SETTINGS)){plugin.lang().send(p, "§cНедоступно: §7нет права менять центр точек.");return;}
        pendingPointCenter.add(p.getUniqueId());
        pending.clear();
        p.closeInventory();
        plugin.lang().send(p, "§bEventHeads §7• Центр постановки точек");
        plugin.lang().send(p, "§7Введите: §fX Z§7. Пример: §f1250 -340§7. Для возврата к 0 0 введите §f0 0§7. §cотмена §7— отмена.");
    }
    public void toggleRolePermission(Player p,String role,String perm){ toggleRolePermission(p,role,perm,1); }
    public void toggleRolePermission(Player p,String role,String perm,int page){
        CustomRole r=plugin.access().customRoles().get(role.toLowerCase(Locale.ROOT));
        if(r==null)return;
        RoleDraft current=roleDrafts.get(p.getUniqueId());
        if(current==null || !current.id.equalsIgnoreCase(r.id())){
            current=new RoleDraft(r.id(),r.displayName(),r.weight(),new LinkedHashSet<>(r.permissions()),r.icon(),r.maxPoints(),r.pointRadius());
        }
        LinkedHashSet<String> set=new LinkedHashSet<>();
        for(String existing:current.permissions) if(existing!=null&&!existing.isBlank()) set.add(existing.trim().toLowerCase(Locale.ROOT));
        String normalized=perm==null?"":perm.trim().toLowerCase(Locale.ROOT);
        if(set.contains("*")){
            set.remove("*");
            set.addAll(List.of("view","create","edit","delete","spawn","points","points-all","stats","schedule","access","give","settings","test","break","item","export","import","anti-esp","audit","templates"));
        }
        if(!set.add(normalized))set.remove(normalized);
        roleDrafts.put(p.getUniqueId(),new RoleDraft(current.id,current.name,current.weight,set,current.icon,current.maxPoints,current.pointRadius));
        plugin.data().log("role.permission.toggle",p.getName(),role+" -> "+normalized+"="+set.contains(normalized));
        plugin.gui().openRoleEditor(p,role,Math.max(1,page));
    }
    public void saveRoleDraft(Player p){ RoleDraft d=roleDrafts.get(p.getUniqueId()); if(d==null)return; if(!d.id.matches("[A-Za-z0-9_-]{1,32}")){plugin.lang().send(p, "§cID роли должен содержать 1–32 символа: a-z, 0-9, _ или -.");return;} if(!plugin.access().isAdmin(p.getUniqueId()) && d.weight>=plugin.access().weight(plugin.access().roleId(p.getUniqueId()))){plugin.lang().send(p, "§cВес новой роли должен быть ниже вашей роли.");return;} if(!plugin.access().createRole(d.id,d.name,d.weight,d.permissions,d.icon,d.maxPoints,d.pointRadius)){plugin.lang().send(p, "§cРоль уже существует или ID некорректен.");return;} plugin.data().log("role.create",p.getName(),d.id+"; points="+d.maxPoints+"; radius="+d.pointRadius+"; permissions="+String.join(",",d.permissions)); roleDrafts.remove(p.getUniqueId());plugin.lang().send(p, "§a✓ Роль §f"+d.id+" §aсоздана.");plugin.gui().openRoles(p); }
    public void saveRoleEditor(Player p,String id){ RoleDraft d=roleDrafts.get(p.getUniqueId()); if(d==null)return; if(!plugin.access().isAdmin(p.getUniqueId()) && d.weight>=plugin.access().weight(plugin.access().roleId(p.getUniqueId()))){plugin.lang().send(p, "§cВес роли не может быть равным или выше вашей роли.");return;} if(!plugin.access().updateRole(id,d.name,d.weight,d.permissions,d.icon,d.maxPoints,d.pointRadius)){plugin.lang().send(p, "§cНе удалось сохранить роль.");return;} plugin.data().log("role.save",p.getName(),id+"; points="+d.maxPoints+"; radius="+d.pointRadius+"; permissions="+String.join(",",d.permissions)); roleDrafts.remove(p.getUniqueId());plugin.lang().send(p, "§a✓ Роль §f"+id+" §aсохранена.");plugin.gui().openRoles(p); }
    public void startPointToolBinding(Player p){ pendingPointToolBinding.put(p.getUniqueId(), ""); pending.clear(); pendingVisual.remove(p.getUniqueId()); p.closeInventory(); plugin.lang().send(p, "§bEventHeads §7• Введите ID событий через запятую. Пример: §fseason,halloween,treasure§7. §cотмена §7— отмена."); }
    public void startAccessSearch(Player p){ pendingAccessSearch.put(p.getUniqueId(), ""); pending.clear(); pendingVisual.remove(p.getUniqueId()); p.closeInventory(); plugin.lang().send(p, "§bEventHeads §7• Введите ник или часть ника игрока для поиска. §cотмена §7— отмена."); }
    public void cancelRoleState(Player p){roleDrafts.remove(p.getUniqueId());pendingRoleField.remove(p.getUniqueId());pendingRoleAssign.remove(p.getUniqueId());pendingRoleIcon.remove(p.getUniqueId());pendingPointCenter.remove(p.getUniqueId());}
    private boolean giveSafely(Player p,ItemStack item){if(item==null||item.getType().isAir())return false;if(p.getInventory().getItemInMainHand().getType().isAir()){p.getInventory().setItemInMainHand(item);return true;}Map<Integer,ItemStack> left=p.getInventory().addItem(item);return left.isEmpty();}

    private record RoleDraft(String id,String name,int weight,Set<String> permissions,String icon,int maxPoints,double pointRadius){
        RoleDraft(String id,String name,int weight,Set<String> permissions){this(id,name,weight,permissions,"WRITABLE_BOOK",-1,-1.0D);}
        RoleDraft(String id,String name,int weight,Set<String> permissions,String icon){this(id,name,weight,permissions,icon,-1,-1.0D);}
    }

    @EventHandler
    public void inventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if(pendingRoleIcon.containsKey(p.getUniqueId())){
            if(e.getClickedInventory()!=p.getInventory() || e.getSlotType()==InventoryType.SlotType.OUTSIDE)return;
            ItemStack item=e.getCurrentItem();
            if(item==null||item.getType().isAir())return;
            RoleDraft d=roleDrafts.get(p.getUniqueId());
            if(d==null){pendingRoleIcon.remove(p.getUniqueId());return;}
            e.setCancelled(true);
            roleDrafts.put(p.getUniqueId(),new RoleDraft(d.id,d.name,d.weight,d.permissions,item.getType().name(),d.maxPoints,d.pointRadius));
            pendingRoleIcon.remove(p.getUniqueId());
            plugin.lang().send(p, "§a✓ Иконка роли установлена: §f"+item.getType().name());
            if(plugin.access().customRoles().containsKey(d.id.toLowerCase(Locale.ROOT))) plugin.gui().openRoleEditor(p,d.id); else plugin.gui().openRoleCreator(p);
            return;
        }
        String eventId = pendingVisual.get(p.getUniqueId());
        if (eventId == null) return;
        if (e.getClickedInventory() != p.getInventory()) return;
        if (e.getSlotType() == InventoryType.SlotType.OUTSIDE) return;
        var item = e.getCurrentItem();
        if (item == null || item.getType().isAir()) return;
        EventDefinition def = plugin.events().get(eventId);
        if (def == null) { pendingVisual.remove(p.getUniqueId()); return; }
        e.setCancelled(true);
        def.visual = item.clone();
        plugin.data().saveEvent(def);
        plugin.data().log("visual.set",p.getName(),eventId);
        pendingVisual.remove(p.getUniqueId());
        plugin.lang().send(p, plugin.lang().tr(p, "visual-selected"));
        plugin.gui().openEditor(p, eventId);
    }

    @EventHandler
    public void chat(AsyncPlayerChatEvent ev) {
        UUID uuid = ev.getPlayer().getUniqueId();
        String val = ev.getMessage().trim();
        if(pendingPointCenter.contains(uuid)){
            ev.setCancelled(true);
            pendingPointCenter.remove(uuid);
            if(val.equalsIgnoreCase("cancel")||val.equalsIgnoreCase("отмена")){SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->plugin.lang().send(ev.getPlayer(),"§eОтменено."));return;}
            SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->{
                try{
                    String[] parts=val.replace(',','.').trim().split("\\s+");
                    if(parts.length!=2) throw new IllegalArgumentException();
                    double x=Double.parseDouble(parts[0]);
                    double z=Double.parseDouble(parts[1]);
                    if(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000) throw new IllegalArgumentException();
                    plugin.access().setPointCenter(x,z);
                    plugin.data().log("points.center",ev.getPlayer().getName(),plugin.access().pointCenterText());
                    plugin.lang().send(ev.getPlayer(),"§a✓ Центр постановки точек: §f"+plugin.access().pointCenterText());
                    plugin.gui().openMyPoints(ev.getPlayer());
                }catch(Exception ex){
                    plugin.lang().send(ev.getPlayer(),"§cНекорректно. Введите два числа: X Z, например 1250 -340.");
                    plugin.gui().openMyPoints(ev.getPlayer());
                }
            });
            return;
        }
        if(pendingPointToolBinding.containsKey(uuid)){ ev.setCancelled(true); String raw=val; pendingPointToolBinding.remove(uuid); if(raw.equalsIgnoreCase("cancel")||raw.equalsIgnoreCase("отмена")){SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->plugin.lang().send(ev.getPlayer(),"§eОтменено."));return;} SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->{ List<String> ids=new ArrayList<>(); for(String part:raw.split(",")){String token=part.trim(); if(token.isBlank())continue; String id=plugin.resolveEventId(token); if(plugin.events().containsKey(id)&&!ids.contains(id))ids.add(id);} if(ids.isEmpty()){plugin.lang().send(ev.getPlayer(),"§cНе найдено ни одного существующего события.");return;} ItemStack tool=plugin.items().makeWand(ids); if(!giveSafely(ev.getPlayer(),tool)){plugin.lang().send(ev.getPlayer(),"§cСвободного места для инструмента нет. Ваши предметы не изменены.");return;} plugin.pointListener().setPointMode(ev.getPlayer(),ids); plugin.lang().send(ev.getPlayer(),"§a✓ Шкурка привязана к событиям: §f"+String.join(", ",ids)+"§a. §7Режим установки точек включён — кликните по блоку."); plugin.gui().openPoints(ev.getPlayer(),ids.get(0)); }); return; }
        if(pendingAccessSearch.containsKey(uuid)){ ev.setCancelled(true); String q=val; pendingAccessSearch.remove(uuid); if(q.equalsIgnoreCase("cancel")||q.equalsIgnoreCase("отмена")){SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->plugin.lang().send(ev.getPlayer(),"§eПоиск отменён."));return;} SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->plugin.gui().openAccess(ev.getPlayer(),q)); return; }
        if(pendingRoleAssign.containsKey(uuid)){ ev.setCancelled(true); String role=pendingRoleAssign.remove(uuid); if(val.equalsIgnoreCase("cancel")||val.equalsIgnoreCase("отмена")){SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->plugin.lang().send(ev.getPlayer(),"§eОтменено."));return;} SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->{org.bukkit.OfflinePlayer target=org.bukkit.Bukkit.getOfflinePlayer(val); if(target.getUniqueId().equals(uuid)){plugin.lang().send(ev.getPlayer(),"§cНельзя назначить роль самому себе.");return;} if(plugin.access().isSuperAdmin(target.getUniqueId())){plugin.lang().send(ev.getPlayer(),"§cСупер-администратор Dok_Si защищён и не участвует в ролях EventHeads.");return;} if(!plugin.access().canGrant(ev.getPlayer(),role)){plugin.lang().send(ev.getPlayer(),plugin.lang().tr(ev.getPlayer(),"role-too-high"));return;} plugin.access().setRole(target,role);plugin.data().log("role-assign",ev.getPlayer().getName(),target.getName()+" -> "+role);plugin.lang().send(ev.getPlayer(),"§a✓ Игрок §f"+val+" §aдобавлен в роль §f"+role+"§a.");plugin.gui().openRoleEditor(ev.getPlayer(),role);}); return; }
        if(pendingRoleField.containsKey(uuid)){ ev.setCancelled(true); String field=pendingRoleField.remove(uuid); if(val.equalsIgnoreCase("cancel")||val.equalsIgnoreCase("отмена")){SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->plugin.lang().send(ev.getPlayer(),"§eОтменено."));return;} SchedulerUtil.runEntity(plugin,ev.getPlayer(),()->applyRoleField(ev.getPlayer(),field,val)); return; }
        if (!pending.containsKey(uuid)) return;
        ev.setCancelled(true);
        String[] x = pending.remove(uuid);
        if (val.equalsIgnoreCase("cancel") || val.equalsIgnoreCase("отмена")) {
            SchedulerUtil.runEntity(plugin,ev.getPlayer(), () -> plugin.lang().send(ev.getPlayer(),plugin.lang().tr(ev.getPlayer(), "input-cancel")));
            return;
        }
        SchedulerUtil.runEntity(plugin,ev.getPlayer(), () -> apply(ev.getPlayer(), x[0], x[1], val));
    }

    private void applyRoleField(Player p,String field,String v){
        try{
            if(field.startsWith("point-settings:")){
                String[] x=field.split(":",3); if(x.length!=3)throw new IllegalArgumentException();
                String role=x[1], f=x[2];
                if(!plugin.access().canEditRolePointSettings(p,role))throw new SecurityException();
                switch(f){
                    case "maxPoints" -> { if(!plugin.access().setRoleMaxPoints(p,role,parsePointLimit(v)))throw new IllegalArgumentException(); }
                    case "radius" -> { if(!plugin.access().setRolePointRadius(p,role,parsePointRadius(v)))throw new IllegalArgumentException(); }
                    case "center" -> {
                        String raw=v.trim();
                        if(raw.equalsIgnoreCase("global")||raw.equalsIgnoreCase("default")||raw.equalsIgnoreCase("сброс")){
                            if(!plugin.access().clearRolePointCenter(p,role))throw new IllegalArgumentException();
                        } else {
                            String[] parts=raw.replace(',', '.').split("\\s+");
                            if(parts.length!=2)throw new IllegalArgumentException();
                            double cx=parseFiniteDouble(parts[0],-30_000_000.0D,30_000_000.0D);
                            double cz=parseFiniteDouble(parts[1],-30_000_000.0D,30_000_000.0D);
                            if(!plugin.access().setRolePointCenter(p,role,cx,cz))throw new IllegalArgumentException();
                        }
                    }
                    default -> throw new IllegalArgumentException();
                }
                plugin.data().log("role.point-setting",p.getName(),role+" -> "+f+"="+v);
                plugin.lang().send(p,"§a✓ Настройка точек роли сохранена.");
                plugin.gui().openRolePointSettings(p,role); return;
            }
            RoleDraft d=roleDrafts.get(p.getUniqueId());
            if(d==null){plugin.lang().send(p, "§cЧерновик роли не найден.");return;}
            if(field.startsWith("edit:")){
                String[] x=field.split(":",3);
                String id=x[1];
                CustomRole old=plugin.access().customRoles().get(id.toLowerCase(Locale.ROOT));
                if(old==null)return;
                String f=x[2];
                RoleDraft base=roleDrafts.getOrDefault(p.getUniqueId(),new RoleDraft(old.id(),old.displayName(),old.weight(),new LinkedHashSet<>(old.permissions()),old.icon(),old.maxPoints(),old.pointRadius()));
                String name=base.name; int weight=base.weight; int maxPoints=base.maxPoints; double radius=base.pointRadius; String icon=base.icon;
                switch(f){
                    case "name" -> name=requireText(v);
                    case "weight" -> weight=parseInt(v,1,99);
                    case "maxPoints" -> maxPoints=parsePointLimit(v);
                    case "radius" -> radius=parsePointRadius(v);
                    case "icon" -> { try{org.bukkit.Material.valueOf(v.trim().toUpperCase(Locale.ROOT));}catch(Exception ex){plugin.lang().send(p, "§cНеизвестный Material. Пример: NETHER_STAR, CHEST, DIAMOND.");return;} icon=v.trim().toUpperCase(Locale.ROOT); }
                    default -> throw new IllegalArgumentException();
                }
                RoleDraft nd=new RoleDraft(base.id,name,weight,new LinkedHashSet<>(base.permissions),icon,maxPoints,radius);
                roleDrafts.put(p.getUniqueId(),nd);
                plugin.gui().openRoleEditor(p,id);return;
            }
            if(field.equals("id"))d=new RoleDraft(requireRoleId(v),d.name,d.weight,d.permissions,d.icon,d.maxPoints,d.pointRadius);
            else if(field.equals("name"))d=new RoleDraft(d.id,requireText(v),d.weight,d.permissions,d.icon,d.maxPoints,d.pointRadius);
            else if(field.equals("weight"))d=new RoleDraft(d.id,d.name,parseInt(v,1,99),d.permissions,d.icon,d.maxPoints,d.pointRadius);
            else if(field.equals("maxPoints"))d=new RoleDraft(d.id,d.name,d.weight,d.permissions,d.icon,parsePointLimit(v),d.pointRadius);
            else if(field.equals("radius"))d=new RoleDraft(d.id,d.name,d.weight,d.permissions,d.icon,d.maxPoints,parsePointRadius(v));
            else if(field.equals("icon")){ try{ org.bukkit.Material.valueOf(v.trim().toUpperCase(Locale.ROOT)); }catch(Exception ex){ throw new IllegalArgumentException(); } d=new RoleDraft(d.id,d.name,d.weight,d.permissions,v.trim().toUpperCase(Locale.ROOT),d.maxPoints,d.pointRadius); }
            else throw new IllegalArgumentException();
            roleDrafts.put(p.getUniqueId(),d);plugin.gui().openRoleCreator(p);
        }catch(Exception ex){plugin.lang().send(p, "§cНекорректное значение. Проверьте формат и попробуйте снова.");}
    }
    private int parsePointLimit(String raw){
        String s=raw.trim().toLowerCase(Locale.ROOT);
        if(s.equals("-1")||s.equals("без ограничения")||s.equals("безлимит")||s.equals("unlimited"))return -1;
        return parseInt(s,0,1_000_000);
    }
    private double parsePointRadius(String raw){
        String s=raw.trim().toLowerCase(Locale.ROOT).replace(',','.');
        if(s.equals("-1")||s.equals("без ограничения")||s.equals("безлимит")||s.equals("unlimited"))return -1.0D;
        return parseFiniteDouble(s,0.0D,1_000_000.0D);
    }
    private String requireRoleId(String s){if(!s.matches("[A-Za-z0-9_-]{1,32}"))throw new IllegalArgumentException();return s.toLowerCase(Locale.ROOT);}

    private void apply(Player p, String id, String field, String v) {
        if(id!=null && id.startsWith("__template__:")){ applyTemplateSetting(p,id.substring("__template__:".length()),field,v); return; }
        if ("__global__".equals(id)) {
            try {
                switch (field) {
                    case "decoyCount" -> { long[] r=parseRangeLong(v); if(r[0]<0||r[1]>100||r[1]<r[0])throw new IllegalArgumentException(); plugin.getConfig().set("security.anti-esp-decoys-min",(int)r[0]); plugin.getConfig().set("security.anti-esp-decoys-max",(int)r[1]); }
                    case "decoyRadius" -> { double[] r=parseDoubleRange(v,0.5,128); plugin.getConfig().set("security.anti-esp-decoys-radius-min",r[0]); plugin.getConfig().set("security.anti-esp-decoys-radius-max",r[1]); }
                    case "decoyDelay" -> { long[] r=parseDurationRange(v,0,86400); plugin.getConfig().set("security.anti-esp-decoys-delay-min-seconds",r[0]); plugin.getConfig().set("security.anti-esp-decoys-delay-max-seconds",r[1]); }
                    case "decoyLifetime" -> { long[] r=parseDurationRange(v,1,604800); plugin.getConfig().set("security.anti-esp-decoys-lifetime-min-seconds",r[0]); plugin.getConfig().set("security.anti-esp-decoys-lifetime-max-seconds",r[1]); }
                    default -> throw new IllegalArgumentException();
                }
                plugin.saveConfig(); plugin.gui().openAntiEspSettings(p);
            } catch(Exception ex) { plugin.lang().send(p, "§cНекорректное значение. Проверьте формат и диапазон."); plugin.gui().openAntiEspSettings(p); }
            return;
        }
        EventDefinition e = plugin.events().get(id);
        if (e == null) return;
        try {
            if (field.startsWith("pointChance:")) {
                int pi = Integer.parseInt(field.substring("pointChance:".length()));
                double chance = parseFiniteDouble(v);
                if (chance < 0 || chance > 100 || pi < 0 || pi >= e.points.size()) throw new IllegalArgumentException();
                SpawnPoint point=e.points.get(pi);
                if(!plugin.access().canEditPoint(p.getUniqueId(),point)){plugin.lang().send(p, plugin.access().pointEditDenied());return;}
                double oldChance=point.chance;
                point.chance = chance;
                plugin.data().saveEvent(e);
                plugin.data().log("point.chance",p.getName(),e.id+" #"+(pi+1)+" "+oldChance+" -> "+chance);
                plugin.gui().openPoints(p, id);
                return;
            }
            if(field.equals("duplicateEvent")) {
                String newId=requireRoleId(v); if(plugin.events().containsKey(newId))throw new IllegalArgumentException(); EventDefinition copy=plugin.templates().duplicate(e,newId,p); plugin.events().put(newId,copy); plugin.data().saveEvent(copy); plugin.data().log("event.duplicate",p.getName(),e.id+" -> "+newId); plugin.lang().send(p, "§a✓ Ивент скопирован: §f"+newId); plugin.gui().openEditor(p,newId); return;
            }
            if(field.startsWith("particleSetting:")) {
                String[] q=field.split(":",3); if(q.length!=3)throw new IllegalArgumentException(); String pn=q[1].toUpperCase(Locale.ROOT); if(e.collectParticles.stream().noneMatch(x->x.equalsIgnoreCase(pn)))throw new IllegalArgumentException(); ParticleSettings ps=e.particleSettings.computeIfAbsent(pn,k->new ParticleSettings());
                switch(q[2]){case "radius"->ps.radius=parseFiniteDouble(v,0,128);case "duration"->ps.durationSeconds=(v.equalsIgnoreCase("global")||v.equalsIgnoreCase("default")||v.equalsIgnoreCase("full")?-1:parseDuration(v,1,604800));case "count"->ps.count=(v.equalsIgnoreCase("global")||v.equalsIgnoreCase("default")?-1:parseInt(v,0,100000));case "speed"->ps.speed=parseFiniteDouble(v,0,100);case "size"->ps.size=(float)parseFiniteDouble(v,0.01,10);case "color"->{String c=v.trim().toUpperCase(Locale.ROOT);if(c.equals("GLOBAL")||c.equals("DEFAULT")||c.equals("EMPTY")){ps.color="";}else{String[] colors=c.replace(';',',').split(",");if(colors.length>2)throw new IllegalArgumentException();for(String hex:colors)if(!hex.matches("#[0-9A-F]{6}"))throw new IllegalArgumentException();ps.color=String.join(",",colors);}}default->throw new IllegalArgumentException();}
                plugin.data().saveEvent(e);plugin.data().log("particle.settings",p.getName(),e.id+" -> "+pn+"."+q[2]+"="+v);plugin.gui().openParticleSettings(p,id,pn);return;
            }
            switch (field) {
                case "name" -> e.adminName = requireText(v);
                case "playerName" -> e.playerName = requireText(v);
                case "description" -> e.description = requireText(v);
                case "minReward" -> { int n=parseInt(v,-100000000,Integer.MAX_VALUE); if(n>e.rewardMax) throw new RangeOrderException(); e.rewardMin=n; }
                case "maxReward" -> { int n=parseInt(v,-100000000,Integer.MAX_VALUE); if(n<e.rewardMin) throw new RangeOrderException(); e.rewardMax=n; }
                case "maxActive" -> e.maxActive = parseInt(v,1,100000);
                case "regionMax" -> e.regionMaxActive = parseInt(v,1,100000);
                case "distance" -> e.minDistance = parseFiniteDouble(v,0,100000);
                case "playerDistance" -> e.minPlayerDistance = parseFiniteDouble(v,0,100000);
                case "chance" -> e.spawnChance = parseFiniteDouble(v,0,100);
                case "minHeight" -> { int n=parseInt(v,-2032,2032); if(n>e.maxY) throw new RangeOrderException(); e.minY=n; }
                case "maxHeight" -> { int n=parseInt(v,-2032,2032); if(n<e.minY) throw new RangeOrderException(); e.maxY=n; }
                case "height" -> { long[] r=parseRangeLong(v); if(r[0]<-2032||r[1]>2032)throw new IllegalArgumentException(); e.minY=(int)r[0];e.maxY=(int)r[1]; }
                case "minDelay" -> { long n=parseDuration(v,0,86400); if(n>e.maxDelaySeconds) throw new RangeOrderException(); e.minDelaySeconds=n; }
                case "maxDelay" -> { long n=parseDuration(v,0,86400); if(n<e.minDelaySeconds) throw new RangeOrderException(); e.maxDelaySeconds=n; }
                case "delay" -> { long[] r=parseDurationRange(v,0,86400); e.minDelaySeconds=r[0];e.maxDelaySeconds=r[1]; }
                case "minLifetime" -> { long n=parseDuration(v,1,604800); if(n>e.maxLifetimeSeconds) throw new RangeOrderException(); e.minLifetimeSeconds=n; }
                case "maxLifetime" -> { long n=parseDuration(v,1,604800); if(n<e.minLifetimeSeconds) throw new RangeOrderException(); e.maxLifetimeSeconds=n; }
                case "lifetime" -> { long[] r=parseDurationRange(v,1,604800); e.minLifetimeSeconds=r[0];e.maxLifetimeSeconds=r[1]; }
                case "region" -> e.addRegion(requireText(v));
                case "particleCount" -> { if(v.equalsIgnoreCase("global")||v.equalsIgnoreCase("default")){e.collectParticleCount=-1;}else{int n=parseInt(v,0,100);e.collectParticleCount=n;} }
                case "particleColor" -> { String c=v.trim().toUpperCase(Locale.ROOT); if(!c.matches("#[0-9A-F]{6}")) throw new IllegalArgumentException(); e.collectParticleColor=c; e.collectParticleColors.clear(); e.collectParticleColors.add(c); if(e.collectParticle.isBlank()) e.collectParticle="DUST"; ParticleSettings ps=e.particleSettings.computeIfAbsent("DUST",k->new ParticleSettings()); ps.color=""; }
                case "particleColors" -> { String raw=v.replace(';',','); List<String> colors=new ArrayList<>(); for(String part:raw.split(",")){String c=part.trim().toUpperCase(Locale.ROOT); if(!c.matches("#[0-9A-F]{6}")) throw new IllegalArgumentException(); colors.add(c);} if(colors.isEmpty()) throw new IllegalArgumentException(); e.collectParticleColors.clear(); e.collectParticleColors.addAll(colors); e.collectParticleColor=colors.get(0); e.collectParticle="DUST"; ParticleSettings ps=e.particleSettings.computeIfAbsent("DUST",k->new ParticleSettings()); ps.color=""; }
                case "particleColorsAdd" -> { String raw=v.replace(';',','); List<String> colors=new ArrayList<>(); for(String part:raw.split(",")){String c=part.trim().toUpperCase(Locale.ROOT); if(!c.matches("#[0-9A-F]{6}")) throw new IllegalArgumentException(); colors.add(c);} if(colors.isEmpty()) throw new IllegalArgumentException(); for(String c:colors) if(e.collectParticleColors.stream().noneMatch(x->x.equalsIgnoreCase(c))) e.collectParticleColors.add(c); e.collectParticleColor=e.collectParticleColors.get(0); if(!e.collectParticles.stream().anyMatch(x->x.equalsIgnoreCase("DUST"))) e.collectParticles.add("DUST"); e.collectParticle=e.collectParticles.isEmpty()?"DUST":e.collectParticles.get(0); }
                case "particleType" -> { String name=v.trim().toUpperCase(Locale.ROOT); if(!ParticleCatalog.isSupported(name)) throw new IllegalArgumentException(); e.collectParticle=name; }
                case "animationSpeed" -> e.animationSpeed=parseFiniteDouble(v,0.05,10.0);
                case "weeklySchedule" -> {String[] q=v.split("\\s*\\|\\s*",2);if(q.length!=2)throw new IllegalArgumentException();String[] left=q[0].trim().split("\\s+",2);if(left.length!=2)throw new IllegalArgumentException();e.weeklyDayOfWeek=parseWeekday(left[0]);e.weeklyTimeMinutes=parseClockMinutes(left[1]);e.weeklyDurationSeconds=parseDuration(q[1].trim(),60,604800);e.weeklyScheduleEnabled=true;e.dailyScheduleEnabled=false;e.enabled=true;}
                case "dailySchedule" -> {String[] q=v.split("\\s*\\|\\s*",2);if(q.length!=2)throw new IllegalArgumentException();e.dailyTimeMinutes=parseClockMinutes(q[0].trim());e.dailyDurationSeconds=parseDuration(q[1].trim(),60,604800);e.dailyScheduleEnabled=true;e.weeklyScheduleEnabled=false;e.startAt=null;e.endAt=null;e.enabled=true;}
                case "conditionsWorld" -> { e.conditions.world=(v.equalsIgnoreCase("any")?"":requireText(v)); e.conditions.enabled=true; }
                case "conditionsTime" -> {long[] r=parseRangeLong(v);if(r[0]<0||r[1]>24000||r[0]>r[1])throw new IllegalArgumentException();e.conditions.timeMin=(int)r[0];e.conditions.timeMax=(int)r[1];e.conditions.enabled=true;}
                case "conditionsHelmet" -> { e.conditions.requiredHelmetMaterial=normalizeMaterialOrEmpty(v); e.conditions.enabled=true; }
                case "conditionsItem" -> { e.conditions.requiredInventoryMaterial=normalizeMaterialOrEmpty(v); e.conditions.enabled=true; }
                case "conditionsMessage" -> { e.conditions.failMessage=requireText(v); e.conditions.enabled=true; }
                case "conditionsPenalty" -> { e.conditions.failPenalty=parseInt(v,0,100000000); e.conditions.enabled=true; }
                case "soundCustom" -> {
                    if(v.equalsIgnoreCase("empty") || v.equalsIgnoreCase("global")){ e.sound=""; e.sounds.clear(); e.soundEnabled=true; }
                    else {
                        e.sounds.clear();
                        for(String part:v.split(",")){String sn=requireText(part).toUpperCase(Locale.ROOT); if(!sn.isBlank()&&!e.sounds.contains(sn)) e.sounds.add(sn);}
                        e.sound=e.sounds.isEmpty()?"":e.sounds.get(0); e.soundEnabled=true;
                    }
                }
                case "soundVolume" -> e.soundVolume=(float)parseFiniteDouble(v,0,10);
                case "soundPitch" -> e.soundPitch=(float)parseFiniteDouble(v,0.5,2);
                case "scheduleStart" -> { e.startAt=parseInstantOrRelative(v); if(e.endAt!=null&&e.endAt.isBefore(e.startAt)) throw new RangeOrderException(); }
                case "scheduleEnd" -> { e.endAt=parseInstantOrRelative(v); if(e.endAt.isBefore(e.startAt==null?Instant.now():e.startAt)) throw new RangeOrderException(); }
                case "schedule" -> {
                    String[] a=v.split("\\s*\\|\\s*",2);
                    e.startAt=parseInstantOrRelative(a[0]);
                    if(a.length>1&&!a[1].isBlank()){
                        String second=a[1].trim();
                        if(second.matches("^[+]?\\d+(?:\\.\\d+)?[smhdw]$")) e.endAt=e.startAt.plusSeconds(parseDuration(second,1,315360000));
                        else e.endAt=parseInstantOrRelative(second);
                    } else e.endAt=null;
                    if(e.endAt!=null&&e.endAt.isBefore(e.startAt))throw new RangeOrderException();
                }
                default -> throw new IllegalArgumentException();
            }
            // Do not silently repair invalid ordering: the editor must force the administrator to fix it.
            // Не исправляем порядок автоматически — администратор должен увидеть и исправить ошибку.
            validate(e);
            plugin.data().saveEvent(e);
            plugin.data().log("event.setting",p.getName(),e.id+" -> "+field+"="+v);
            switch (field) {
                case "particleCount", "particleColor", "particleColors", "particleType" -> plugin.gui().openParticleEditor(p,id);
                case "particleColorsAdd" -> plugin.gui().openParticlePalette(p,id);
                case "weeklySchedule", "dailySchedule" -> plugin.gui().openEventSettings(p,id,"schedule");
                case "conditionsWorld","conditionsTime","conditionsHelmet","conditionsItem","conditionsMessage","conditionsPenalty" -> plugin.gui().openEventSettings(p,id,"conditions");
                case "soundCustom","soundVolume","soundPitch" -> plugin.gui().openSoundPicker(p,id);
                case "animationSpeed" -> plugin.gui().openEventSettings(p,id,"animation");
                case "schedule", "scheduleStart", "scheduleEnd" -> plugin.gui().openEventSettings(p,id,"schedule");
                case "maxActive", "regionMax", "distance", "playerDistance", "chance", "minHeight", "maxHeight", "height", "minDelay", "maxDelay", "delay", "minLifetime", "maxLifetime", "lifetime", "region" -> plugin.gui().openEventSettings(p,id,"spawn");
                case "minReward", "maxReward" -> plugin.gui().openEventSettings(p,id,"rewards");
                default -> plugin.gui().openEventSettings(p,id,"info");
            }
        } catch (RangeOrderException ex) {
            plugin.lang().send(p, plugin.lang().tr(p,"range-order-error"));
            reopenAfterInput(p,id,field);
        } catch (Exception ex) {
            String range=switch(field){
                case "minDelay","maxDelay","delay"->"0–86400 сек. (например: 15s, 5m, 2h, 1d). Чем меньше диапазон — тем чаще пытается спавнить.";
                case "minLifetime","maxLifetime","lifetime"->"1–604800 сек. (30s, 5m, 2h, 7d). Изменение min/max применяется к новым спавнам; активные можно пересчитать кнопкой «Применить к активным».";
                case "minHeight","maxHeight","height"->"-2032–2032";
                case "chance"->"0–100";
                case "distance","playerDistance"->"0 и больше блоков";
                case "schedule", "scheduleStart", "scheduleEnd"->"2026-08-31 12:00 | 2026-09-07 23:59 или 2d | 1d";
                default->"корректное значение";
            };
            plugin.lang().send(p, plugin.lang().tr(p,"value-error").replace("{range}",range));
            reopenAfterInput(p,id,field);
        }
    }

    private void reopenAfterInput(Player p,String id,String field){
        if(id==null||plugin.events().get(id)==null)return;
        switch(field){
            case "particleCount","particleColor","particleColors","particleType" -> plugin.gui().openParticleEditor(p,id);
            case "particleColorsAdd" -> plugin.gui().openParticlePalette(p,id);
            case "weeklySchedule", "dailySchedule" -> plugin.gui().openEventSettings(p,id,"schedule");
            case "conditionsWorld","conditionsTime","conditionsHelmet","conditionsItem","conditionsMessage","conditionsPenalty" -> plugin.gui().openEventSettings(p,id,"conditions");
            case "soundCustom","soundVolume","soundPitch" -> plugin.gui().openSoundPicker(p,id);
            case "animationSpeed" -> plugin.gui().openEventSettings(p,id,"animation");
            case "schedule", "scheduleStart", "scheduleEnd" -> plugin.gui().openEventSettings(p,id,"schedule");
            case "minReward","maxReward" -> plugin.gui().openEventSettings(p,id,"rewards");
            case "maxActive","regionMax","distance","chance","minHeight","maxHeight","height","minDelay","maxDelay","delay","minLifetime","maxLifetime","lifetime","region","pointChance" -> plugin.gui().openEventSettings(p,id,"spawn");
            default -> plugin.gui().openEventSettings(p,id,"info");
        }
    }

    private int parseWeekday(String raw){String x=raw.trim().toLowerCase(Locale.ROOT);return switch(x){case "1","пн","понедельник","mon","monday"->1;case "2","вт","вторник","tue","tuesday"->2;case "3","ср","среда","wed","wednesday"->3;case "4","чт","четверг","thu","thursday"->4;case "5","пт","пятница","fri","friday"->5;case "6","сб","суббота","sat","saturday"->6;case "7","вс","воскресенье","sun","sunday"->7;default->throw new IllegalArgumentException();};}
    private int parseClockMinutes(String raw){String[] a=raw.trim().split(":");if(a.length!=2)throw new IllegalArgumentException();int h=Integer.parseInt(a[0]),m=Integer.parseInt(a[1]);if(h<0||h>23||m<0||m>59)throw new IllegalArgumentException();return h*60+m;}
    private String normalizeMaterialOrEmpty(String raw){String x=raw.trim();if(x.equalsIgnoreCase("empty")||x.equalsIgnoreCase("none")||x.isBlank())return "";try{return org.bukkit.Material.valueOf(x.toUpperCase(Locale.ROOT)).name();}catch(Exception ex){throw new IllegalArgumentException();}}
    private String requireText(String s){if(s.isBlank()||s.length()>128)throw new IllegalArgumentException();return s;}
    private int parseInt(String s,int min,int max){int v=Integer.parseInt(s.replace("_","").trim());if(v<min||v>max)throw new IllegalArgumentException();return v;}
    private double parseFiniteDouble(String s){return parseFiniteDouble(s,-Double.MAX_VALUE,Double.MAX_VALUE);}
    private double parseFiniteDouble(String s,double min,double max){double v=Double.parseDouble(s.trim().replace(',','.'));if(!Double.isFinite(v)||v<min||v>max)throw new IllegalArgumentException();return v;}

    /** Plain numbers are seconds. Suffixes: s, m, h, d, w. RU/EN: 1m=minute, 1h=hour, 1d=day, 1w=week. */
    private long parseDuration(String raw,long min,long max){
        String s=raw.trim().toLowerCase(Locale.ROOT).replace(',','.').replaceFirst("^\\+","");
        Matcher m=Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)([smhdw]?)$").matcher(s);
        if(!m.matches())throw new IllegalArgumentException();
        double value=Double.parseDouble(m.group(1));
        long multiplier=switch(m.group(2)){case "s",""->1;case "m"->60;case "h"->3600;case "d"->86400;case "w"->604800;default->throw new IllegalArgumentException();};
        double seconds=value*multiplier;
        if(!Double.isFinite(seconds)||seconds<min||seconds>max||seconds>Long.MAX_VALUE)throw new IllegalArgumentException();
        return Math.round(seconds);
    }
    private long[] parseDurationRange(String raw,long min,long max){String s=raw.trim();Matcher m=RANGE.matcher(s);if(!m.matches())throw new IllegalArgumentException();String a=m.group(1)!=null?m.group(1):m.group(3);String b=m.group(2)!=null?m.group(2):m.group(4);long x=parseDuration(a,min,max),y=parseDuration(b,min,max);if(y<x)throw new RangeOrderException();return new long[]{x,y};}
    private double[] parseDoubleRange(String raw,double min,double max){String s=raw.trim();String[] parts=s.split("\\s*(?:[-–—]|\\.\\.|\\||:)\\s*|\\s+",2);if(parts.length!=2)throw new IllegalArgumentException();double a=Double.parseDouble(parts[0].replace(',','.')),b=Double.parseDouble(parts[1].replace(',','.'));if(!Double.isFinite(a)||!Double.isFinite(b)||a<min||b>max||b<a)throw new IllegalArgumentException();return new double[]{a,b};}
    private long[] parseRangeLong(String raw){String s=raw.trim();Matcher m=RANGE.matcher(s);if(!m.matches())throw new IllegalArgumentException();String a=m.group(1)!=null?m.group(1):m.group(3);String b=m.group(2)!=null?m.group(2):m.group(4);if(a.matches(".*[a-zA-Z].*")||b.matches(".*[a-zA-Z].*"))throw new IllegalArgumentException();long x=Long.parseLong(a),y=Long.parseLong(b);if(y<x)throw new RangeOrderException();return new long[]{x,y};}

    /**
     * Absolute schedule: yyyy-MM-dd HH:mm, ISO date-time, or instant.
     * Relative schedule: 7d / 12h / 30m from the moment of input.
     */
    private Instant parseInstantOrRelative(String raw){
        String s=raw.trim();
        if(s.matches("^[+]?\\d+(?:\\.\\d+)?[smhdw]$")){
            long seconds=parseDuration(s,1,315360000);
            return Instant.now().plusSeconds(seconds);
        }
        String normalized=s.replace('T',' ').trim();
        DateTimeFormatter[] formats={DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]",Locale.ROOT),DateTimeFormatter.ISO_LOCAL_DATE_TIME};
        for(DateTimeFormatter f:formats){try{return LocalDateTime.parse(normalized,f).atZone(ZoneId.systemDefault()).toInstant();}catch(DateTimeParseException ignored){}}
        return Instant.parse(s);
    }

    private void applyTemplateSetting(Player p,String template,String field,String v){
        try{
            String t=template.toLowerCase(Locale.ROOT);
            if(v.equalsIgnoreCase("cancel")||v.equalsIgnoreCase("отмена")){plugin.gui().openTemplateSettings(p,t);return;}
            switch(field){
                case "reward-min", "reward-max" -> plugin.templates().saveTemplateSetting(t,field,parseInt(v,-100000000,Integer.MAX_VALUE));
                case "max-active", "region-max" -> plugin.templates().saveTemplateSetting(t,field,parseInt(v,1,100000));
                case "min-distance", "min-player-distance" -> plugin.templates().saveTemplateSetting(t,field,parseFiniteDouble(v,0,100000));
                case "min-delay", "max-delay" -> plugin.templates().saveTemplateSetting(t,field,parseDuration(v,0,86400));
                case "min-lifetime", "max-lifetime" -> plugin.templates().saveTemplateSetting(t,field,parseDuration(v,1,604800));
                case "particle-count" -> plugin.templates().saveTemplateSetting(t,field,parseInt(v,0,100000));
                case "particles" -> { List<String> list=new ArrayList<>(); for(String raw:v.split(",")){String x=raw.trim().toUpperCase(Locale.ROOT); if(x.isBlank())continue; if(!ParticleCatalog.isSupported(x))throw new IllegalArgumentException(); if(!list.contains(x))list.add(x);} plugin.templates().saveTemplateSetting(t,field,list); }
                case "sounds" -> { List<String> list=new ArrayList<>(); for(String raw:v.split(",")){String x=raw.trim().toUpperCase(Locale.ROOT); if(x.isBlank())continue; if(!list.contains(x))list.add(x);} plugin.templates().saveTemplateSetting(t,field,list); }
                case "helmet", "inventory-item" -> plugin.templates().saveTemplateSetting(t,field,normalizeMaterialOrEmpty(v));
                case "fail-message", "description", "player-name", "admin-name" -> plugin.templates().saveTemplateSetting(t,field,requireText(v));
                case "fail-penalty" -> plugin.templates().saveTemplateSetting(t,field,parseInt(v,-100000000,Integer.MAX_VALUE));
                case "animation-speed" -> plugin.templates().saveTemplateSetting(t,field,parseFiniteDouble(v,0.05,10));
                case "sound-volume" -> plugin.templates().saveTemplateSetting(t,field,parseFiniteDouble(v,0,10));
                case "sound-pitch" -> plugin.templates().saveTemplateSetting(t,field,parseFiniteDouble(v,0.5,2));
                case "weekly" -> { String[] q=v.split("\\s*\\|\\s*",2); if(q.length!=2)throw new IllegalArgumentException(); String[] left=q[0].trim().split("\\s+",2); if(left.length!=2)throw new IllegalArgumentException(); int day=parseWeekday(left[0]); int time=parseClockMinutes(left[1]); long duration=parseDuration(q[1].trim(),60,604800); plugin.templates().saveTemplateSetting(t,"weekly.enabled",true); plugin.templates().saveTemplateSetting(t,"weekly.day",day); plugin.templates().saveTemplateSetting(t,"weekly.time",time); plugin.templates().saveTemplateSetting(t,"weekly.duration",duration); }
                default -> throw new IllegalArgumentException();
            }
            plugin.data().log("template.setting",p.getName(),t+" -> "+field+"="+v); plugin.lang().send(p, "§a✓ Настройка шаблона сохранена."); plugin.gui().openTemplateSettings(p,t,field.equals("weekly")?2:1);
        }catch(Exception ex){plugin.lang().send(p, "§cНекорректное значение. Проверьте формат и диапазон.");plugin.gui().openTemplateSettings(p,template);}
    }

    private void validate(EventDefinition e){
        if(e.rewardMin>e.rewardMax)throw new RangeOrderException();
        if(e.minDelaySeconds>e.maxDelaySeconds)throw new RangeOrderException();
        if(e.minLifetimeSeconds>e.maxLifetimeSeconds)throw new RangeOrderException();
        if(e.minY>e.maxY)throw new RangeOrderException();
        if(e.maxActive<1||e.regionMaxActive<1||e.minDelaySeconds<0||e.minLifetimeSeconds<1||e.minPlayerDistance<0)throw new IllegalArgumentException();
    }
    private static final class RangeOrderException extends IllegalArgumentException {}
}

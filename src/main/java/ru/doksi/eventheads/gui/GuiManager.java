package ru.doksi.eventheads.gui;

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.antiesp.AntiEspReport;
import ru.doksi.eventheads.catalog.NoteCatalog;
import ru.doksi.eventheads.catalog.ParticleCatalog;
import ru.doksi.eventheads.catalog.ParticleSettings;
import ru.doksi.eventheads.events.EventConditions;
import ru.doksi.eventheads.events.EventDefinition;
import ru.doksi.eventheads.events.SpawnPoint;
import ru.doksi.eventheads.localization.ChatColorUtil;
import ru.doksi.eventheads.roles.CustomRole;
import ru.doksi.eventheads.roles.Perm;
import ru.doksi.eventheads.roles.Role;
import ru.doksi.eventheads.util.SchedulerUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Полностью управляет GUI EventHeads.
 *
 * Важный принцип: каждый экран имеет собственный тип holder-а и собственные action-id.
 * Это не даёт кнопке «Назад» из одного раздела случайно вернуть игрока в главное меню.
 * Все интерактивные предметы помечаются PDC, поэтому обычные предметы в инвентаре
 * игрока никогда не воспринимаются как кнопки нашего GUI.
 */
public final class GuiManager {
    private final Map<UUID,ScheduledTask> refreshTasks=new java.util.concurrent.ConcurrentHashMap<>();

    private Inventory guiInventory(Player p, EventMenuHolder h, String title){
        return Bukkit.createInventory(h,54,ChatColorUtil.color(plugin.lang().gui(p,title)));
    }
    private static final String[] PERMISSIONS = {"view","create","edit","delete","spawn","points","points-all","stats","schedule","access","give","settings","test","break","item","export","import","anti-esp","audit","templates"};

    private String permissionTitle(String permission){
        return switch(permission){
            case "view" -> "Ивенты • Просмотр";
            case "create" -> "Ивенты • Создание";
            case "edit" -> "Ивенты • Настройки";
            case "delete" -> "Ивенты • Удаление";
            case "spawn" -> "Спавн • Управление";
            case "points" -> "Точки • Свои";
            case "points-all" -> "Точки • Все";
            case "stats" -> "Статистика";
            case "schedule" -> "Расписание";
            case "access" -> "Сотрудники и роли";
            case "give" -> "Выдача";
            case "settings" -> "Системные настройки";
            case "test" -> "Тестовый спавн";
            case "break" -> "Принудительное снятие";
            case "item" -> "Предмет EventHead";
            case "export" -> "Экспорт";
            case "import" -> "Импорт";
            case "anti-esp" -> "Anti-ESP уведомления";
            case "audit" -> "Журнал действий";
            case "templates" -> "Шаблоны";
            default -> permission;
        };
    }
    private static final Material BORDER = Material.PURPLE_STAINED_GLASS_PANE;
    private final EventHeadsPlugin plugin;
    private final NamespacedKey actionKey;
    private final Map<UUID,Long> roleDeletePendingUntil=new HashMap<>();
    private final Map<UUID,String> roleDeletePendingId=new HashMap<>();

    public GuiManager(EventHeadsPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
    }

    public void markRoleDeletePending(Player p,String id){roleDeletePendingId.put(p.getUniqueId(),id);roleDeletePendingUntil.put(p.getUniqueId(),System.currentTimeMillis()+20000L);}
    public void clearRoleDeletePending(Player p){roleDeletePendingId.remove(p.getUniqueId());roleDeletePendingUntil.remove(p.getUniqueId());}
    public boolean roleDeletePending(Player p,String id){Long until=roleDeletePendingUntil.get(p.getUniqueId());String pending=roleDeletePendingId.get(p.getUniqueId());if(until==null||pending==null||!pending.equalsIgnoreCase(id)||System.currentTimeMillis()>until){clearRoleDeletePending(p);return false;}return true;}

    public String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColorUtil.color(name));
            List<String> lines = new ArrayList<>();
            for (String line : lore) lines.add(ChatColorUtil.color(line));
            meta.setLore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack menuHead(String type, String name, String... lore) {
        ItemStack stack = plugin.items().makeMenuHead(type);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColorUtil.color(name));
            meta.setLore(Arrays.stream(lore).map(ChatColorUtil::color).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void setAction(ItemStack stack, String value) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, value);
            stack.setItemMeta(meta);
        }
    }

    private void put(Inventory inventory, int slot, ItemStack stack) { inventory.setItem(slot, stack); }
    private void put(Inventory inventory, int slot, ItemStack stack, String action) { setAction(stack, action); inventory.setItem(slot, stack); }

    private void border(Inventory inventory) {
        ItemStack pane = item(BORDER, "&8 ");
        int rows = inventory.getSize() / 9;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9, col = slot % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) inventory.setItem(slot, pane.clone());
        }
    }

    private int[] gridSlots() {
        // Только внутренние клетки: 7 столбцов x 4 строки. Край меню всегда остаётся рамкой.
        return new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    }

    private void nav(Inventory inv, int previousPage, int nextPage, int pages, String actionPrefix, String backAction) {
        if (previousPage > 0) put(inv, 45, item(Material.ARROW, "&b← Предыдущая", "&7Страница: &f" + previousPage), actionPrefix + previousPage);
        if (nextPage <= pages) put(inv, 53, item(Material.ARROW, "&bСледующая →", "&7Страница: &f" + nextPage), actionPrefix + nextPage);
        put(inv, 49, item(Material.ARROW, "&b← Назад", "&7Вернуться на предыдущий экран."), backAction);
        put(inv, 50, item(Material.BARRIER, "&cЗакрыть", "&7Закрыть меню."), "close");
    }

    public void openMain(Player p) {
        EventMenuHolder h = new EventMenuHolder("main", null);
        Inventory inv = guiInventory(p,h,"&8EventHeads &7• &6Центр управления");
        h.inventory(inv); border(inv);
        boolean pointOnly=plugin.access().has(p,Perm.POINTS)
                && !plugin.access().has(p,Perm.EDIT)
                && !plugin.access().has(p,Perm.GIVE)
                && !plugin.access().has(p,Perm.STATS)
                && !plugin.access().has(p,Perm.ACCESS)
                && !plugin.access().has(p,Perm.SETTINGS);
        if (plugin.access().has(p, Perm.VIEW) || plugin.access().has(p, Perm.POINTS)) {
            String action = "events";
            put(inv, 20, menuHead("events", "&6Ивенты", "&7Список доступных ивентов.", plugin.access().has(p, Perm.EDIT)?"&7Есть доступ к настройкам.":"&7Доступны только операции с точками.", "&aЛКМ — открыть"), action);
        }
        if (plugin.access().has(p, Perm.STATS)) put(inv, 22, item(Material.BOOKSHELF, "&bСтатистика", "&7Сборы по каждому событию.", "&7Внутри отображается сам предмет события.", "&aЛКМ — открыть"), "statslist");
        if (plugin.access().has(p, Perm.ACCESS)) put(inv, 24, menuHead("staff", "&aСотрудники и роли", "&7Игроки, роли, права и поиск.", "&7Назначение без OP через EventHeads/LuckPerms.", "&aЛКМ — открыть"), "access");
        if (plugin.access().has(p, Perm.SETTINGS)) put(inv, 31, item(Material.COMPARATOR, "&eДиагностика", "&7Проверка состояния EventHeads и данных.", "&aЛКМ — открыть"), "debug");
        if (plugin.access().has(p, Perm.POINTS)) put(inv, 29, item(Material.RABBIT_HIDE, "&aМои точки", "&7Только ваши точки EventHeads.", "&7ЛКМ — открыть список", "&7Можно менять шанс и удалять свои точки."), "myPoints");
        if (plugin.access().has(p, Perm.SETTINGS)) put(inv, 32, item(Material.GLASS, "&dАнти-ESP приманки", "&7Глобальные настройки ложных стоек.", "&7Количество, радиус, задержка, время жизни.", "&aЛКМ — настроить"), "antiEspSettings");
        if (plugin.access().has(p, Perm.AUDIT)) put(inv, 34, item(Material.WRITABLE_BOOK, "&dЖурнал действий", "&7Кто и что менял в EventHeads.", "&7Открывается только в GUI.", "&aЛКМ — открыть"), "auditLog");
        if (plugin.access().has(p, Perm.CREATE) || plugin.access().has(p, Perm.TEMPLATES)) put(inv, 35, item(Material.NETHER_STAR, "&aШаблоны ивентов", "&7Пасха, зима, ярмарка, поиск.", "&7Можно быстро создать готовую конфигурацию.", "&aЛКМ — открыть"), "eventTemplates");
        if(!pointOnly) {
            put(inv, 33, item(Material.BOOK, "&bСправка", "&7Все основные команды.", "&7Полная команда + короткая команда + пример.", "&aЛКМ — открыть"), "help");
            put(inv, 40, item(Material.KNOWLEDGE_BOOK, "&dКак работать", "&7Пошаговая инструкция для модератора.", "&7Команды, права, точки, спавн и сбор EventHead.", "&aЛКМ — открыть справку"), "howItWorks");
            put(inv, 42, item(Material.CLOCK, "&6Состояние событий", "&7Когда событие началось и когда закончится.", "&7Статус событий.", "&aЛКМ — открыть"), "scheduleOverview");
        }
        put(inv, 50, item(Material.BARRIER, "&cЗакрыть", "&7Закрыть меню."), "close");
        plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openAntiEspSettings(Player p) {
        EventMenuHolder h=new EventMenuHolder("antiEspSettings",null); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dАнти-ESP приманки"); h.inventory(inv); border(inv);
        boolean enabled=plugin.getConfig().getBoolean("security.anti-esp-decoys",true);
        put(inv,10,toggle(Material.GLASS,"&dЛожные стойки",enabled,"toggle:antiEsp"));
        put(inv,12,field(Material.PLAYER_HEAD,"&eКоличество",""+plugin.getConfig().getInt("security.anti-esp-decoys-min",2)+" – "+plugin.getConfig().getInt("security.anti-esp-decoys-max",4),"global:decoyCount"));
        put(inv,14,field(Material.COMPASS,"&eРадиус",""+plugin.getConfig().getDouble("security.anti-esp-decoys-radius-min",3.5)+" – "+plugin.getConfig().getDouble("security.anti-esp-decoys-radius-max",10.0)+" блоков","global:decoyRadius"));
        put(inv,16,field(Material.CLOCK,"&eЗадержка повторного появления",formatDuration(plugin.getConfig().getLong("security.anti-esp-decoys-delay-min-seconds",10))+" – "+formatDuration(plugin.getConfig().getLong("security.anti-esp-decoys-delay-max-seconds",30)),"global:decoyDelay"));
        put(inv,19,field(Material.CLOCK,"&eВремя жизни",""+formatDuration(plugin.getConfig().getLong("security.anti-esp-decoys-lifetime-min-seconds",15))+" – "+formatDuration(plugin.getConfig().getLong("security.anti-esp-decoys-lifetime-max-seconds",30)),"global:decoyLifetime"));
        put(inv,23,item(Material.BOOKSHELF,"&dТоп срабатываний","&7Кто чаще всего взаимодействовал с ловушками.","&7Закопанные и наземные срабатывания разделены.","&aЛКМ — открыть"),"antiEspTop");
        put(inv,21,item(Material.LIME_DYE,"&aКак работают приманки","&7Ложные стойки появляются вокруг настоящего EventHead.","&7Позиции случайные и меняются от цикла к циклу.","&7Голова всегда наклонена вниз; награды нет.","&7Настройки общие для всех событий."),"noop");
        put(inv,45,item(Material.ARROW,"&b← Назад","&7Вернуться в центр управления."),"back"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openAntiEspTop(Player p){openAntiEspTop(p,1);}

    public void openAntiEspTop(Player p,int page){
        EventMenuHolder h=new EventMenuHolder("antiEspTop",null);
        Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dТоп Anti-ESP");
        h.inventory(inv);border(inv);
        int perPage=28;
        List<AntiEspReport.Entry> all=plugin.antiEspReport().top(Math.min(200,page*perPage));
        int from=Math.max(0,(page-1)*perPage);
        int to=Math.min(all.size(),from+perPage);
        if(from<to){
            java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm");
            int[] slots=gridSlots();int rank=from+1;
            for(int i=from;i<to;i++){AntiEspReport.Entry e=all.get(i);String last=e.last>0?fmt.format(new java.util.Date(e.last)):"—";
                put(inv,slots[i-from],item(Material.PLAYER_HEAD,"&f"+rank+". &d"+e.name,
                        "&7Всего срабатываний: &f"+e.total,
                        "&7Закопанные: &c"+e.buried,
                        "&7Наземные/воздушные: &e"+e.surface,
                        "&7Последнее: &f"+last),"noop");rank++;}
        }else{
            put(inv,22,item(Material.BARRIER,"&cПока нет данных","&7Игроки ещё не срабатывали на ловушках."),"noop");
        }
        int pages=Math.max(1,(all.size()+perPage-1)/perPage);
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница: &f"+(page-1)),"antiEspTop:page:"+(page-1));
        if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница: &f"+(page+1)),"antiEspTop:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Назад","&7Вернуться к Anti-ESP настройкам."),"antiEspSettings");
        put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");
        plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openHowItWorks(Player p) {
        EventMenuHolder h = new EventMenuHolder("howItWorks", null);
        Inventory inv = guiInventory(p,h,"&8EventHeads &7• &dКак работать");
        h.inventory(inv); border(inv);
        put(inv, 10, item(Material.NETHER_STAR, "&61. Создайте ивент", "&7Откройте «Ивенты» → «Создать ивент». ", "&7Получите готовый шаблон."), "events");
        put(inv, 12, item(Material.COMPASS, "&62. Настройте спавн", "&7Выберите RANDOM, POINTS или MIXED.", "&7Добавьте точки или регион WorldGuard."), "noop");
        put(inv, 14, item(Material.GOLD_INGOT, "&63. Настройте награду", "&7Укажите минимум и максимум монет.", "&7Для экономики используйте Vault + экономический плагин."), "noop");
        put(inv, 16, item(Material.FIREWORK_STAR, "&64. Настройте анимацию", "&7Тип частиц выбирается кликом.", "&7Для цветов выбирайте DUST и задавайте HEX."), "noop");
        put(inv, 28, item(Material.RABBIT_HIDE, "&65. Поставьте точки", "&7Получите инструмент точек.", "&7Можно привязать сразу несколько ивентов к одной шкурке."), "noop");
        put(inv, 30, item(Material.RABBIT_FOOT, "&66. Чёрный список", "&7Лапка умеет блокировать место", "&7для одного или нескольких событий."), "noop");
        put(inv, 32, item(Material.CHEST, "&67. Проверьте спавн", "&7Используйте «Тест спавна».", "&7Диагностика покажет причину отказа."), "noop");
        put(inv, 34, item(Material.ENDER_EYE, "&68. Сохранение и перенос", "&7Экспортируйте ивент в YAML.", "&7На другом сервере импортируйте тот же файл."), "noop");
        put(inv, 49, item(Material.ARROW, "&b← Назад", "&7Вернуться в главное меню."), "back");
        put(inv, 50, item(Material.BARRIER, "&cЗакрыть"), "close");
        plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openHelp(Player p) { openHelp(p, 1); }

    public void openHelp(Player p, int requestedPage) {
        List<HelpRow> rows = new ArrayList<>();
        rows.add(new HelpRow("/eventheads help", "/eh help", "Открывает подробную справку.", "/eventheads help", "Полезно для любого сотрудника."));
        rows.add(new HelpRow("/eventheads menu", "/eh menu", "Открывает главное меню EventHeads.", "/eventheads menu", "Центр управления плагином."));
        rows.add(new HelpRow("/eventheads list", "/eh list", "Показывает список созданных событий.", "/eventheads list", "Можно открыть редактор ПКМ."));
        if (plugin.access().has(p, Perm.CREATE)) rows.add(new HelpRow("/eventheads create <id>", "/eh create <id>", "Создаёт новый шаблон события и сразу открывает редактор.", "/eventheads create halloween", "Создаёт событие halloween."));
        if (plugin.access().has(p, Perm.EDIT)) rows.add(new HelpRow("/eventheads item edit <id>", "/eh edit <id>", "Открывает редактор выбранного события.", "/eventheads edit halloween", "Настройки разделены на Общие, Спавн, Награды, Анимация и Расписание."));
        if (plugin.access().has(p, Perm.POINTS)) rows.add(new HelpRow("/eventheads point add <id[,id2,...]>", "/eh add <id[,id2,...]>", "Включает режим постановки точек. Одна шкурка может быть привязана к нескольким событиям.", "/eventheads point add halloween,summer", "Один клик поставит точку сразу двум событиям."));
        if (plugin.access().has(p, Perm.POINTS)) rows.add(new HelpRow("/eventheads point remove <id> <number>", "/eh rp <id> <number>", "Удаляет точку по номеру.", "/eventheads point remove halloween 2", "Удалит точку №2."));
        if (plugin.access().has(p, Perm.POINTS)) rows.add(new HelpRow("/eventheads point cancel", "/eh pc", "Выключает режим постановки точек.", "/eventheads point cancel", "Шкурка после этого становится обычным предметом, если она не защищена."));
        if (plugin.access().has(p, Perm.EDIT)) rows.add(new HelpRow("/eventheads region set <id> <region>", "/eh reg set <id> <region>", "Добавляет WorldGuard-регион как допустимую область спавна.", "/eventheads region set halloween spawn", "WG = приват/регион WorldGuard."));
        if (plugin.access().has(p, Perm.TEST)) rows.add(new HelpRow("/eventheads spawn test <id>", "/eh test <id>", "Проверяет, может ли событие найти место для спавна.", "/eventheads spawn test halloween", "Особенно полезно при POINTS/MIXED."));
        if (plugin.access().has(p, Perm.STATS)) rows.add(new HelpRow("/eventheads stats <id>", "/eh stat <id>", "Открывает статистику конкретного события.", "/eventheads stats halloween", "Внутри показывается предмет самого события."));
        if (plugin.access().has(p, Perm.ACCESS)) rows.add(new HelpRow("/eventheads access", "/eh acl", "Открывает сотрудников и роли.", "/eventheads access", "Здесь можно искать игроков и назначать роли."));
        if (plugin.access().has(p, Perm.ACCESS)) rows.add(new HelpRow("/eventheads role add <player> <roleId>", "/eh role add <player> <roleId>", "Добавляет игрока в уже существующую роль.", "/eventheads role add Steve helperplus", "OP игроку не нужен."));
        if (plugin.access().has(p, Perm.ACCESS)) rows.add(new HelpRow("/eventheads role create <id> <name> <weight> <permissions>", "/eh role create <id> <name> <weight> <permissions>", "Создаёт кастомную роль вручную.", "/eventheads role create helperplus Помощник 45 view,points,give,test", "Для сложного набора прав удобнее использовать GUI-конструктор."));
        if (plugin.access().has(p, Perm.EDIT)) rows.add(new HelpRow("/eventheads export <id>", "/eh export <id>", "Экспортирует настройки, точки, блокировки и визуальный предмет.", "/eventheads export halloween", "Файл попадает в plugins/EventHeads/exports/."));
        if (plugin.access().has(p, Perm.EDIT)) rows.add(new HelpRow("/eventheads import <id>", "/eh import <id>", "Импортирует ранее экспортированный YAML.", "/eventheads import halloween", "Удобно для переноса на другой сервер."));
        if (plugin.access().has(p, Perm.SETTINGS)) rows.add(new HelpRow("/eventheads debug", "/eh debug", "Показывает состояние EventHeads и его интеграций.", "/eventheads debug", "Команда специально НЕ выводит список всех плагинов сервера."));
        if (plugin.access().has(p, Perm.SETTINGS)) rows.add(new HelpRow("/eventheads antiesp top [N]", "/eh antiesp top [N]", "Показывает топ срабатываний Anti-ESP по общему количеству.", "/eventheads antiesp top 10", "В личном сообщении staff видит только общий счётчик и последнюю точку."));
        if (plugin.getConfig().getBoolean("security.anti-esp-personal-visibility-commands", true) && (plugin.access().has(p, Perm.SETTINGS) || p.hasPermission(plugin.getConfig().getString("security.anti-esp-personal-visibility-permission", "eventheads.anti-esp.personal")))) {
            rows.add(new HelpRow("/eventheads antiesp hide|show", "/eh antiesp hide|show", "Скрывает или показывает визуальные предметы Anti-ESP только для себя.", "/eh antiesp hide", "Можно отключить эти команды в config.yml."));
        }
        if (plugin.access().has(p, Perm.ACCESS)) rows.add(new HelpRow("/eventheads role delete <id> confirm", "/eh role delete <id> confirm", "Удаляет только кастомную роль после двойного подтверждения.", "/eventheads role delete helperplus confirm", "Встроенные роли удалить нельзя."));
        if (plugin.access().has(p, Perm.ACCESS)) rows.add(new HelpRow("/eventheads access remove <player> confirm", "/eh access remove <player> confirm", "Удаляет участника из EventHeads после двойного подтверждения.", "/eventheads access remove Steve confirm", "Проверка выполняется повторно перед удалением."));
        if (plugin.access().has(p, Perm.SETTINGS)) rows.add(new HelpRow("/eventheads state", "/eh state", "Открывает состояние событий без выдачи доступа к редактору.", "/eventheads state", "Редактор расписания открывается только с правом schedule."));
        if (plugin.access().has(p, Perm.SETTINGS)) rows.add(new HelpRow("/eventheads reload", "/eh reload", "Перечитывает конфигурацию и данные EventHeads.", "/eventheads reload", "Используйте после ручной правки config.yml."));

        int perPage = 28;
        int pages = Math.max(1, (int)Math.ceil(rows.size() / (double)perPage));
        int page = Math.min(Math.max(requestedPage, 1), pages);
        EventMenuHolder h = new EventMenuHolder("help", null, page);
        Inventory inv = guiInventory(p,h,"&8EventHeads &7• &6Справка &8[" + page + "/" + pages + "]");
        h.inventory(inv); border(inv);
        int[] slots = gridSlots();
        int start=(page-1)*perPage;
        for(int i=start, s=0;i<Math.min(rows.size(),start+perPage);i++,s++){
            HelpRow r=rows.get(i);
            ItemStack book=item(Material.BOOK, "&e"+r.longCommand,
                    "&6Что делает:", "&7"+r.description,
                    "&bПолная: &f"+r.longCommand,
                    "&dКороткая: &f"+r.shortCommand,
                    "&aПример: &f"+r.exampleCommand,
                    "&8"+r.example,
                    "&7ЛКМ — показать пример в чат");
            put(inv,slots[s],book,"help-example:"+Base64.getEncoder().encodeToString(r.exampleCommand.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        if(page>1) put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"help-page:"+(page-1));
        if(page<pages) put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"help-page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Назад","&7Вернуться в главное меню."),"helpBack");
        put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");
        plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openEvents(Player p) { openEvents(p,1); }
    public void openEvents(Player p,int requestedPage){
        List<EventDefinition> all=new ArrayList<>(plugin.events().values()); int perPage=27; int pages=Math.max(1,(int)Math.ceil(all.size()/(double)perPage)); int page=Math.min(Math.max(1,requestedPage),pages);
        boolean canView=plugin.access().has(p,Perm.VIEW) || plugin.access().has(p,Perm.CREATE) || plugin.access().has(p,Perm.TEMPLATES), canPoints=plugin.access().has(p,Perm.POINTS), canEdit=plugin.access().has(p,Perm.EDIT), canGive=plugin.access().has(p,Perm.GIVE), canStats=plugin.access().has(p,Perm.STATS);
        EventMenuHolder h=new EventMenuHolder("events",null,page); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Ивенты &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);
        int start=(page-1)*perPage;int[] slots=gridSlots();
        for(int i=start,s=0;i<Math.min(all.size(),start+perPage);i++,s++){
            EventDefinition e=all.get(i); ItemStack visual=e.visual==null?plugin.items().makeMenuHead("events"):e.visual.clone(); ItemMeta meta=visual.getItemMeta(); if(meta==null)continue;
            String state=e.currentlyScheduled()?"&a":"&c";
            meta.setDisplayName(ChatColorUtil.color(canEdit?state+e.playerName+" &8• &7"+modeRu(e.spawnMode):"&a"+e.playerName));
            List<String> lore=new ArrayList<>();
            lore.add("&7Точек: &f"+visiblePointCount(p,e));
            if(canPoints) lore.add("&7Чёрный список: &f"+e.blockedLocations.size());
            if(canView && canGive) lore.add("&aЛКМ — получить EventHead");
            if(canEdit) lore.add("&eПКМ — открыть настройки");
            else if(canPoints) lore.add("&eПКМ — мои точки этого ивента");
            if(canStats) lore.add("&bShift+ПКМ — статистика");
            meta.setLore(colorLore(lore)); visual.setItemMeta(meta); put(inv,slots[s],visual,"event:"+e.id);
        }
        if((canEdit || plugin.access().has(p,Perm.CREATE) || plugin.access().has(p,Perm.TEMPLATES)) && (plugin.access().has(p,Perm.CREATE) || plugin.access().has(p,Perm.TEMPLATES))) put(inv,43,item(Material.NETHER_STAR,"&aСоздать ивент","&7Создать новый ивент или шаблон прямо из меню."),"eventCreate");
        if(canView) put(inv,44,item(Material.BOOK,"&bДоступные действия",canGive?"&aЛКМ — получить EventHead":"&7ЛКМ скрыто: нет права выдачи",canEdit?"&eПКМ — настройки":"&eПКМ — мои точки",canStats?"&bShift+ПКМ — статистика":"&7Статистика недоступна"),"noop");
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"events:page:"+(page-1));
        if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"events:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Назад","&7Главное меню"),"back"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openEditor(Player p,String id){
        EventDefinition e=plugin.events().get(id);if(e==null){plugin.lang().send(p, plugin.lang().tr(p,"not-found").replace("{value}",id));return;}
        EventMenuHolder h=new EventMenuHolder("editor",id);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6"+e.playerName);h.inventory(inv);border(inv);
        ItemStack visual=e.visual==null?new ItemStack(Material.PLAYER_HEAD):e.visual.clone();ItemMeta vm=visual.getItemMeta();if(vm!=null){vm.setDisplayName(ChatColorUtil.color("&bИсходная голова / предмет"));vm.setLore(colorLore(List.of("&7Нажмите здесь, затем ЛКМ по нужному предмету в инвентаре.","&7Источник только копируется во внешний вид EventHead.")));visual.setItemMeta(vm);}put(inv,4,visual,"visual");
        if(showPage("info") && plugin.access().has(p,Perm.EDIT))put(inv,10,item(Material.PAPER,"&eОбщие","&7Название администратора: &f"+e.adminName,"&7Название игрока: &f"+e.playerName,"&7Создал: &f"+safe(e.authorName),"&7Описание: &f"+limit(e.description,42),"&aЛКМ — открыть"),"settings:info");
        if(showPage("spawn") && plugin.access().has(p,Perm.SPAWN))put(inv,12,item(Material.COMPASS,"&6Спавн","&7Режим: &f"+modeRu(e.spawnMode),"&7Шанс: &f"+fmt(e.spawnChance)+"%","&7Точки: &f"+e.points.size(),"&7Высота: &f"+e.minY+".."+e.maxY,"&aЛКМ — открыть"),"settings:spawn");
        if(showPage("rewards") && plugin.access().has(p,Perm.EDIT))put(inv,14,item(Material.GOLD_INGOT,"&eНаграды","&7Диапазон: &e"+e.rewardMin+"–"+e.rewardMax+" монет","&7Экономика подключается через Vault."),"settings:rewards");
        if(showPage("animation") && plugin.access().has(p,Perm.EDIT))put(inv,16,item(Material.FIREWORK_STAR,"&dАнимация","&7Типов: &f"+Math.max(1,e.collectParticles.size()),"&7Звук: &f"+(e.sound==null||e.sound.isBlank()?"глобальный":e.sound),"&aЛКМ — открыть"),"settings:animation");
        if(plugin.access().has(p,Perm.EDIT))put(inv,19,item(Material.PLAYER_HEAD,"&cУсловия сбора","&7Мир, время, погода, экипировка и предметы.","&aЛКМ — открыть"),"settings:conditions");
        if(plugin.access().has(p,Perm.EDIT))put(inv,21,item(Material.STRUCTURE_VOID,"&bДублировать ивент","&7Создать копию всех настроек, точек, частиц и анимации.","&aЛКМ — выбрать ID копии"),"duplicateEvent");
        if(showPage("schedule") && plugin.access().has(p,Perm.SCHEDULE)){ ItemStack schedule=scheduleItem(p,e); setAction(schedule,"scheduleMenu"); put(inv,28,schedule); }
        if(showPage("spawn") && plugin.access().has(p,Perm.SPAWN))put(inv,30,item(Material.BARRIER,"&cРегионы — недоступно","&7Раздел временно отключён.","&cWorldGuard-регионы в GUI не поддерживаются.","&8Настройка выполняется через команды.","&4Открыть меню нельзя."),"regionsDisabled");
        if(showPage("spawn") && plugin.access().has(p,Perm.POINTS))put(inv,32,item(Material.FIREWORK_ROCKET,"&bТочки и блокировки","&7Точек: &f"+e.points.size(),"&7Чёрный список: &f"+e.blockedLocations.size(),"&aЛКМ — открыть"),"points");
        if(plugin.access().has(p,Perm.SPAWN))put(inv,34,item(e.enabled?Material.LIME_DYE:Material.RED_DYE,e.enabled?"&aИвент включён":"&cИвент выключен","&7Изменить автоматический спавн.","&8Активный спавн: "+(e.enabled?"&aразрешён":"&cзапрещён")),"toggle:enabled");
        if(plugin.access().has(p,Perm.TEST) && plugin.access().has(p,Perm.SPAWN))put(inv,37,item(Material.BEACON,"&bТест спавна","&7Проверить текущие ограничения.","&aЛКМ — выполнить тест"),"test");
        put(inv,39,item(Material.LIME_DYE,"&aСохранить","&7Записать все изменения."),"save");
        if(plugin.access().has(p,Perm.EDIT))put(inv,41,item(Material.CHEST,"&bЭкспорт","&7Экспортировать этот ивент.","&7exports/"+id+".yml"),"export:"+id);
        put(inv,49,item(Material.ARROW,"&b← К ивентам","&7Вернуться именно к списку ивентов."),"eventsBack");if(plugin.access().has(p,Perm.DELETE))put(inv,50,item(Material.BARRIER,"&cУдалить","&7Для безопасности подтверждается командой."),"delete");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    /**
     * Открывает одну логическую страницу редактора. Разделы намеренно разделены:
     * модератор видит только относящиеся к задаче параметры и быстрее находит нужную настройку.
     */
    public void openEventSettings(Player p,String id,String pageName){
        EventDefinition e=plugin.events().get(id);if(e==null)return;
        boolean allowed=switch(pageName.toLowerCase(Locale.ROOT)){
            case "spawn" -> plugin.access().has(p,Perm.SPAWN);
            case "schedule" -> plugin.access().has(p,Perm.SCHEDULE);
            case "info","rewards","animation","conditions" -> plugin.access().has(p,Perm.EDIT);
            default -> false;
        };
        if(!allowed)return;
        EventMenuHolder h=new EventMenuHolder("settings_"+pageName,id);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6"+settingsTitle(pageName)+" &7• &f"+e.playerName);h.inventory(inv);border(inv);
        switch(pageName){case "info"->drawInfo(inv,e);case "spawn"->drawSpawn(inv,e,p);case "rewards"->drawRewards(inv,e);case "animation"->drawAnimation(inv,e,p);case "schedule"->drawSchedule(inv,e,p);case "conditions"->drawConditions(inv,e);}
        put(inv,48,item(Material.ARROW,"&b← Обзор ивента","&7Вернуться на общую страницу."),"backEditor");put(inv,49,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    private void drawInfo(Inventory inv,EventDefinition e){
        put(inv,10,field(Material.NAME_TAG,"&eНазвание администратора",e.adminName,"edit:name"));
        put(inv,12,field(Material.NAME_TAG,"&eНазвание для игрока",e.playerName,"edit:playerName"));
        put(inv,14,field(Material.PAPER,"&eОписание",e.description.isBlank()?"Не задано":e.description,"edit:description"));
        put(inv,16,item(Material.PLAYER_HEAD,"&bИсходный предмет","&7Текущий: &f"+safeItemName(e.visual),"&aЛКМ — выбрать предмет из инвентаря"),"visual");
        put(inv,28,item(Material.BOOK,"&dПодсказка","&7Источник используется как внешний вид EventHead.","&7Он не забирается из вашего инвентаря."),"noop");
        put(inv,30,item(Material.CLOCK,"&7Создано","&7"+formatInstant(Instant.ofEpochMilli(e.createdAt)),"&7Создал: &f"+safe(e.authorName)),"noop");
    }

    private void drawSpawn(Inventory inv,EventDefinition e,Player p){
        put(inv,10,field(Material.CHEST,"&eМаксимум активных",Integer.toString(e.maxActive),"edit:maxActive"));
        put(inv,12,field(Material.CHEST,"&eМаксимум в регионе",Integer.toString(e.regionMaxActive),"edit:regionMax"));
        put(inv,14,field(Material.COMPASS,"&eМинимальная дистанция от EventHead",Double.toString(e.minDistance),"edit:distance"));
        put(inv,15,field(Material.PLAYER_HEAD,"&eНе спавнить рядом с игроками",e.minPlayerDistance<=0?"выключено":formatRadiusValue(e.minPlayerDistance)+" блоков","edit:playerDistance"));
        put(inv,16,field(Material.LEATHER_BOOTS,"&eВысота Y",e.minY+" | "+e.maxY,"edit:height"));
        put(inv,19,field(Material.FIREWORK_ROCKET,"&eШанс спавна",fmt(e.spawnChance)+"%","edit:chance"));
        ItemStack minDelay=field(Material.CLOCK,"&eМинимальная задержка спавна",formatDuration(e.minDelaySeconds),"edit:minDelay"); ItemMeta md=minDelay.getItemMeta(); if(md!=null){List<String> lore=md.getLore()==null?new ArrayList<>():new ArrayList<>(md.getLore());lore.add(ChatColorUtil.color("&7Меньше секунд = быстрее появляются новые EventHead."));md.setLore(lore);minDelay.setItemMeta(md);} put(inv,21,minDelay);
        ItemStack maxDelay=field(Material.CLOCK,"&eМаксимальная задержка спавна",formatDuration(e.maxDelaySeconds),"edit:maxDelay"); ItemMeta xd=maxDelay.getItemMeta(); if(xd!=null){List<String> lore=xd.getLore()==null?new ArrayList<>():new ArrayList<>(xd.getLore());lore.add(ChatColorUtil.color("&7Больше секунд = медленнее появляются новые EventHead."));xd.setLore(lore);maxDelay.setItemMeta(xd);} put(inv,23,maxDelay);
        put(inv,25,field(Material.CLOCK,"&eВремя жизни: минимум",formatDuration(e.minLifetimeSeconds),"edit:minLifetime"));
        put(inv,28,field(Material.CLOCK,"&eВремя жизни: максимум",formatDuration(e.maxLifetimeSeconds),"edit:maxLifetime"));
        put(inv,29,item(Material.REPEATER,"&bИнтервал спавна","&7Текущий диапазон: &f"+formatDurationRange(e.minDelaySeconds,e.maxDelaySeconds),"&7Например: &f5с – 15с &7= новый спавн раз в 5–15 секунд.","&aЛКМ — ввести диапазон"),"edit:delay");
        put(inv,31,item(Material.CHEST,"&dПрименить время жизни к активным","&7Изменённые min/max обычно применяются к новым спавнам.","&7Нажмите здесь, чтобы немедленно пересчитать время для уже существующих.","&aЛКМ — применить"),"lifetimeApplyActive");
        put(inv,33,toggle(Material.WATER_BUCKET,"Вода",e.allowWater,"toggle:water"));
        put(inv,34,toggle(Material.LAVA_BUCKET,"Лава",e.allowLava,"toggle:lava"));
        put(inv,37,toggle(Material.PHANTOM_MEMBRANE,"Плавающий спавн",e.allowFloating,"toggle:air"));
        put(inv,39,toggle(Material.GRASS_BLOCK,"Твёрдая опора",e.requireSolidGround,"toggle:ground"));
        put(inv,41,item(Material.ENDER_EYE,"&6Режим спавна","&7Текущий: &f"+modeRu(e.spawnMode),"&7СЛУЧАЙНЫЙ — ищет автоматически.","&7ТОЧКИ — только сохранённые точки.","&7СМЕШАННЫЙ — точки + случайный поиск.","&aЛКМ — следующий режим"),"spawnModeCycle");
        if(plugin.access().has(p,Perm.POINTS)) put(inv,43,item(Material.FIREWORK_STAR,"&bТочки","&7Сохранено: &f"+e.points.size(),"&aЛКМ — открыть точки"),"points");
    }

    private void drawRewards(Inventory inv,EventDefinition e){
        put(inv,10,item(Material.GOLD_NUGGET,"&eМинимальная сумма","&f"+e.rewardMin+" монет","&7Положительное число — выдать монеты.","&7Отрицательное — списать монеты.","&aЛКМ — изменить через чат"),"edit:minReward");
        put(inv,12,item(Material.GOLD_INGOT,"&eМаксимальная сумма","&f"+e.rewardMax+" монет","&7Положительное число — выдать монеты.","&7Отрицательное — списать монеты.","&aЛКМ — изменить через чат"),"edit:maxReward");
        put(inv,28,item(Material.BOOK,"&bКак работает сумма","&7Например: &f5–10 &7= игрок получает 5–10 монет.","&7Например: &f-5–-1 &7= с игрока списывается 1–5 монет.","&7Если списание невозможно, EventHead не засчитывается."),"noop");
    }

    private void drawAnimation(Inventory inv,EventDefinition e,Player p){
        put(inv,10,item(Material.FIREWORK_STAR,"&dНастройки частиц","&7Тип: &f"+plugin.lang().particleTitle(p,particleType(e)),"&7Цветов: &f"+particleColors(e).size(),"&7Количество: &f"+(e.collectParticleCount<0?"глобальное":e.collectParticleCount),"&aЛКМ — открыть выбор частиц"),"particles");
        put(inv,12,field(Material.CLOCK,"&eСкорость анимации",fmt(e.animationSpeed)+"x","edit:animationSpeed"));
        put(inv,19,item(Material.NOTE_BLOCK,"&bЗвук","&7Сейчас: &f"+(e.sound==null||e.sound.isBlank()?"глобальный":e.sound),"&7Громкость: &f"+fmt(e.soundVolume),"&7Тон: &f"+fmt(e.soundPitch),"&aЛКМ — выбрать звук"),"soundPicker");
        put(inv,21,item(Material.JUKEBOX,"&dПредпросмотр анимации","&7Проиграть звук и все выбранные частицы рядом с вами.","&7Используются индивидуальные настройки частиц.","&aЛКМ — запустить"),"animationPreview");
        put(inv,14,toggle(Material.ENDER_PEARL,"Маленькая стойка во время анимации",e.animationSmall,"toggle:animationSmall"));
        put(inv,16,toggle(Material.REPEATER,"Случайный поворот",e.randomRotation,"toggle:rotation"));
        put(inv,28,item(Material.BLAZE_POWDER,"&bКак работают цвета","&7HEX-цвета применяются к DUST.","&7Несколько цветов распределяются между частицами."),"noop");
        put(inv,30,item(Material.BARRIER,"&cСбросить настройки частиц","&7Вернуть индивидуальные значения к глобальным."),"particleReset");
    }

    private void drawSchedule(Inventory inv,EventDefinition e,Player p){
        ItemStack combined=scheduleItem(p,e);setAction(combined,"scheduleEdit");put(inv,22,combined);
        put(inv,10,item(Material.CLOCK,"&eДата начала","&7Текущая: &f"+(e.startAt==null?"не задана":formatInstant(e.startAt)),"&aЛКМ — изменить"),"scheduleStart");
        put(inv,12,item(Material.CLOCK,"&eДата окончания","&7Текущая: &f"+(e.endAt==null?"не задана":formatInstant(e.endAt)),"&aЛКМ — изменить"),"scheduleEnd");
        put(inv,13,item(Material.WRITABLE_BOOK,"&bОдна строка","&7Формат: дата начала | дата окончания","&7Пример: &f2026-09-10 18:00 | 2026-09-17 23:59","&aЛКМ — ввести"),"scheduleEdit");
        put(inv,25,item(e.dailyScheduleEnabled?Material.LIME_DYE:Material.GRAY_DYE,"&6Ежедневный запуск",e.dailyScheduleEnabled?"&aВключён":"&7Выключен","&7Время: &f"+formatMinutes(e.dailyTimeMinutes),"&7Длительность: &f"+formatDuration(e.dailyDurationSeconds),"&7Следующий: &f"+dailyUntilNext(e),"&aЛКМ — настроить","&eПКМ — выключить"),"dailySchedule");
        put(inv,34,item(e.weeklyScheduleEnabled?Material.LIME_DYE:Material.GRAY_DYE,"&6Еженедельный запуск",e.weeklyScheduleEnabled?"&aВключён":"&7Выключен","&7День: &f"+weeklyDayName(e.weeklyDayOfWeek),"&7Время: &f"+formatMinutes(e.weeklyTimeMinutes),"&7Длительность: &f"+formatDuration(e.weeklyDurationSeconds),"&7Следующий: &f"+weeklyUntilNext(e),"&aЛКМ — настроить","&eПКМ — выключить"),"weeklySchedule");
        put(inv,31,item(e.enabled?Material.LIME_DYE:Material.RED_DYE,e.enabled?"&aАвтоматический спавн включён":"&cАвтоматический спавн выключен","&aЛКМ — переключить"),"toggle:enabled");
        put(inv,39,item(Material.BARRIER,"&cОчистить расписание","&7Удаляет дату, ежедневное и еженедельное расписание.","&aЛКМ — выполнить"),"scheduleClear");
    }
    private String dailyUntilNext(EventDefinition e){if(!e.dailyScheduleEnabled)return "не задано";java.time.ZonedDateTime now=java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault());int mins=Math.floorMod(e.dailyTimeMinutes,1440);java.time.ZonedDateTime next=now.toLocalDate().atStartOfDay(now.getZone()).plusMinutes(mins);if(!next.isAfter(now))next=next.plusDays(1);return formatRemaining(next.toInstant(),now.toInstant());}

    private void drawConditions(Inventory inv,EventDefinition e){
        EventConditions c=e.conditions;
        put(inv,10,toggle(Material.LIME_DYE,"Условия сбора",c.enabled,"toggle:conditions"));
        ItemStack world=field(Material.GRASS_BLOCK,"Мир",c.world==null||c.world.isBlank()?"любой":c.world,"conditionsWorld"); addLore(world,"&8ПКМ — убрать ограничение мира"); put(inv,12,world);
        put(inv,14,item(Material.WATER_BUCKET,"&eПогода","&7Сейчас: &f"+c.weather,"&aЛКМ — сменить: ANY → CLEAR → RAIN → THUNDER"),"conditionsWeather");
        put(inv,16,item(Material.CLOCK,"&eДень / ночь","&7Сейчас: &f"+c.dayNight,"&aЛКМ — ANY → DAY → NIGHT"),"conditionsDayNight");
        ItemStack worldTime=field(Material.CLOCK,"Время мира",c.timeMin+"–"+c.timeMax+" тиков","conditionsTime"); addLore(worldTime,"&8ПКМ — сбросить 0–24000"); put(inv,19,worldTime);
        ItemStack helmet=field(Material.LEATHER_HELMET,"Шлем",c.requiredHelmetMaterial==null||c.requiredHelmetMaterial.isBlank()?"не требуется":c.requiredHelmetMaterial,"conditionsHelmet"); addLore(helmet,"&8ЛКМ — изменить","&8ПКМ — снять это требование"); put(inv,21,helmet);
        ItemStack invItem=field(Material.CHEST,"Предмет в инвентаре",c.requiredInventoryMaterial==null||c.requiredInventoryMaterial.isBlank()?"не требуется":c.requiredInventoryMaterial,"conditionsItem"); addLore(invItem,"&8ЛКМ — изменить","&8ПКМ — снять это требование"); put(inv,23,invItem);
        ItemStack failMessage=field(Material.WRITABLE_BOOK,"Сообщение при отказе",c.failMessage,"conditionsMessage"); addLore(failMessage,"&8ПКМ — вернуть сообщение по умолчанию"); put(inv,28,failMessage);
        ItemStack penalty=field(Material.GOLD_NUGGET,"Штраф при отказе",Integer.toString(c.failPenalty)+" монет","conditionsPenalty"); addLore(penalty,"&8ПКМ — поставить 0"); put(inv,30,penalty);
        put(inv,34,item(Material.BARRIER,"&cСбросить все условия","&7Очистить мир, время, погоду, день/ночь, шлем и предмет.","&aЛКМ — полностью отключить условия"),"conditionsClear");
        put(inv,32,item(Material.ENDER_EYE,"&bКак работает","&7Например: наденьте PUMPKIN → можно собирать.","&7При невыполненном условии событие не забирается."),"noop");
    }

    private ItemStack field(Material material,String title,String value,String action){ItemStack s=item(material,title,"&7Текущее: &f"+value,"&aЛКМ — изменить через чат");setAction(s,action);return s;}
    private void addLore(ItemStack item,String... lines){
        if(item==null||item.getItemMeta()==null)return;
        ItemMeta meta=item.getItemMeta();
        List<String> lore=meta.getLore()==null?new ArrayList<>():new ArrayList<>(meta.getLore());
        for(String line:lines) lore.add(ChatColorUtil.color(line));
        meta.setLore(lore); item.setItemMeta(meta);
    }
    private String formatRadiusValue(double radius){ if(Math.rint(radius)==radius)return Long.toString(Math.round(radius)); return java.math.BigDecimal.valueOf(radius).stripTrailingZeros().toPlainString(); }
    private ItemStack toggle(Material material,String title,boolean enabled,String action){ItemStack s=item(enabled?Material.LIME_DYE:Material.GRAY_DYE,(enabled?"&a✔ ":"&c✖ ")+title,"&7Состояние: "+(enabled?"&aвключено":"&cвыключено"),"&aЛКМ — переключить");setAction(s,action);return s;}

    /**
     * RU: семь инструментов редактора находятся в верхней строке.
     * Частицы занимают все внутренние слоты четырёх строк:
     * 10-16, 19-25, 28-34, 37-43.
     * Страница создаётся динамически из фактического количества частиц: 28 частиц максимум на страницу.
     */
    private static final int[] PARTICLE_CATALOG_SLOTS = {
        10,11,12,13,14,15,16,
        19,20,21,22,23,24,25,
        28,29,30,31,32,33,34,
        37,38,39,40,41,42,43
    };
    private static final int PARTICLE_PAGE_SIZE = PARTICLE_CATALOG_SLOTS.length;

    /** Количество страниц каталога частиц для текущего числа записей. */
    public int particlePageCount() {
        return Math.max(1, (int) Math.ceil(ParticleCatalog.catalogNames().size() / (double) PARTICLE_PAGE_SIZE));
    }

    /** Полный каталог Particle enum с несколькими одновременно включёнными эффектами. */
    public void openParticleEditor(Player p,String id){ openParticleEditor(p,id,1); }
    public void openParticleEditor(Player p,String id,int requestedPage){
        EventDefinition e=plugin.events().get(id);if(e==null)return;
        List<String> all=ParticleCatalog.catalogNames();
        int perPage=PARTICLE_PAGE_SIZE;
        int pages=Math.max(1,(int)Math.ceil(all.size()/(double)perPage));
        int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("particles",id,page);
        Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dЧастицы &8["+page+"/"+pages+"]");
        h.inventory(inv);border(inv);
        boolean dustOn=e.collectParticles.stream().anyMatch(x->x.equalsIgnoreCase("DUST"));
        boolean transitionOn=e.collectParticles.stream().anyMatch(x->x.equalsIgnoreCase("DUST_COLOR_TRANSITION"));
        // Верхняя линия — инструменты редактора. Если NOTE выбрана, слот 0 открывает ноты.
        if(e.collectParticles.stream().anyMatch(x->x.equalsIgnoreCase("NOTE"))) put(inv,0,item(Material.NOTE_BLOCK,"&dНоты","&7Выберите несколько нот Minecraft.","&7Можно включить хоть все 25.","&aЛКМ — открыть ноты"),"notePicker");
        put(inv,1,item(Material.REDSTONE,"&eОбщее количество частиц","&7Текущее: &f"+(e.collectParticleCount<0?"глобальное":e.collectParticleCount),"&7Это ОБЩИЙ лимит на один тик.","&7При нескольких типах лимит делится между ними.","&7Распределение: &f"+particleDistribution(e),"&aЛКМ — изменить через чат"),"particleCount");
        put(inv,2,particleColorButton(e),"particleColor");
        put(inv,3,item(Material.PAINTING,"&eПалитра DUST","&f"+coloredHexList(particleColors(e)),"&716 базовых цветов красителей.","&aЛКМ — открыть палитру"),"particlePalette");
        put(inv,4,item(dustOn?Material.LIME_DYE:Material.GRAY_DYE,(dustOn?"&a✔ ":"&7○ ")+"Цветная пыль (DUST)","&7"+(dustOn?"Включена":"Выключена")+" в списке типов.","&7Быстрое включение — без поиска в каталоге ниже.","&aЛКМ — переключить"),"particleToggle:DUST");
        put(inv,5,item(transitionOn?Material.LIME_DYE:Material.GRAY_DYE,(transitionOn?"&a✔ ":"&7○ ")+"Переход цвета","&7"+(transitionOn?"Включён":"Выключен")+" в списке типов.","&7DUST_COLOR_TRANSITION — быстрое включение.","&aЛКМ — переключить"),"particleToggle:DUST_COLOR_TRANSITION");
        put(inv,6,item(Material.NAME_TAG,"&eВыбранные частицы","&f"+e.collectParticles.size()+" "+particleCountWord(e.collectParticles.size())+" включено","&7Показывает только включённые типы,","&7чтобы не искать их в общем каталоге.","&aЛКМ — открыть список"),"particlesSelected");
        put(inv,7,item(Material.FIREWORK_STAR,"&dКаталог частиц","&7Всего в каталоге: &f"+all.size(),"&7Java Edition 26.2.","&aЛКМ — следующая страница","&eПКМ — предыдущая страница"),"particles");

        List<String> catalogNames = ParticleCatalog.catalogNames();
        int start=(page-1)*perPage;
        int count=Math.min(perPage, Math.max(0,catalogNames.size()-start));
        for(int s=0;s<count;s++){
            String name=catalogNames.get(start+s);
            boolean selected=e.collectParticles.stream().anyMatch(x->x.equalsIgnoreCase(name));
            ItemStack it=item(particleIcon(name),(selected?"&a✔ ":"&7○ ")+plugin.lang().particleTitle(p,name),"&f"+plugin.lang().particleDescription(p,name),"&8"+name,selected?"&aЛКМ — настройки":"&aЛКМ — включить");
            put(inv,PARTICLE_CATALOG_SLOTS[s],it,"particleToggle:"+name);
        }
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"particles:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"particles:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Настройки анимации","&7Назад"),"backAnimationPage");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    /** RU: 16 базовых цветов красителей Minecraft с приблизительными HEX-значениями с вики. */
    private static final String[][] BASE_DYE_COLORS = {
        {"Белый","#F9FFFE","WHITE_DYE"}, {"Оранжевый","#F9801D","ORANGE_DYE"}, {"Пурпурный","#C74EBD","MAGENTA_DYE"},
        {"Голубой","#3AB3DA","LIGHT_BLUE_DYE"}, {"Жёлтый","#FED83D","YELLOW_DYE"}, {"Лаймовый","#80C71F","LIME_DYE"},
        {"Розовый","#F38BAA","PINK_DYE"}, {"Серый","#474F52","GRAY_DYE"}, {"Светло-серый","#9D9D97","LIGHT_GRAY_DYE"},
        {"Бирюзовый","#169C9C","CYAN_DYE"}, {"Фиолетовый","#8932B8","PURPLE_DYE"}, {"Синий","#3C44AA","BLUE_DYE"},
        {"Коричневый","#835432","BROWN_DYE"}, {"Зелёный","#5E7C16","GREEN_DYE"}, {"Красный","#B02E26","RED_DYE"},
        {"Чёрный","#1D1D21","BLACK_DYE"},
    };

    /** RU: рабочая палитра DUST — раньше кнопка "Палитра DUST" ничего не открывала. */
    public void openEventTemplates(Player p){
        EventMenuHolder h=new EventMenuHolder("eventTemplates",null);
        Inventory inv=guiInventory(p,h,"&8EventHeads &7• &aШаблоны ивентов"); h.inventory(inv); border(inv);
        String[][] t={{"easter","Пасхальный ивент","Готовая награда, точки, частицы и звук."},{"winter","Зимний ивент","Снег, частицы зимы и звук."},{"fair","Ярмарка","Готовый шаблон для ярмарки."},{"search","Поиск предметов","Есть условие экипировки и сообщение отказа."},{"custom","Свой","Пустой шаблон для ручной настройки."}};
        Material[] m={Material.RABBIT_FOOT,Material.SNOWBALL,Material.EMERALD,Material.COMPASS,Material.NETHER_STAR};
        int[] slots={10,12,14,16,30};
        for(int i=0;i<t.length;i++) put(inv,slots[i],item(m[i],"&e"+t[i][1],"&7"+t[i][2],"&aЛКМ — создать","&bПКМ — настроить шаблон"),"template:"+t[i][0]);
        put(inv,49,item(Material.ARROW,"&b← Ивенты"),"eventsBack"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    public void openTemplateSettings(Player p,String template){ openTemplateSettings(p,template,1); }
    public void openTemplateSettings(Player p,String template,int requestedPage){
        String t=template.toLowerCase(Locale.ROOT);
        int page=Math.max(1,Math.min(2,requestedPage));
        EventMenuHolder h=new EventMenuHolder("templateSettings",t,page); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Настройка шаблона &8["+page+"/2]"); h.inventory(inv); border(inv);
        org.bukkit.configuration.file.FileConfiguration c=plugin.getConfig(); String b="templates."+t+".";
        int rewardMin=c.getInt(b+"reward-min",defaultTemplateValue(t,"reward-min",1));
        int rewardMax=c.getInt(b+"reward-max",defaultTemplateValue(t,"reward-max",5));
        String mode=c.getString(b+"spawn-mode",defaultTemplateMode(t));
        int maxActive=c.getInt(b+"max-active",30), regionMax=c.getInt(b+"region-max",30);
        double md=c.getDouble(b+"min-distance",plugin.getConfig().getDouble("spawn.default-min-distance",8));
        double mp=c.getDouble(b+"min-player-distance",plugin.getConfig().getDouble("spawn.default-min-player-distance",0));
        long minDelay=c.getLong(b+"min-delay",15), maxDelay=c.getLong(b+"max-delay",60), minLife=c.getLong(b+"min-lifetime",30), maxLife=c.getLong(b+"max-lifetime",120);
        int pc=c.getInt(b+"particle-count",6); boolean sound=c.getBoolean(b+"sound-enabled",true);
        String particles=String.join(", ",c.getStringList(b+"particles")); String sounds=String.join(", ",c.getStringList(b+"sounds"));
        boolean conditions=c.getBoolean(b+"conditions-enabled",t.equals("search")); String helmet=c.getString(b+"helmet",t.equals("search")?"PUMPKIN":""); String invItem=c.getString(b+"inventory-item","");
        boolean solid=c.getBoolean(b+"require-solid-ground",true), water=c.getBoolean(b+"allow-water",false), lava=c.getBoolean(b+"allow-lava",false), floating=c.getBoolean(b+"allow-floating",false);
        double animSpeed=c.getDouble(b+"animation-speed",1.0); boolean small=c.getBoolean(b+"animation-small",false), rotation=c.getBoolean(b+"random-rotation",true);
        float volume=(float)c.getDouble(b+"sound-volume",1.0), pitch=(float)c.getDouble(b+"sound-pitch",1.0);
        String failMessage=c.getString(b+"fail-message","§cВы не можете собрать: выполните требования."); int failPenalty=c.getInt(b+"fail-penalty",0);
        boolean weekly=c.getBoolean(b+"weekly.enabled",false); String weeklyText=weekly?(weeklyDayName(c.getInt(b+"weekly.day",6))+" в "+formatMinutes(c.getInt(b+"weekly.time",1200))+" • "+formatDuration(c.getLong(b+"weekly.duration",3600))):"не задано";
        String desc=c.getString(b+"description",plugin.templates().templateDescription(t)); String playerName=c.getString(b+"player-name",plugin.templates().templateName(t)); String adminName=c.getString(b+"admin-name",playerName);
        if(page==1){
            put(inv,10,item(Material.GOLD_NUGGET,"&eМинимальная награда","&f"+rewardMin+" монет","&aЛКМ — изменить"),"templateSetting:"+t+":reward-min");
            put(inv,12,item(Material.GOLD_INGOT,"&eМаксимальная награда","&f"+rewardMax+" монет","&aЛКМ — изменить"),"templateSetting:"+t+":reward-max");
            put(inv,14,item(Material.ENDER_EYE,"&6Режим спавна","&f"+mode,"&aЛКМ — следующий"),"templateMode:"+t);
            put(inv,16,item(Material.CHEST,"&eМаксимум активных","&f"+maxActive,"&aЛКМ — изменить"),"templateSetting:"+t+":max-active");
            put(inv,19,item(Material.CHEST,"&eМаксимум в регионе","&f"+regionMax,"&aЛКМ — изменить"),"templateSetting:"+t+":region-max");
            put(inv,21,item(Material.COMPASS,"&eМинимальная дистанция","&f"+fmt(md)+" блоков","&aЛКМ — изменить"),"templateSetting:"+t+":min-distance");
            put(inv,23,item(Material.PLAYER_HEAD,"&eНе спавнить рядом с игроками","&f"+(mp<=0?"выключено":formatRadiusValue(mp)+" блоков"),"&aЛКМ — изменить"),"templateSetting:"+t+":min-player-distance");
            put(inv,25,item(Material.CLOCK,"&eМинимальная задержка","&f"+formatDuration(minDelay),"&aЛКМ — изменить"),"templateSetting:"+t+":min-delay");
            put(inv,28,item(Material.CLOCK,"&eМаксимальная задержка","&f"+formatDuration(maxDelay),"&aЛКМ — изменить"),"templateSetting:"+t+":max-delay");
            put(inv,30,item(Material.CLOCK,"&eМинимальное время жизни","&f"+formatDuration(minLife),"&aЛКМ — изменить"),"templateSetting:"+t+":min-lifetime");
            put(inv,32,item(Material.CLOCK,"&eМаксимальное время жизни","&f"+formatDuration(maxLife),"&aЛКМ — изменить"),"templateSetting:"+t+":max-lifetime");
            put(inv,34,item(Material.REDSTONE,"&eКоличество частиц","&f"+pc,"&aЛКМ — изменить"),"templateSetting:"+t+":particle-count");
            put(inv,37,item(Material.FIREWORK_STAR,"&dТипы частиц","&7"+(particles.isBlank()?"по умолчанию":particles),"&aЛКМ — список через запятую"),"templateSetting:"+t+":particles");
            put(inv,39,toggleTemplate(sound,"Звук","templateSound:"+t));
            put(inv,41,item(Material.NOTE_BLOCK,"&dЗвуки","&7"+(sounds.isBlank()?"по умолчанию":sounds),"&aЛКМ — список через запятую"),"templateSetting:"+t+":sounds");
            put(inv,43,toggleTemplate(conditions,"Условия сбора","templateSetting:"+t+":conditions-enabled"));
            put(inv,45,item(Material.BOOK,"&bСтраница 1/2","&7Основные настройки шаблона"),"noop");
            put(inv,53,item(Material.ARROW,"&bСледующая →","&7Дополнительные настройки"),"templateSettingsPage:"+t+":2");
        } else {
            put(inv,10,toggle(Material.GRASS_BLOCK,"Твёрдая опора",solid,"templateToggle:"+t+":require-solid-ground"));
            put(inv,12,toggle(Material.WATER_BUCKET,"Вода",water,"templateToggle:"+t+":allow-water"));
            put(inv,14,toggle(Material.LAVA_BUCKET,"Лава",lava,"templateToggle:"+t+":allow-lava"));
            put(inv,16,toggle(Material.PHANTOM_MEMBRANE,"Плавающий спавн",floating,"templateToggle:"+t+":allow-floating"));
            put(inv,19,item(Material.CLOCK,"&dСкорость анимации","&f"+fmt(animSpeed)+"x","&aЛКМ — изменить"),"templateSetting:"+t+":animation-speed");
            put(inv,21,toggle(Material.ENDER_PEARL,"Маленькая стойка",small,"templateToggle:"+t+":animation-small"));
            put(inv,23,toggle(Material.REPEATER,"Случайный поворот",rotation,"templateToggle:"+t+":random-rotation"));
            put(inv,25,item(Material.NOTE_BLOCK,"&dГромкость звука","&f"+fmt(volume),"&aЛКМ — изменить"),"templateSetting:"+t+":sound-volume");
            put(inv,28,item(Material.COMPARATOR,"&dТон звука","&f"+fmt(pitch),"&aЛКМ — изменить"),"templateSetting:"+t+":sound-pitch");
            put(inv,30,item(Material.LEATHER_HELMET,"&eШлем","&7"+(helmet.isBlank()?"не требуется":helmet),"&aЛКМ — изменить","&7ПКМ — убрать"),"templateSetting:"+t+":helmet");
            put(inv,32,item(Material.CHEST,"&eПредмет в инвентаре","&7"+(invItem.isBlank()?"не требуется":invItem),"&aЛКМ — изменить","&7ПКМ — убрать"),"templateSetting:"+t+":inventory-item");
            put(inv,34,item(Material.WRITABLE_BOOK,"&eСообщение при отказе","&7"+limit(failMessage,42),"&aЛКМ — изменить"),"templateSetting:"+t+":fail-message");
            put(inv,37,item(Material.GOLD_NUGGET,"&eШтраф при отказе","&f"+failPenalty+" монет","&7Отрицательное значение = списание","&aЛКМ — изменить"),"templateSetting:"+t+":fail-penalty");
            put(inv,39,item(Material.CLOCK,"&bЕженедельное расписание","&7"+weeklyText,"&aЛКМ — задать через чат","&7Формат: суббота 20:00 | 1h","&7ПКМ — отключить"),"templateWeekly:"+t);
            put(inv,41,item(Material.NAME_TAG,"&bНазвание для игрока","&f"+playerName,"&aЛКМ — изменить"),"templateSetting:"+t+":player-name");
            put(inv,43,item(Material.NAME_TAG,"&bНазвание администратора","&f"+adminName,"&aЛКМ — изменить"),"templateSetting:"+t+":admin-name");
            put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Основные настройки"),"templateSettingsPage:"+t+":1");
            put(inv,47,item(Material.PAPER,"&bОписание шаблона","&7"+limit(desc,42),"&aЛКМ — изменить"),"templateSetting:"+t+":description");
            put(inv,49,item(Material.ARROW,"&b← Шаблоны"),"eventTemplates"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");
        }
        if(page==1){put(inv,49,item(Material.ARROW,"&b← Шаблоны"),"eventTemplates");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");}
        plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    private ItemStack toggleTemplate(boolean enabled,String title,String action){ItemStack s=item(enabled?Material.LIME_DYE:Material.GRAY_DYE,enabled?"&a✓ "+title:"&c✖ "+title,"&7Шаблон будет создаваться "+(enabled?"со звуком":"без звука"),"&aЛКМ — переключить");setAction(s,action);return s;}
    public String templateDefaultMode(String t){return switch(t.toLowerCase(Locale.ROOT)){case "easter","winter","fair","search"->"POINTS";default->"RANDOM";};}
    private String defaultTemplateMode(String t){return templateDefaultMode(t);}
    private int defaultTemplateValue(String t,String key,int fallback){return switch(t+":"+key){case "easter:reward-min"->5;case "easter:reward-max"->12;case "winter:reward-min"->8;case "winter:reward-max"->18;case "fair:reward-min"->10;case "fair:reward-max"->25;case "search:reward-min"->2;case "search:reward-max"->8;default->fallback;};}
    private String weeklyUntilNext(EventDefinition e){ if(!e.weeklyScheduleEnabled)return "не задано"; java.time.ZonedDateTime now=java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()); java.time.DayOfWeek day=java.time.DayOfWeek.of(Math.max(1,Math.min(7,e.weeklyDayOfWeek))); for(int i=0;i<=7;i++){ java.time.ZonedDateTime d=now.toLocalDate().plusDays(i).atStartOfDay(now.getZone()).plusMinutes(Math.floorMod(e.weeklyTimeMinutes,1440)); if(d.getDayOfWeek()==day && d.isAfter(now)) return formatRemaining(d.toInstant(),now.toInstant()); } return "7д"; }
    private String weeklyCurrentRemaining(EventDefinition e){ if(!e.weeklyScheduleEnabled||!e.currentlyScheduled())return "—"; java.time.ZonedDateTime now=java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()); java.time.DayOfWeek day=java.time.DayOfWeek.of(Math.max(1,Math.min(7,e.weeklyDayOfWeek))); long duration=Math.max(1,e.weeklyDurationSeconds); int startMinutes=Math.floorMod(e.weeklyTimeMinutes,1440); for(int daysBack=0;daysBack<7;daysBack++){ java.time.LocalDate date=now.toLocalDate().minusDays(daysBack); if(date.getDayOfWeek()!=day)continue; java.time.ZonedDateTime start=date.atStartOfDay(now.getZone()).plusMinutes(startMinutes); java.time.ZonedDateTime end=start.plusSeconds(duration); if(!now.isBefore(start)&&now.isBefore(end)) return formatRemaining(end.toInstant(),now.toInstant()); } return "—"; }

    public void createFromTemplate(Player p,String template){
        String base="event_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()); String id=base; int n=1; while(plugin.events().containsKey(id)) id=base+"_"+(n++);
        EventDefinition d=plugin.templates().create(p,template,id); plugin.events().put(id,d); plugin.data().saveEvent(d); plugin.data().log("template.create",p.getName(),template+" -> "+id); plugin.lang().send(p, "§a✓ Создан ивент по шаблону: §f"+id); openEditor(p,id);
    }
    public void openAuditLog(Player p){openAuditLog(p,"ALL",1);}
    public void openAuditLog(Player p,int requestedPage){openAuditLog(p,"ALL",requestedPage);}
    public void openAuditLog(Player p,String filter,int requestedPage){
        if(!plugin.access().has(p,Perm.AUDIT))return;
        String normalized=(filter==null||filter.isBlank()?"ALL":filter.toUpperCase(Locale.ROOT));
        EventMenuHolder h=new EventMenuHolder("auditLog",normalized,requestedPage); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dЖурнал действий"); h.inventory(inv); border(inv);
        String[] filters={"ALL","POINTS","ROLES","EVENTS","REWARDS","SETTINGS"}; String[] labels={"Все","Точки","Роли","Ивенты","Награды","Настройки"};
        int[] fslots={1,2,3,4,5,6}; for(int i=0;i<filters.length;i++){boolean on=normalized.equals(filters[i]);put(inv,fslots[i],item(on?Material.LIME_DYE:Material.GRAY_DYE,(on?"&a✔ ":"&7")+labels[i],"&7Фильтр журнала","&aЛКМ — выбрать"),"auditFilter:"+filters[i]);}
        put(inv,7,item(Material.PAPER,"&dФильтр","&7Сейчас: &f"+auditFilterName(normalized),"&7Журнал хранится только в данных EventHeads."),"noop");
        var sec=plugin.data().auditEntries(); List<String> ids=new ArrayList<>(); if(sec!=null)ids.addAll(sec.getKeys(false)); ids.removeIf(id->{String act=sec.getString(id+".action","");return !auditMatches(normalized,act);}); ids.sort((a,b)->Long.compare(Long.parseLong(b),Long.parseLong(a)));
        int perPage=28,pages=Math.max(1,(ids.size()+perPage-1)/perPage),page=Math.min(Math.max(1,requestedPage),pages),from=(page-1)*perPage; int[] slots=gridSlots();
        for(int i=from;i<Math.min(ids.size(),from+perPage);i++){String id=ids.get(i);long tm=sec.getLong(id+".time",0);String when=tm>0?new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new java.util.Date(tm)):"—";String actor=sec.getString(id+".actor","?");String act=sec.getString(id+".action","");String det=sec.getString(id+".details","");put(inv,slots[i-from],item(Material.PAPER,"&f"+when,"&7Кто: &f"+actor,"&7Что сделал: &f"+auditHumanAction(act,det),"&7Подробности: &f"+limit(auditHumanDetails(act,det),70)),"noop");}
        if(ids.isEmpty())put(inv,22,item(Material.BARRIER,"&7Ничего не найдено","&8Для выбранного фильтра записей пока нет."),"noop");
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"auditLog:page:"+(page-1)); if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"auditLog:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Назад"),"back"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    private boolean auditMatches(String filter,String action){if("ALL".equals(filter))return true;String a=action==null?"":action.toLowerCase(Locale.ROOT);return switch(filter){case "POINTS"->a.contains("point")||a.contains("blocked")||a.contains("region");case "ROLES"->a.contains("role")||a.contains("access");case "EVENTS"->a.contains("event")||a.contains("template")||a.contains("schedule")||a.contains("spawn")||a.contains("region");case "REWARDS"->a.contains("reward")||a.contains("condition")||a.contains("coin");case "SETTINGS"->a.contains("settings")||a.contains("sound")||a.contains("particle")||a.contains("anti-esp");default->true;};}
    private String auditFilterName(String filter){return switch(filter){case "POINTS"->"точки";case "ROLES"->"роли";case "EVENTS"->"ивенты";case "REWARDS"->"награды";case "SETTINGS"->"настройки";default->"все";};}
    private String auditHumanAction(String action,String details){
        String a=action==null?"":action.toLowerCase(Locale.ROOT);
        return switch(a){
            case "point.add" -> "§aСоздал точку§7";
            case "point.remove" -> "§cУдалил точку§7";
            case "point.chance" -> "§eИзменил шанс точки§7";
            case "points.center" -> "§bИзменил центр постановки точек§7";
            case "role.permission.toggle" -> "§dИзменил право роли§7";
            case "role.create" -> "§aСоздал роль§7";
            case "role.save" -> "§aИзменил роль§7";
            case "role.delete" -> "§cУдалил роль§7";
            case "access" -> "§aИзменил доступ сотрудника§7";
            case "access.add" -> "§aДобавил сотрудника§7";
            case "access.remove" -> "§cУдалил сотрудника§7";
            case "access.override" -> "§6Изменил индивидуальное право сотрудника§7";
            case "role-assign" -> "§aНазначил роль сотруднику§7";
            case "event.duplicate" -> "§bСкопировал ивент§7";
            case "event.setting" -> "§eИзменил настройку ивента§7";
            case "template.create" -> "§aСоздал ивент из шаблона§7";
            case "template.open" -> "§7Открыл настройки шаблона§7";
            case "sound.set" -> "§dИзменил выбранные звуки§7";
            case "sound.clear" -> "§7Сбросил звуки§7";
            case "sound.toggle" -> "§dВключил/выключил звук§7";
            case "sound.off" -> "§cВыключил звук§7";
            case "particle.settings" -> "§dИзменил настройки частицы§7";
            case "particle.note" -> "§dИзменил ноты§7";
            case "particle.toggle" -> "§dВключил/выключил частицу§7";
            case "particle.disable" -> "§cВыключил частицу§7";
            case "particle.palette" -> "§dИзменил палитру DUST§7";
            case "particle.reset" -> "§7Сбросил настройки частицы§7";
            case "particle.settings.open" -> "§7Открыл настройки частицы§7";
            case "settings.toggle" -> "§6Изменил настройку спавна/ивента§7";
            case "blocked.remove" -> "§cУдалил место из чёрного списка§7";
            case "region.remove" -> "§cУдалил WorldGuard-регион§7";
            case "region.clear" -> "§cОчистил список регионов§7";
            case "template.setting" -> "§bИзменил шаблон§7";
            case "conditions.clear" -> "§cСбросил все условия сбора§7";
            case "conditions.change" -> "§eИзменил условия сбора§7";
            case "anti-esp-reset" -> "§7Сбросил Anti-ESP§7";
            case "anti-esp-toggle" -> "§6Изменил Anti-ESP§7";
            case "schedule.clear" -> "§7Очистил расписание§7";
            case "event.toggle" -> "§6Включил/выключил ивент§7";
            case "visual.set" -> "§bИзменил внешний вид EventHead§7";
            case "spawn.mode" -> "§6Изменил режим спавна§7";
            case "spawn.test" -> "§7Запустил тест спавна§7";
            case "region.add" -> "§aДобавил WorldGuard-регион§7";
            case "region.selection" -> "§bСохранил выделение WorldEdit для региона§7";
            case "blocked.add" -> "§aДобавил место в чёрный список§7";
            case "blocked.global.add" -> "§aДобавил глобальное место в чёрный список§7";
            case "blocked.global.remove" -> "§cУдалил глобальное место из чёрного списка§7";
            case "delete" -> "§cУдалил предмет/ивент§7";
            case "schedule.weekly.disable" -> "§7Отключил еженедельное расписание§7";
            case "point.tool" -> "§bПолучил инструмент точек§7";
            case "blocked.tool" -> "§bПолучил инструмент чёрного списка§7";
            case "particle.type" -> "§dИзменил основной тип частиц§7";
            case "particle.preview" -> "§7Запустил предпросмотр частицы§7";
            case "animation.preview" -> "§7Запустил предпросмотр анимации§7";
            case "save" -> "§aСохранил изменения§7";
            default -> action==null||action.isBlank()?"§7Изменил данные§7":action;
        };
    }

    private String auditHumanDetails(String action,String details){
        if(details==null||details.isBlank())return "Без дополнительных данных";
        String d=details.replace(" -> "," → ").replace(" at "," • ").replace(" X="," • X=").replace(",Z=",", Z=");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("#(\\d+)\s+(-?\\d+(?:\\.\\d+)?)\s+->\s+(-?\\d+(?:\\.\\d+)?)").matcher(details);
        if(m.find()) return "Точка #"+m.group(1)+": шанс "+m.group(2)+"% → "+m.group(3)+"%";
        if(action!=null && (action.equals("event.setting")||action.equals("template.setting")||action.equals("settings.toggle"))){
            int arrow=d.indexOf("→"); String raw=arrow>=0?d.substring(arrow+1).trim():d;
            int eq=raw.indexOf('='); String key=eq>=0?raw.substring(0,eq).trim():raw; String val=eq>=0?raw.substring(eq+1).trim():"";
            String human=switch(key){
                case "playerDistance"->"Не спавнить рядом с игроками";
                case "distance"->"Минимальная дистанция от других EventHead";
                case "maxActive"->"Максимум активных";
                case "regionMax"->"Максимум в регионе";
                case "chance"->"Шанс спавна";
                case "minDelay"->"Минимальная задержка";
                case "maxDelay"->"Максимальная задержка";
                case "minLifetime"->"Минимальное время жизни";
                case "maxLifetime"->"Максимальное время жизни";
                case "animationSpeed"->"Скорость анимации";
                case "minReward"->"Минимальная награда";
                case "maxReward"->"Максимальная награда";
                case "name"->"Название администратора";
                case "playerName"->"Название для игрока";
                case "description"->"Описание";
                case "reward-min"->"Минимальная награда шаблона";
                case "reward-max"->"Максимальная награда шаблона";
                case "max-active"->"Максимум активных шаблона";
                case "region-max"->"Максимум в регионе шаблона";
                case "min-distance"->"Минимальная дистанция шаблона";
                case "min-player-distance"->"Дистанция от игроков шаблона";
                case "spawn-mode"->"Режим спавна шаблона";
                case "particle-count"->"Количество частиц шаблона";
                case "sound-enabled"->"Звук шаблона";
                case "conditions-enabled"->"Условия сбора шаблона";
                case "weekly.enabled"->"Еженедельное расписание шаблона";
                case "water"->"Разрешение воды";
                case "lava"->"Разрешение лавы";
                case "air"->"Плавающий спавн";
                case "ground"->"Твёрдая опора";
                case "rotation"->"Случайный поворот";
                case "animationSmall"->"Маленькая стойка";
                case "conditions"->"Условия сбора";
                case "enabled"->"Ивент включён";
                default->key;
            };
            return human+(val.isBlank()?"":": "+val);
        }
        return limit(d,110);
    }

    public void openParticlePalette(Player p,String id){
        EventDefinition e=plugin.events().get(id);if(e==null)return;
        EventMenuHolder h=new EventMenuHolder("particlePalette",id);
        Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dПалитра DUST");
        h.inventory(inv);border(inv);
        List<String> selected=particleColors(e);
        int[] slots={19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38};
        for(int i=0;i<BASE_DYE_COLORS.length;i++){
            String[] c=BASE_DYE_COLORS[i];
            boolean on=selected.stream().anyMatch(x->x.equalsIgnoreCase(c[1]));
            Material mat;try{mat=Material.valueOf(c[2]);}catch(Exception ex){mat=Material.WHITE_DYE;}
            put(inv,slots[i],item(mat,(on?"&a✔ ":"&7○ ")+c[0],ChatColorUtil.color("&f"+c[1])+ChatColorUtil.color(c[1]+"█"),on?"&aВключён — ЛКМ выключить":"&7ЛКМ — включить"),"particlePaletteToggle:"+c[1]);
        }
        put(inv,40,item(Material.WRITABLE_BOOK,"&eСвой HEX-цвет","&7Ввести произвольный цвет через чат.","&7Например: §f#C79923","&aЛКМ — ввести"),"particleColorsCustom");
        put(inv,49,item(Material.ARROW,"&b← Частицы","&7Назад"),"particles");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    /** RU: отдельный экран только с уже выбранными типами частиц — чтобы не листать весь каталог. */
    public void openSelectedParticles(Player p,String id,int requestedPage){
        EventDefinition e=plugin.events().get(id);if(e==null)return;
        List<String> selected=new ArrayList<>(e.collectParticles);
        Collections.sort(selected);
        int perPage=PARTICLE_PAGE_SIZE;
        int pages=Math.max(1,(int)Math.ceil(selected.size()/(double)perPage));
        int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("particlesSelected",id,page);
        Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dВыбранные частицы &8["+page+"/"+pages+"]");
        h.inventory(inv);border(inv);
        put(inv,4,item(Material.NAME_TAG,"&eВсего выбрано","&f"+selected.size()+" "+particleCountWord(selected.size()),"&7Общий лимит частиц: &f"+particleCountValue(e),"&7Распределение на типы: &f"+particleDistribution(e)));
        if(selected.isEmpty()) put(inv,31,item(Material.BARRIER,"&7Ничего не выбрано","&8Откройте каталог и включите хотя бы один тип."));
        int start=(page-1)*perPage;
        int count=Math.min(perPage, Math.max(0,selected.size()-start));
        for(int s=0;s<count;s++){
            String name=selected.get(start+s);
            ItemStack it=item(particleIcon(name),"&a✔ "+plugin.lang().particleTitle(p,name),"&f"+plugin.lang().particleDescription(p,name),"&8"+name,"&aЛКМ — настройки","&cПКМ — выключить частицу");
            put(inv,PARTICLE_CATALOG_SLOTS[s],it,"particleSelected:"+name);
        }
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"particlesSelected:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"particlesSelected:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Каталог частиц","&7Назад"),"particles");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    public void openParticleSettings(Player p,String id,String particle){
        EventDefinition e=plugin.events().get(id); if(e==null)return; String name=particle.toUpperCase(Locale.ROOT); if(e.collectParticles.stream().noneMatch(x->x.equalsIgnoreCase(name)))return;
        ParticleSettings ps=e.particleSettings.computeIfAbsent(name,k->new ParticleSettings()); EventMenuHolder h=new EventMenuHolder("particleSettings",id); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dНастройки частицы"); h.inventory(inv); border(inv);
        put(inv,4,item(particleIcon(name),"&d"+plugin.lang().particleTitle(p,name),"&7Тип: &f"+name),"noop");
        boolean hasSetting=false;
        if(supportsRadius(name)){put(inv,10,field(Material.COMPASS,"Радиус",fmt(ps.radius)+" блоков","particleSetting:"+name+":radius"));hasSetting=true;}
        if(supportsDuration(name)){put(inv,12,field(Material.CLOCK,"Длительность",ps.durationSeconds<0?"полная анимация":formatDuration(ps.durationSeconds),"particleSetting:"+name+":duration"));hasSetting=true;}
        if(supportsCount(name)){put(inv,14,field(Material.REDSTONE,"Количество",ps.count<0?"по общему лимиту":Integer.toString(ps.count),"particleSetting:"+name+":count"));hasSetting=true;}
        if(supportsSpeed(name)){put(inv,16,field(Material.FEATHER,"Скорость",fmt(ps.speed),"particleSetting:"+name+":speed"));hasSetting=true;}
        if(supportsColor(name)){put(inv,19,item(Material.RED_DYE,"&eЦвет","&7Текущее: &f"+(ps.color==null||ps.color.isBlank()?"наследуется из палитры DUST":ps.color),"&7Только параметры, поддерживающие цвет.","&aЛКМ — изменить через чат","&7GLOBAL — наследовать палитру."),"particleSetting:"+name+":color");hasSetting=true;}
        if(supportsSize(name)){put(inv,21,field(Material.SLIME_BALL,"Размер",fmt(ps.size),"particleSetting:"+name+":size"));hasSetting=true;}
        if(!hasSetting)put(inv,10,item(Material.BARRIER,"&7Нет дополнительных параметров","&8Для этой частицы параметры разброса/цвета/размера не применяются."),"noop");
        if(name.equals("NOTE")){
            boolean hasNotes=!ps.notes.isEmpty()||ps.note>=0;
            var n=NoteCatalog.get(hasNotes?(ps.notes.isEmpty()?ps.note:ps.notes.get(0)):6);
            String title=hasNotes?ChatColorUtil.color(n.hex()+"Ноты"):"§dНоты";
            put(inv,23,item(Material.NOTE_BLOCK,title,ChatColorUtil.color("&7Выбрано: &f"+noteListSummary(ps)),"&7Для каждой выбранной ноты хранится свой pitch и цвет.","&aЛКМ — выбрать несколько нот"),"notePicker");
        }
        put(inv,30,item(Material.JUKEBOX,"&dПредпросмотр","&7Показать только эту частицу рядом с вами.","&aЛКМ — проиграть"),"particlePreview:"+name); put(inv,39,item(Material.BARRIER,"&cСбросить","&7Вернуть настройки этой частицы."),"particleSettingReset:"+name); put(inv,49,item(Material.ARROW,"&b← Выбранные частицы"),"particlesSelected"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    public void openNotePicker(Player p,String id){
        EventMenuHolder h=new EventMenuHolder("notePicker",id); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dНоты"); h.inventory(inv); border(inv); int[] slots=PARTICLE_CATALOG_SLOTS;
        List<NoteCatalog.Note> all=NoteCatalog.all();
        ParticleSettings ps=plugin.events().get(id).particleSettings.computeIfAbsent("NOTE",k->new ParticleSettings());
        int selectedCount=ps.notes.isEmpty()?(ps.note>=0?1:0):ps.notes.size();
        put(inv,4,item(Material.NAME_TAG,"&eВыбрано нот","&f"+selectedCount+" из 25","&7ЛКМ — продолжить выбор","&eПКМ — снять все выбранные ноты"),"noteSummary");
        for(int i=0;i<all.size();i++){
            var n=all.get(i);
            boolean selected=ps.notes.contains(n.id()) || (ps.notes.isEmpty() && ps.note>=0 && ps.note==n.id());
            String noteName=ChatColorUtil.color(n.hex()+(selected?"✔ ":"")+n.name());
            String solfege=ChatColorUtil.color(n.hex()+n.shortName());
            String coloredCode=ChatColorUtil.color(n.hex()+"█ "+n.hex());
            put(inv,slots[i],item(Material.NOTE_BLOCK,noteName,"&7Сольфеджио: "+solfege,"&7Октава/номер: &f"+n.id(),"&7Pitch: &f"+String.format(Locale.ROOT,"%.6f",n.pitch()),"&7Цвет: "+coloredCode,selected?"&aЛКМ — убрать":"&aЛКМ — выбрать"),"note:"+n.id());
        }
        put(inv,49,item(Material.ARROW,"&b← Назад"),"particleSettingsBack"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    public void openSoundPicker(Player p,String id){
        EventDefinition e=plugin.events().get(id); if(e==null)return; EventMenuHolder h=new EventMenuHolder("soundPicker",id); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &dЗвук"); h.inventory(inv); border(inv);
        String[][] a={{"ENTITY_PLAYER_LEVELUP","Повышение уровня"},{"BLOCK_NOTE_BLOCK_PLING","Нота"},{"BLOCK_AMETHYST_BLOCK_CHIME","Аметист"},{"ENTITY_EXPERIENCE_ORB_PICKUP","Опыт"},{"ENTITY_ITEM_PICKUP","Подбор предмета"},{"ENTITY_FIREWORK_ROCKET_BLAST","Фейерверк"},{"ENTITY_ALLAY_ITEM_GIVEN","Allay"},{"ENTITY_WITHER_SPAWN","Визер"}}; int[] slots={10,12,14,16,19,21,23,25};
        if(e.sounds.isEmpty() && e.sound!=null && !e.sound.isBlank()) e.sounds.add(e.sound);
        for(int i=0;i<a.length;i++){String sn=a[i][0];boolean on=e.sounds.stream().anyMatch(x->x.equalsIgnoreCase(sn));put(inv,slots[i],item(Material.NOTE_BLOCK,(on?"&a✔ ":"&7○ ")+a[i][1],"&7"+sn,on?"&aВыбран — ЛКМ убрать":"&aЛКМ — добавить"),"sound:"+sn);}
        String selected=e.sounds.isEmpty()?"нет — используется глобальный":String.join(", ",e.sounds);
        put(inv,4,item(e.soundEnabled?Material.LIME_DYE:Material.GRAY_DYE,e.soundEnabled?"&aЗвук включён":"&cЗвук выключен","&7Выбрано: &f"+Math.max(0,e.sounds.size()),"&7"+selected,"&aЛКМ — включить/выключить"),"soundToggle");
        put(inv,28,field(Material.NAME_TAG,"Свой звук",e.sounds.isEmpty()?"глобальный":selected,"soundCustom")); put(inv,30,field(Material.JUKEBOX,"Громкость",fmt(e.soundVolume),"soundVolume")); put(inv,32,field(Material.COMPARATOR,"Тон",fmt(e.soundPitch),"soundPitch")); put(inv,34,item(Material.NOTE_BLOCK,"&dПрослушать звук","&7Проиграть один случайный выбранный звук.","&aЛКМ — прослушать"),"soundPreview"); put(inv,37,item(Material.BARRIER,"&cИспользовать глобальный","&7Очистить выбранные звуки, не выключая звук."),"soundClear"); put(inv,39,item(Material.RED_DYE,"&cТолько выключить звук","&7Полностью отключить звук у этого ивента.","&aЛКМ — выключить"),"soundOff"); put(inv,49,item(Material.ARROW,"&b← Анимация"),"backEditor"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    public void previewEventEffects(Player p,String id){EventDefinition e=plugin.events().get(id);if(e==null)return;plugin.animation().previewEffects(p,e);plugin.lang().send(p, "§a✓ Предпросмотр анимации: §f"+(e.collectParticles.isEmpty()?"глобальная частица":e.collectParticles.size()+" частиц")+" §7+ звук");}
    public void previewEventSound(Player p,String id){EventDefinition e=plugin.events().get(id);if(e==null)return;if(!e.soundEnabled){plugin.lang().send(p, "§cЗвук для этого ивента выключен.");return;}plugin.animation().previewSound(p,e);String shown=e.sounds.isEmpty()?(e.sound==null||e.sound.isBlank()?"глобальный":e.sound):String.join(", ",e.sounds);plugin.lang().send(p, "§a✓ Предпросмотр звука: §f"+shown);}

    public void previewParticle(Player p,String id,String particle){
        EventDefinition e=plugin.events().get(id);
        if(e==null)return;
        ParticleSettings ps=e.particleSettings.getOrDefault(particle,new ParticleSettings());
        Location l=p.getLocation().clone().add(0,1,0);
        try{
            Particle part=Particle.valueOf(particle.toUpperCase(Locale.ROOT));
            int count=ps.count<0?8:Math.max(1,ps.count);
            double spread=Math.max(0,ps.radius),speed=Math.max(0,ps.speed);
            if(part==Particle.NOTE){
                java.util.List<Integer> notes=ps.notes.isEmpty()?java.util.List.of(Math.max(0,Math.min(24,ps.note))):ps.notes;
                int kinds=Math.max(1,notes.size());
                for(int i=0;i<notes.size();i++){
                    int amount=count/kinds+(i<count%kinds?1:0);
                    if(amount<=0)continue;
                    double off=Math.max(0,Math.min(24,notes.get(i)))/24.0D;
                    l.getWorld().spawnParticle(part,l,amount,off,0,0,speed);
                }
            }else if(part==Particle.DUST){
                java.util.List<String> colors=ps.color!=null&&!ps.color.isBlank()?java.util.List.of(ps.color):particleColors(e);
                int kinds=Math.max(1,colors.size());
                for(int i=0;i<colors.size();i++){
                    String hex=colors.get(i); if(hex==null||!hex.matches("#[0-9A-Fa-f]{6}"))continue;
                    int amount=count/kinds+(i<count%kinds?1:0); if(amount<=0)continue;
                    int rgb=Integer.parseInt(hex.substring(1),16);
                    l.getWorld().spawnParticle(part,l,amount,spread,spread,spread,speed,new Particle.DustOptions(org.bukkit.Color.fromRGB(rgb),Math.max(.01f,ps.size)));
                }
            }else{
                Object data=plugin.animation().previewParticleDataForGui(part,l,e.visual,ps);
                if(data==null) l.getWorld().spawnParticle(part,l,count,spread,spread,spread,speed);
                else l.getWorld().spawnParticle(part,l,count,spread,spread,spread,speed,data);
            }
            plugin.lang().send(p, "§a✓ Предпросмотр: §f"+plugin.lang().particleTitle(p,particle));
        }catch(Exception ex){plugin.lang().send(p, "§cНе удалось показать частицу: §f"+particle);}
    }

    private String coloredHexList(List<String> colors){
        if(colors==null||colors.isEmpty()) return ChatColorUtil.color("&f#FFFFFF"+"#FFFFFF█");
        List<String> out=new ArrayList<>();
        for(String c:colors){ String hex=c==null?"#FFFFFF":c.toUpperCase(Locale.ROOT); out.add(ChatColorUtil.color("&f"+hex)+ChatColorUtil.color(hex+"█")); }
        return String.join(ChatColorUtil.color("&7, "),out);
    }
    private String noteListSummary(ParticleSettings ps){
        List<Integer> ids=new ArrayList<>(ps.notes);
        if(ids.isEmpty() && ps.note>=0) ids.add(ps.note);
        if(ids.isEmpty()) return "не выбраны";
        List<String> names=new ArrayList<>();
        for(Integer i:ids){var n=NoteCatalog.get(i);names.add(ChatColorUtil.color(n.hex()+n.shortName()+" #"+n.id())); if(names.size()>=8)break;}
        if(ids.size()>8) names.add("§7и ещё "+(ids.size()-8));
        return String.join("§7, ",names);
    }

    private String particleCountWord(int n){int m=n%100; if(m>=11&&m<=14)return "типов"; return switch(n%10){case 1->"тип"; case 2,3,4->"типа"; default->"типов";};}
    private String particleRu(String type){return ParticleCatalog.title(type);}
    private String particleDescription(String type){return ParticleCatalog.description(type);}
    public String particleTypeForClick(EventDefinition e){return e.collectParticles.isEmpty()?particleType(e):String.join(", ",e.collectParticles);}
    private List<String> particleColors(EventDefinition e){if(e.collectParticleColors!=null&&!e.collectParticleColors.isEmpty())return e.collectParticleColors;return List.of(e.collectParticleColor==null?"#FFFFFF":e.collectParticleColor);}
    private String particleType(EventDefinition e){return (e.collectParticle==null||e.collectParticle.isBlank()?plugin.getConfig().getString("animation.particle","END_ROD"):e.collectParticle).toUpperCase(Locale.ROOT);}
    private Material particleIcon(String type){return ParticleCatalog.icon(type);}

    private int particleCountValue(EventDefinition e){return Math.max(0,e.collectParticleCount<0?plugin.getConfig().getInt("animation.particle-count",4):e.collectParticleCount);}

    private boolean supportsRadius(String n){
        return switch(n){
            case "VIBRATION","SONIC_BOOM" -> false;
            default -> true;
        };
    }
    private boolean supportsDuration(String n){ return n.equals("TRAIL"); }
    private boolean supportsCount(String n){ return !n.equals("VIBRATION") && !n.equals("SONIC_BOOM"); }
    private boolean supportsSpeed(String n){
        return switch(n){
            case "VIBRATION","SONIC_BOOM","SHRIEK","SCULK_CHARGE","ITEM","BLOCK","BLOCK_CRUMBLE","BLOCK_MARKER","DUST_PILLAR","FALLING_DUST" -> false;
            default -> true;
        };
    }
    private boolean supportsSize(String n){return switch(n){case "DUST","DUST_COLOR_TRANSITION","EFFECT","INSTANT_EFFECT"->true;default->false;};}
    private boolean supportsColor(String n){return switch(n){case "DUST","DUST_COLOR_TRANSITION","EFFECT","INSTANT_EFFECT","ENTITY_EFFECT","FLASH","TINTED_LEAVES","TRAIL"->true;default->false;};}

    private String particleDistribution(EventDefinition e){
        int total=Math.max(0,e.collectParticleCount<0?plugin.getConfig().getInt("animation.particle-count",4):e.collectParticleCount);
        List<String> selected=new ArrayList<>();
        for(String name:e.collectParticles) if(ParticleCatalog.isSupported(name)&&selected.stream().noneMatch(x->x.equalsIgnoreCase(name))) selected.add(name);
        if(selected.isEmpty()) selected.add(particleType(e));
        if(selected.size()==1)return String.valueOf(total);
        int base=total/selected.size(), rem=total%selected.size();
        StringBuilder out=new StringBuilder();
        for(int i=0;i<selected.size();i++){if(i>0)out.append(", ");out.append(base+(i<rem?1:0));}
        return out.toString();
    }

    private ItemStack particleColorButton(EventDefinition e){
        String current=e.collectParticleColors==null||e.collectParticleColors.isEmpty()?e.collectParticleColor:e.collectParticleColors.get(0);
        if(current==null||!current.matches("#[0-9A-Fa-f]{6}")) current="#FFFFFF";
        ItemStack s=item(Material.RED_DYE,"&eКастомные частицы / HEX","&f"+current,"&7Собственные цвета DUST можно вводить прямо через чат.","&7Также доступны 16 цветов в палитре.","&aЛКМ — ввести HEX");
        return s;
    }

    private Material roleIcon(String icon){try{return Material.valueOf(icon.toUpperCase(Locale.ROOT));}catch(Exception ignored){return Material.WRITABLE_BOOK;}}

    public void openRegions(Player p,String id){
        EventDefinition e=plugin.events().get(id);if(e==null)return;EventMenuHolder h=new EventMenuHolder("regions",id);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &3WorldGuard / WorldEdit");h.inventory(inv);border(inv);
        int slot=10;for(String region:e.worldGuardRegions){if(slot>=44)break;put(inv,slot++,item(Material.MAP,"&e"+region,"&7WorldGuard-регион: допустимая область спавна.","&cЛКМ — удалить"),"regionRemove:"+region);if(slot%9==0)slot+=2;}
        if(e.worldGuardRegions.isEmpty())inv.setItem(22,item(Material.BARRIER,"&cWorldGuard-регионов нет","&7Добавьте регион через команду или WorldGuard."));
        put(inv,46,item(Material.ANVIL,"&aДобавить WorldGuard-регион","&7/eventheads region set "+id+" <region>","&aЛКМ — показать команду"),"regionAddInfo");
        put(inv,47,item(Material.FIREWORK_STAR,"&bWorldEdit-область","&7Текущее: "+(e.hasArea?"&aзадана":"&cне задана"),"&aЛКМ — использовать текущее выделение"),"regionSelection:"+id);
        put(inv,48,item(Material.BARRIER,"&cОчистить все WG-регионы","&7Удалить все привязанные WG-регионы."),"regionClear:"+id);
        put(inv,49,item(Material.ARROW,"&b← Спавн","&7Назад к настройкам спавна."),"backSpawn");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openScheduleOverview(Player p){ openScheduleOverview(p,1); }
    public void openScheduleOverview(Player p,int requestedPage){
        List<EventDefinition> all=new ArrayList<>(plugin.events().values()); int perPage=27; int pages=Math.max(1,(int)Math.ceil(all.size()/(double)perPage)); int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("scheduleOverview",null,page); Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Состояние всех событий &8["+page+"/"+pages+"]"); h.inventory(inv); border(inv);
        int start=(page-1)*perPage; int[] slots=gridSlots(); Instant now=Instant.now();
        for(int i=start,sx=0;i<Math.min(all.size(),start+perPage);i++,sx++){EventDefinition e=all.get(i); String status=scheduleStatus(e,now); String c=statusColor(status); String symbol=statusSymbol(status);
            List<String> lore=new ArrayList<>(); lore.add(c+symbol+" &fСтатус: "+c+status); if(e.weeklyScheduleEnabled){lore.add("&7Расписание: &f"+weeklyDayName(e.weeklyDayOfWeek)+" "+formatMinutes(e.weeklyTimeMinutes)); lore.add("&7Длительность: &f"+formatDuration(e.weeklyDurationSeconds));} else {lore.add("&7Начало: &f"+(e.startAt==null?"не задано":formatInstant(e.startAt))); lore.add("&7Окончание: &f"+(e.endAt==null?"не задано":formatInstant(e.endAt)));}
            if(e.startAt!=null&&e.startAt.isAfter(now)) lore.add("&bДо начала: &f"+formatRemaining(e.startAt,now));
            if(e.endAt!=null&&e.endAt.isAfter(now)&&!startAtIsAfter(e,now)) lore.add("&dДо окончания: &f"+formatRemaining(e.endAt,now));
            ItemStack v=e.visual==null?new ItemStack(Material.PLAYER_HEAD):e.visual.clone(); ItemMeta m=v.getItemMeta(); if(m==null)continue; m.setDisplayName(ChatColorUtil.color(c+symbol+" &f"+e.playerName)); m.setLore(colorLore(lore)); v.setItemMeta(m); put(inv,slots[sx],v,"scheduleOverview:event:"+e.id); }
        if(all.isEmpty())inv.setItem(22,item(Material.BARRIER,"&cСобытий пока нет","&7Создайте первый ивент в меню «Ивенты»."));
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"scheduleOverview:page:"+(page-1)); if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"scheduleOverview:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Назад","&7Главное меню"),"back"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    private boolean startAtIsAfter(EventDefinition e,Instant now){return e.startAt!=null&&e.startAt.isAfter(now);}
    private String scheduleStatus(EventDefinition e,Instant now){if(!e.enabled)return "ВЫКЛЮЧЕНО";if(e.weeklyScheduleEnabled)return e.currentlyScheduled()?"АКТИВНО":"ЕЖЕНЕДЕЛЬНО";if(e.startAt!=null&&now.isBefore(e.startAt))return "НЕ НАЧАЛОСЬ"; if(e.endAt!=null&&!now.isBefore(e.endAt))return "ЗАВЕРШЕНО"; return "АКТИВНО";}
    private String statusColor(String status){return switch(status){case "АКТИВНО"->"&a";case "ЗАВЕРШЕНО"->"&c";case "НЕ НАЧАЛОСЬ"->"&e";default->"&7";};}
    private String statusSymbol(String status){return switch(status){case "АКТИВНО"->"●";case "ЗАВЕРШЕНО"->"■";case "НЕ НАЧАЛОСЬ"->"◆";default->"○";};}

    public void openStatsList(Player p){openStatsList(p,1);}
    public void openStatsList(Player p,int requestedPage){
        List<EventDefinition> all=new ArrayList<>(plugin.events().values());int perPage=27;int pages=Math.max(1,(int)Math.ceil(all.size()/(double)perPage));int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("statslist",null,page);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Статистика &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);int[] slots=gridSlots();int start=(page-1)*perPage;
        for(int i=start,s=0;i<Math.min(all.size(),start+perPage);i++,s++){EventDefinition e=all.get(i);ItemStack visual=e.visual==null?new ItemStack(Material.PLAYER_HEAD):e.visual.clone();ItemMeta m=visual.getItemMeta();if(m==null)continue;m.setDisplayName(ChatColorUtil.color("&e"+e.playerName));long lastStart=plugin.data().getEventLastStart(e.id);long lastEnd=plugin.data().getEventLastEnd(e.id);m.setLore(colorLore(List.of("&7Создал: &f"+safe(e.authorName),"&7Собрано: &f"+totalForEvent(e.id),"&7Запусков: &f"+plugin.data().getEventStarts(e.id),"&7Последний запуск: &f"+(lastStart>0?formatInstant(Instant.ofEpochMilli(lastStart)):"нет"),"&7Последнее завершение: &f"+(lastEnd>0?formatInstant(Instant.ofEpochMilli(lastEnd)):"нет"),"&aЛКМ — открыть статистику")));visual.setItemMeta(m);put(inv,slots[s],visual,"stats:"+e.id);}
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"statslist:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"statslist:page:"+(page+1));put(inv,49,item(Material.ARROW,"&b← Назад","&7Главное меню"),"back");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openStats(Player p,String id){openStats(p,id,1);}
    public void openStats(Player p,String id,int requestedPage){
        EventDefinition e=plugin.events().get(id);if(e==null)return;
        List<UUID> players=new ArrayList<>();var sec=plugin.data().stats().getConfigurationSection("players");
        if(sec!=null)for(String u:sec.getKeys(false))try{UUID uuid=UUID.fromString(u);if(plugin.data().getCount(uuid,id)>0)players.add(uuid);}catch(Exception ignored){}
        players.sort(java.util.Comparator.comparingInt((UUID u)->plugin.data().getCount(u,id)).reversed().thenComparing(u->plugin.data().stats().getString("players."+u+".name",u.toString()),String.CASE_INSENSITIVE_ORDER));
        int perPage=28;int pages=Math.max(1,(int)Math.ceil(players.size()/(double)perPage));int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("stats",id,page);Inventory inv=guiInventory(p,h,"&8Статистика &7• &6"+e.playerName+" &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);
        int[] slots=gridSlots();int start=(page-1)*perPage;
        for(int i=start,sidx=0;i<Math.min(players.size(),start+perPage);i++,sidx++){UUID uuid=players.get(i);String name=plugin.data().stats().getString("players."+uuid+".name",uuid.toString());ItemStack visual=e.visual==null?new ItemStack(Material.PLAYER_HEAD):e.visual.clone();ItemMeta m=visual.getItemMeta();if(m==null)continue;m.setDisplayName(ChatColorUtil.color("&e"+e.playerName));m.setLore(colorLore(List.of("&7Игрок: &f"+name,"&7Собрано: &f"+plugin.data().getCount(uuid,id),"&7Награда: &e"+plugin.data().getReward(uuid,id)+" монет")));visual.setItemMeta(m);put(inv,slots[sidx],visual,"noop");}
        if(players.isEmpty())inv.setItem(22,item(Material.BARRIER,"&cСборов пока нет","&7Никто ещё не собрал этот EventHead."));
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"stats:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"stats:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Статистика","&7Вернуться к списку событий."),"stats_back");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openStatsHistory(Player p,String id,int requestedPage){
        if(!plugin.access().has(p,Perm.STATS))return;EventDefinition e=plugin.events().get(id);if(e==null)return;
        var sec=plugin.data().getEventHistory(id);List<String> keys=new ArrayList<>();if(sec!=null)keys.addAll(sec.getKeys(false));keys.sort((a,b)->Long.compare(parseLongSafe(b),parseLongSafe(a)));
        int perPage=28;int pages=Math.max(1,(int)Math.ceil(keys.size()/(double)perPage));int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("statsHistory",id,page);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6История &7• &f"+e.playerName+" &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);int start=(page-1)*perPage;int[] slots=gridSlots();
        for(int sidx=0;sidx<Math.min(perPage,keys.size()-start);sidx++){String key=keys.get(start+sidx);long st=sec.getLong(key+".start",parseLongSafe(key));long en=sec.getLong(key+".end",0L);String reason=sec.getString(key+".reason","ended");put(inv,slots[sidx],item(Material.CLOCK,"&eЗапуск &f"+formatInstant(Instant.ofEpochMilli(st)),"&7Начало: &f"+formatInstant(Instant.ofEpochMilli(st)),"&7Окончание: &f"+(en>0?formatInstant(Instant.ofEpochMilli(en)):"ещё идёт"),"&7Причина: &f"+reason),"noop");}
        if(keys.isEmpty())inv.setItem(22,item(Material.BARRIER,"&cИстории пока нет","&7Ивент ещё не запускался."));if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая"),"statsHistory:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →"),"statsHistory:page:"+(page+1));put(inv,49,item(Material.ARROW,"&b← Статистика"),"stats_back");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv);p.openInventory(inv);
    }
    private long parseLongSafe(String value){try{return Long.parseLong(value);}catch(Exception ignored){return 0L;}}

    public void openPoints(Player p,String id){openPoints(p,id,1,"event");}
    public void openPoints(Player p,String id,int requestedPage){openPoints(p,id,requestedPage,"event");}
    public void openPoints(Player p,String id,int requestedPage,String backTarget){
        if(!plugin.access().has(p,Perm.POINTS)) return;
        EventDefinition e=plugin.events().get(id);if(e==null)return;
        boolean allAccess=plugin.access().isAdmin(p.getUniqueId()) || plugin.access().allows(p.getUniqueId(),Perm.POINTS_ALL);
        List<Integer> visible=new ArrayList<>();
        for(int i=0;i<e.points.size();i++){SpawnPoint sp=e.points.get(i);if(allAccess || p.getUniqueId().equals(sp.addedBy)) visible.add(i);}
        int perPage=28;int pages=Math.max(1,(int)Math.ceil(visible.size()/(double)perPage));int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("points",id,page);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Точки &7• &f"+e.playerName+" &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);int start=(page-1)*perPage;int[] slots=gridSlots();
        for(int s=0;s<Math.min(visible.size()-start,perPage);s++){int index=visible.get(start+s);SpawnPoint sp=e.points.get(index);boolean editable=plugin.access().canEditPoint(p.getUniqueId(),sp);String owner=sp.addedBy==null?"неизвестно (старая точка)":plugin.access().playerName(sp.addedBy);
            List<String> lore=new ArrayList<>();lore.add("&7Координаты: &f"+loc(sp.location));lore.add("&7Шанс: &f"+fmt(sp.chance)+"%");lore.add("&7Поставил: &f"+owner);
            if(editable){lore.add("&aЛКМ — изменить шанс");lore.add("&eПКМ — удалить");}
            put(inv,slots[s],item(editable?Material.CHEST:Material.BARRIER,"&eТочка #"+(index+1),lore.toArray(new String[0])),"point:"+index);
        }
        if(visible.isEmpty())inv.setItem(22,item(Material.BARRIER,"&cТочек не видно","&7Здесь показываются только ваши точки. Администратор/points-all видит все."));
        put(inv,46,item(Material.RABBIT_HIDE,"&aДобавить точку","&7Получить инструмент точки для этого ивента.","&aЛКМ — получить"),"pointadd");
        put(inv,47,item(Material.RABBIT_FOOT,"&cЧёрный список","&7Инструмент блокировки мест для этого события.","&aЛКМ — получить"),"blockedTool");
        put(inv,48,item(Material.RABBIT_HIDE,"&bМульти-инструмент точек","&7Привязать шкурку к нескольким событиям.","&aЛКМ — настроить"),"pointTool");
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"points:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"points:page:"+(page+1));
        if("main".equalsIgnoreCase(backTarget)) put(inv,49,item(Material.ARROW,"&b← Главное меню","&7Вернуться в центр управления."),"back");
        else put(inv,49,item(Material.ARROW,"&b← Обзор ивента","&7Вернуться в основное меню ивента."),"backEditor");
        put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public int visiblePointCount(Player p, EventDefinition e){
        if(e==null)return 0;
        boolean allAccess=plugin.access().isAdmin(p.getUniqueId()) || plugin.access().allows(p.getUniqueId(),Perm.POINTS_ALL);
        if(allAccess)return e.points.size();
        int n=0;for(SpawnPoint sp:e.points)if(p.getUniqueId().equals(sp.addedBy))n++;return n;
    }

    public void openMyPoints(Player p){openMyPoints(p,1);}
    public void openMyPoints(Player p,int requestedPage){
        if(!plugin.access().has(p,Perm.POINTS)) return;
        UUID owner=p.getUniqueId();
        List<EventDefinition> grouped=new ArrayList<>();
        for(EventDefinition e:plugin.events().values()) if(e.points.stream().anyMatch(sp->owner.equals(sp.addedBy))) grouped.add(e);
        int perPage=28;int pages=Math.max(1,(int)Math.ceil(grouped.size()/(double)perPage));int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("myPoints",null,page);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &aМои точки &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);int[] slots=gridSlots();int start=(page-1)*perPage;
        for(int sidx=0;sidx<Math.min(perPage,grouped.size()-start);sidx++){EventDefinition e=grouped.get(start+sidx);int count=(int)e.points.stream().filter(sp->owner.equals(sp.addedBy)).count();put(inv,slots[sidx],item(Material.CHEST,"&a"+e.playerName,"&7Ваших точек: &f"+count,"&7Нажмите, чтобы увидеть координаты и шанс каждой точки.","&aЛКМ — открыть точки этого ивента"),"myPointEvent:"+e.id);}
        if(grouped.isEmpty())inv.setItem(22,item(Material.BARRIER,"&cУ вас нет точек","&7Поставьте первую точку через инструмент."));
        put(inv,46,item(Material.RABBIT_HIDE,"&aИнструмент точки","&7Выберите ивент для привязки шкурки.","&aЛКМ — выбрать ивент"),"pointToolChoose");
        put(inv,47,item(Material.RABBIT_FOOT,"&cЧёрный список","&7Глобальный инструмент блокировки мест.","&7Работает для всех событий.","&aЛКМ — получить"),"myGlobalBlockedTool");
        if(plugin.access().has(p,Perm.SETTINGS))put(inv,48,item(Material.COMPASS,"&dГлобальный центр точек","&7Сейчас: &f"+plugin.access().pointCenterText(),"&7Используется ролями без собственного центра.","&aЛКМ — изменить через чат","&eПКМ — сбросить в X=0, Z=0"),"pointCenter");
        else {
            String ownRole=plugin.access().roleId(p.getUniqueId());
            int ownMax=plugin.access().roleMaxPoints(ownRole); double ownRadius=plugin.access().rolePointRadius(ownRole);
            put(inv,48,item(Material.TARGET,"&dНастройки моей роли","&7Роль: &f"+plugin.access().roleDisplay(p.getUniqueId()),"&7Лимит: &f"+(ownMax<0?"без ограничения":ownMax),"&7Радиус: &f"+(ownRadius<0?"без ограничения":formatRadiusValue(ownRadius)+" блоков"),"&7Центр: &f"+plugin.access().rolePointCenterText(ownRole),"&8Только информация.","&8Изменяет старшая роль."),"noop");
        }
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"myPoints:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"myPoints:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Главное меню"),"back");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv);p.openInventory(inv);
    }

    private record MyPointRef(String eventId,int index){}

    public void openPointEventPicker(Player p){openPointEventPicker(p,1);}
    public void openPointEventPicker(Player p,int requestedPage){
        if(!plugin.access().has(p,Perm.POINTS)) return;
        List<EventDefinition> all=new ArrayList<>(plugin.events().values());int perPage=27;int pages=Math.max(1,(int)Math.ceil(all.size()/(double)perPage));int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("pointEventPicker",null,page);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &aВыбор ивента для точки &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);int start=(page-1)*perPage;int[] slots=gridSlots();
        for(int i=start,s=0;i<Math.min(all.size(),start+perPage);i++,s++){EventDefinition e=all.get(i);put(inv,slots[s],item(Material.FIREWORK_STAR,"&e"+e.playerName,"&7Ваших точек: &f"+visiblePointCount(p,e),"&aЛКМ — получить инструмент","&eПКМ — открыть мои точки"),"pointPick:"+e.id);}
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"pointPicker:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"pointPicker:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Мои точки"),"myPoints");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openBlocked(Player p,String id){openBlocked(p,id,1);}
    public void openBlocked(Player p,String id,int requestedPage){
        if(!plugin.access().has(p,Perm.POINTS)) return;
        EventDefinition e=plugin.events().get(id);if(e==null)return;int perPage=28;int pages=Math.max(1,(int)Math.ceil(e.blockedLocations.size()/(double)perPage));int page=Math.min(Math.max(1,requestedPage),pages);EventMenuHolder h=new EventMenuHolder("blocked",id,page);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Чёрный список &7• &f"+e.playerName+" &8["+page+"/"+pages+"]");h.inventory(inv);border(inv);int start=(page-1)*perPage;int[] slots=gridSlots();
        for(int i=start,s=0;i<Math.min(e.blockedLocations.size(),start+perPage);i++,s++)put(inv,slots[s],item(Material.BARRIER,"&cМесто #"+(i+1),"&7"+loc(e.blockedLocations.get(i)),"&eЛКМ — удалить"),"blocked:"+i);
        if(e.blockedLocations.isEmpty())inv.setItem(22,item(Material.LIME_DYE,"&aСписок пуст","&7Запрещённых мест нет."));
        put(inv,46,item(Material.RABBIT_FOOT,"&cПолучить лапку","&7Этот инструмент будет привязан к событию.","&aЛКМ — получить"),"blockedTool");
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"blocked:page:"+(page-1));if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"blocked:page:"+(page+1));put(inv,49,item(Material.ARROW,"&b← Обзор ивента","&7Вернуться в основное меню ивента."),"backEditor");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openRoles(Player p){openRoles(p,1);}

    public void openRoles(Player p,int requestedPage){
        List<String> ids=plugin.access().roleIds();
        int perPage=21;
        int pages=Math.max(1,(int)Math.ceil(ids.size()/(double)perPage));
        int page=Math.min(Math.max(1,requestedPage),pages);
        EventMenuHolder h=new EventMenuHolder("roles",null,page);
        Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Роли &8["+page+"/"+pages+"]");
        h.inventory(inv); border(inv);
        int[] slots=gridSlots();
        int start=(page-1)*perPage;
        for(int i=start,s=0;i<Math.min(ids.size(),start+perPage);i++,s++){
            String id=ids.get(i);
            CustomRole cr=plugin.access().customRoles().get(id.toLowerCase(Locale.ROOT));
            boolean custom=cr!=null;
            String display=custom?cr.displayName():id;
            String perms=custom?String.join(", ",cr.permissions()):builtinPermissions(id);
            int maxPoints=plugin.access().roleMaxPoints(id); double radius=plugin.access().rolePointRadius(id); String center=plugin.access().rolePointCenterText(id);
            ItemStack card=custom?item(roleIcon(plugin.access().roleIcon(id)),"&a"+display,"&7Вес: &f"+plugin.access().weight(id),"&7Лимит точек: &f"+(maxPoints<0?"без ограничения":maxPoints),"&7Радиус: &f"+(radius<0?"без ограничения":formatRadiusValue(radius)+" блоков"),"&7Центр: &f"+center,"&7Права: &f"+perms,"&aЛКМ — редактировать","&eПКМ — назначить игрока"):item(Material.NAME_TAG,"&e"+display,"&7Вес: &f"+plugin.access().weight(id),"&7Лимит точек: &f"+(maxPoints<0?"без ограничения":maxPoints),"&7Радиус: &f"+(radius<0?"без ограничения":formatRadiusValue(radius)+" блоков"),"&7Центр: &f"+center,"&7Права: &f"+perms,"&eПКМ — назначить игрока");
            put(inv,slots[s],card,(custom?"roleEdit:":"roleInfo:")+id);
        }
        put(inv,43,menuHead("staff","&aСоздать роль","&7Открыть визуальный конструктор.","&7Выберите права кликами и сохраните."),"roleCreate");
        put(inv,44,item(Material.COMPASS,"&bПоиск игрока","&7Искать сотрудника по нику или роли.","&aЛКМ — поиск"),"accessSearch");
        if(page>1)put(inv,45,item(Material.ARROW,"&b← Предыдущая","&7Страница "+(page-1)),"roles:page:"+(page-1));
        if(page<pages)put(inv,53,item(Material.ARROW,"&bСледующая →","&7Страница "+(page+1)),"roles:page:"+(page+1));
        put(inv,49,item(Material.ARROW,"&b← Сотрудники","&7Вернуться к списку сотрудников."),"access_back");
        put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");
        plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    /** Права роли занимают все внутренние слоты строк 3–5; это позволяет видеть все 20 прав сразу. */
    private static final int[] ROLE_PERM_SLOTS = {19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

    public void openBuiltinRoleInfo(Player p,String id){
        String upper=id.toUpperCase(Locale.ROOT); Role role; try{role=Role.valueOf(upper);}catch(Exception ex){return;}
        EventMenuHolder h=new EventMenuHolder("role_info",id,1); Inventory inv=guiInventory(p,h,"&8Роль &7• &6"+role.name()); h.inventory(inv); border(inv);
        put(inv,10,item(Material.NAME_TAG,"&eРоль","&f"+role.name(),"&7Вес: &f"+role.weight()));
        int max=plugin.access().roleMaxPoints(id); double radius=plugin.access().rolePointRadius(id); boolean canEdit=plugin.access().canEditRolePointSettings(p,id);
        put(inv,11,item(Material.CHEST,"&eЛимит точек","&f"+(max<0?"без ограничения":max),canEdit?"&aЛКМ — изменить":"&8Нет права менять эту роль"),canEdit?"rolePointSetting:"+id+":maxPoints":"noop");
        put(inv,12,item(Material.COMPASS,"&eРадиус от центра","&f"+(radius<0?"без ограничения":formatRadiusValue(radius)+" блоков"),canEdit?"&aЛКМ — изменить":"&8Нет права менять эту роль"),canEdit?"rolePointSetting:"+id+":radius":"noop");
        put(inv,14,item(Material.TARGET,"&eЦентр постановки","&f"+plugin.access().rolePointCenterText(id),"&7Глобальный центр: &f"+plugin.access().pointCenterText(),canEdit?"&aЛКМ — изменить X Z":"&8Нет права менять эту роль"),canEdit?"rolePointSetting:"+id+":center":"noop");
        String[] perms=PERMISSIONS; int[] slots=ROLE_PERM_SLOTS;
        for(int i=0;i<Math.min(perms.length,slots.length);i++){String perm=perms[i];boolean yes=role.allows(perm);put(inv,slots[i],item(yes?Material.LIME_DYE:Material.GRAY_DYE,(yes?"&a✔ ":"&c✖ ")+permissionTitle(perm),"&7Ключ: &f"+perm,"&7"+permDescription(perm),"&8"+(yes?"Разрешено":"Запрещено")));}
        put(inv,46,item(Material.BOOK,"&bПрава роли",role.permissions().contains("*")?"&7Разрешено: &fвсе 20 прав":"&7Разрешено: &f"+role.permissions().size()+" / "+PERMISSIONS.length,"&7Настройки точек встроенной роли сохраняются отдельно."));
        put(inv,49,item(Material.ARROW,"&b← Роли"),"roles"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openRolePointSettings(Player p,String roleId){
        if(roleId==null||roleId.isBlank()||!plugin.access().canEditRolePointSettings(p,roleId)){plugin.lang().send(p,"§cНедоступно: §7у вашей роли нет права менять настройки точек этой роли.");return;}
        String lower=roleId.toLowerCase(Locale.ROOT); CustomRole cr=plugin.access().customRoles().get(lower); String display=cr!=null?cr.displayName():roleId.toUpperCase(Locale.ROOT);
        EventMenuHolder h=new EventMenuHolder("role_point_settings",roleId,1); Inventory inv=guiInventory(p,h,"&8Роль &7• &6"+display+" &7• &dТочки"); h.inventory(inv); border(inv);
        int max=plugin.access().roleMaxPoints(roleId); double radius=plugin.access().rolePointRadius(roleId);
        put(inv,4,item(Material.TARGET,"&dНастройки точек роли","&7Роль: &f"+display,"&7Эти значения общие для всех игроков этой роли."),"noop");
        put(inv,10,item(Material.CHEST,"&eЛимит точек","&f"+(max<0?"без ограничения":max),"&7Суммарное число точек одного игрока этой роли.","&aЛКМ — изменить","&eПКМ — снять ограничение"),"rolePointSetting:"+roleId+":maxPoints");
        put(inv,12,item(Material.COMPASS,"&eРадиус от центра","&f"+(radius<0?"без ограничения":formatRadiusValue(radius)+" блоков"),"&7Максимальное горизонтальное расстояние от центра.","&aЛКМ — изменить","&eПКМ — снять ограничение"),"rolePointSetting:"+roleId+":radius");
        put(inv,14,item(Material.TARGET,"&eЦентр постановки","&f"+plugin.access().rolePointCenterText(roleId),"&7Глобальный центр: &f"+plugin.access().pointCenterText(),"&aЛКМ — ввести X Z","&eПКМ — вернуть глобальный центр"),"rolePointSetting:"+roleId+":center");
        put(inv,16,item(Material.BOOK,"&bВажно","&7Настройки применяются ко всей роли.","&7Helper меняет настройки HELPER для всех HELPER.","&7У роли без своего центра используется глобальный центр."),"noop");
        put(inv,49,item(Material.ARROW,"&b← Назад"),"rolePointBack:"+roleId); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openRoleEditor(Player p,String id){ openRoleEditor(p,id,1); }
    public void openRoleEditor(Player p,String id,int ignoredPage){
        plugin.input().ensureRoleDraft(p,id);CustomRole r=plugin.access().customRoles().get(id.toLowerCase(Locale.ROOT));if(r==null){plugin.lang().send(p, "§cРоль не найдена.");return;}
        EventMenuHolder h=new EventMenuHolder("role_editor",id,1);Inventory inv=guiInventory(p,h,"&8Роль &7• &6"+r.displayName());h.inventory(inv);border(inv);
        put(inv,10,item(Material.PAPER,"&eID роли","&f"+r.id(),"&8ID не меняется после создания."),"roleIdInfo");
        put(inv,11,field(Material.NAME_TAG,"&eНазвание",plugin.input().roleDraftName(p,r.displayName()),"roleNameInput"));
        put(inv,12,field(Material.ANVIL,"&eВес",Integer.toString(plugin.input().roleDraftWeight(p,r.weight())),"roleWeightInput"));
        put(inv,13,item(roleIcon(plugin.input().roleDraftIcon(p,r.icon())),"&eИконка роли","&7Текущая: &f"+plugin.input().roleDraftIcon(p,r.icon()),"&aЛКМ — выбрать предмет из инвентаря"),"roleIconSelect");
        put(inv,14,field(Material.CHEST,"&eЛимит точек",plugin.input().roleDraftMaxPoints(p,r.maxPoints())<0?"без ограничения":Integer.toString(plugin.input().roleDraftMaxPoints(p,r.maxPoints())),"roleMaxPointsInput"));
        put(inv,15,field(Material.COMPASS,"&eРадиус от центра",plugin.input().roleDraftRadius(p,r.pointRadius())<0?"без ограничения":formatRadiusValue(plugin.input().roleDraftRadius(p,r.pointRadius()))+" блоков","roleRadiusInput"));
        put(inv,16,item(Material.TARGET,"&dЦентр постановки","&7Центр роли: &f"+plugin.access().rolePointCenterText(id),"&7Глобальный центр: &f"+plugin.access().pointCenterText(),"&aЛКМ — настроить центр роли"),"rolePointSettings:"+id);
        int[] slots=ROLE_PERM_SLOTS;for(int i=0;i<Math.min(PERMISSIONS.length,slots.length);i++){String perm=PERMISSIONS[i];boolean yes=plugin.input().roleDraftHas(p,perm);put(inv,slots[i],item(yes?Material.LIME_DYE:Material.GRAY_DYE,(yes?"&a✔ ":"&c✖ ")+permissionTitle(perm),"&7Ключ: &f"+perm,"&7"+permDescription(perm),"&8"+(yes?"Разрешено":"Запрещено"),"&aЛКМ — переключить"),"rolePerm:"+id+":"+perm);}
        put(inv,46,item(Material.BOOK,"&bПрава роли","&7Выбрано: &f"+countDraftPermissions(p)+"&7 / "+PERMISSIONS.length,"&7Можно включить сразу несколько прав."),"noop");
        put(inv,47,item(Material.LIME_DYE,"&aСохранить","&7Сохранить название, вес, права и лимиты."),"roleSave:"+id);
        if(plugin.access().isAdmin(p.getUniqueId())){boolean pending=roleDeletePending(p,id);put(inv,45,item(pending?Material.TNT:Material.BARRIER,pending?"&c⚠ Нажмите ещё раз — удалить":"&cУдалить роль",pending?"&7Повторный клик подтверждает удаление.":"&7Кастомная роль будет удалена."),"roleDelete:"+id);}
        put(inv,49,item(Material.ARROW,"&b← Роли"),"roles");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }
    private int countDraftPermissions(Player p){int n=0;for(String perm:PERMISSIONS)if(plugin.input().roleDraftHas(p,perm))n++;return n;}

    public void openRoleCreator(Player p){ openRoleCreator(p,1); }
    public void openRoleCreator(Player p,int ignoredPage){
        EventMenuHolder h=new EventMenuHolder("role_creator",null,1);Inventory inv=guiInventory(p,h,"&8EventHeads &7• &6Конструктор роли");h.inventory(inv);border(inv);
        put(inv,4,item(Material.WRITABLE_BOOK,"&aСоздание кастомной роли","&7Выберите несколько прав кликами.","&7Ниже можно задать лимит точек и радиус."),"roleDraftInfo");
        put(inv,10,field(Material.PAPER,"&eID роли",plugin.input().roleDraftId(p,"event_role"),"draftId"));
        put(inv,11,field(Material.NAME_TAG,"&eНазвание",plugin.input().roleDraftName(p,"Помощник"),"draftName"));
        put(inv,12,field(Material.ANVIL,"&eВес",Integer.toString(plugin.input().roleDraftWeight(p,45)),"draftWeight"));
        put(inv,13,item(roleIcon(plugin.input().roleDraftIcon(p,"WRITABLE_BOOK")),"&eИконка роли","&7Текущая: &f"+plugin.input().roleDraftIcon(p,"WRITABLE_BOOK"),"&aЛКМ — выбрать предмет из инвентаря"),"draftIconSelect");
        put(inv,14,field(Material.CHEST,"&eЛимит точек",plugin.input().roleDraftMaxPoints(p,-1)<0?"без ограничения":Integer.toString(plugin.input().roleDraftMaxPoints(p,-1)),"draftMaxPointsInput"));
        put(inv,15,field(Material.COMPASS,"&eРадиус от центра",plugin.input().roleDraftRadius(p,-1.0D)<0?"без ограничения":formatRadiusValue(plugin.input().roleDraftRadius(p,-1.0D))+" блоков","draftRadiusInput"));
        put(inv,16,item(Material.TARGET,"&dЦентр","&7Текущий центр: &f"+plugin.access().pointCenterText()),"noop");
        int[] slots=ROLE_PERM_SLOTS;for(int i=0;i<Math.min(PERMISSIONS.length,slots.length);i++){String perm=PERMISSIONS[i];boolean yes=plugin.input().roleDraftHas(p,perm);put(inv,slots[i],item(yes?Material.LIME_DYE:Material.GRAY_DYE,(yes?"&a✔ ":"&c✖ ")+perm,"&7"+permDescription(perm),"&aЛКМ — переключить"),"draftPerm:"+perm);}
        put(inv,46,item(Material.BOOK,"&bПрава роли","&7Выбрано: &f"+countDraftPermissions(p)+"&7 / "+PERMISSIONS.length,"&7Можно включить сразу несколько прав."),"noop");
        put(inv,47,item(Material.LIME_DYE,"&aСохранить роль","&7Создать роль с выбранными правами."),"roleDraftSave");put(inv,49,item(Material.ARROW,"&b← Роли"),"roles");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openAccessPlayer(Player p,UUID uuid){
        if(plugin.isSuperAdmin(uuid) && !plugin.isSuperAdmin(p.getUniqueId())){
            plugin.lang().send(p, "§cСупер-администратор Dok_Si скрыт из списка сотрудников.");
            openAccess(p);
            return;
        }
        String roleId=plugin.access().roleId(uuid); EventMenuHolder h=new EventMenuHolder("access_player",roleId);
        Inventory inv=guiInventory(p,h,"&8Сотрудник &7• &6"+plugin.access().playerName(uuid)); h.inventory(inv); border(inv);
        String lp=plugin.access().luckPermsGroup(uuid);
        put(inv,4,menuHead("staff","&e"+plugin.access().playerName(uuid),"&7Роль EventHeads: &f"+plugin.access().roleDisplay(uuid),"&7LuckPerms: &f"+(lp==null?"—":lp),"&7Лимит своих точек: &f"+(plugin.access().maxPointsFor(uuid)<0?"без ограничения":plugin.access().maxPointsFor(uuid)),"&7Радиус точек: &f"+(plugin.access().pointRadiusFor(uuid)<0?"без ограничения":formatRadiusValue(plugin.access().pointRadiusFor(uuid))+" блоков"),"&7Центр: &f"+plugin.access().pointCenterText()));
        String staffRole=plugin.access().roleId(uuid);
        boolean canEditRolePoints=plugin.access().canEditRolePointSettings(p,staffRole);
        int staffMax=plugin.access().roleMaxPoints(staffRole); double staffRadius=plugin.access().rolePointRadius(staffRole); String staffCenter=plugin.access().rolePointCenterText(staffRole);
        put(inv,5,item(Material.CHEST,"&eЛимит точек","&f"+(staffMax<0?"без ограничения":staffMax),"&7Сколько точек может иметь игрок этой роли.",canEditRolePoints?"&aЛКМ — изменить лимит роли":"&8Только просмотр"),canEditRolePoints?"staffRoleMaxPoints:"+staffRole:"noop");
        put(inv,6,item(Material.COMPASS,"&eРадиус от центра","&f"+(staffRadius<0?"без ограничения":formatRadiusValue(staffRadius)+" блоков"),"&7Максимальное расстояние от центра постановки.",canEditRolePoints?"&aЛКМ — изменить радиус роли":"&8Только просмотр"),canEditRolePoints?"staffRoleRadius:"+staffRole:"noop");
        put(inv,7,item(Material.TARGET,"&eЦентр постановки","&f"+staffCenter,"&7Глобальный центр: &f"+plugin.access().pointCenterText(),canEditRolePoints?"&aЛКМ — изменить центр роли":"&8Только просмотр"),canEditRolePoints?"staffRoleCenter:"+staffRole:"noop");
        int[] slots=gridSlots();
        for(int i=0;i<Math.min(PERMISSIONS.length,slots.length);i++){
            String perm=PERMISSIONS[i]; boolean roleYes=plugin.access().roleAllows(uuid,perm); boolean effective=plugin.access().allows(uuid,perm); Boolean override=plugin.access().override(uuid,perm);
            List<String> lore=new ArrayList<>(); lore.add("&7Раздел: &f"+permissionTitle(perm));
            lore.add("&7Ключ: &f"+perm);
            lore.add("&7"+permDescription(perm)); lore.add("&7В роли: "+(roleYes?"&aесть":"&cнет")); lore.add("&7Итог: "+(effective?"&aразрешено":"&cзапрещено")); if(override!=null) lore.add("&8Индивидуальное право: "+(override?"разрешить":"запретить")); lore.add("&aЛКМ — индивидуально переключить");
            put(inv,slots[i],item(effective?Material.LIME_DYE:Material.GRAY_DYE,(effective?"&a✔ ":"&c✖ ")+perm,lore.toArray(new String[0])),"perm:"+uuid+":"+perm);
        }
        put(inv,49,item(Material.ARROW,"&b← Сотрудники","&7Вернуться к списку."),"access_back"); put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close"); plugin.lang().localizeInventory(p,inv); p.openInventory(inv);
    }

    public void openAccess(Player p){openAccess(p,"");}
    public void openAccess(Player p,String query){EventMenuHolder h=new EventMenuHolder("access",null);Inventory inv=guiInventory(p,h,query==null||query.isBlank()?"&8EventHeads &7• &6Сотрудники":"&8EventHeads &7• &6Поиск: "+query);h.inventory(inv);border(inv);int slot=10;String needle=query==null?"":query.toLowerCase(Locale.ROOT);java.util.LinkedHashSet<UUID> staff=new java.util.LinkedHashSet<>(plugin.access().snapshot().keySet());for(Player op:plugin.getServer().getOnlinePlayers())staff.add(op.getUniqueId());for(UUID uuid:staff){if(plugin.isSuperAdmin(uuid)) continue; String name=plugin.access().playerName(uuid);String role=plugin.access().roleDisplay(uuid);String lp=plugin.access().luckPermsGroup(uuid);if(!needle.isBlank()&&!name.toLowerCase(Locale.ROOT).contains(needle)&&!role.toLowerCase(Locale.ROOT).contains(needle)&&(lp==null||!lp.toLowerCase(Locale.ROOT).contains(needle)))continue;put(inv,slot++,menuHead("staff","&e"+name,"&7EventHeads: &f"+role,"&7LuckPerms: &f"+(lp==null?"—":lp),"&7ЛКМ — права","&cПКМ — удалить из EventHeads (двойное подтверждение)"),"access:"+uuid);if(slot%9==0)slot+=2;if(slot>=45)break;}if(slot==10)inv.setItem(22,item(Material.BARRIER,"&cНичего не найдено","&7Попробуйте другую часть ника или ID роли."));if(plugin.access().has(p,Perm.ACCESS)){put(inv,46,item(Material.COMPASS,"&bПоиск","&7Найти сотрудника.","&aЛКМ — ввод в чат"),"accessSearch");put(inv,47,item(Material.ANVIL,"&aРоли","&7Создание и редактирование ролей."),"roles");}put(inv,49,item(Material.ARROW,"&b← Главное меню","&7Вернуться в центр управления."),"back");put(inv,50,item(Material.BARRIER,"&cЗакрыть"),"close");plugin.lang().localizeInventory(p,inv); p.openInventory(inv);}

    /** Обновляет только динамические часы расписания. PDC-действие устанавливаем заново,
     * иначе после первого обновления кнопка превращалась в обычный предмет. */
    public void startRefresh(Player p){
        if(p==null)return;
        stopRefresh(p);
        ScheduledTask task=SchedulerUtil.runEntityRepeating(plugin,p,()->refreshOpenEditor(p),20L,20L);
        if(task!=null)refreshTasks.put(p.getUniqueId(),task);
    }

    public void stopRefresh(Player p){
        if(p==null)return;
        ScheduledTask task=refreshTasks.remove(p.getUniqueId());
        if(task!=null)task.cancel();
    }

    public void refreshOpenEditor(Player p){
        if(p==null)return;
        if(!(p.getOpenInventory().getTopInventory().getHolder() instanceof EventMenuHolder h))return;
        if("scheduleOverview".equals(h.type)){refreshScheduleOverview(p,h);return;}
        EventDefinition e=h.eventId==null?null:plugin.events().get(h.eventId); if(e==null)return;
        switch(h.type){
            case "editor" -> {ItemStack s=scheduleItem(p,e);setAction(s,"scheduleMenu");p.getOpenInventory().getTopInventory().setItem(28,s);}
            case "settings_schedule" -> {ItemStack s=scheduleItem(p,e);setAction(s,"scheduleEdit");p.getOpenInventory().getTopInventory().setItem(22,s);}
            default -> {}
        }
    }

    /** Обновляет только карточки состояния событий, не закрывая и не открывая инвентарь заново. */
    private void refreshScheduleOverview(Player p, EventMenuHolder h){
        Inventory inv=p.getOpenInventory().getTopInventory();
        List<EventDefinition> all=new ArrayList<>(plugin.events().values());
        int perPage=27; int page=Math.max(1,h.page); int start=(page-1)*perPage; int[] slots=gridSlots(); Instant now=Instant.now();
        for(int s=0;s<slots.length;s++){
            int idx=start+s;
            if(idx>=start+perPage || idx>=all.size()){inv.setItem(slots[s],null);continue;}
            EventDefinition e=all.get(idx); String status=scheduleStatus(e,now); String c=statusColor(status); String symbol=statusSymbol(status);
            List<String> lore=new ArrayList<>(); lore.add(c+symbol+" &fСтатус: "+c+status); if(e.weeklyScheduleEnabled){lore.add("&7Расписание: &f"+weeklyDayName(e.weeklyDayOfWeek)+" "+formatMinutes(e.weeklyTimeMinutes)); lore.add("&7Длительность: &f"+formatDuration(e.weeklyDurationSeconds));} else {lore.add("&7Начало: &f"+(e.startAt==null?"не задано":formatInstant(e.startAt))); lore.add("&7Окончание: &f"+(e.endAt==null?"не задано":formatInstant(e.endAt)));}
            if(e.startAt!=null&&e.startAt.isAfter(now)) lore.add("&bДо начала: &f"+formatRemaining(e.startAt,now));
            if(e.endAt!=null&&e.endAt.isAfter(now)&&!startAtIsAfter(e,now)) lore.add("&dДо окончания: &f"+formatRemaining(e.endAt,now));
            ItemStack v=e.visual==null?new ItemStack(Material.PLAYER_HEAD):e.visual.clone(); ItemMeta m=v.getItemMeta(); if(m==null)continue; m.setDisplayName(ChatColorUtil.color(c+symbol+" &f"+e.playerName)); m.setLore(colorLore(lore)); v.setItemMeta(m); setAction(v,"scheduleOverview:event:"+e.id); inv.setItem(slots[s],v);
        }
    }

    private String formatDurationRange(long min,long max){return min==max?formatDuration(min):formatDuration(min)+" – "+formatDuration(max);}
    private String formatDuration(long seconds){long weeks=seconds/604800;seconds%=604800;long days=seconds/86400;seconds%=86400;long hours=seconds/3600;seconds%=3600;long minutes=seconds/60;long sec=seconds%60;List<String> parts=new ArrayList<>();if(weeks>0)parts.add(weeks+"н");if(days>0)parts.add(days+"д");if(hours>0)parts.add(hours+"ч");if(minutes>0)parts.add(minutes+"м");if(sec>0||parts.isEmpty())parts.add(sec+"с");return String.join(" ",parts);}

    private ItemStack scheduleItem(Player p,EventDefinition e){List<String> lore=new ArrayList<>();Instant now=Instant.now();
        if(e.weeklyScheduleEnabled){
            lore.add("&aЕженедельно: &f"+weeklyDayName(e.weeklyDayOfWeek)+" в "+formatMinutes(e.weeklyTimeMinutes));
            lore.add("&7Длительность: &f"+formatDuration(e.weeklyDurationSeconds));
            if(e.currentlyScheduled()){
                lore.add("&aИдёт сейчас: &fдо завершения "+weeklyCurrentRemaining(e));
                lore.add("&7Следующий запуск через: &f"+weeklyUntilNext(e));
            } else {
                lore.add("&7Следующий запуск через: &f"+weeklyUntilNext(e));
            }
            lore.add("&7Длительность одного запуска: &f"+formatDuration(e.weeklyDurationSeconds));
            lore.add("&7Статус: "+(e.currentlyScheduled()?"&aсейчас активно":"&eожидает следующего запуска"));
            lore.add("&8Пример: суббота 20:00 | 1h");
        } else if(e.startAt==null){
            lore.add("&7Старт: &cне задан");lore.add("&8Ввод: 2026-09-10 18:00 | 2026-09-17 23:59");lore.add("&8Ввод: 2d | 1d = через 2 дня + 1 день длительности");
        } else {
            lore.add("&7Дата начала: &f"+formatInstant(e.startAt));
            lore.add("&7Дата окончания: &f"+(e.endAt==null?"—":formatInstant(e.endAt)));
            lore.add("&r");
            if(e.startAt.isAfter(now)) lore.add("&bДо начала: &f"+formatRemaining(e.startAt,now)); else lore.add("&aДо начала: &f0н 0д 0ч 0м 0с &7(уже началось)");
            if(e.endAt!=null){if(e.endAt.isAfter(now))lore.add("&dДо окончания: &f"+formatRemaining(e.endAt,now));else lore.add("&cДо окончания: &f0н 0д 0ч 0м 0с &7(завершено)");}else lore.add("&7Окончание: &eне задано");
        }
        lore.add("&r");lore.add("&aЛКМ — настроить расписание");lore.add("&eПКМ — отключить недельное расписание");return item(Material.CLOCK,"&eРасписание",lore.toArray(new String[0]));}
    private String formatInstant(Instant i){return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(i);}
    private String formatRemaining(Instant target,Instant now){long sec=Math.max(0,Duration.between(now,target).getSeconds());long w=sec/604800;sec%=604800;long d=sec/86400;sec%=86400;long h=sec/3600;sec%=3600;long m=sec/60;long s=sec%60;return w+"н "+d+"д "+h+"ч "+m+"м "+s+"с";}
    private String modeRu(EventDefinition.SpawnMode m){return switch(m){case RANDOM->"СЛУЧАЙНЫЙ";case POINTS->"ТОЧКИ";case MIXED->"СМЕШАННЫЙ";};}
    private boolean showPage(String p){List<String> c=plugin.getConfig().getStringList("ui.settings-pages");return c.isEmpty()||c.stream().anyMatch(x->x.equalsIgnoreCase(p));}
    private String settingsTitle(String n){return switch(n){case "info"->"Общие";case "spawn"->"Спавн";case "rewards"->"Награды";case "animation"->"Анимация";case "schedule"->"Расписание";case "conditions"->"Условия сбора";default->n;};}
    private String weeklyDayName(int d){return switch(d){case 1->"понедельник";case 2->"вторник";case 3->"среда";case 4->"четверг";case 5->"пятница";case 6->"суббота";case 7->"воскресенье";default->"суббота";};}
    private String formatMinutes(int m){m=Math.floorMod(m,1440);return String.format(Locale.ROOT,"%02d:%02d",m/60,m%60);}
    private String safeItemName(ItemStack i){return i==null||i.getType().isAir()?"не задан":(i.getItemMeta()!=null&&i.getItemMeta().hasDisplayName()?ChatColor.stripColor(i.getItemMeta().getDisplayName()):i.getType().name());}
    private String safe(String s){return s==null||s.isBlank()?"неизвестно":s;}
    private String limit(String s,int max){if(s==null||s.isBlank())return"—";return s.length()<=max?s:s.substring(0,Math.max(0,max-1))+"…";}
    private String loc(Location l){return l.getWorld()==null?"?":l.getWorld().getName()+" X="+l.getBlockX()+" Y="+l.getBlockY()+" Z="+l.getBlockZ();}
    private int totalForEvent(String id){int t=0;var sec=plugin.data().stats().getConfigurationSection("players");if(sec!=null)for(String u:sec.getKeys(false))try{t+=plugin.data().getCount(UUID.fromString(u),id);}catch(Exception ignored){}return t;}
    private String fmt(double d){return String.format(Locale.ROOT,"%.2f",d);}
    private List<String> colorLore(List<String> l){return l.stream().map(ChatColorUtil::color).toList();}
    private String builtinPermissions(String id){try{return String.join(", ",Role.valueOf(id.toUpperCase(Locale.ROOT)).permissions());}catch(Exception ignored){return"—";}}
    public String builtinPermissionSummary(String id){return builtinPermissions(id);}
    public int roleMaxPoints(String id){CustomRole cr=plugin.access().customRoles().get(id.toLowerCase(Locale.ROOT));return cr==null?-1:cr.maxPoints();}
    public double rolePointRadius(String id){CustomRole cr=plugin.access().customRoles().get(id.toLowerCase(Locale.ROOT));return cr==null?-1.0D:cr.pointRadius();}
    private String permDescription(String p){return switch(p){case"view"->"просматривать меню и список ивентов";case"create"->"создавать новые события";case"edit"->"редактировать настройки";case"delete"->"удалять события";case"spawn"->"управлять спавном";case"points"->"ставить точки, чёрный список и использовать инструменты";case"points-all"->"редактировать и удалять любые точки, независимо от владельца";case"stats"->"просматривать статистику";case"schedule"->"включать/выключать расписание";case"access"->"управлять сотрудниками и ролями; выдавать доступ другим игрокам";case"give"->"получать/выдавать EventHeads";case"settings"->"диагностика и reload";case"test"->"тестировать спавн";case"break"->"удалять активные экземпляры без награды";case"item"->"административные операции EventHeads";case"export"->"экспортировать событие";case"import"->"импортировать событие";case"anti-esp"->"получать уведомления о срабатываниях Anti-ESP";case"audit"->"просматривать журнал действий сотрудников в GUI";case"templates"->"создавать и дублировать ивенты из шаблонов";default->"специальное право EventHeads";};}
    private record HelpRow(String longCommand,String shortCommand,String description,String exampleCommand,String example){}
}

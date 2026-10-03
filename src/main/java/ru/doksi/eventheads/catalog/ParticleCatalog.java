package ru.doksi.eventheads.catalog;

import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.*;

/**
 * Fixed Java Edition 26.2 particle catalog.
 *
 * The runtime enum may grow on newer Paper versions, but EventHeads deliberately
 * keeps its editor catalog pinned to the Java Edition 26.2 set. This prevents
 * 26.3-only particles (for example the poplar leaf particles) from appearing
 * accidentally after a server update.
 */
public final class ParticleCatalog {
    private ParticleCatalog() {}

    /**
     * Java 26.3-only particle names that must never appear in the 26.2 editor catalog.
     * Paper 26.2 exposes the complete 26.2 enum through Particle.values().
     */
    private static final Set<String> JAVA_26_3_EXCLUDED = Set.of(
        "RED_POPLAR_LEAVES",
        "ORANGE_POPLAR_LEAVES",
        "YELLOW_POPLAR_LEAVES"
    );

    private static List<Particle> supportedParticleSnapshot() {
        List<Particle> out = new ArrayList<>();
        for (Particle particle : Particle.values()) {
            if (!JAVA_26_3_EXCLUDED.contains(particle.name())) out.add(particle);
        }
        return Collections.unmodifiableList(out);
    }

    private static final Map<String, Info> INFO = createInfo();

    /**
     * Fixed editor catalog. The order follows createInfo(), so the Java 26.2
     * catalog always contains exactly the authored 125 entries (minus explicit
     * 26.3 exclusions) instead of shrinking when enum resolution is incomplete.
     */
    public static List<String> catalogNames() {
        List<String> out = new ArrayList<>();
        for (String name : INFO.keySet()) {
            if (!JAVA_26_3_EXCLUDED.contains(name)) out.add(name);
        }
        return Collections.unmodifiableList(out);
    }

    public static List<Particle> supportedParticles() {
        return supportedParticleSnapshot();
    }

    /** Resolves a catalog name to the runtime Particle enum when available. */
    public static Particle resolve(String name) {
        if (name == null) return null;
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        if (JAVA_26_3_EXCLUDED.contains(normalized)) return null;
        try { return Particle.valueOf(normalized); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public static boolean isSupported(String name) {
        if (name == null) return false;
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        if (JAVA_26_3_EXCLUDED.contains(normalized)) return false;
        return INFO.containsKey(normalized) && resolve(normalized) != null;
    }

    public static boolean isSupported(Particle particle) {
        return particle != null && isSupported(particle.name());
    }

    public static String title(String name) {
        Info info = INFO.get(normalize(name));
        return info == null ? humanize(name) : info.title;
    }

    public static String description(String name) {
        Info info = INFO.get(normalize(name));
        return info == null ? "Визуальный эффект Minecraft Java Edition 26.2." : info.description;
    }

    public static Material icon(String name) {
        Info info = INFO.get(normalize(name));
        String materialName = info == null ? "AMETHYST_SHARD" : info.icon;
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            return fallbackIcon(normalize(name));
        }
        return material;
    }

    private static Material fallbackIcon(String particle) {
        return switch (particle) {
            case "SMALL_FLAME", "SOUL_FIRE_FLAME", "SOUL" -> Material.BLAZE_POWDER;
            case "LAVA", "FALLING_LAVA", "LANDING_LAVA" -> Material.LAVA_BUCKET;
            case "WATER", "FALLING_WATER", "SPLASH" -> Material.WATER_BUCKET;
            case "SMOKE", "LARGE_SMOKE", "WHITE_SMOKE" -> Material.BLACK_DYE;
            case "GUST", "SMALL_GUST", "GUST_EMITTER_LARGE", "GUST_EMITTER_SMALL" -> Material.WIND_CHARGE;
            default -> Material.AMETHYST_SHARD;
        };
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }

    private static String humanize(String name) {
        if (name == null || name.isBlank()) return "Частица";
        String s = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record Info(String title, String description, String icon) {}

    private static Info i(String title, String description, String icon) {
        return new Info(title, description, icon);
    }

    private static Map<String, Info> createInfo() {
        Map<String, Info> m = new LinkedHashMap<>();
        m.put("ANGRY_VILLAGER", i("Злой житель", "Красные искры недовольства жителя.", "EMERALD"));
        m.put("ASH", i("Пепел", "Мелкие тёмные частицы пепла.", "GRAY_DYE"));
        m.put("BLOCK", i("Осколки блока", "Частицы выбранного блока.", "STONE"));
        m.put("BLOCK_CRUMBLE", i("Крошение блока", "Крупинки блока при его разрушении.", "GRAVEL"));
        m.put("BLOCK_MARKER", i("Метка блока", "Маркер выбранного блока.", "TARGET"));
        m.put("BUBBLE", i("Пузырьки", "Пузырьки воздуха в воде.", "TUBE_CORAL"));
        m.put("BUBBLE_COLUMN_UP", i("Пузырьковый столб", "Пузырьки, поднимающиеся вверх.", "SOUL_SAND"));
        m.put("BUBBLE_POP", i("Лопнувшие пузырьки", "Мелкие пузырьки при всплытии и лопании.", "BUBBLE_CORAL"));
        m.put("CAMPFIRE_COSY_SMOKE", i("Дым костра", "Спокойный дым обычного костра.", "CAMPFIRE"));
        m.put("CAMPFIRE_SIGNAL_SMOKE", i("Сигнальный дым костра", "Высокий столб дыма костра.", "CAMPFIRE"));
        m.put("CHERRY_LEAVES", i("Листья сакуры", "Падающие розовые листья.", "CHERRY_LEAVES"));
        m.put("CLOUD", i("Облачко", "Светлое быстро исчезающее облако.", "WHITE_WOOL"));
        m.put("COMPOSTER", i("Компост", "Зелёные частицы компостера.", "COMPOSTER"));
        m.put("COPPER_FIRE_FLAME", i("Медное пламя", "Пламя медного огня.", "COPPER_BLOCK"));
        m.put("CRIMSON_SPORE", i("Багровые споры", "Споры багрового леса.", "CRIMSON_FUNGUS"));
        m.put("CRIT", i("Критический удар", "Искры критического попадания.", "NETHER_STAR"));
        m.put("CURRENT_DOWN", i("Течение вниз", "Пузырьковое течение, направленное вниз.", "WATER_BUCKET"));
        m.put("DAMAGE_INDICATOR", i("Индикатор урона", "Частица, отмечающая нанесённый урон.", "IRON_SWORD"));
        m.put("DOLPHIN", i("След дельфина", "Частицы следа плывущего дельфина.", "TROPICAL_FISH_BUCKET"));
        m.put("DRAGON_BREATH", i("Дыхание дракона", "Фиолетовое облако драконьего дыхания.", "DRAGON_BREATH"));
        m.put("DRIPPING_DRIPSTONE_LAVA", i("Капли лавы с натёчного камня", "Лава капает с натёчного камня.", "POINTED_DRIPSTONE"));
        m.put("DRIPPING_DRIPSTONE_WATER", i("Капли воды с натёчного камня", "Вода капает с натёчного камня.", "POINTED_DRIPSTONE"));
        m.put("DRIPPING_HONEY", i("Капли мёда", "Медленно падающие капли мёда.", "HONEY_BOTTLE"));
        m.put("DRIPPING_LAVA", i("Капли лавы", "Капли лавы сверху вниз.", "LAVA_BUCKET"));
        m.put("DRIPPING_OBSIDIAN_TEAR", i("Капли слёз обсидиана", "Тёмно-фиолетовые капли.", "CRYING_OBSIDIAN"));
        m.put("DRIPPING_WATER", i("Капли воды", "Обычные капли воды.", "WATER_BUCKET"));
        m.put("DUST", i("Цветная пыль", "Настраиваемая пыль с HEX-цветом.", "REDSTONE"));
        m.put("DUST_COLOR_TRANSITION", i("Переход цвета", "Пыль с переходом между двумя оттенками.", "REDSTONE_BLOCK"));
        m.put("DUST_PILLAR", i("Столб пыли", "Падающая пыль в форме столба.", "SAND"));
        m.put("DUST_PLUME", i("Клуб пыли", "Плотный небольшой клуб пыли.", "ARMADILLO_SCUTE"));
        m.put("EFFECT", i("Эффект зелья", "Цветные частицы эффекта зелья.", "POTION"));
        m.put("EGG_CRACK", i("Осколки яйца", "Мелкие осколки разбитого или вылупившегося яйца.", "EGG"));
        m.put("ELDER_GUARDIAN", i("Древний страж", "Пугающая частица древнего стража.", "ELDER_GUARDIAN_SPAWN_EGG"));
        m.put("ELECTRIC_SPARK", i("Электрическая искра", "Мелкие яркие электрические искры.", "LIGHTNING_ROD"));
        m.put("ENCHANT", i("Зачарование", "Поток частиц от зачарованного предмета.", "ENCHANTING_TABLE"));
        m.put("ENCHANTED_HIT", i("Зачарованный удар", "Искры зачарованного попадания.", "ENCHANTED_GOLDEN_APPLE"));
        m.put("END_ROD", i("Энд-стержни", "Белые искры с фиолетовым оттенком.", "END_ROD"));
        m.put("ENTITY_EFFECT", i("Эффект сущности", "Цветной эффект вокруг сущности.", "SPLASH_POTION"));
        m.put("EXPLOSION", i("Взрыв", "Небольшая вспышка взрыва.", "TNT"));
        m.put("EXPLOSION_EMITTER", i("Источник взрыва", "Плотная визуальная вспышка взрыва.", "TNT_MINECART"));
        m.put("FALLING_DRIPSTONE_LAVA", i("Падающая натёчная лава", "Лава, падающая с натёка.", "POINTED_DRIPSTONE"));
        m.put("FALLING_DRIPSTONE_WATER", i("Падающая натёчная вода", "Вода, падающая с натёка.", "POINTED_DRIPSTONE"));
        m.put("FALLING_DUST", i("Падающая пыль блока", "Частицы блока, падающие вниз.", "SAND"));
        m.put("FALLING_HONEY", i("Падающий мёд", "Более быстрые падающие медовые капли.", "HONEYCOMB"));
        m.put("FALLING_LAVA", i("Падающая лава", "Крупные частицы падающей лавы.", "LAVA_BUCKET"));
        m.put("FALLING_NECTAR", i("Падающий нектар", "Капли нектара, падающие вниз.", "SUSPICIOUS_STEW"));
        m.put("FALLING_OBSIDIAN_TEAR", i("Падающая слеза", "Падающие частицы слёз обсидиана.", "CRYING_OBSIDIAN"));
        m.put("FALLING_SPORE_BLOSSOM", i("Падающие споры", "Зелёные частицы спороцвета.", "SPORE_BLOSSOM"));
        m.put("FALLING_WATER", i("Падающая вода", "Частицы воды, падающие вниз.", "WATER_BUCKET"));
        m.put("FIREFLY", i("Светлячки", "Мелкие светящиеся частицы светлячков.", "FIREFLY_BUSH"));
        m.put("FIREWORK", i("Фейерверк", "Яркие частицы фейерверка.", "FIREWORK_ROCKET"));
        m.put("FISHING", i("Поплавок", "Частицы на поверхности при рыбалке.", "FISHING_ROD"));
        m.put("FLAME", i("Пламя", "Обычные огненные частицы.", "BLAZE_POWDER"));
        m.put("FLASH", i("Вспышка", "Очень яркая короткая вспышка.", "GLOWSTONE"));
        m.put("GEYSER", i("Гейзер", "Спецэффект гейзера из Java 26.2.", "WATER_BUCKET"));
        m.put("GEYSER_BASE", i("Основание гейзера", "Основание и облако гейзера.", "CALCITE"));
        m.put("GEYSER_PLUME", i("Струя гейзера", "Высокая струя частиц гейзера.", "WATER_BUCKET"));
        m.put("GEYSER_POOF", i("Пуф гейзера", "Небольшой выброс частиц гейзера.", "CLOUD"));
        m.put("GLOW", i("Свечение", "Мягкие светящиеся частицы.", "GLOW_INK_SAC"));
        m.put("GLOW_SQUID_INK", i("Светящиеся чернила", "Светящиеся чернильные частицы спрута.", "GLOW_INK_SAC"));
        m.put("GUST", i("Порыв ветра", "Кольцевой эффект порыва бриза.", "FEATHER"));
        m.put("GUST_EMITTER_LARGE", i("Большой источник порыва", "Мощный источник порыва воздуха.", "WIND_CHARGE"));
        m.put("GUST_EMITTER_SMALL", i("Малый источник порыва", "Небольшой источник порыва воздуха.", "WIND_CHARGE"));
        m.put("HAPPY_VILLAGER", i("Довольный житель", "Зелёные искры радости жителя.", "EMERALD"));
        m.put("HEART", i("Сердца", "Сердечки как при приручении.", "RED_DYE"));
        m.put("INFESTED", i("Заражение", "Эффект заражённого блока.", "INFESTED_STONE"));
        m.put("INSTANT_EFFECT", i("Мгновенный эффект", "Резкий всплеск эффекта зелья.", "SPLASH_POTION"));
        m.put("ITEM", i("Осколки предмета", "Частицы падающего предмета.", "ITEM_FRAME"));
        m.put("ITEM_COBWEB", i("Паутина предмета", "Эффект частиц паутины.", "COBWEB"));
        m.put("ITEM_SLIME", i("Частицы слизи", "Зелёные кусочки слизистого предмета.", "SLIME_BALL"));
        m.put("ITEM_SNOWBALL", i("Частицы снежка", "Белые частицы снежка.", "SNOWBALL"));
        m.put("LANDING_HONEY", i("Приземление мёда", "Медовые частицы при приземлении.", "HONEYCOMB"));
        m.put("LANDING_LAVA", i("Приземление лавы", "Частицы лавы при попадании.", "LAVA_BUCKET"));
        m.put("LANDING_OBSIDIAN_TEAR", i("Приземление слезы", "Частицы слёз обсидиана при попадании.", "CRYING_OBSIDIAN"));
        m.put("LARGE_SMOKE", i("Крупный дым", "Плотные клубы дыма.", "BLACK_DYE"));
        m.put("LAVA", i("Всплеск лавы", "Крупные горячие капли лавы.", "LAVA_BUCKET"));
        m.put("MYCELIUM", i("Мицелий", "Летающие частицы мицелия.", "MYCELIUM"));
        m.put("NAUTILUS", i("Наутилус", "Частицы водного эффекта наутилуса.", "NAUTILUS_SHELL"));
        m.put("NOTE", i("Нота", "Цветная частица музыкальной ноты.", "NOTE_BLOCK"));
        m.put("NOXIOUS_GAS", i("Ядовитый газ", "Частицы токсичного газа Java 26.2.", "POISONOUS_POTATO"));
        m.put("NOXIOUS_GAS_CLOUD", i("Облако ядовитого газа", "Плотное облако токсичного газа.", "POISONOUS_POTATO"));
        m.put("OMINOUS_SPAWNING", i("Зловещее появление", "Оменные частицы появления.", "OMINOUS_BOTTLE"));
        m.put("PALE_OAK_LEAVES", i("Листья бледного дуба", "Падающие листья бледного дуба.", "PALE_OAK_LEAVES"));
        m.put("PAUSE_MOB_GROWTH", i("Пауза роста", "Визуальный эффект остановки роста моба.", "CLOCK"));
        m.put("POOF", i("Пуф", "Маленькое облачко при исчезновении.", "CLOUD"));
        m.put("PORTAL", i("Портал", "Фиолетовые частицы портала.", "ENDER_PEARL"));
        m.put("RAID_OMEN", i("Знак нашествия", "Оменные частицы рейда.", "OMINOUS_BOTTLE"));
        m.put("RAIN", i("Дождь", "Мелкие частицы дождя.", "WATER_BUCKET"));
        m.put("RESET_MOB_GROWTH", i("Сброс роста", "Визуальный эффект сброса роста моба.", "CLOCK"));
        m.put("REVERSE_PORTAL", i("Обратный портал", "Поток частиц обратного портала.", "ENDER_EYE"));
        m.put("SCRAPE", i("Соскабливание", "Частицы при снятии воска.", "SHEARS"));
        m.put("SCULK_CHARGE", i("Заряд скалка", "Пульсирующий заряд скалка.", "SCULK"));
        m.put("SCULK_CHARGE_POP", i("Всплеск скалка", "Разлетающиеся частицы заряда.", "SCULK_CATALYST"));
        m.put("SCULK_SOUL", i("Душа скалка", "Душеподобные частицы скалка.", "SCULK_SHRIEKER"));
        m.put("SHRIEK", i("Крик скалка", "Волна крика скалкового сенсора.", "SCULK_SHRIEKER"));
        m.put("SMALL_FLAME", i("Малое пламя", "Мелкие огненные частицы.", "BLAZE_POWDER"));
        m.put("SMALL_GUST", i("Малый порыв", "Небольшое кольцо воздуха.", "FEATHER"));
        m.put("SMOKE", i("Дым", "Обычные частицы дыма.", "BLACK_DYE"));
        m.put("SNEEZE", i("Чих", "Частицы чиха панды.", "PANDA_SPAWN_EGG"));
        m.put("SNOWFLAKE", i("Снежинки", "Белые снежные хлопья.", "SNOWBALL"));
        m.put("SONIC_BOOM", i("Звуковой удар", "Мощная звуковая волна надзирателя.", "ECHO_SHARD"));
        m.put("SOUL", i("Души", "Синие частицы душ.", "SOUL_SAND"));
        m.put("SOUL_FIRE_FLAME", i("Пламя душ", "Синие частицы пламени душ.", "SOUL_TORCH"));
        m.put("SPIT", i("Плевок", "Мелкие летящие частицы плевка.", "LLAMA_SPAWN_EGG"));
        m.put("SPLASH", i("Брызги", "Капли воды при всплеске.", "WATER_BUCKET"));
        m.put("SPORE_BLOSSOM_AIR", i("Споры цветка", "Зелёные споры спороцвета.", "SPORE_BLOSSOM"));
        m.put("SQUID_INK", i("Чернила", "Тёмные частицы чернил спрута.", "INK_SAC"));
        m.put("SULFUR_BUBBLES", i("Серные пузырьки", "Пузырьки серы из Java 26.2.", "GLASS_BOTTLE"));
        m.put("SULFUR_CUBE_GOO", i("Серная слизь", "Серный слизистый эффект Java 26.2.", "SLIME_BALL"));
        m.put("SWEEP_ATTACK", i("Взмах оружия", "Широкий след от атаки оружием.", "IRON_SWORD"));
        m.put("TINTED_LEAVES", i("Тонированные листья", "Тонированный эффект листвы с цветом.", "TINTED_GLASS"));
        m.put("TOTEM_OF_UNDYING", i("Тотем", "Разлетающиеся частицы бессмертия.", "TOTEM_OF_UNDYING"));
        m.put("TRAIL", i("След", "Цветной след, который идёт по траектории.", "SPECTRAL_ARROW"));
        m.put("TRIAL_OMEN", i("Знак испытания", "Оменные частицы испытания.", "OMINOUS_BOTTLE"));
        m.put("TRIAL_SPAWNER_DETECTION", i("Обнаружение испытательного спавнера", "Подсветка обнаружения игрока спавнером.", "TRIAL_SPAWNER"));
        m.put("TRIAL_SPAWNER_DETECTION_OMINOUS", i("Зловещее обнаружение спавнера", "Оменный вариант обнаружения игрока.", "TRIAL_SPAWNER"));
        m.put("UNDERWATER", i("Подводные пузырьки", "Мелкие пузырьки под водой.", "PUFFERFISH_BUCKET"));
        m.put("VAULT_CONNECTION", i("Связь с хранилищем", "Частицы связи игрока с vault.", "VAULT"));
        m.put("VIBRATION", i("Вибрация", "Частица вибрации, идущая к цели.", "SCULK_SENSOR"));
        m.put("WARPED_SPORE", i("Искажённые споры", "Споры искажённого леса.", "WARPED_FUNGUS"));
        m.put("WAX_OFF", i("Воск снят", "Частицы при снятии воска со свечи.", "HONEYCOMB"));
        m.put("WAX_ON", i("Воск нанесён", "Частицы при нанесении воска.", "HONEYCOMB"));
        m.put("WHITE_ASH", i("Белый пепел", "Светлый пепел частиц.", "LIGHT_GRAY_DYE"));
        m.put("WHITE_SMOKE", i("Белый дым", "Светлый дымок.", "WHITE_DYE"));
        m.put("WITCH", i("Магия ведьмы", "Фиолетовые магические искры.", "CAULDRON"));
        return Collections.unmodifiableMap(m);
    }
}

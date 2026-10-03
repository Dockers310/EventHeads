
package ru.doksi.eventheads.localization;

// RU: Автоматический выбор языка по локали клиента Minecraft: RU/EN/DE/UK/ES/KK/FR/PL.
// EN: Automatically selects the GUI/system language from the Minecraft client locale.

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.catalog.ParticleCatalog;
import ru.doksi.eventheads.commands.Commands;
import ru.doksi.eventheads.roles.Role;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.text.DecimalFormat;
import java.util.Locale;

public final class LocaleManager {
    private final EventHeadsPlugin plugin;
    private YamlConfiguration ru,en,de,uk,es,kk,fr,pl;
    /**
     * RU: точний словник перекладу для GUI/чату (перекладено вручну), ru-текст -> {en,de,fr,es,pl,uk,kk}.
     * EN: curated exact-match GUI/chat translation dictionary, ru text -> {en,de,fr,es,pl,uk,kk}.
     * Loaded once from gui_translations.tsv; used before any word-substitution fallback so full
     * phrases translate correctly instead of being rebuilt word by word.
     */
    private java.util.Map<String,java.util.Map<String,String>> guiExactMap=java.util.Collections.emptyMap();
    public LocaleManager(EventHeadsPlugin plugin){this.plugin=plugin; reload();}
    public void reload(){
        ru=load("messages_ru.yml"); en=load("messages_en.yml"); de=load("messages_de.yml"); uk=load("messages_uk.yml");
        es=load("messages_es.yml"); kk=load("messages_kk.yml"); fr=load("messages_fr.yml"); pl=load("messages_pl.yml");
        loadGuiExactMap();
    }

    /** Loads the curated ru -> {en,de,fr,es,pl,uk,kk} phrase dictionary bundled as a plugin resource. */
    private void loadGuiExactMap(){
        java.util.Map<String,java.util.Map<String,String>> m=new java.util.HashMap<>();
        String[] langs={"en","de","fr","es","pl","uk","kk"};
        try(java.io.InputStream in=plugin.getResource("gui_translations.tsv")){
            if(in==null){plugin.getLogger().warning("gui_translations.tsv not found in plugin resources; GUI translations will fall back to word substitution.");guiExactMap=m;return;}
            java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(in,StandardCharsets.UTF_8));
            String line;
            while((line=r.readLine())!=null){
                if(line.isEmpty())continue;
                String[] cols=line.split("\t",-1);
                if(cols.length<8)continue;
                String ruKey=unescapeTsv(cols[0]).trim();
                if(ruKey.isEmpty())continue;
                java.util.Map<String,String> byLang=new java.util.HashMap<>();
                for(int i=0;i<langs.length;i++) byLang.put(langs[i],unescapeTsv(cols[i+1]));
                m.put(ruKey,byLang);
            }
        }catch(Exception ex){
            plugin.getLogger().warning("Failed to load gui_translations.tsv: "+ex.getMessage());
        }
        guiExactMap=m;
    }
    private String unescapeTsv(String s){return s.replace("\\n","\n").replace("\\t","\t").replace("\\\\","\\");}

    /**
     * Splits text on colour codes, newlines, %-style format specifiers and {placeholder} tokens
     * (mirroring how the source phrases were extracted), looks each trimmed fragment up in the
     * curated dictionary for this language family, and reassembles the string with the original
     * delimiters and whitespace preserved. Fragments with no exact match are left untouched so the
     * existing word-substitution fallback can still attempt them afterwards.
     */
    private String applyGuiExactMap(String text,String family){
        if(text==null||text.isEmpty()||guiExactMap.isEmpty())return text;
        java.util.regex.Pattern delim=java.util.regex.Pattern.compile(
                "(?:[\u00a7&][0-9a-fk-orA-FK-OR])+|\\n|%[-#+ 0,(]*\\d*(?:\\.\\d+)?[sdfn]|\\{[a-zA-Z_]+\\}");
        java.util.regex.Matcher matcher=delim.matcher(text);
        StringBuilder out=new StringBuilder();
        int last=0;
        while(matcher.find()){
            out.append(translateFragment(text.substring(last,matcher.start()),family));
            out.append(matcher.group());
            last=matcher.end();
        }
        out.append(translateFragment(text.substring(last),family));
        return out.toString();
    }
    private String translateFragment(String fragment,String family){
        if(fragment.isEmpty())return fragment;
        String trimmed=fragment.trim();
        if(trimmed.isEmpty())return fragment;
        java.util.Map<String,String> byLang=guiExactMap.get(trimmed);
        if(byLang==null)return fragment;
        String translated=byLang.get(family);
        if(translated==null||translated.isEmpty())return fragment;
        int leadLen=fragment.indexOf(trimmed);
        String lead=leadLen>0?fragment.substring(0,leadLen):"";
        String trail=fragment.substring(leadLen+trimmed.length());
        return lead+translated+trail;
    }
    private YamlConfiguration load(String n){
        File f=new File(plugin.getDataFolder(),n);
        if(!f.exists()) plugin.saveResource(n,false);
        repairKnownDuplicateKeys(f);
        return YamlConfiguration.loadConfiguration(f);
    }
    /**
     * RU: убирает ЛЮБЫЕ повторяющиеся ключи в уже существующем файле локализации,
     * сохраняя ПОСЛЕДНЕЕ вхождение (именно его использует SnakeYAML).
     * EN: removes any duplicate keys from an existing locale file, keeping the last occurrence.
     */
    private void repairKnownDuplicateKeys(File f){
        try{
            if(!f.exists())return;
            List<String> lines=new ArrayList<>(Files.readAllLines(f.toPath(),StandardCharsets.UTF_8));
            List<String> pathStack=new ArrayList<>();
            List<Integer> indentStack=new ArrayList<>();
            java.util.Map<String,int[]> seen=new java.util.HashMap<>();
            java.util.List<Integer> duplicateStarts=new ArrayList<>();
            java.util.List<Integer> duplicateIndents=new ArrayList<>();
            java.util.Set<String> duplicateNames=new java.util.LinkedHashSet<>();
            int blockIndent=-1;
            for(int i=0;i<lines.size();i++){
                String line=lines.get(i);
                String trimmed=line.trim();
                if(trimmed.isEmpty()||trimmed.startsWith("#")||trimmed.startsWith("-"))continue;
                int rawIndent=line.length()-line.stripLeading().length();
                if(blockIndent>=0){
                    if(rawIndent>blockIndent)continue;
                    blockIndent=-1;
                }
                int colon=indexOfKeyColon(trimmed);
                if(colon<0)continue;
                int indent=rawIndent;
                String value=trimmed.substring(colon+1).trim();
                if(value.matches("^[|>][-+0-9]*$"))blockIndent=indent;
                while(!indentStack.isEmpty()&&indentStack.get(indentStack.size()-1)>=indent){
                    indentStack.remove(indentStack.size()-1);
                    pathStack.remove(pathStack.size()-1);
                }
                String name=trimmed.substring(0,colon).trim();
                String path=String.join(".",pathStack)+(pathStack.isEmpty()?"":".")+name;
                // SnakeYAML оставляет ПОСЛЕДНЕЕ вхождение, поэтому удаляем более раннее:
                // фактическое поведение плагина после ремонта не меняется.
                int[] previous=seen.put(path,new int[]{i,indent});
                if(previous!=null){
                    duplicateStarts.add(previous[0]);
                    duplicateIndents.add(previous[1]);
                    duplicateNames.add(path);
                }
                indentStack.add(indent);
                pathStack.add(name);
            }
            if(duplicateStarts.isEmpty())return;
            File backup=new File(f.getParentFile(),f.getName()+".duplicate-backup");
            Files.copy(f.toPath(),backup.toPath(),StandardCopyOption.REPLACE_EXISTING);
            boolean[] drop=new boolean[lines.size()];
            for(int d=0;d<duplicateStarts.size();d++){
                int startLine=duplicateStarts.get(d);
                int indent=duplicateIndents.get(d);
                drop[startLine]=true;
                for(int i=startLine+1;i<lines.size();i++){
                    String line=lines.get(i);
                    if(line.trim().isEmpty()){continue;}
                    int cur=line.length()-line.stripLeading().length();
                    if(cur<=indent)break;
                    drop[i]=true;
                }
            }
            List<String> out=new ArrayList<>();
            for(int i=0;i<lines.size();i++) if(!drop[i]) out.add(lines.get(i));
            Files.write(f.toPath(),out,StandardCharsets.UTF_8);
            plugin.getLogger().info("В "+f.getName()+" найдены и удалены дублирующиеся ключи: "+duplicateNames
                    +"; резервная копия: "+backup.getName());
        }catch(Exception ex){plugin.getLogger().warning("Не удалось автоматически исправить дубликаты в "+f.getName()+": "+ex.getMessage());}
    }

    /** RU: позиция двоеточия ключа вне кавычек, иначе -1. */
    private int indexOfKeyColon(String trimmed){
        boolean single=false,doubleQ=false;
        for(int i=0;i<trimmed.length();i++){
            char c=trimmed.charAt(i);
            if(c=='\''&&!doubleQ)single=!single;
            else if(c=='"'&&!single)doubleQ=!doubleQ;
            else if(c==':'&&!single&&!doubleQ){
                if(i+1>=trimmed.length()||trimmed.charAt(i+1)==' '||i+1==trimmed.length())return i;
                return i;
            }
        }
        return -1;
    }

    public String tr(Player p,String key){
        String locale=p.getLocale().toLowerCase(Locale.ROOT);
        String family=clientFamily(p);
        boolean client=plugin.getConfig().getBoolean("language.client",true);
        String fallback=plugin.getConfig().getString("language.fallback","ru").toLowerCase(Locale.ROOT);
        YamlConfiguration primary=client?languageFor(locale):languageFor(fallback);
        YamlConfiguration secondary=languageFor(fallback);
        String s=primary.getString(key,null);
        if(s==null)s=builtinText(family,key);
        if(s==null)s=en.getString(key,null);
        if(s==null)s=secondary.getString(key,null);
        if(s==null)s=ru.getString(key,null);
        if(s==null)s=key;
        if("point-mode".equals(key)||"point-mode-cancelled".equals(key)) s=s.replace("/event point", "/eventheads point");
        return ChatColorUtil.color(s);
    }
    public String tr(Player p,String key,String k,Object v){return tr(p,key).replace("{"+k+"}",String.valueOf(v));}
    public String msg(Player p,String key){return tr(p,key);}
    private YamlConfiguration languageFor(String locale){
        String l=locale==null?"ru":locale.toLowerCase(Locale.ROOT);
        if(l.startsWith("en"))return en; if(l.startsWith("de"))return de; if(l.startsWith("uk"))return uk; if(l.startsWith("es"))return es;
        if(l.startsWith("kk")||l.startsWith("kz"))return kk; if(l.startsWith("fr"))return fr; if(l.startsWith("pl"))return pl;
        return ru;
    }

    /** Language is selected from the Minecraft client locale. */
    public boolean isEnglish(Player p){ return "en".equals(clientFamily(p)); }

    private String builtinText(String family,String key){
        if("economy-received".equals(key)){
            return switch(family){case "de"->"&a+{amount}₮ gutgeschrieben. &7Kontostand: &f{balance}₮.";case "uk"->"&a+{amount}₮ зараховано. &7Баланс: &f{balance}₮.";case "es"->"&a+{amount}₮ acreditado. &7Saldo: &f{balance}₮.";case "kk"->"&a+{amount}₮ есепке түсті. &7Баланс: &f{balance}₮.";case "fr"->"&a+{amount}₮ crédité. &7Solde : &f{balance}₮.";case "pl"->"&a+{amount}₮ dodano. &7Saldo: &f{balance}₮.";case "en"->"&a+{amount}₮ credited. &7Balance: &f{balance}₮.";default->"&a+{amount}₮ зачислено. &7Баланс: &f{balance}₮.";};
        }
        if("economy-spent".equals(key)){
            return switch(family){case "de"->"&c-{amount}₮ abgebucht. &7Kontostand: &f{balance}₮.";case "uk"->"&c-{amount}₮ списано. &7Баланс: &f{balance}₮.";case "es"->"&c-{amount}₮ descontado. &7Saldo: &f{balance}₮.";case "kk"->"&c-{amount}₮ есептен алынды. &7Баланс: &f{balance}₮.";case "fr"->"&c-{amount}₮ débité. &7Solde : &f{balance}₮.";case "pl"->"&c-{amount}₮ pobrano. &7Saldo: &f{balance}₮.";case "en"->"&c-{amount}₮ charged. &7Balance: &f{balance}₮.";default->"&c-{amount}₮ списано. &7Баланс: &f{balance}₮.";};
        }
        if("economy-failed".equals(key)){
            return switch(family){case "de"->"&cKontostand konnte nicht geändert werden: nicht genug Geld oder Wirtschaft nicht verfügbar.";case "uk"->"&cНе вдалося змінити баланс: недостатньо коштів або економіка недоступна.";case "es"->"&cNo se pudo cambiar el saldo: fondos insuficientes o economía no disponible.";case "kk"->"&cБаланс өзгертілмеді: қаражат жеткіліксіз немесе экономика қолжетімсіз.";case "fr"->"&cImpossible de modifier le solde : fonds insuffisants ou économie indisponible.";case "pl"->"&cNie można zmienić salda: brak środków lub ekonomia niedostępna.";case "en"->"&cCould not change your balance: insufficient funds or economy unavailable.";default->"&cНе удалось изменить баланс: недостаточно средств или экономика недоступна.";};
        }
        if("economy-balance-unknown".equals(key)){
            return switch(family){case "de"->"&cKontostand nicht verfügbar.";case "uk"->"&cБаланс недоступний.";case "es"->"&cSaldo no disponible.";case "kk"->"&cБаланс қолжетімсіз.";case "fr"->"&cSolde indisponible.";case "pl"->"&cSaldo niedostępne.";case "en"->"&cBalance unavailable.";default->"&cБаланс недоступен.";};
        }
        return null;
    }

    /** Localized particle title/description. Non-RU locales never fall back to a Russian GUI phrase. */
    public String particleTitle(Player p,String type){
        String ru=ParticleCatalog.title(type);
        if(p==null||"ru".equals(clientFamily(p))) return ru;
        String family=clientFamily(p);
        String translated=translateGui(ru,family);
        if(containsCyrillic(translated)) {
            String enTitle=englishParticleTitle(type);
            String localizedTitle=applyWordMap(enTitle, englishFamilyFallback(family));
            return containsCyrillic(localizedTitle)?enTitle:localizedTitle;
        }
        return translated;
    }
    public String particleDescription(Player p,String type){
        String ru=ParticleCatalog.description(type);
        if(p==null||"ru".equals(clientFamily(p))) return ru;
        String family=clientFamily(p);
        String translated=translateGui(ru,family);
        if(containsCyrillic(translated)) return switch(family){
            case "de" -> "Visueller Partikeleffekt: "+particleTitle(p,type)+".";
            case "uk" -> "Візуальний ефект частинки: "+particleTitle(p,type)+".";
            case "es" -> "Efecto visual de partícula: "+particleTitle(p,type)+".";
            case "kk" -> "Көрнекі бөлшек әсері: "+particleTitle(p,type)+".";
            case "fr" -> "Effet visuel de particule : "+particleTitle(p,type)+".";
            case "pl" -> "Wizualny efekt cząsteczki: "+particleTitle(p,type)+".";
            default -> "Visual particle effect: "+particleTitle(p,type)+".";
        };
        return translated;
    }
    private boolean containsCyrillic(String value){return value!=null && value.matches(".*[А-Яа-яЁё].*");}
    private String englishParticleTitle(String type){
        if(type==null||type.isBlank()) return "Particle";
        String s=type.toLowerCase(Locale.ROOT).replace('_',' ');
        return Character.toUpperCase(s.charAt(0))+s.substring(1);
    }

    /**
     * GUI localization for hard-coded legacy labels.  Existing message files are still
     * authoritative for chat/config strings; this layer translates the legacy GUI labels
     * so changing the Minecraft client language actually changes the visible menus too.
     */
    /** Sends a player-facing message through the same localization pipeline as GUI text. */
    public void send(Player p,String text){
        if(p==null)return;
        p.sendMessage(ChatColorUtil.color(gui(p,text)));
    }

    /** Localizes an item that is about to be given to a player. */
    public ItemStack localizeItem(Player p, ItemStack stack){
        if(stack==null||p==null||!plugin.getConfig().getBoolean("language.client",true))return stack;
        ItemMeta meta=stack.getItemMeta();
        if(meta==null)return stack;
        String family=clientFamily(p);
        if(meta.hasDisplayName()) meta.setDisplayName(guiColored(meta.getDisplayName(),family));
        if(meta.hasLore() && meta.getLore()!=null){
            java.util.List<String> lore=new java.util.ArrayList<>();
            for(String line:meta.getLore()) lore.add(guiColored(line,family));
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public String gui(Player p,String text){
        if(text==null||text.isEmpty())return text;
        if(!plugin.getConfig().getBoolean("language.client",true))return text;
        String family=clientFamily(p);
        return translateGui(text,family);
    }

    public void localizeInventory(Player p,org.bukkit.inventory.Inventory inv){
        if(!plugin.getConfig().getBoolean("language.client",true)||inv==null)return;
        for(org.bukkit.inventory.ItemStack stack:inv.getContents()){
            if(stack==null||!stack.hasItemMeta()||stack.getItemMeta()==null)continue;
            org.bukkit.inventory.meta.ItemMeta meta=stack.getItemMeta();
            if(meta.hasDisplayName()) meta.setDisplayName(guiColored(meta.getDisplayName(),clientFamily(p)));
            if(meta.hasLore()&&meta.getLore()!=null){
                java.util.List<String> lore=new java.util.ArrayList<>();
                for(String line:meta.getLore()) lore.add(guiColored(line,clientFamily(p)));
                meta.setLore(lore);
            }
            stack.setItemMeta(meta);
        }
    }

    private String guiColored(String colored,String family){
        if(colored==null)return null;
        return translateGui(colored,family);
    }

    private String clientFamily(Player p){
        if(p==null||!plugin.getConfig().getBoolean("language.client",true))return plugin.getConfig().getString("language.fallback","ru").toLowerCase(Locale.ROOT);
        String l=p.getLocale();
        if(l==null||l.isBlank())return plugin.getConfig().getString("language.fallback","ru").toLowerCase(Locale.ROOT);
        l=l.toLowerCase(Locale.ROOT).replace('-','_');
        // Minecraft has many regional/fun locales.  The plugin follows the base language
        // where a dedicated translation exists, otherwise English is the neutral fallback.
        if(l.startsWith("ru"))return "ru";
        if(l.startsWith("en"))return "en";
        if(l.startsWith("de"))return "de";
        if(l.startsWith("uk"))return "uk";
        if(l.startsWith("es"))return "es";
        if(l.startsWith("kk")||l.startsWith("kz"))return "kk";
        if(l.startsWith("fr"))return "fr";
        if(l.startsWith("pl"))return "pl";
        return "en";
    }

    /**\n     * Second-pass localization for legacy hard-coded strings. The plugin historically\n     * contained many short Russian fragments (especially lore, chat prompts and\n     * statistics). Exact phrase maps above handle the important UI phrases; this\n     * fallback translates the remaining common words so non-RU clients do not fall\n     * back to Russian.\n     */
    /**
     * Safe fallback for legacy Russian GUI text. It never leaves a partially translated
     * word behind: replacements are case-insensitive and match complete words/phrases only.
     */
    private String translateRussianFallback(String text,String family){
        if(text==null||text.isEmpty()||"ru".equals(family)) return text;
        String out=applyWordMap(text,residualLexicon(family));
        if(containsCyrillic(out)) out=applyWordMap(out,ruEnglishFallback());
        out=applyWordMap(out,englishFamilyFallback(family));
        return out;
    }

    private String applyWordMap(String text,java.util.Map<String,String> map){
        if(text==null||map==null||map.isEmpty())return text;
        String out=text;
        java.util.List<java.util.Map.Entry<String,String>> entries=new java.util.ArrayList<>(map.entrySet());
        entries.sort((a,b)->Integer.compare(b.getKey().length(),a.getKey().length()));
        for(var e:entries){
            String key=e.getKey(), replacement=e.getValue();
            if(key==null||key.isBlank()||replacement==null)continue;
            // RU: Границу слова определяем по алфавиту первого символа ключа (кириллица/не кириллица),
            // а не по общей категории "буква" — иначе Minecraft-код цвета без пробела перед текстом
            // (например "&aЛКМ" или "&7Вернуться") склеивается с русским словом и оно никогда не
            // находит совпадение. Для кириллических ключей в класс границы включена ТОЛЬКО кириллица:
            // код цвета — это либо латинская буква (a-f), либо цифра (0-9), и оба варианта должны
            // свободно граничить с русским словом.
            // EN: Boundary class follows the key's own alphabet (Cyrillic vs. non-Cyrillic) instead of
            // the generic \p{L} letter category — otherwise a Minecraft color code glued directly to the
            // text (e.g. "&aЛКМ" or "&7Вернуться", no space) merges with the Cyrillic word and the key
            // never matches. For Cyrillic keys the boundary class is Cyrillic ONLY: a color code is
            // either a Latin letter (a-f) or a digit (0-9), and both must freely border a Russian word.
            boolean cyrillicKey=Character.UnicodeScript.of(key.codePointAt(0))==Character.UnicodeScript.CYRILLIC;
            String boundaryClass=cyrillicKey?"\\p{IsCyrillic}":"\\p{L}\\p{N}_";
            String regex="(?iu)(?<!["+boundaryClass+"])"+java.util.regex.Pattern.quote(key)+"(?!["+boundaryClass+"])";
            java.util.regex.Pattern pattern=java.util.regex.Pattern.compile(regex);
            java.util.regex.Matcher matcher=pattern.matcher(out);
            StringBuffer sb=new StringBuffer();
            while(matcher.find()){
                String source=matcher.group();
                String value=replacement;
                if(source.equals(source.toUpperCase(java.util.Locale.ROOT))) value=value.toUpperCase(java.util.Locale.ROOT);
                else if(!source.isEmpty()&&Character.isUpperCase(source.codePointAt(0))) value=Character.toUpperCase(value.charAt(0))+value.substring(1);
                matcher.appendReplacement(sb,java.util.regex.Matcher.quoteReplacement(value));
            }
            matcher.appendTail(sb);
            out=sb.toString();
        }
        return out;
    }

    private java.util.Map<String,String> ruEnglishFallback(){
        java.util.Map<String,String> m=new java.util.LinkedHashMap<>();
        String[][] r={
            {"для","for"},{"без","without"},{"через","through"},{"изменил","changed"},{"изменить","edit"},{"изменено","changed"},{"изменение","change"},{"изменения","changes"},{"нет","none"},{"есть","there is"},{"шаблона","template"},{"шаблон","template"},{"шаблоны","templates"},{"ограничения","restrictions"},{"ограничение","restriction"},{"монет","coins"},{"минимальная","minimum"},{"минимальное","minimum"},{"минимальный","minimum"},{"максимальная","maximum"},{"максимальное","maximum"},{"максимальный","maximum"},{"как","how"},{"открывает","opens"},{"получить","get"},{"получите","get"},{"получил","received"},{"получает","receives"},{"или","or"},{"этого","this"},{"сбора","collection"},{"собрать","collect"},{"собирает","collects"},{"переключить","toggle"},{"переключение","toggle"},{"мои","my"},{"несколько","multiple"},{"сотрудника","staff member"},{"сотруднику","staff member"},{"при","when"},{"начала","start"},{"окончания","end"},{"удалил","deleted"},{"удалить","delete"},{"удалена","deleted"},{"удалено","deleted"},{"удалены","deleted"},{"ивентов","events"},{"ивента","event"},{"ивенты","events"},{"ивент","event"},{"настроить","configure"},{"настройка","setting"},{"настройки","settings"},{"ещё","more"},{"цветов","colors"},{"цвета","colors"},{"сразу","immediately"},{"после","after"},{"прав","permissions"},{"право","permission"},{"создал","created"},{"создать","create"},{"создан","created"},{"создана","created"},{"создано","created"},{"дата","date"},{"центра","center"},{"это","this"},{"управления","management"},{"управление","management"},{"началось","started"},{"всех","all"},{"пока","yet"},{"выберите","select"},{"выбрать","select"},{"показывает","shows"},{"показать","show"},{"отключить","disable"},{"включить","enable"},{"типов","types"},{"типы","types"},{"тип","type"},{"дистанция","distance"},{"дистанции","distance"},{"расстояние","distance"},{"например","for example"},{"ввести","enter"},{"убрать","remove"},{"частицу","particle"},{"частиц","particles"},{"частицы","particles"},{"вернуть","return"},{"предметы","items"},{"предмета","item"},{"предмет","item"},{"инвентаре","inventory"},{"инвентаря","inventory"},{"данных","data"},{"данные","data"},{"удалять","delete"},{"команды","commands"},{"команда","command"},{"статус","status"},{"всего","total"},{"редактор","editor"},{"редактора","editor"},{"один","one"},{"одного","one"},{"удаляет","deletes"},{"сотрудников","staff"},{"здесь","here"},{"использовать","use"},{"блокировки","blocks"},{"общий","global"},{"общая","global"},{"показать","show"},{"администратора","administrator"},{"администратор","administrator"},{"регионы","regions"},{"регион","region"},{"списку","list"},{"списка","list"},{"используется","used"},{"активных","active"},{"регионе","region"},{"чтобы","so that"},{"задана","set"},{"задано","set"},{"отказе","refusal"},{"отказа","refusal"},{"ничего","nothing"},{"звуки","sounds"},{"звука","sound"},{"добавил","added"},{"добавить","add"},{"сбросил","reset"},{"сбросить","reset"},{"список","list"},{"окончание","end"},{"встроенной","built-in"},{"встроенная","built-in"},{"встроенные","built-in"},{"полностью","completely"},{"экран","screen"},{"создание","creation"},{"внутренние","internal"},{"диагностика","diagnostics"},{"приманки","decoys"},{"приманка","decoy"},{"что","what"},{"основные","main"},{"полная","full"},{"полный","full"},{"топ","top"},{"срабатываний","triggers"},{"срабатывание","trigger"},{"разделены","separated"},{"появляются","appear"},{"создайте","create"},{"создаёт","creates"},{"создавать","create"},{"новый","new"},{"может","can"},{"событиям","events"},{"события","events"},{"событие","event"},{"если","if"},{"область","area"},{"статистику","statistics"},{"статистика","statistics"},{"искать","search"},{"конструктор","builder"},{"видит","sees"},{"внешний","external"},{"внешний вид","appearance"},{"вид","appearance"},{"диапазон","range"},{"автоматический","automatic"},{"этот","this"},{"страницу","page"},{"страницы","pages"},{"параметры","parameters"},{"настройку","setting"},{"обзор","overview"},{"спавнить","spawn"},{"спавн","spawn"},{"игроками","players"},{"игроков","players"},{"секунд","seconds"},{"новые","new"},{"применяются","applied"},{"плавающий","floating"},{"плавающего","floating"},{"твёрдая","solid"},{"твердая","solid"},{"опора","support"},{"сумма","amount"},{"отрицательное","negative"},{"выбор","selection"},{"проиграть","play"},{"маленькая","small"},{"стойка","stand"},{"стойки","stands"},{"поворот","rotation"},{"ограничение","limit"},{"снять","remove"},{"умолчанию","default"},{"нот","notes"},{"ноты","notes"},{"палитру","palette"},{"палитра","palette"},{"каталоге","catalog"},{"каталог","catalog"},{"максимум","maximum"},{"будет","will"},{"включил","enabled"},{"чёрного","black"},{"чёрный","black"},{"запустил","started"},{"точка","point"},{"точки","points"},{"этой","this"},{"начало","start"},{"мест","locations"},{"место","location"},{"редактировать","edit"},{"ключ","key"},{"ввод","input"},{"просматривать","view"},{"просмотр","view"},{"удаление","deletion"},{"собственные","custom"},{"свои","own"},{"уведомления","notifications"},{"всегда","always"},{"доступны","available"},{"операции","operations"},{"состояния","state"},{"анти","anti"},{"анти-esp","Anti-ESP"},{"открывается","opens"},{"конфигурацию","configuration"},{"справка","help"},{"короткая","short"},{"работать","work"},{"справку","help"},{"когда","when"},{"ложные","fake"},{"закопанные","buried"},{"наземные","ground"},{"работают","work"},{"голова","head"},{"готовый","ready"},{"откройте","open"},{"добавьте","add"},{"привязать","bind"},{"нескольким","multiple"},{"нескольких","multiple"},{"отказа","refusal"},{"файл","file"},{"полезно","useful"},{"выбранного","selected"},{"одна","one"},{"шкурка","leather"},{"шкурке","leather"},{"одной","one"},{"двойного","double"},{"подтверждения","confirmation"},{"выполняется","executed"},{"выдачи","giving"},{"расписания","schedule"},{"данные","data"},{"ручной","manual"},{"прямо","directly"},{"источник","source"},{"высота","height"},{"дублировать","duplicate"},{"раздел","section"},{"выполнить","execute"},{"изменения","changes"},{"экспортировать","export"},{"быстрее","faster"},{"забирается","collected"},{"раз","time"},{"положительное","positive"},{"число","number"},{"выдать","give"},{"списать","charge"},{"списание","charge"},{"вами","you"},{"индивидуальные","individual"},{"между","between"},{"мира","world"},{"ночь","night"},{"штраф","penalty"},{"занимают","occupy"},{"слоты","slots"},{"строк","rows"},{"строки","rows"},{"записей","entries"},{"инструменты","tools"},{"распределение","distribution"},{"базовых","basic"},{"красителей","dyes"},{"списке","list"},{"быстрое","quick"},{"включение","enablement"},{"ниже","below"},{"кнопка","button"},{"минимальное","minimum"},{"запятую","comma"},{"задать","set"},{"хранится","stored"},{"индивидуальное","individual"},{"открыл","opened"},{"очистил","cleared"},{"регионов","regions"},{"сохранил","saved"},{"выделение","selection"},{"получил","got"},{"дополнительных","additional"},{"разрешение","permission"},{"выбранными","selected"},{"каждой","each"},{"прослушать","preview"},{"команду","command"},{"собрано","collected"},{"идёт","active"},{"основное","main"},{"ролей","roles"},{"назначить","assign"},{"кликами","clicks"},{"иконка","icon"},{"временный","temporary"},{"задаётся","set"},{"обновляет","updates"},{"заново","again"},{"запуска","launch"},{"задан","set"},{"создаваться","created"},{"управлять","manage"},{"выдавать","grant"},{"получать","receive"},{"получит","will receive"},{"важный","important"},{"принцип","principle"},{"каждый","each"},{"имеет","has"},{"собственный","own"},{"даёт","gives"},{"кнопке","button"},{"раздела","section"},{"случайно","randomly"},{"интерактивные","interactive"},{"помечаются","marked"},{"поэтому","therefore"},{"обычные","normal"},{"никогда","never"},{"воспринимаются","treated"},{"нашего","our"},{"системные","system"},{"принудительное","forced"},{"снятие","removal"},{"клетки","cells"},{"столбцов","columns"},{"строк","rows"},{"край","edge"},{"остаётся","remains"},{"рамкой","border"},{"предыдущий","previous"},{"доступных","available"},{"точками","points"},{"сборы","collections"},{"каждому","each"},{"отображается","displayed"},{"сам","itself"},{"назначение","assignment"},{"менять","change"},{"глобальные","global"},{"менял","changed"},{"пасха","Easter"},{"зима","winter"},{"быстро","quickly"},{"готовую","ready"},{"пошаговая","step-by-step"},{"инструкция","guide"},{"модератора","moderator"},{"закончится","ends"},{"повторного","repeat"},{"появления","appearance"},{"чаще","more often"},{"взаимодействовал","interacted"},{"ловушками","traps"},{"срабатывания","triggers"},{"вокруг","around"},{"настоящего","real"},{"позиции","positions"},{"случайные","random"},{"меняются","change"},{"цикла","cycle"},{"циклу","cycle"},{"наклонена","tilted"},{"вниз","down"},{"воздушные","air"},{"срабатывали","triggered"},{"ловушках","traps"},{"награду","reward"},{"укажите","specify"},{"экономики","economy"},{"экономический","economic"},{"плагин","plugin"},{"анимацию","animation"},{"выбирается","selected"},{"кликом","click"},{"выбирайте","choose"},{"задавайте","set"},{"одной","one"},{"лапка","paw"},{"умеет","can"},{"блокировать","block"},{"проверьте","check"},{"покажет","shows"},{"причину","reason"},{"сохранение","saving"},{"перенос","transfer"},{"экспортируйте","export"},{"другом","other"},{"сервере","server"},{"импортируйте","import"},{"тот","that"},{"подробную","detailed"},{"любого","any"},{"плагином","plugin"},{"созданных","created"},{"включает","includes"},{"быть","be"},{"привязана","bound"},{"поставит","place"},{"двум","two"},{"номеру","number"},{"выключает","disables"},{"становится","becomes"},{"обычным","normal"},{"она","it"},{"защищена","protected"},{"допустимую","allowed"},{"приват","private"},{"проверяет","checks"},{"особенно","especially"},{"конкретного","specific"},{"показывается","shown"},{"самого","itself"},{"существующую","existing"},{"игроку","player"},{"нужен","needed"},{"вручную","manually"},{"сложного","complex"},{"набора","set"},{"удобнее","more convenient"},{"попадает","goes"},{"импортирует","imports"},{"ранее","previously"},{"экспортированный","exported"},{"переноса","transfer"},{"другой","another"},{"сервер","server"},{"его","its"},{"интеграций","integrations"},{"специально","specifically"},{"выводит","outputs"},{"плагинов","plugins"},{"сервера","server"},{"количеству","amount"},{"личном","personal"},{"сообщении","message"},{"счётчик","counter"},{"последнюю","last"},{"скрывает","hides"},{"визуальные","visual"},{"себя","itself"},{"эти","these"},{"участника","member"},{"повторно","again"},{"перед","before"},{"удалением","deletion"},{"доступа","access"},{"редактору","editor"},{"правом","permission"},{"перечитывает","reloads"},{"правки","edits"},{"делает","makes"},{"доступные","available"},{"скрыто","hidden"},{"недоступна","unavailable"},{"исходная","original"},{"затем","then"},{"нужному","needed"},{"предмету","item"},{"копируется","copied"},{"подключается","connected"},{"экипировка","equipment"},{"копию","copy"},{"настроек","settings"},{"копии","copy"},{"временно","temporarily"},{"отключён","disabled"},{"поддерживаются","supported"},{"активный","active"},{"разрешён","allowed"},{"запрещён","forbidden"},{"проверить","check"},{"текущие","current"},{"записать","write"},{"ивентам","events"},{"именно","exactly"},{"безопасности","security"},{"подтверждается","confirmed"},{"одну","one"},{"логическую","logical"},{"разделы","sections"},{"намеренно","intentionally"},{"модератор","moderator"},{"относящиеся","related"},{"задаче","task"},{"находит","finds"},{"нужную","needed"},{"общую","global"},{"исходный","source"},{"подсказка","hint"},{"вашего","your"},{"меньше","less"},{"больше","more"},{"медленнее","slower"},{"интервал","interval"},{"активным","active"},{"изменённые","changed"},{"обычно","usually"},{"новым","new"},{"спавнам","spawns"},{"немедленно","immediately"},{"пересчитать","recalculate"},{"существующих","existing"},{"ищет","searches"},{"автоматически","automatically"},{"сохранённые","saved"},{"списывается","charged"},{"невозможно","impossible"},{"засчитывается","counted"},{"используются","used"},{"запустить","launch"},{"распределяются","distributed"},{"частицами","particles"},{"значения","values"},{"глобальным","global"},{"строка","row"},{"дату","date"},{"любой","any"},{"сменить","change"},{"тиков","ticks"},{"поставить","place"},{"погоду","weather"},{"наденьте","wear"},{"собирать","collect"},{"невыполненном","unmet"},{"условии","condition"},{"семь","seven"},{"инструментов","tools"},{"находятся","are located"},{"верхней","top"},{"строке","row"},{"четырёх","four"},{"создаётся","created"},{"динамически","dynamically"},{"фактического","actual"},{"количества","amount"},{"страниц","pages"},{"каталога","catalog"},{"текущего","current"},{"числа","number"},{"полный","full"},{"одновременно","simultaneously"},{"включёнными","enabled"},{"эффектами","effects"},{"верхняя","top"},{"линия","line"},{"выбрана","selected"},{"слот","slot"},{"хоть","at least"},{"общее","total"},{"тик","tick"},{"типах","types"},{"делится","divided"},{"ними","them"},{"цветная","colored"},{"пыль","dust"},{"поиска","search"},{"переход","transition"},{"включённые","enabled"},{"общем","general"},{"приблизительными","approximate"},{"значениями","values"},{"вики","wiki"},{"пурпурный","purple"},{"рабочая","working"},{"раньше","previously"},{"открывала","opened"},{"пасхальный","Easter"},{"готовая","ready"},{"зимний","winter"},{"снег","snow"},{"зимы","winter"},{"ярмарки","fair"},{"предметов","items"},{"условие","condition"},{"экипировки","equipment"},{"пустой","empty"},{"можете","can"},{"выполните","perform"},{"требования","requirements"},{"дополнительные","additional"},{"создаваться","be created"},{"звуком","sound"},{"шаблону","template"},{"журнала","log"},{"сделал","did"},{"подробности","details"},{"фильтра","filter"},{"назначил","assigned"},{"сотруднику","staff member"},{"скопировал","copied"},{"региона","region"},{"отключил","disabled"},{"основной","main"},{"других","other"},{"воды","water"},{"лавы","lava"},{"произвольный","custom"},{"отдельный","separate"},{"типами","types"},{"листать","page through"},{"весь","all"},{"включите","enable"},{"хотя","at least"},{"лимиту","limit"},{"наследуется","inherited"}};
        for(String[] x:r)m.put(x[0],x[1]);
        return m;
    }

    private java.util.Map<String,String> englishFamilyFallback(String family){
        java.util.Map<String,String> m=new java.util.LinkedHashMap<>();
        String[][] r;
        switch(family){
            case "de" -> r=new String[][]{{"for","für"},{"without","ohne"},{"through","durch"},{"changed","geändert"},{"edit","bearbeiten"},{"change","Änderung"},{"changes","Änderungen"},{"none","keine"},{"template","Vorlage"},{"templates","Vorlagen"},{"restrictions","Beschränkungen"},{"coins","Münzen"},{"minimum","Minimum"},{"maximum","Maximum"},{"how","wie"},{"opens","öffnet"},{"get","erhalten"},{"or","oder"},{"this","diese"},{"collection","Sammeln"},{"collect","sammeln"},{"toggle","umschalten"},{"my","meine"},{"multiple","mehrere"},{"staff member","Mitarbeiter"},{"when","wenn"},{"start","Start"},{"end","Ende"},{"events","Events"},{"settings","Einstellungen"},{"colors","Farben"},{"immediately","sofort"},{"permissions","Berechtigungen"},{"created","erstellt"},{"create","erstellen"},{"date","Datum"},{"center","Zentrum"},{"management","Verwaltung"},{"all","alle"},{"select","auswählen"},{"shows","zeigt"},{"show","anzeigen"},{"disable","deaktivieren"},{"enable","aktivieren"},{"types","Typen"},{"distance","Abstand"},{"example","Beispiel"},{"remove","entfernen"},{"particle","Partikel"},{"particles","Partikel"},{"return","zurückkehren"},{"items","Gegenstände"},{"item","Gegenstand"},{"inventory","Inventar"},{"data","Daten"},{"delete","löschen"},{"commands","Befehle"},{"status","Status"},{"total","gesamt"},{"editor","Editor"},{"one","eins"},{"players","Spieler"},{"here","hier"},{"use","verwenden"},{"blocks","Blöcke"},{"global","global"},{"administrator","Administrator"},{"regions","Regionen"},{"list","Liste"},{"active","aktiv"},{"region","Region"},{"so that","damit"},{"set","gesetzt"},{"refusal","Ablehnung"},{"nothing","nichts"},{"sounds","Sounds"},{"sound","Sound"},{"add","hinzufügen"},{"reset","zurücksetzen"},{"in-built","integriert"},{"built-in","integriert"},{"completely","vollständig"},{"screen","Bildschirm"},{"creation","Erstellung"},{"internal","intern"},{"diagnostics","Diagnose"},{"decoys","Köder"},{"decoy","Köder"},{"what","was"},{"main","Haupt"},{"full","vollständig"},{"top","Top"},{"triggers","Auslösungen"},{"appear","erscheinen"},{"new","neu"},{"can","kann"},{"if","wenn"},{"area","Bereich"},{"statistics","Statistik"},{"search","Suche"},{"builder","Konstruktor"},{"sees","sieht"},{"appearance","Aussehen"},{"range","Bereich"},{"automatic","automatisch"},{"page","Seite"},{"pages","Seiten"},{"parameters","Parameter"},{"setting","Einstellung"},{"overview","Übersicht"},{"spawn","Spawnen"},{"seconds","Sekunden"},{"applied","angewendet"},{"floating","schwebend"},{"solid","fest"},{"support","Unterstützung"},{"amount","Menge"},{"negative","negativ"},{"selection","Auswahl"},{"play","abspielen"},{"small","klein"},{"stand","Ständer"},{"rotation","Drehung"},{"limit","Limit"},{"notes","Noten"},{"palette","Palette"},{"catalog","Katalog"},{"will","wird"},{"black","schwarz"},{"point","Punkt"},{"points","Punkte"},{"locations","Orte"},{"input","Eingabe"},{"own","eigene"},{"view","ansehen"},{"custom","eigen"},{"notifications","Benachrichtigungen"},{"available","verfügbar"},{"operations","Vorgänge"},{"states","Zustände"},{"anti","Anti"},{"configuration","Konfiguration"},{"help","Hilfe"},{"short","kurz"},{"work","arbeiten"},{"fake","falsch"},{"buried","vergraben"},{"ground","Boden"},{"head","Kopf"},{"ready","fertig"},{"open","öffnen"},{"bind","verbinden"},{"file","Datei"},{"useful","nützlich"},{"selected","ausgewählt"},{"leather","Leder"},{"confirmation","Bestätigung"},{"manual","manuell"},{"directly","direkt"},{"source","Quelle"},{"height","Höhe"},{"duplicate","duplizieren"},{"execute","ausführen"},{"faster","schneller"},{"time","Zeit"},{"positive","positiv"},{"number","Zahl"},{"give","geben"},{"charge","abbuchen"},{"individual","individuell"},{"between","zwischen"},{"world","Welt"},{"night","Nacht"},{"penalty","Strafe"},{"slots","Slots"},{"rows","Zeilen"},{"entries","Einträge"},{"tools","Werkzeuge"},{"distribution","Verteilung"},{"basic","grundlegend"},{"dyes","Farbstoffe"},{"button","Schaltfläche"},{"comma","Komma"},{"stored","gespeichert"},{"each","jede"},{"preview","Vorschau"},{"command","Befehl"},{"collected","gesammelt"},{"active","aktiv"},{"roles","Rollen"},{"assign","zuweisen"},{"clicks","Klicks"},{"icon","Symbol"},{"temporary","temporär"},{"updates","aktualisiert"},{"again","erneut"},{"launch","Start"},{"manage","verwalten"},{"receive","erhalten"},{"important","wichtig"},{"each","jeweils"},{"has","hat"},{"gives","gibt"},{"quick","schnell"},{"guide","Anleitung"},{"moderator","Moderator"},{"ends","endet"},{"cycle","Zyklus"},{"random","zufällig"},{"around","um"},{"real","echt"},{"positions","Positionen"},{"change","ändern"},{"effects","Effekte"},{"down","nach unten"},{"air","Luft"},{"reward","Belohnung"},{"specify","angeben"},{"economy","Wirtschaft"},{"economic","wirtschaftlich"},{"plugin","Plugin"},{"animation","Animation"},{"choose","wählen"},{"click","Klick"},{"set","festlegen"},{"block","blockieren"},{"check","prüfen"},{"reason","Grund"},{"saving","Speichern"},{"transfer","Übertragung"},{"other","anderes"},{"server","Server"},{"detailed","detailliert"},{"any","beliebig"},{"created","erstellt"},{"includes","enthält"},{"be","sein"},{"bound","verbunden"},{"place","platzieren"},{"two","zwei"},{"becomes","wird"},{"protected","geschützt"},{"allowed","erlaubt"},{"check","prüfen"},{"specific","bestimmt"},{"shown","angezeigt"},{"needed","benötigt"},{"complex","komplex"},{"copy","Kopie"},{"temporary","vorübergehend"},{"disabled","deaktiviert"},{"supported","unterstützt"},{"forbidden","verboten"},{"current","aktuell"},{"write","schreiben"},{"exactly","genau"},{"security","Sicherheit"},{"confirmed","bestätigt"},{"sections","Abschnitte"},{"intentionally","absichtlich"},{"task","Aufgabe"},{"finds","findet"},{"hint","Hinweis"},{"less","weniger"},{"more","mehr"},{"slower","langsamer"},{"interval","Intervall"},{"usually","normalerweise"},{"immediately","sofort"},{"automatically","automatisch"},{"saved","gespeichert"},{"impossible","unmöglich"},{"counted","gezählt"},{"used","verwendet"},{"distributed","verteilt"},{"values","Werte"},{"row","Zeile"},{"seven","sieben"},{"upper","oben"},{"four","vier"},{"dynamically","dynamisch"},{"actual","tatsächlich"},{"number","Anzahl"},{"simultaneously","gleichzeitig"},{"enabled","aktiviert"},{"effects","Effekte"},{"line","Zeile"},{"slot","Slot"},{"total","gesamt"},{"divided","geteilt"},{"dust","Staub"},{"transition","Übergang"},{"approximate","ungefähr"},{"wiki","Wiki"},{"purple","lila"},{"working","aktiv"},{"previously","zuvor"},{"easter","Ostern"},{"winter","Winter"},{"snow","Schnee"},{"fair","Jahrmarkt"},{"condition","Bedingung"},{"equipment","Ausrüstung"},{"empty","leer"},{"requirements","Anforderungen"},{"additional","zusätzlich"},{"template","Vorlage"},{"log","Protokoll"},{"assigned","zugewiesen"},{"region","Region"},{"other","andere"},{"main","Haupt"},{"water","Wasser"},{"lava","Lava"},{"custom","benutzerdefiniert"},{"separate","separat"},{"types","Typen"},{"page through","blättern"},{"all","alle"},{"enable","aktivieren"},{"at least","mindestens"},{"inherited","geerbt"}};
            case "uk" -> r=new String[][]{{"for","для"},{"without","без"},{"through","через"},{"changed","змінено"},{"edit","редагувати"},{"change","зміна"},{"changes","зміни"},{"none","немає"},{"template","шаблон"},{"templates","шаблони"},{"restrictions","обмеження"},{"coins","монети"},{"minimum","мінімум"},{"maximum","максимум"},{"how","як"},{"opens","відкриває"},{"get","отримати"},{"or","або"},{"this","це"},{"collection","збір"},{"collect","збирати"},{"toggle","перемкнути"},{"my","мої"},{"multiple","кілька"},{"staff member","співробітник"},{"when","коли"},{"start","початок"},{"end","кінець"},{"events","івенти"},{"settings","налаштування"},{"colors","кольори"},{"immediately","одразу"},{"permissions","права"},{"created","створено"},{"create","створити"},{"date","дата"},{"center","центр"},{"management","керування"},{"all","усі"},{"select","вибрати"},{"shows","показує"},{"show","показати"},{"disable","вимкнути"},{"enable","увімкнути"},{"types","типи"},{"distance","відстань"},{"example","приклад"},{"remove","видалити"},{"particle","частинка"},{"particles","частинки"},{"return","повернутися"},{"items","предмети"},{"item","предмет"},{"inventory","інвентар"},{"data","дані"},{"delete","видалити"},{"commands","команди"},{"status","стан"},{"total","всього"},{"editor","редактор"},{"one","один"},{"players","гравці"},{"here","тут"},{"use","використовувати"},{"blocks","блоки"},{"global","глобальний"},{"administrator","адміністратор"},{"regions","регіони"},{"list","список"},{"active","активний"},{"region","регіон"},{"so that","щоб"},{"set","задано"},{"refusal","відмова"},{"nothing","нічого"},{"sounds","звуки"},{"sound","звук"},{"add","додати"},{"reset","скинути"},{"built-in","вбудований"},{"completely","повністю"},{"screen","екран"},{"creation","створення"},{"internal","внутрішній"},{"diagnostics","діагностика"},{"decoys","приманки"},{"decoy","приманка"},{"what","що"},{"main","головний"},{"full","повний"},{"top","топ"},{"triggers","спрацьовування"},{"appear","з'являються"},{"new","новий"},{"can","може"},{"if","якщо"},{"area","область"},{"statistics","статистика"},{"search","пошук"},{"builder","конструктор"},{"sees","бачить"},{"appearance","вигляд"},{"range","діапазон"},{"automatic","автоматичний"},{"page","сторінка"},{"pages","сторінки"},{"parameters","параметри"},{"setting","налаштування"},{"overview","огляд"},{"spawn","спавн"},{"seconds","секунди"},{"applied","застосовано"},{"floating","плаваючий"},{"solid","тверда"},{"support","опора"},{"amount","кількість"},{"negative","негативний"},{"selection","виділення"},{"play","відтворити"},{"small","малий"},{"stand","стійка"},{"rotation","поворот"},{"limit","ліміт"},{"notes","ноти"},{"palette","палітра"},{"catalog","каталог"},{"will","буде"},{"black","чорний"},{"point","точка"},{"points","точки"},{"locations","місця"},{"input","ввід"},{"own","власні"},{"view","перегляд"},{"custom","власний"},{"notifications","сповіщення"},{"available","доступний"},{"operations","операції"},{"states","стани"},{"anti","анти"},{"configuration","конфігурація"},{"help","довідка"},{"short","короткий"},{"work","працювати"},{"fake","підроблені"},{"buried","закопані"},{"ground","земля"},{"head","голова"},{"ready","готовий"},{"open","відкрити"},{"bind","прив'язати"},{"file","файл"},{"useful","корисний"},{"selected","вибрано"},{"leather","шкіра"},{"confirmation","підтвердження"},{"manual","вручну"},{"directly","безпосередньо"},{"source","джерело"},{"height","висота"},{"duplicate","дублювати"},{"execute","виконати"},{"faster","швидше"},{"time","час"},{"positive","позитивний"},{"number","число"},{"give","видати"},{"charge","списати"},{"individual","індивідуальний"},{"between","між"},{"world","світ"},{"night","ніч"},{"penalty","штраф"},{"slots","слоти"},{"rows","рядки"},{"entries","записи"},{"tools","інструменти"},{"distribution","розподіл"},{"basic","основний"},{"dyes","барвники"},{"button","кнопка"},{"comma","кома"},{"stored","зберігається"},{"each","кожен"},{"preview","попередній перегляд"},{"command","команда"},{"collected","зібрано"},{"roles","ролі"},{"assign","призначити"},{"clicks","кліки"},{"icon","іконка"},{"temporary","тимчасовий"},{"updates","оновлює"},{"again","знову"},{"launch","запуск"},{"manage","керувати"},{"receive","отримувати"},{"important","важливий"},{"has","має"},{"gives","дає"},{"quick","швидкий"},{"guide","інструкція"},{"moderator","модератор"},{"ends","закінчується"},{"cycle","цикл"},{"random","випадковий"},{"around","навколо"},{"real","справжній"},{"positions","позиції"},{"effects","ефекти"},{"down","вниз"},{"air","повітря"},{"reward","нагорода"},{"specify","вкажіть"},{"economy","економіка"},{"economic","економічний"},{"plugin","плагін"},{"animation","анімація"},{"choose","вибрати"},{"click","клік"},{"block","блокувати"},{"check","перевірити"},{"reason","причина"},{"saving","збереження"},{"transfer","перенесення"},{"other","інший"},{"server","сервер"},{"detailed","детальний"},{"any","будь-який"},{"includes","містить"},{"be","бути"},{"bound","прив'язаний"},{"place","поставити"},{"two","два"},{"becomes","стає"},{"protected","захищений"},{"allowed","дозволено"},{"specific","конкретний"},{"shown","показано"},{"needed","потрібний"},{"complex","складний"},{"copy","копія"},{"temporarily","тимчасово"},{"disabled","вимкнено"},{"supported","підтримується"},{"forbidden","заборонено"},{"current","поточний"},{"write","записати"},{"exactly","саме"},{"security","безпека"},{"confirmed","підтверджено"},{"sections","розділи"},{"intentionally","навмисно"},{"task","завдання"},{"finds","знаходить"},{"hint","підказка"},{"less","менше"},{"more","більше"},{"slower","повільніше"},{"interval","інтервал"},{"usually","зазвичай"},{"automatically","автоматично"},{"saved","збережено"},{"impossible","неможливо"},{"counted","зараховано"},{"used","використовується"},{"distributed","розподілено"},{"values","значення"},{"row","рядок"},{"seven","сім"},{"upper","верхній"},{"four","чотири"},{"dynamically","динамічно"},{"actual","фактичний"},{"number","номер"},{"simultaneously","одночасно"},{"enabled","увімкнено"},{"line","рядок"},{"slot","слот"},{"divided","поділено"},{"dust","пил"},{"transition","перехід"},{"approximate","приблизний"},{"wiki","вікі"},{"purple","фіолетовий"},{"working","працює"},{"previously","раніше"},{"easter","Великдень"},{"winter","зима"},{"snow","сніг"},{"fair","ярмарок"},{"condition","умова"},{"equipment","екіпіровка"},{"empty","порожній"},{"requirements","вимоги"},{"additional","додатковий"},{"template","шаблон"},{"log","журнал"},{"assigned","призначено"},{"main","основний"},{"water","вода"},{"lava","лава"},{"custom","власний"},{"separate","окремий"},{"types","типи"},{"all","всі"},{"enable","увімкнути"},{"at least","щонайменше"},{"inherited","успадкований"}};
            case "es" -> r=new String[][]{{"for","para"},{"without","sin"},{"through","a través de"},{"changed","cambiado"},{"edit","editar"},{"change","cambio"},{"changes","cambios"},{"none","ninguno"},{"template","plantilla"},{"templates","plantillas"},{"restrictions","restricciones"},{"coins","monedas"},{"minimum","mínimo"},{"maximum","máximo"},{"how","cómo"},{"opens","abre"},{"get","obtener"},{"or","o"},{"this","este"},{"collection","recogida"},{"collect","recoger"},{"toggle","alternar"},{"my","mis"},{"multiple","varios"},{"staff member","miembro del personal"},{"when","cuando"},{"start","inicio"},{"end","fin"},{"events","eventos"},{"settings","ajustes"},{"colors","colores"},{"immediately","inmediatamente"},{"permissions","permisos"},{"created","creado"},{"create","crear"},{"date","fecha"},{"center","centro"},{"management","gestión"},{"all","todos"},{"select","seleccionar"},{"shows","muestra"},{"show","mostrar"},{"disable","desactivar"},{"enable","activar"},{"types","tipos"},{"distance","distancia"},{"example","ejemplo"},{"remove","eliminar"},{"particle","partícula"},{"particles","partículas"},{"return","volver"},{"items","objetos"},{"item","objeto"},{"inventory","inventario"},{"data","datos"},{"delete","eliminar"},{"commands","comandos"},{"status","estado"},{"total","total"},{"editor","editor"},{"one","uno"},{"players","jugadores"},{"here","aquí"},{"use","usar"},{"blocks","bloques"},{"global","global"},{"administrator","administrador"},{"regions","regiones"},{"list","lista"},{"active","activo"},{"region","región"},{"so that","para que"},{"set","establecido"},{"refusal","rechazo"},{"nothing","nada"},{"sounds","sonidos"},{"sound","sonido"},{"add","añadir"},{"reset","restablecer"},{"built-in","integrado"},{"completely","completamente"},{"screen","pantalla"},{"creation","creación"},{"internal","interno"},{"diagnostics","diagnóstico"},{"decoys","señuelos"},{"decoy","señuelo"},{"what","qué"},{"main","principal"},{"full","completo"},{"top","top"},{"triggers","activaciones"},{"appear","aparecen"},{"new","nuevo"},{"can","puede"},{"if","si"},{"area","área"},{"statistics","estadísticas"},{"search","buscar"},{"builder","constructor"},{"sees","ve"},{"appearance","apariencia"},{"range","rango"},{"automatic","automático"},{"page","página"},{"pages","páginas"},{"parameters","parámetros"},{"setting","ajuste"},{"overview","resumen"},{"spawn","aparición"},{"seconds","segundos"},{"applied","aplicado"},{"floating","flotante"},{"solid","sólido"},{"support","soporte"},{"amount","cantidad"},{"negative","negativo"},{"selection","selección"},{"play","reproducir"},{"small","pequeño"},{"stand","soporte"},{"rotation","rotación"},{"limit","límite"},{"notes","notas"},{"palette","paleta"},{"catalog","catálogo"},{"will","será"},{"black","negro"},{"point","punto"},{"points","puntos"},{"locations","ubicaciones"},{"input","entrada"},{"own","propios"},{"view","ver"},{"custom","personalizado"},{"notifications","notificaciones"},{"available","disponible"},{"operations","operaciones"},{"states","estados"},{"anti","anti"},{"configuration","configuración"},{"help","ayuda"},{"short","corto"},{"work","funcionar"},{"fake","falsos"},{"buried","enterrados"},{"ground","suelo"},{"head","cabeza"},{"ready","listo"},{"open","abrir"},{"bind","vincular"},{"file","archivo"},{"useful","útil"},{"selected","seleccionado"},{"leather","cuero"},{"confirmation","confirmación"},{"manual","manual"},{"directly","directamente"},{"source","fuente"},{"height","altura"},{"duplicate","duplicar"},{"execute","ejecutar"},{"faster","más rápido"},{"time","tiempo"},{"positive","positivo"},{"number","número"},{"give","dar"},{"charge","cobrar"},{"individual","individual"},{"between","entre"},{"world","mundo"},{"night","noche"},{"penalty","penalización"},{"slots","ranuras"},{"rows","filas"},{"entries","entradas"},{"tools","herramientas"},{"distribution","distribución"},{"basic","básico"},{"dyes","tintes"},{"button","botón"},{"comma","coma"},{"stored","guardado"},{"each","cada"},{"preview","vista previa"},{"command","comando"},{"collected","recogido"},{"roles","roles"},{"assign","asignar"},{"clicks","clics"},{"icon","icono"},{"temporary","temporal"},{"updates","actualiza"},{"again","de nuevo"},{"launch","inicio"},{"manage","gestionar"},{"receive","recibir"},{"important","importante"},{"has","tiene"},{"gives","da"},{"quick","rápido"},{"guide","guía"},{"moderator","moderador"},{"ends","termina"},{"cycle","ciclo"},{"random","aleatorio"},{"around","alrededor"},{"real","real"},{"positions","posiciones"},{"effects","efectos"},{"down","abajo"},{"air","aire"},{"reward","recompensa"},{"specify","especificar"},{"economy","economía"},{"economic","económico"},{"plugin","plugin"},{"animation","animación"},{"choose","elegir"},{"click","clic"},{"block","bloquear"},{"check","comprobar"},{"reason","motivo"},{"saving","guardado"},{"transfer","transferencia"},{"other","otro"},{"server","servidor"},{"detailed","detallado"},{"any","cualquiera"},{"includes","incluye"},{"be","ser"},{"bound","vinculado"},{"place","colocar"},{"two","dos"},{"becomes","se convierte"},{"protected","protegido"},{"allowed","permitido"},{"specific","específico"},{"shown","mostrado"},{"needed","necesario"},{"complex","complejo"},{"copy","copia"},{"temporarily","temporalmente"},{"disabled","desactivado"},{"supported","compatible"},{"forbidden","prohibido"},{"current","actual"},{"write","escribir"},{"exactly","exactamente"},{"security","seguridad"},{"confirmed","confirmado"},{"sections","secciones"},{"intentionally","intencionadamente"},{"task","tarea"},{"finds","encuentra"},{"hint","pista"},{"less","menos"},{"more","más"},{"slower","más lento"},{"interval","intervalo"},{"usually","normalmente"},{"automatically","automáticamente"},{"saved","guardado"},{"impossible","imposible"},{"counted","contado"},{"used","usado"},{"distributed","distribuido"},{"values","valores"},{"row","fila"},{"seven","siete"},{"upper","superior"},{"four","cuatro"},{"dynamically","dinámicamente"},{"actual","real"},{"number","número"},{"simultaneously","simultáneamente"},{"enabled","activado"},{"line","línea"},{"slot","ranura"},{"divided","dividido"},{"dust","polvo"},{"transition","transición"},{"approximate","aproximado"},{"wiki","wiki"},{"purple","morado"},{"working","funcionando"},{"previously","anteriormente"},{"easter","Pascua"},{"winter","invierno"},{"snow","nieve"},{"fair","feria"},{"condition","condición"},{"equipment","equipamiento"},{"empty","vacío"},{"requirements","requisitos"},{"additional","adicional"},{"template","plantilla"},{"log","registro"},{"assigned","asignado"},{"water","agua"},{"lava","lava"},{"custom","personalizado"},{"separate","separado"},{"types","tipos"},{"page through","pasar páginas"},{"all","todo"},{"enable","activar"},{"at least","al menos"},{"inherited","heredado"}};
            case "kk" -> r=new String[][]{{"for","үшін"},{"without","онсыз"},{"through","арқылы"},{"changed","өзгертілді"},{"edit","өзгерту"},{"change","өзгеріс"},{"changes","өзгерістер"},{"none","жоқ"},{"template","үлгі"},{"templates","үлгілер"},{"restrictions","шектеулер"},{"coins","тиындар"},{"minimum","минимум"},{"maximum","максимум"},{"how","қалай"},{"opens","ашады"},{"get","алу"},{"or","немесе"},{"this","бұл"},{"collection","жинау"},{"collect","жинау"},{"toggle","ауыстыру"},{"my","менің"},{"multiple","бірнеше"},{"staff member","қызметкер"},{"when","кезде"},{"start","басталу"},{"end","аяқталу"},{"events","ивенттер"},{"settings","баптаулар"},{"colors","түстер"},{"immediately","бірден"},{"permissions","құқықтар"},{"created","жасалды"},{"create","жасау"},{"date","күн"},{"center","орталық"},{"management","басқару"},{"all","барлығы"},{"select","таңдау"},{"shows","көрсетеді"},{"show","көрсету"},{"disable","өшіру"},{"enable","қосу"},{"types","типтер"},{"distance","қашықтық"},{"example","мысал"},{"remove","жою"},{"particle","бөлшек"},{"particles","бөлшектер"},{"return","қайту"},{"items","заттар"},{"item","зат"},{"inventory","инвентарь"},{"data","деректер"},{"delete","жою"},{"commands","командалар"},{"status","күй"},{"total","барлығы"},{"editor","редактор"},{"one","бір"},{"players","ойыншылар"},{"here","мұнда"},{"use","қолдану"},{"blocks","блоктар"},{"global","жаһандық"},{"administrator","әкімші"},{"regions","аймақтар"},{"list","тізім"},{"active","белсенді"},{"region","аймақ"},{"so that","сондықтан"},{"set","орнатылған"},{"refusal","бас тарту"},{"nothing","ештеңе"},{"sounds","дыбыстар"},{"sound","дыбыс"},{"add","қосу"},{"reset","қалпына келтіру"},{"built-in","кірістірілген"},{"completely","толығымен"},{"screen","экран"},{"creation","құру"},{"internal","ішкі"},{"diagnostics","диагностика"},{"decoys","алдаушылар"},{"decoy","алдаушы"},{"what","не"},{"main","негізгі"},{"full","толық"},{"top","топ"},{"triggers","іске қосулар"},{"appear","пайда болады"},{"new","жаңа"},{"can","алады"},{"if","егер"},{"area","аймақ"},{"statistics","статистика"},{"search","іздеу"},{"builder","құрастырушы"},{"sees","көреді"},{"appearance","сыртқы түр"},{"range","диапазон"},{"automatic","автоматты"},{"page","бет"},{"pages","беттер"},{"parameters","параметрлер"},{"setting","баптау"},{"overview","шолу"},{"spawn","спавн"},{"seconds","секунд"},{"applied","қолданылды"},{"floating","қалқымалы"},{"solid","қатты"},{"support","тірек"},{"amount","саны"},{"negative","теріс"},{"selection","таңдау"},{"play","ойнату"},{"small","кішкентай"},{"stand","тұрғы"},{"rotation","бұрылу"},{"limit","шектеу"},{"notes","ноталар"},{"palette","палитра"},{"catalog","каталог"},{"will","болады"},{"black","қара"},{"point","нүкте"},{"points","нүктелер"},{"locations","орындар"},{"input","енгізу"},{"own","өз"},{"view","көру"},{"custom","өзіндік"},{"notifications","хабарламалар"},{"available","қолжетімді"},{"operations","операциялар"},{"states","күйлер"},{"anti","анти"},{"configuration","конфигурация"},{"help","көмек"},{"short","қысқа"},{"work","жұмыс істеу"},{"fake","жалған"},{"buried","көмілген"},{"ground","жер"},{"head","бас"},{"ready","дайын"},{"open","ашу"},{"bind","байлау"},{"file","файл"},{"useful","пайдалы"},{"selected","таңдалған"},{"leather","тері"},{"confirmation","растау"},{"manual","қолмен"},{"directly","тікелей"},{"source","көз"},{"height","биіктік"},{"duplicate","көшірмелеу"},{"execute","орындау"},{"faster","жылдамырақ"},{"time","уақыт"},{"positive","оң"},{"number","сан"},{"give","беру"},{"charge","шегеру"},{"individual","жеке"},{"between","арасында"},{"world","әлем"},{"night","түн"},{"penalty","айыппұл"},{"slots","слоттар"},{"rows","жолдар"},{"entries","жазбалар"},{"tools","құралдар"},{"distribution","бөлу"},{"basic","негізгі"},{"dyes","бояулар"},{"button","батырма"},{"comma","үтір"},{"stored","сақталады"},{"each","әр"},{"preview","алдын ала қарау"},{"command","команда"},{"collected","жиналды"},{"roles","рөлдер"},{"assign","тағайындау"},{"clicks","басулар"},{"icon","белгіше"},{"temporary","уақытша"},{"updates","жаңартады"},{"again","қайта"},{"launch","іске қосу"},{"manage","басқару"},{"receive","алу"},{"important","маңызды"},{"has","бар"},{"gives","береді"},{"quick","жылдам"},{"guide","нұсқаулық"},{"moderator","модератор"},{"ends","аяқталады"},{"cycle","цикл"},{"random","кездейсоқ"},{"around","айналасында"},{"real","нақты"},{"positions","орындар"},{"effects","әсерлер"},{"down","төмен"},{"air","ауа"},{"reward","сыйақы"},{"specify","көрсету"},{"economy","экономика"},{"economic","экономикалық"},{"plugin","плагин"},{"animation","анимация"},{"choose","таңдау"},{"click","басу"},{"block","бұғаттау"},{"check","тексеру"},{"reason","себеп"},{"saving","сақтау"},{"transfer","тасымалдау"},{"other","басқа"},{"server","сервер"},{"detailed","егжей-тегжейлі"},{"any","кез келген"},{"includes","қамтиды"},{"be","болу"},{"bound","байланған"},{"place","орнату"},{"two","екі"},{"becomes","болады"},{"protected","қорғалған"},{"allowed","рұқсат етілген"},{"specific","нақты"},{"shown","көрсетілді"},{"needed","қажет"},{"complex","күрделі"},{"copy","көшірме"},{"temporarily","уақытша"},{"disabled","өшірулі"},{"supported","қолдаулы"},{"forbidden","тыйым салынған"},{"current","ағымдағы"},{"write","жазу"},{"exactly","дәл"},{"security","қауіпсіздік"},{"confirmed","расталды"},{"sections","бөлімдер"},{"intentionally","әдейі"},{"task","тапсырма"},{"finds","табады"},{"hint","кеңес"},{"less","азырақ"},{"more","көбірек"},{"slower","баяуырақ"},{"interval","аралық"},{"usually","әдетте"},{"automatically","автоматты түрде"},{"saved","сақталды"},{"impossible","мүмкін емес"},{"counted","есептелді"},{"used","қолданылады"},{"distributed","бөлінді"},{"values","мәндер"},{"row","жол"},{"seven","жеті"},{"upper","жоғарғы"},{"four","төрт"},{"dynamically","динамикалық түрде"},{"actual","нақты"},{"number","нөмір"},{"simultaneously","бір уақытта"},{"enabled","қосулы"},{"line","жол"},{"slot","слот"},{"divided","бөлінген"},{"dust","шаң"},{"transition","ауысу"},{"approximate","шамамен"},{"wiki","вики"},{"purple","күлгін"},{"working","жұмыс істейді"},{"previously","бұрын"},{"easter","Пасха"},{"winter","қыс"},{"snow","қар"},{"fair","жәрмеңке"},{"condition","шарт"},{"equipment","жабдық"},{"empty","бос"},{"requirements","талаптар"},{"additional","қосымша"},{"template","үлгі"},{"log","журнал"},{"assigned","тағайындалған"},{"water","су"},{"lava","лава"},{"custom","өзіндік"},{"separate","бөлек"},{"types","типтер"},{"all","барлығы"},{"enable","қосу"},{"at least","кемінде"},{"inherited","мұраланған"}};
            case "fr" -> r=new String[][]{{"for","pour"},{"without","sans"},{"through","à travers"},{"changed","modifié"},{"edit","modifier"},{"change","changement"},{"changes","changements"},{"none","aucun"},{"template","modèle"},{"templates","modèles"},{"restrictions","restrictions"},{"coins","pièces"},{"minimum","minimum"},{"maximum","maximum"},{"how","comment"},{"opens","ouvre"},{"get","obtenir"},{"or","ou"},{"this","ce"},{"collection","collecte"},{"collect","collecter"},{"toggle","activer/désactiver"},{"my","mes"},{"multiple","plusieurs"},{"staff member","membre du personnel"},{"when","quand"},{"start","début"},{"end","fin"},{"events","événements"},{"settings","paramètres"},{"colors","couleurs"},{"immediately","immédiatement"},{"permissions","permissions"},{"created","créé"},{"create","créer"},{"date","date"},{"center","centre"},{"management","gestion"},{"all","tous"},{"select","sélectionner"},{"shows","affiche"},{"show","afficher"},{"disable","désactiver"},{"enable","activer"},{"types","types"},{"distance","distance"},{"example","exemple"},{"remove","supprimer"},{"particle","particule"},{"particles","particules"},{"return","retourner"},{"items","objets"},{"item","objet"},{"inventory","inventaire"},{"data","données"},{"delete","supprimer"},{"commands","commandes"},{"status","état"},{"total","total"},{"editor","éditeur"},{"one","un"},{"players","joueurs"},{"here","ici"},{"use","utiliser"},{"blocks","blocs"},{"global","global"},{"administrator","administrateur"},{"regions","régions"},{"list","liste"},{"active","actif"},{"region","région"},{"so that","afin que"},{"set","défini"},{"refusal","refus"},{"nothing","rien"},{"sounds","sons"},{"sound","son"},{"add","ajouter"},{"reset","réinitialiser"},{"built-in","intégré"},{"completely","complètement"},{"screen","écran"},{"creation","création"},{"internal","interne"},{"diagnostics","diagnostic"},{"decoys","leurres"},{"decoy","leurre"},{"what","ce que"},{"main","principal"},{"full","complet"},{"top","top"},{"triggers","déclenchements"},{"appear","apparaissent"},{"new","nouveau"},{"can","peut"},{"if","si"},{"area","zone"},{"statistics","statistiques"},{"search","recherche"},{"builder","constructeur"},{"sees","voit"},{"appearance","apparence"},{"range","plage"},{"automatic","automatique"},{"page","page"},{"pages","pages"},{"parameters","paramètres"},{"setting","paramètre"},{"overview","aperçu"},{"spawn","apparition"},{"seconds","secondes"},{"applied","appliqué"},{"floating","flottant"},{"solid","solide"},{"support","support"},{"amount","quantité"},{"negative","négatif"},{"selection","sélection"},{"play","jouer"},{"small","petit"},{"stand","support"},{"rotation","rotation"},{"limit","limite"},{"notes","notes"},{"palette","palette"},{"catalog","catalogue"},{"will","sera"},{"black","noir"},{"point","point"},{"points","points"},{"locations","emplacements"},{"input","saisie"},{"own","propres"},{"view","voir"},{"custom","personnalisé"},{"notifications","notifications"},{"available","disponible"},{"operations","opérations"},{"states","états"},{"anti","anti"},{"configuration","configuration"},{"help","aide"},{"short","court"},{"work","fonctionner"},{"fake","faux"},{"buried","enterrés"},{"ground","sol"},{"head","tête"},{"ready","prêt"},{"open","ouvrir"},{"bind","lier"},{"file","fichier"},{"useful","utile"},{"selected","sélectionné"},{"leather","cuir"},{"confirmation","confirmation"},{"manual","manuel"},{"directly","directement"},{"source","source"},{"height","hauteur"},{"duplicate","dupliquer"},{"execute","exécuter"},{"faster","plus rapide"},{"time","temps"},{"positive","positif"},{"number","nombre"},{"give","donner"},{"charge","facturer"},{"individual","individuel"},{"between","entre"},{"world","monde"},{"night","nuit"},{"penalty","pénalité"},{"slots","emplacements"},{"rows","lignes"},{"entries","entrées"},{"tools","outils"},{"distribution","répartition"},{"basic","de base"},{"dyes","colorants"},{"button","bouton"},{"comma","virgule"},{"stored","stocké"},{"each","chaque"},{"preview","aperçu"},{"command","commande"},{"collected","collecté"},{"roles","rôles"},{"assign","attribuer"},{"clicks","clics"},{"icon","icône"},{"temporary","temporaire"},{"updates","met à jour"},{"again","à nouveau"},{"launch","lancement"},{"manage","gérer"},{"receive","recevoir"},{"important","important"},{"has","a"},{"gives","donne"},{"quick","rapide"},{"guide","guide"},{"moderator","modérateur"},{"ends","se termine"},{"cycle","cycle"},{"random","aléatoire"},{"around","autour"},{"real","réel"},{"positions","positions"},{"effects","effets"},{"down","vers le bas"},{"air","air"},{"reward","récompense"},{"specify","indiquer"},{"economy","économie"},{"economic","économique"},{"plugin","plugin"},{"animation","animation"},{"choose","choisir"},{"click","clic"},{"block","bloquer"},{"check","vérifier"},{"reason","raison"},{"saving","enregistrement"},{"transfer","transfert"},{"other","autre"},{"server","serveur"},{"detailed","détaillé"},{"any","n’importe quel"},{"includes","comprend"},{"be","être"},{"bound","lié"},{"place","placer"},{"two","deux"},{"becomes","devient"},{"protected","protégé"},{"allowed","autorisé"},{"specific","spécifique"},{"shown","affiché"},{"needed","nécessaire"},{"complex","complexe"},{"copy","copie"},{"temporarily","temporairement"},{"disabled","désactivé"},{"supported","pris en charge"},{"forbidden","interdit"},{"current","actuel"},{"write","écrire"},{"exactly","exactement"},{"security","sécurité"},{"confirmed","confirmé"},{"sections","sections"},{"intentionally","intentionnellement"},{"task","tâche"},{"finds","trouve"},{"hint","indice"},{"less","moins"},{"more","plus"},{"slower","plus lent"},{"interval","intervalle"},{"usually","généralement"},{"automatically","automatiquement"},{"saved","enregistré"},{"impossible","impossible"},{"counted","compté"},{"used","utilisé"},{"distributed","réparti"},{"values","valeurs"},{"row","ligne"},{"seven","sept"},{"upper","supérieur"},{"four","quatre"},{"dynamically","dynamiquement"},{"actual","réel"},{"number","numéro"},{"simultaneously","simultanément"},{"enabled","activé"},{"line","ligne"},{"slot","emplacement"},{"divided","divisé"},{"dust","poussière"},{"transition","transition"},{"approximate","approximatif"},{"wiki","wiki"},{"purple","violet"},{"working","fonctionnel"},{"previously","auparavant"},{"easter","Pâques"},{"winter","hiver"},{"snow","neige"},{"fair","foire"},{"condition","condition"},{"equipment","équipement"},{"empty","vide"},{"requirements","exigences"},{"additional","supplémentaire"},{"template","modèle"},{"log","journal"},{"assigned","attribué"},{"water","eau"},{"lava","lave"},{"custom","personnalisé"},{"separate","séparé"},{"types","types"},{"all","tous"},{"enable","activer"},{"at least","au moins"},{"inherited","hérité"}};
            case "pl" -> r=new String[][]{{"for","dla"},{"without","bez"},{"through","przez"},{"changed","zmieniono"},{"edit","edytuj"},{"change","zmiana"},{"changes","zmiany"},{"none","brak"},{"template","szablon"},{"templates","szablony"},{"restrictions","ograniczenia"},{"coins","monety"},{"minimum","minimum"},{"maximum","maksimum"},{"how","jak"},{"opens","otwiera"},{"get","pobierz"},{"or","lub"},{"this","to"},{"collection","zbieranie"},{"collect","zbierz"},{"toggle","przełącz"},{"my","moje"},{"multiple","wiele"},{"staff member","pracownik"},{"when","gdy"},{"start","start"},{"end","koniec"},{"events","wydarzenia"},{"settings","ustawienia"},{"colors","kolory"},{"immediately","natychmiast"},{"permissions","uprawnienia"},{"created","utworzono"},{"create","utwórz"},{"date","data"},{"center","centrum"},{"management","zarządzanie"},{"all","wszystkie"},{"select","wybierz"},{"shows","pokazuje"},{"show","pokaż"},{"disable","wyłącz"},{"enable","włącz"},{"types","typy"},{"distance","odległość"},{"example","przykład"},{"remove","usuń"},{"particle","cząsteczka"},{"particles","cząsteczki"},{"return","wróć"},{"items","przedmioty"},{"item","przedmiot"},{"inventory","ekwipunek"},{"data","dane"},{"delete","usuń"},{"commands","komendy"},{"status","status"},{"total","łącznie"},{"editor","edytor"},{"one","jeden"},{"players","gracze"},{"here","tutaj"},{"use","użyj"},{"blocks","bloki"},{"global","globalny"},{"administrator","administrator"},{"regions","regiony"},{"list","lista"},{"active","aktywny"},{"region","region"},{"so that","aby"},{"set","ustawiono"},{"refusal","odmowa"},{"nothing","nic"},{"sounds","dźwięki"},{"sound","dźwięk"},{"add","dodaj"},{"reset","resetuj"},{"built-in","wbudowany"},{"completely","całkowicie"},{"screen","ekran"},{"creation","tworzenie"},{"internal","wewnętrzny"},{"diagnostics","diagnostyka"},{"decoys","wabiki"},{"decoy","wabik"},{"what","co"},{"main","główny"},{"full","pełny"},{"top","top"},{"triggers","uruchomienia"},{"appear","pojawiają się"},{"new","nowy"},{"can","może"},{"if","jeśli"},{"area","obszar"},{"statistics","statystyki"},{"search","szukaj"},{"builder","konstruktor"},{"sees","widzi"},{"appearance","wygląd"},{"range","zakres"},{"automatic","automatyczny"},{"page","strona"},{"pages","strony"},{"parameters","parametry"},{"setting","ustawienie"},{"overview","przegląd"},{"spawn","pojawianie"},{"seconds","sekundy"},{"applied","zastosowano"},{"floating","latający"},{"solid","stałe"},{"support","podłoże"},{"amount","ilość"},{"negative","ujemny"},{"selection","wybór"},{"play","odtwórz"},{"small","mały"},{"stand","stojak"},{"rotation","obrót"},{"limit","limit"},{"notes","nuty"},{"palette","paleta"},{"catalog","katalog"},{"will","będzie"},{"black","czarny"},{"point","punkt"},{"points","punkty"},{"locations","lokalizacje"},{"input","wprowadzenie"},{"own","własne"},{"view","widok"},{"custom","własny"},{"notifications","powiadomienia"},{"available","dostępny"},{"operations","operacje"},{"states","stany"},{"anti","anti"},{"configuration","konfiguracja"},{"help","pomoc"},{"short","krótki"},{"work","działać"},{"fake","fałszywe"},{"buried","zakopane"},{"ground","ziemia"},{"head","głowa"},{"ready","gotowy"},{"open","otwórz"},{"bind","powiąż"},{"file","plik"},{"useful","przydatny"},{"selected","wybrane"},{"leather","skóra"},{"confirmation","potwierdzenie"},{"manual","ręczny"},{"directly","bezpośrednio"},{"source","źródło"},{"height","wysokość"},{"duplicate","duplikuj"},{"execute","wykonaj"},{"faster","szybciej"},{"time","czas"},{"positive","dodatni"},{"number","liczba"},{"give","daj"},{"charge","obciąż"},{"individual","indywidualny"},{"between","między"},{"world","świat"},{"night","noc"},{"penalty","kara"},{"slots","sloty"},{"rows","wiersze"},{"entries","wpisy"},{"tools","narzędzia"},{"distribution","rozkład"},{"basic","podstawowy"},{"dyes","barwniki"},{"button","przycisk"},{"comma","przecinek"},{"stored","zapisane"},{"each","każdy"},{"preview","podgląd"},{"command","komenda"},{"collected","zebrane"},{"roles","role"},{"assign","przypisz"},{"clicks","kliknięcia"},{"icon","ikona"},{"temporary","tymczasowy"},{"updates","aktualizuje"},{"again","ponownie"},{"launch","uruchomienie"},{"manage","zarządzaj"},{"receive","otrzymać"},{"important","ważny"},{"has","ma"},{"gives","daje"},{"quick","szybki"},{"guide","instrukcja"},{"moderator","moderator"},{"ends","kończy się"},{"cycle","cykl"},{"random","losowy"},{"around","wokół"},{"real","rzeczywisty"},{"positions","pozycje"},{"effects","efekty"},{"down","w dół"},{"air","powietrze"},{"reward","nagroda"},{"specify","podaj"},{"economy","ekonomia"},{"economic","ekonomiczny"},{"plugin","plugin"},{"animation","animacja"},{"choose","wybierz"},{"click","klik"},{"block","blokuj"},{"check","sprawdź"},{"reason","powód"},{"saving","zapisywanie"},{"transfer","przeniesienie"},{"other","inny"},{"server","serwer"},{"detailed","szczegółowy"},{"any","dowolny"},{"includes","zawiera"},{"be","być"},{"bound","powiązany"},{"place","umieść"},{"two","dwa"},{"becomes","staje się"},{"protected","chroniony"},{"allowed","dozwolone"},{"specific","konkretny"},{"shown","pokazane"},{"needed","potrzebny"},{"complex","złożony"},{"copy","kopia"},{"temporarily","tymczasowo"},{"disabled","wyłączony"},{"supported","obsługiwany"},{"forbidden","zabroniony"},{"current","bieżący"},{"write","zapisz"},{"exactly","dokładnie"},{"security","bezpieczeństwo"},{"confirmed","potwierdzone"},{"sections","sekcje"},{"intentionally","celowo"},{"task","zadanie"},{"finds","znajduje"},{"hint","wskazówka"},{"less","mniej"},{"more","więcej"},{"slower","wolniej"},{"interval","interwał"},{"usually","zwykle"},{"automatically","automatycznie"},{"saved","zapisano"},{"impossible","niemożliwe"},{"counted","zaliczone"},{"used","używane"},{"distributed","rozdzielone"},{"values","wartości"},{"row","wiersz"},{"seven","siedem"},{"upper","górny"},{"four","cztery"},{"dynamically","dynamicznie"},{"actual","rzeczywisty"},{"number","numer"},{"simultaneously","jednocześnie"},{"enabled","włączony"},{"line","linia"},{"slot","slot"},{"divided","podzielony"},{"dust","pył"},{"transition","przejście"},{"approximate","przybliżony"},{"wiki","wiki"},{"purple","fioletowy"},{"working","działający"},{"previously","wcześniej"},{"easter","Wielkanoc"},{"winter","zima"},{"snow","śnieg"},{"fair","jarmark"},{"condition","warunek"},{"equipment","ekwipunek"},{"empty","pusty"},{"requirements","wymagania"},{"additional","dodatkowy"},{"template","szablon"},{"log","dziennik"},{"assigned","przypisany"},{"water","woda"},{"lava","lawa"},{"custom","własny"},{"separate","osobny"},{"types","typy"},{"all","wszystkie"},{"enable","włącz"},{"at least","co najmniej"},{"inherited","odziedziczony"}};
            case "en", "ru" -> r=new String[0][0];
            default -> r=new String[0][0];
        }
        for(String[] row:r)m.put(row[0],row[1]);
        return m;
    }

    private java.util.Map<String,String> residualLexicon(String family){
        java.util.Map<String,String> m=new java.util.LinkedHashMap<>();
        String data="""
капли|drops|Tropfen|краплі|gotas|тамшылар|gouttes|krople
эффект|effect|Effekt|ефект|efecto|әсер|effet|efekt
удалось|succeeded|gelungen|вдалося|logrado|сәтті болды|réussi|udało się
используйте|use|verwenden|використовуйте|use|пайдаланыңыз|utilisez|użyj
супер-администратор|super administrator|Superadministrator|супер-адміністратор|superadministrador|супер әкімші|super-administrateur|superadministrator
ивентовый|event|Event-|івентовий|evento|іс-шара|événement|eventowy
вас|you|Sie|вас|usted|сіз|vous|was
пузырьки|bubbles|Bläschen|бульбашки|burbujas|көпіршіктер|bulles|bąbelki
падающие|falling|fallende|падаючі|que caen|құлайтын|tombants|spadające
падающая|falling|fallend|падаюча|que cae|құлайтын|tombante|spadająca
мелкие|small|kleine|дрібні|pequeñas|ұсақ|petits|małe
споры|spores|Sporen|спори|esporas|споралар|spores|zarodniki
искры|sparks|Funken|іскри|chispas|ұшқындар|étincelles|iskry
должен|must|muss|має|debe|тиіс|doit|musi
вашей|your|Ihrer|вашої|su|сіздің|votre|twojej
эту|this|diese|цю|esta|бұл|cette|tę
частица|particle|Partikel|частинка|partícula|бөлшек|particule|cząsteczka
удаления|removal|Löschung|видалення|eliminación|жою|suppression|usunięcie
течение|duration|Dauer|тривалість|duración|ұзақтығы|durée|czas trwania
стоек|stands|Ständer|стійок|soportes|тіректер|supports|stojaków
ложных|fake|falschen|фальшивих|falsos|жалған|faux|fałszywe
кастомную|custom|benutzerdefinierte|власну|personalizada|реттелетін|personnalisée|niestandardową
дым|smoke|Rauch|дим|humo|түтін|fumée|dym
визуальный|visual|visuell|візуальний|visual|көрнекі|visuel|wizualny
существует|exists|existiert|існує|existe|бар|existe|istnieje
порыва|gust|Böe|пориву|ráfaga|екпін|rafale|podmuch
пламя|flame|Flamme|полум'я|llama|жалын|flamme|płomień
облако|cloud|Wolke|хмара|nube|бұлт|nuage|chmura
настройкам|settings|Einstellungen|налаштувань|ajustes|параметрлер|réglages|ustawienia
листья|leaves|Blätter|листя|hojas|жапырақтар|feuilles|liście
лапку|hide|Kaninchenhaut|шкірку|piel de conejo|қоян терісі|peau de lapin|skórę królika
инструмента|tool|Werkzeugs|інструмента|herramienta|құрал|outil|narzędzie
изменены|changed|geändert|змінено|cambiadas|өзгертілді|modifiées|zmienione
зелёные|green|grüne|зелені|verdes|жасыл|vertes|zielone
защищён|protected|geschützt|захищений|protegido|қорғалған|protégé|chroniony
запрет|restriction|Sperre|заборона|prohibición|тыйым|restriction|zakaz
заблокировано|blocked|gesperrt|заблоковано|bloqueado|бұғатталған|bloqué|zablokowane
добавляет|adds|fügt hinzu|додає|añade|қосады|ajoute|dodaje
выключил|disabled|ausgeschaltet|вимкнув|desactivó|өшірді|désactivé|wyłączył
блоку|block|Block|блоку|bloque|блок|bloc|blok
часть|part|Teil|частина|parte|бөлік|partie|część
установки|installation|Installation|встановлення|instalación|орнату|installation|instalacja
тыкву|pumpkin|Kürbis|гарбуз|calabaza|асқабақ|citrouille|dynię
текст|text|Text|текст|texto|мәтін|texte|tekst
след|trail|Spur|слід|rastro|із|trace|ślad
светящиеся|glowing|leuchtende|світні|brillantes|жарқыраған|lumineuses|świecące
роста|growth|Wachstum|росту|crecimiento|өсу|croissance|wzrost
осколки|shards|Splitter|уламки|fragmentos|сынықтар|éclats|odłamki
нужна|needed|benötigt|потрібна|necesaria|қажет|nécessaire|potrzebna
натёчного|dripstone|Tropfstein-|натічного|estalactita|тамшытасты|dripstone|naciekowego
настроенный|configured|konfiguriert|налаштований|configurado|бапталған|configuré|skonfigurowany
найдена|found|gefunden|знайдена|encontrada|табылды|trouvée|znaleziona
кроличьей|rabbit|Kaninchen-|кролячою|conejo|қоян|lapin|króliczą
костра|campfire|Lagerfeuer|багаття|fogata|от|feu de camp|ognisko
камня|stone|Stein|каменю|piedra|тас|pierre|kamień
активные|active|aktive|активні|activas|белсенді|actives|aktywne
экземпляры|instances|Instanzen|екземпляри|instancias|даналар|instances|instancje
участвует|participates|nimmt teil|бере участь|participa|қатысады|participe|uczestniczy
удар|strike|Schlag|удар|golpe|соққы|frappe|uderzenie
такая|such|solche|така|tal|осындай|telle|taka
столб|pillar|Säule|стовп|pilar|баған|pilier|filar
событию|event|Ereignis|події|evento|іс-шараға|événement|wydarzeniu
снят|removed|entfernt|знятий|eliminado|алынды|retiré|usunięty
слёз|tears|Tränen|сліз|lágrimas|жас|larmes|łez
руки|hand|Hand|руки|mano|қол|main|ręki
ролях|roles|Rollen|ролях|roles|рөлдерде|rôles|rolach
разрешить|allow|erlauben|дозволити|permitir|рұқсат ету|autoriser|zezwolić
пыли|dust|Staub|пилу|polvo|шаң|poussière|pyłu
приземление|landing|Landung|приземлення|aterrizaje|қону|atterrissage|lądowanie
привязан|linked|verknüpft|прив'язаний|vinculado|байланыстырылған|lié|powiązany
поставьте|place|platzieren|поставте|coloque|орнатыңыз|placez|ustaw
пепел|ash|Asche|попіл|ceniza|күл|cendres|popiół
оменные|ominous|Omen-|зловісні|ominosos|зұлмат|sinistres|złowieszcze
обсидиана|obsidian|Obsidian|обсидіану|obsidiana|обсидиан|obsidienne|obsydianu
новая|new|neue|нова|nueva|жаңа|nouvelle|nowa
неизвестный|unknown|unbekannt|невідомий|desconocido|белгісіз|inconnu|nieznany
небольшой|small|kleiner|невеликий|pequeño|шағын|petit|mały
находится|located|befindet sich|знаходиться|se encuentra|орналасқан|situé|znajduje się
настройте|configure|konfigurieren|налаштуйте|configure|баптаңыз|configurez|skonfiguruj
мёда|honey|Honig|меду|miel|бал|miel|miodu
копия|copy|Kopie|копія|copia|көшірме|copie|kopia
зелья|potions|Tränke|зілля|pociones|сусындар|potions|mikstury
завершения|completion|Beendigung|завершення|finalización|аяқталуы|achèvement|zakończenie
душ|souls|Seelen|душ|almas|жандар|âmes|dusze
газа|gas|Gas|газ|gas|газ|gaz|gaz
выдаёт|gives|gibt|видає|da|береді|donne|daje
всплеск|splash|Spritzer|сплеск|salpicadura|шашырау|éclaboussure|rozprysk
воска|wax|Wachs|воску|cera|балауыз|cire|wosku
взрыва|explosion|Explosion|вибух|explosión|жарылыс|explosion|eksplozja
белые|white|weiße|білі|blancas|ақ|blanches|białe
яркие|bright|helle|яскраві|brillantes|ашық|vives|jasne
яйца|eggs|Eier|яйця|huevos|жұмыртқалар|œufs|jajka
шкуркой|hide|Kaninchenhaut|шкіркою|piel de conejo|қоян терісі|peau de lapin|skórą królika
чернила|ink|Tinte|чорнила|tinta|сия|encre|atrament
цветом|color|Farbe|кольором|color|түспен|couleur|kolorem
цветной|colored|farbig|кольоровий|coloreado|түсті|coloré|kolorowy
хранилище|storage|Speicher|сховище|almacenamiento|сақтау қоймасы|stockage|magazyn
фиолетовые|purple|violette|фіолетові|moradas|күлгін|violettes|fioletowe
фейковые|fake|gefälschte|фальшиві|falsas|жалған|faux|fałszywe
участников|participants|Teilnehmer|учасників|participantes|қатысушылар|participants|uczestników
управляет|manages|verwaltet|керує|gestiona|басқарады|gère|zarządza
тёмные|dark|dunkle|темні|oscuras|қараңғы|sombres|ciemne
токсичного|toxic|giftigen|токсичного|tóxico|улы|toxique|toksyczny
сущности|entities|Entitäten|сутності|entidades|нысандар|entités|encje
струя|jet|Strahl|струмінь|chorro|ағыс|jet|strumień
старт|start|Start|старт|inicio|бастау|début|start
станет|becomes|wird|стане|se convierte|айналады|devient|stanie się
спрута|squid|Tintfisch|спрута|calamar|кальмар|calmar|kałamarnicy
спороцвета|spore blossom|Sporenblüte|спороцвіту|flor de esporas|спора гүлі|fleur de spores|kwiat zarodników
специальную|special|spezielle|спеціальну|especial|арнайы|spéciale|specjalną
спавнера|spawner|Spawner|спавнера|generador|спаунер|générateur|spawnera
сохраняет|saves|speichert|зберігає|guarda|сақтайды|enregistre|zapisuje
сохранены|saved|gespeichert|збережені|guardados|сақталған|enregistrés|zapisane
соответствует|matches|entspricht|відповідає|coincide|сәйкес келеді|correspond|pasuje
создания|creation|Erstellung|створення|creación|жасау|création|tworzenia
содержать|contain|enthalten|містити|contener|қамтуы|contenir|zawierać
снятии|removal|Entfernung|знятті|eliminación|алып тастағанда|retrait|usunięciu
снова|again|erneut|знову|de nuevo|қайта|à nouveau|ponownie
снежка|snowball|Schneeball|сніжка|bola de nieve|қар добы|boule de neige|śnieżka
сколько|how many|wie viele|скільки|cuántos|қанша|combien|ile
синие|blue|blaue|сині|azules|көк|bleues|niebieskie
сброшен|reset|zurückgesetzt|скинутий|restablecido|қалпына келтірілді|réinitialisé|zresetowany
сброс|reset|Zurücksetzen|скидання|restablecimiento|қалпына келтіру|réinitialisation|reset
ролями|roles|Rollen|ролями|roles|рөлдермен|rôles|rolami
разлетающиеся|scattering|auseinanderfliegende|розлітаються|dispersos|шашырайтын|dispersées|rozlatujące się
поток|stream|Strom|потік|flujo|ағын|flux|strumień
постоянный|permanent|dauerhaft|постійний|permanente|тұрақты|permanent|stały
порыв|gust|Böe|порив|ráfaga|екпін|rafale|podmuch
портала|portal|Portals|порталу|portal|порталдың|portail|portalu
портал|portal|Portal|портал|portal|портал|portail|portal
попробуйте|try|versuchen Sie|спробуйте|intente|тырысыңыз|essayez|spróbuj
попадания|hit|Treffer|попадання|impactos|соққылар|coups|trafienia
попадании|hit|Treffer|попаданні|impacto|соққыда|coup|trafieniu
подтверждение|confirmation|Bestätigung|підтвердження|confirmación|растау|confirmation|potwierdzenie
палитре|palette|Palette|палітрі|paleta|палитрада|palette|palecie
падающий|falling|fallender|падаючий|que cae|құлайтын|tombant|spadający
отсутствует|missing|fehlt|відсутній|falta|жоқ|manquant|brakuje
основание|base|Grundlage|основа|base|негіз|base|podstawa
огненные|fiery|feurige|вогняні|ardientes|отты|enflammées|ogniste
общему|general|allgemeinen|загальному|general|жалпы|général|ogólnemu
обнаружения|detection|Erkennung|виявлення|detección|анықтау|détection|wykrywania
обнаружение|detection|Erkennung|виявлення|detección|анықтау|détection|wykrywanie
облачко|cloud|Wölkchen|хмарка|nubecita|бұлтша|nuage|chmurka
нота|note|Note|нота|nota|нота|note|nuta
номер|number|Nummer|номер|número|нөмір|numéro|numer
наследовать|inherit|erben|успадкувати|heredar|мұралау|hériter|dziedziczyć
найти|find|finden|знайти|encontrar|табу|trouver|znaleźć
нажимайте|click|klicken|натискайте|haga clic|басыңыз|cliquez|klikaj
надели|wore|angezogen|одягнули|llevaron|киді|porté|założyli
моб|mob|Mob|моб|mob|моб|créature|mob
месте|place|Ort|місці|lugar|орында|lieu|miejscu
медовые|honey|Honig-|медові|de miel|балды|au miel|miodowe
малый|small|klein|малий|pequeño|шағын|petit|mały
леса|forest|Wald|лісу|bosque|орманның|forêt|lasu
лапки|hide|Kaninchenhaut|шкірки|pieles|терілер|peaux|skóry
крупные|large|große|великі|grandes|ірі|grandes|duże
корректном|correct|korrekten|коректному|correcto|дұрыс|correct|poprawnym
клуб|club|Club|клуб|club|клуб|club|klub
клик|click|Klick|клік|clic|басу|clic|klik
капает|drips|tropft|капає|gotea|тамшылайды|goutte|kapie
испытания|testing|Test|випробування|prueba|сынақ|test|testy
изменять|change|ändern|змінювати|cambiar|өзгерту|modifier|zmieniać
ивентовому|event|Event-|івентовому|de evento|іс-шараға|d'événement|eventowemu
знак|symbol|Zeichen|знак|símbolo|таңба|symbole|symbol
зловещее|ominous|ominöse|зловісне|ominosa|зұлым|sinistre|złowieszcze
зачарованного|enchanted|verzauberten|зачарованого|encantado|сиқырланған|enchanté|zaklętego
заряд|charge|Ladung|заряд|carga|заряд|charge|ładunek
запрещена|forbidden|verboten|заборонена|prohibida|тыйым салынған|interdite|zabroniona
записи|entries|Einträge|записи|registros|жазбалар|entrées|wpisy
заблокировать|block|sperren|заблокувати|bloquear|бұғаттау|bloquer|zablokować
жителя|villager|Dorfbewohner|жителя|aldeano|ауыл тұрғыны|villageois|wieśniaka
дождь|rain|Regen|дощ|lluvia|жаңбыр|pluie|deszcz
добавлено|added|hinzugefügt|додано|añadido|қосылды|ajouté|dodano
два|two|zwei|два|dos|екі|deux|dwa
вне|outside|außerhalb|поза|fuera|сыртында|à l'extérieur|poza
вводить|enter|eingeben|вводити|introducir|енгізу|saisir|wprowadzić
будут|will|werden|будуть|serán|болады|seront|będą
более|more|mehr|більше|más|көбірек|plus|więcej
блокам|blocks|Blöcken|блокам|bloques|блоктарға|blocs|blokom
безлимит|unlimited|unbegrenzt|безліміт|ilimitado|шектеусіз|illimité|bez limitu
эффекта|effect|Effekts|ефекту|efecto|әсердің|effet|efektu
выбран|selected|ausgewählt|вибраний|seleccionado|таңдалған|sélectionné|wybrany
выбраны|selected|ausgewählt|вибрані|seleccionados|таңдалған|sélectionnés|wybrane
выключены|disabled|deaktiviert|вимкнені|desactivados|өшірілген|désactivés|wyłączone
ежедневный|daily|täglich|щоденний|diario|күнделікті|quotidien|codzienny
ежедневное|daily|täglich|щоденне|diario|күнделікті|quotidien|codzienne
еженедельное|weekly|wöchentlich|щотижневе|semanal|апталық|hebdomadaire|tygodniowe
еженедельный|weekly|wöchentlich|щотижневий|semanal|апталық|hebdomadaire|tygodniowy
суббота|Saturday|Samstag|субота|sábado|сенбі|samedi|sobota
воскресенье|Sunday|Sonntag|неділя|domingo|жексенбі|dimanche|niedziela
понедельник|Monday|Montag|понеділок|lunes|дүйсенбі|lundi|poniedziałek
вторник|Tuesday|Dienstag|вівторок|martes|сейсенбі|mardi|wtorek
среда|Wednesday|Mittwoch|середа|miércoles|сәрсенбі|mercredi|środa
четверг|Thursday|Donnerstag|четвер|jueves|бейсенбі|jeudi|czwartek
пятница|Friday|Freitag|п'ятниця|viernes|жұма|vendredi|piątek
ясная|clear|klar|ясна|despejado|ашық|claire|bezchmurna
грозa|thunder|Gewitter|гроза|tormenta|найзағай|orage|burza
черновик|draft|Entwurf|чернетка|borrador|жоба|brouillon|szkic
значение|value|Wert|значення|valor|мән|valeur|wartość
параметры|parameters|Parameter|параметри|parámetros|параметрлер|paramètres|parametry
настройка|setting|Einstellung|налаштування|ajuste|баптау|réglage|ustawienie
ввод|input|Eingabe|ввід|entrada|енгізу|saisie|wprowadzanie
проверить|check|prüfen|перевірити|comprobar|тексеру|vérifier|sprawdzić
сохраните|save|speichern|збережіть|guarde|сақтаңыз|enregistrez|zapisz
открыть|open|öffnen|відкрити|abrir|ашу|ouvrir|otworzyć
закройте|close|schließen|закрийте|cierre|жабыңыз|fermez|zamknij
переместить|move|verschieben|перемістити|mover|жылжыту|déplacer|przenieś
переключить|toggle|umschalten|перемкнути|alternar|ауыстыру|basculer|przełącz
поддерживает|supports|unterstützt|підтримує|admite|қолдайды|prend en charge|obsługuje
наследовать|inherit|erben|успадковувати|heredar|мұралау|hériter|dziedziczyć
добавится|will be added|wird hinzugefügt|додасться|se añadirá|қосылады|sera ajouté|zostanie dodane
пуст|empty|leer|порожній|vacío|бос|vide|pusty
ник|nickname|Spitzname|нік|apodo|лақап ат|pseudo|nick
никa|nickname|Spitzname|ніка|apodo|лақап|pseudo|nicku
привязан|linked|verknüpft|прив'язаний|vinculado|байланыстырылған|lié|powiązany
привязанные|linked|verknüpft|прив'язані|vinculados|байланыстырылған|liés|powiązane
точечно|point-by-point|Punkt für Punkt|точково|punto por punto|нүкте бойынша|point par point|punktowo
доступно|available|verfügbar|доступно|disponible|қолжетімді|disponible|dostępne
недоступно|unavailable|nicht verfügbar|недоступно|no disponible|қолжетімсіз|indisponible|niedostępne
сохранены|saved|gespeichert|збережені|guardados|сақталған|enregistrés|zapisane
не|not|nicht|не|no|емес|pas|nie
из|from|aus|з|de|ішінен|de|z
по|on|auf|по|en|бойынша|sur|po
на|to|zu|на|a|-ге|à|na
от|from|von|від|de|-дан|de|od
вы|you|Sie|ви|usted|сіз|vous|Pan/Pani
до|until|bis|до|hasta|дейін|jusqu'à|do
во|in|in|у|en|-де|dans|w
со|with|mit|з|con|-мен|avec|z
при|during|bei|за|en caso de|кезінде|lors de|przy
даже|even|sogar|навіть|incluso|тіпті|même|nawet
ли|whether|ob|чи|si|ма|si|czy
её|her|ihr|її|su|оның|son|jej
вес|weight|Gewicht|вага|peso|салмақ|poids|waga
чат|chat|Chat|чат|chat|чат|chat|czat
кто|who|wer|хто|quién|кім|qui|kto
ни|nor|weder|ні|ni|тіпті|ni|ani
можно|allowed|erlaubt|можна|se puede|болады|on peut|można
вспышка|flash|Blitz|спалах|destello|жарқ ету|éclair|błysk
формат|format|Format|формат|formato|формат|format|format
вернуться|return|zurückkehren|повернутися|volver|қайту|retourner|wróć
блока|of the block|des Blocks|блоку|del bloque|блоктың|du bloc|bloku
жизни|of lifetime|der Lebensdauer|часу життя|de vida|өмір сүру уақытының|de vie|życia
анимации|of the animation|der Animation|анімації|de la animación|анимацияның|de l'animation|animacji
гейзера|of the geyser|des Geysirs|гейзера|del géiser|гейзердің|du geyser|gejzera
супер|super|Super|супер|super|супер|super|super
скалка|dripstone|Tropfstein|сталактит|espeleotema|тамшытас|spéléothème|naciek
пример|example|Beispiel|приклад|ejemplo|мысал|exemple|przykład
выберите|choose|wählen|виберіть|elija|таңдаңыз|choisissez|wybierz
изменил|changed|änderte|змінив|cambió|өзгертті|a changé|zmienił
режим|mode|Modus|режим|modo|режим|mode|tryb
радиус|radius|Radius|радіус|radio|радиус|rayon|promień
воздуха|of air|der Luft|повітря|de aire|ауаның|d'air|powietrza
длительность|duration|Dauer|тривалість|duración|ұзақтығы|durée|czas trwania
запуск|launch|Start|запуск|inicio|іске қосу|lancement|uruchomienie
тестовый|test|Test-|тестовий|de prueba|тесттік|de test|testowy
дыма|of smoke|des Rauchs|диму|de humo|түтіннің|de fumée|dymu
нажмите|click|klicken Sie|натисніть|haga clic|басыңыз|cliquez|kliknij
кроличью|rabbit's|Hasen-|кролячу|de conejo|қоян|de lapin|króliczą
кроличья|rabbit's|Hasen-|кроляча|de conejo|қоян|de lapin|królicza
глобальное|global|global|глобальне|global|жаһандық|global|globalne
глобальная|global|global|глобальна|global|жаһандық|globale|globalna
состояние|state|Zustand|стан|estado|күй|état|stan
тест|test|Test|тест|prueba|тест|test|test
помощник|helper|Helfer|помічник|ayudante|көмекші|assistant|pomocnik
текущий|current|aktuell|поточний|actual|ағымдағы|actuel|bieżący
кастомная|custom|benutzerdefiniert|кастомна|personalizada|өзіндік|personnalisée|własna
сбросил|reset|setzte zurück|скинув|restableció|қалпына келтірді|a réinitialisé|zresetował
запустил|launched|startete|запустив|inició|іске қосты|a lancé|uruchomił
работает|works|funktioniert|працює|funciona|жұмыс істейді|fonctionne|działa
постановки|of placement|der Platzierung|розміщення|de colocación|орналастырудың|de placement|umieszczania
ваши|your|Ihre|ваші|sus|сіздің|vos|wasze
поставьте|place|setzen Sie|поставте|coloque|қойыңыз|placez|postaw
окончание|ending|Ende|закінчення|fin|аяқталуы|fin|zakończenie
моба|of the mob|des Mobs|моба|del mob|тіршілік иесінің|du mob|moba
добавьте|add|fügen Sie hinzu|додайте|añada|қосыңыз|ajoutez|dodaj
настройка|setting|Einstellung|налаштування|ajuste|баптау|paramètre|ustawienie
символа|of characters|Zeichen|символів|de caracteres|таңбаның|de caractères|znaków
серый|gray|grau|сірий|gris|сұр|gris|szary
волна|wave|Welle|хвиля|onda|толқын|onde|fala
встроенную|built-in|integrierte|вбудовану|integrada|кірістірілген|intégrée|wbudowaną
отрицательное|negative|negativ|негативне|negativo|теріс|négatif|ujemne
дня|of the day|des Tages|дня|del día|күннің|du jour|dnia
откройте|open|öffnen Sie|відкрийте|abra|ашыңыз|ouvrez|otwórz
воск|wax|Wachs|віск|cera|балауыз|cire|wosk
некорректен|is incorrect|ist ungültig|некоректний|es incorrecto|дұрыс емес|est incorrect|jest niepoprawny
светлый|light|hell|світлий|claro|ашық|clair|jasny
раздел|section|Abschnitt|розділ|sección|бөлім|section|sekcja
дублей|duplicates|Duplikate|дублікатів|duplicados|көшірмелердің|doublons|duplikatów
привязывает|binds|verknüpft|прив'язує|vincula|байланыстырады|lie|wiąże
неизвестно|unknown|unbekannt|невідомо|desconocido|белгісіз|inconnu|nieznane
открыл|opened|öffnete|відкрив|abrió|ашты|a ouvert|otworzył
импортировать|import|importieren|імпортувати|importar|импорттау|importer|importuj
бледного|pale|blassen|блідого|pálido|бозғылт|pâle|bladego
дуба|of oak|der Eiche|дуба|de roble|еменнің|de chêne|dębu
пуф|puff|Puff|пуф|puf|пуф|pouf|puf
игрока|of the player|des Spielers|гравця|del jugador|ойыншының|du joueur|gracza
событий|of events|der Events|подій|de eventos|іс-шаралардың|d'événements|wydarzeń
спавна|of the spawn|des Spawns|спавну|del spawn|спавнның|du spawn|spawnu
лапка|paw|Pfote|лапка|pata|аяқ|patte|łapka
свечи|of the candle|der Kerze|свічки|de la vela|шамның|de la bougie|świecy
листья|leaves|Blätter|листя|hojas|жапырақтар|feuilles|liście
чего|of what|wovon|чого|de qué|немен|de quoi|czego
того|of that|davon|того|de eso|соның|de cela|tego
этого|of this|davon|цього|de esto|мұның|de ceci|tego
кого|of whom|von wem|кого|de quién|кімнің|de qui|kogo
себе|to oneself|sich|собі|a sí mismo|өзіне|à soi|sobie
себя|oneself|sich|себе|a sí mismo|өзін|soi-même|siebie
белый|white|weiß|білий|blanco|ақ|blanc|biały
чёрный|black|schwarz|чорний|negro|қара|noir|czarny
красный|red|rot|червоний|rojo|қызыл|rouge|czerwony
зелёный|green|grün|зелений|verde|жасыл|vert|zielony
синий|blue|blau|синій|azul|көк|bleu|niebieski
жёлтый|yellow|gelb|жовтий|amarillo|сары|jaune|żółty
фиолетовый|purple|violett|фіолетовий|violeta|күлгін|violet|fioletowy
розовый|pink|rosa|рожевий|rosa|қызғылт|rose|różowy
голубой|light blue|hellblau|блакитний|celeste|ашық көк|bleu clair|błękitny
коричневый|brown|braun|коричневий|marrón|қоңыр|marron|brązowy
серый|gray|grau|сірий|gris|сұр|gris|szary
светло|light|hell|світло|claro|ашық|clair|jasno
тёмно|dark|dunkel|темно|oscuro|күңгірт|foncé|ciemno
лаймовый|lime|limette|лаймовий|lima|лайм|citron vert|limonkowy
выбранные|selected|ausgewählte|вибрані|seleccionados|таңдалған|sélectionnés|wybrane
выбранный|selected|ausgewählt|вибраний|seleccionado|таңдалған|sélectionné|wybrany
выбранной|selected|ausgewählten|вибраної|seleccionada|таңдалған|sélectionnée|wybranej
текущее|current|aktuell|поточне|actual|ағымдағы|actuel|bieżące
текущая|current|aktuell|поточна|actual|ағымдағы|actuelle|bieżąca
текущую|current|aktuell|поточну|actual|ағымдағы|actuelle|bieżącą
глобальный|global|global|глобальний|global|жаһандық|global|globalny
глобальную|global|global|глобальну|global|жаһандық|globale|globalną
экспорт|export|Export|експорт|exportación|экспорт|export|eksport
импорт|import|Import|імпорт|importación|импорт|import|import
экспортировано|exported|exportiert|експортовано|exportado|экспортталды|exporté|wyeksportowano
проверка|check|Prüfung|перевірка|comprobación|тексеру|vérification|sprawdzenie
достигнут|reached|erreicht|досягнуто|alcanzado|жетті|atteint|osiągnięty
некорректное|incorrect|ungültig|некоректне|incorrecto|дұрыс емес|incorrect|niepoprawne
несуществующий|nonexistent|nicht vorhanden|неіснуючий|inexistente|жоқ|inexistant|nieistniejący
ника|of the nickname|des Nicknamens|ніка|del nick|ник|du pseudo|nicku
нику|to the nickname|dem Nicknamen|ніку|al nick|никке|au pseudo|nickowi
житель|villager|Dorfbewohner|житель|aldeano|ауылдық|villageois|mieszkaniec
довольный|happy|zufrieden|задоволений|contento|риза|content|zadowolony
дельфина|of the dolphin|des Delfins|дельфіна|del delfín|дельфиннің|du dauphin|delfina
час|hour|Stunde|година|hora|сағат|heure|godzina
сек|sec|Sek.|сек|seg|сек|sec|sek
блоков|of blocks|Blöcken|блоків|de bloques|блоктардың|de blocs|bloków
блок|block|Block|блок|bloque|блок|bloc|blok
блоках|in blocks|Blöcken|блоках|en bloques|блоктарда|en blocs|blokach
внутри|inside|innerhalb|всередині|dentro|ішінде|à l'intérieur|wewnątrz
ваших|of your|Ihrer|ваших|de sus|сіздің|de vos|waszych
ярмарка|fair|Jahrmarkt|ярмарок|feria|жәрмеңке|foire|jarmark
натёчная|dripstone|Tropfstein-|натічна|de espeleotema|тамшытас|de spéléothème|naciekowa
натёка|of the dripstone|des Tropfsteins|натіку|del espeleotema|тамшытастың|du spéléothème|nacieku
экземпляр|instance|Instanz|екземпляр|instancia|данасы|instance|instancja
кольчужный|chainmail|Ketten-|кольчужний|de cota de malla|шынжыр|de mailles|kolczugowy
незеритовый|netherite|Netherit-|незеритовий|de netherite|незерит|en netherite|netherytowy
железный|iron|Eisen-|залізний|de hierro|темір|en fer|żelazny
золотой|golden|golden|золотий|dorado|алтын|doré|złoty
кожаную|leather|Leder-|шкіряну|de cuero|тері|en cuir|skórzaną
шапку|cap|Mütze|шапку|gorro|қалпақ|bonnet|czapkę
шлему|to the helmet|dem Helm|шолому|al casco|дулығаға|au casque|hełmowi
опыт|experience|Erfahrung|досвід|experiencia|тәжірибе|expérience|doświadczenie
телепорт|teleport|Teleport|телепорт|teletransporte|телепорт|téléportation|teleport
точке|to the point|zum Punkt|точці|al punto|нүктеге|au point|punktu
точкам|to the points|den Punkten|точкам|a los puntos|нүктелерге|aux points|punktom
маркер|marker|Markierung|маркер|marcador|маркер|marqueur|znacznik
взрыв|explosion|Explosion|вибух|explosión|жарылыс|explosion|wybuch
кольцо|ring|Ring|кільце|anillo|сақина|anneau|pierścień
опыт|experience|Erfahrung|досвід|experiencia|тәжірибе|expérience|doświadczenie
метка|marker|Markierung|мітка|marca|белгі|marque|znacznik
огня|of fire|des Feuers|вогню|del fuego|оттың|du feu|ognia
пламени|of the flame|der Flamme|полум'я|de la llama|жалынның|de la flamme|płomienia
пламя|flame|Flamme|полум'я|llama|жалын|flamme|płomień
воды|of water|des Wassers|води|de agua|судың|d'eau|wody
водой|with water|mit Wasser|водою|con agua|сумен|avec de l'eau|wodą
мёд|honey|Honig|мед|miel|бал|miel|miód
слизь|slime|Schleim|слиз|limo|шырыш|vase|śluz
взмах|swing|Schwung|розмах|golpe|сермеу|coup|zamach
оружия|of the weapon|der Waffe|зброї|del arma|қарудың|de l'arme|broni
дыхание|breath|Atem|дихання|aliento|тыныс|souffle|oddech
дракона|of the dragon|des Drachen|дракона|del dragón|айдаһардың|du dragon|smoka
опыт|experience|Erfahrung|досвід|experiencia|тәжірибе|expérience|doświadczenie
крик|scream|Schrei|крик|grito|айқай|cri|krzyk
паутина|cobweb|Spinnweben|павутина|telaraña|өрмекші торы|toile d'araignée|pajęczyna
поплавок|float|Schwimmer|поплавок|flotador|қалқыма|flotteur|spławik
чих|sneeze|Niesen|чхання|estornudo|түшкіру|éternuement|kichnięcie
панды|of the panda|des Pandas|панди|del panda|пандада|du panda|pandy
души|of the soul|der Seele|душі|del alma|жанның|de l'âme|duszy
брызги|splash|Spritzer|бризки|salpicaduras|шашырау|éclaboussures|rozbryzg
свечение|glow|Leuchten|світіння|resplandor|жарқырау|lueur|blask
зачарование|enchantment|Verzauberung|зачарування|encantamiento|тәуіптеу|enchantement|zaklęcie
зачарованный|enchanted|verzaubert|зачарований|encantado|тәуіптелген|enchanté|zaklęty
магия|magic|Magie|магія|magia|сиқыр|magie|magia
ведьмы|of the witch|der Hexe|відьми|de la bruja|бақсының|de la sorcière|wiedźmy
тотем|totem|Totem|тотем|tótem|тотем|totem|totem
визер|wither|Wither|візер|wither|визер|wither|wither
компост|compost|Kompost|компост|compost|компост|compost|kompost
аметист|amethyst|Amethyst|аметист|amatista|аметист|améthyste|ametyst
сердца|hearts|Herzen|серця|corazones|жүректер|cœurs|serca
снежные|snow|Schnee-|сніжні|de nieve|қар|de neige|śnieżne
хлопья|flakes|Flocken|пластівці|copos|түйіршіктер|flocons|płatki
искажённого|of the warped|des Warped-|спотвореного|distorsionado|бұрмаланған|déformé|zniekształconego
багрового|of the crimson|des Crimson-|багряного|carmesí|қызғылт|cramoisi|szkarłatnego
багровые|crimson|Crimson-|багряні|carmesí|қызғылт|cramoisi|szkarłatne
серная|sulfur|Schwefel-|сірчана|de azufre|күкірт|de soufre|siarkowa
серные|sulfur|Schwefel-|сірчані|de azufre|күкірт|de soufre|siarkowe
серный|sulfur|Schwefel-|сірчаний|de azufre|күкірт|de soufre|siarkowy
серы|of sulfur|des Schwefels|сірки|de azufre|күкірттің|de soufre|siarki
слизистый|slimy|schleimig|слизький|viscoso|шырышты|visqueux|śluzowaty
заражение|infection|Infektion|зараження|infección|жұғу|infection|zakażenie
ядовитый|poisonous|giftig|отруйний|venenoso|улы|toxique|trujący
ядовитого|poisonous|giftigen|отруйного|venenoso|улы|toxique|trującego
газ|gas|Gas|газ|gas|газ|gaz|gaz
пузырьковый|bubble|Blasen-|бульбашковий|de burbujas|көпіршікті|à bulles|bąbelkowy
пузырьки|bubbles|Blasen|бульбашки|burbujas|көпіршіктер|bulles|bąbelki
подводные|underwater|Unterwasser-|підводні|submarinas|су астындағы|sous-marines|podwodne
лопнувшие|popped|geplatzte|лопнули|reventadas|жарылған|éclatées|pęknięte
разброса|of spread|der Streuung|розкиду|de dispersión|шашыраудың|de dispersion|rozrzutu
размера|of size|der Größe|розміру|del tamaño|мөлшерінің|de la taille|rozmiaru
переходом|with a transition|mit einem Übergang|переходом|con una transición|өтумен|avec une transition|z przejściem
оттенками|with shades|mit Farbtönen|відтінками|con tonos|реңктермен|avec des teintes|z odcieniami
маленькое|small|kleine|маленьке|pequeña|кішкентай|petite|małe
небольшая|small|kleine|невелика|pequeña|шағын|petite|niewielka
небольшое|small|kleines|невелике|pequeño|шағын|petit|niewielkie
небольшой|small|kleiner|невеликий|pequeño|шағын|petit|niewielki
плотный|dense|dicht|щільний|denso|тығыз|dense|gęsty
плотная|dense|dichte|щільна|densa|тығыз|dense|gęsta
плотное|dense|dichtes|щільне|denso|тығыз|dense|gęste
плотные|dense|dichte|щільні|densos|тығыз|denses|gęste
крупный|large|groß|великий|grande|ірі|gros|duży
крупные|large|große|великі|grandes|ірі|gros|duże
мягкие|soft|weiche|м'які|suaves|жұмсақ|douces|miękkie
яркая|bright|helle|яскрава|brillante|жарқын|vive|jasna
очень|very|sehr|дуже|muy|өте|très|bardzo
широкий|wide|breit|широкий|ancho|кең|large|szeroki
мощная|powerful|starke|потужна|potente|қуатты|puissante|potężna
звуковая|sound|Klang-|звукова|sonora|дыбыстық|sonore|dźwiękowa
надзирателя|of the warden|des Wardens|наглядача|del warden|бақылаушының|du warden|strażnika
резкий|sharp|scharf|різкий|brusco|өткір|brusque|ostry
критического|of critical|kritischen|критичного|crítico|сыни|critique|krytycznego
попадания|of the hit|des Treffers|влучання|del impacto|соққының|de l'impact|trafienia
курсор|cursor|Cursor|курсор|cursor|курсор|curseur|kursor
взят|taken|genommen|взято|tomado|алынды|pris|wzięty
шт|pcs|Stk.|шт|uds|дана|pcs|szt
пн|Mon|Mo|пн|lun|дс|lun|pon
вт|Tue|Di|вт|mar|сс|mar|wt
ср|Wed|Mi|ср|mié|ср|mer|śr
чт|Thu|Do|чт|jue|бс|jeu|czw
пт|Fri|Fr|пт|vie|жм|ven|pt
сб|Sat|Sa|сб|sáb|сб|sam|sob
вс|Sun|So|нд|dom|жс|dim|nd
меньше|less|weniger|менше|menos|аз|moins|mniej
больше|more|mehr|більше|más|көп|plus|więcej
голова|head|Kopf|голова|cabeza|бас|tête|głowa
телом|with body|mit Körper|тілом|con cuerpo|денемен|avec le corps|ciałem
опыт|experience|Erfahrung|досвід|experiencia|тәжірибе|expérience|doświadczenie
следующего|of the next|des nächsten|наступного|del siguiente|келесінің|du suivant|następnego
следующая|next|nächste|наступна|siguiente|келесі|suivante|następna
предыдущая|previous|vorherige|попередня|anterior|алдыңғы|précédente|poprzednia
        
""";
        int index=switch(family){case "de"->2;case "uk"->3;case "es"->4;case "kk"->5;case "fr"->6;case "pl"->7;default->1;};
        if(index<1)return m;
        for(String line:data.split("\\R")){
            String[] a=line.split("\\|",-1);
            if(a.length>=8)m.put(a[0],a[index]);
        }
        return m;
    }

    private String translateGui(String text,String family){
        if(text==null||text.isEmpty()||"ru".equals(family))return text;
        String out=applyGuiExactMap(text,family);
        out=applyGuiMap(out,guiMap(family));
        out=softTranslateRussianGui(out,family);
        if(containsCyrillic(out)) out=applyWordMap(out,ruEnglishFallback());
        out=applyWordMap(out,englishFamilyFallback(family));
        if(containsCyrillic(out)) out=translateRussianFallback(out,family);
        return out;
    }

    private String applyGuiMap(String text,java.util.Map<String,String> map){
        return applyWordMap(text,map);
    }

    /**
     * Final GUI safety net: translates common Russian words that can appear in
     * dynamically generated lore/status strings and therefore cannot all be
     * listed as exact phrases in guiMap. This keeps non-RU clients from seeing
     * accidental Russian leftovers while preserving technical identifiers.
     */
    private String softTranslateRussianGui(String text,String family){
        java.util.Map<String,String> m=new java.util.LinkedHashMap<>();
        switch(family){
            case "de" -> {
                String[][] r={{"Отмена","Abbrechen"},{"ЛКМ","LMB"},{"ПКМ","RMB"},{"Роли","Rollen"},{"Роль","Rolle"},{"Точки","Punkte"},{"точек","Punkten"},{"точки","Punkte"},{"точку","Punkt"},{"Изменить","Ändern"},{"Изменено","Geändert"},{"Закрыть","Schließen"},{"Страница","Seite"},{"Введите","Eingeben"},{"Открыть","Öffnen"},{"Настройки","Einstellungen"},{"Частицы","Partikel"},{"Частиц","Partikel"},{"Центр","Zentrum"},{"Права","Berechtigungen"},{"Ивент","Event"},{"Ивенты","Events"},{"Ивента","Event"},{"Событие","Ereignis"},{"Событий","Ereignisse"},{"Звук","Ton"},{"Спавн","Spawn"},{"Спавна","Spawn"},{"Меню","Menü"},{"Список","Liste"},{"Время","Zeit"},{"Расписание","Zeitplan"},{"Предмет","Gegenstand"},{"Предыдущая","Vorherige"},{"Следующая","Nächste"},{"Пример","Beispiel"},{"Назад","Zurück"},{"Радиус","Radius"},{"Лимит","Limit"},{"Игрок","Spieler"},{"Игрока","Spielers"},{"Игроки","Spieler"},{"Вернуться","Zurückkehren"},{"Название","Name"},{"Формат","Format"},{"Монеты","Münzen"},{"Инструмент","Werkzeug"},{"Чат","Chat"},{"Тип","Typ"},{"Количество","Menge"},{"Максимум","Maximum"},{"Минимум","Minimum"},{"Жизни","Lebensdauer"},{"Блоков","Blöcke"},{"Постановки","Platzierung"},{"Анимации","Animation"},{"Задано","Festgelegt"},{"Длительность","Dauer"},{"Значение","Wert"},{"Главное","Haupt"},{"События","Ereignisse"},{"Удалить","Löschen"},{"Условия","Bedingungen"},{"Цвет","Farbe"},{"Вес","Gewicht"},{"Статистика","Statistik"},{"Поиск","Suche"},{"Шанс","Chance"},{"Задержка","Verzögerung"},{"Создать","Erstellen"},{"Шаблон","Vorlage"},{"Шаблоны","Vorlagen"},{"Регион","Region"},{"Место","Ort"},{"Выбрать","Auswählen"},{"Включён","Aktiviert"},{"Выключен","Deaktiviert"},{"Выключить","Deaktivieren"},{"Включить","Aktivieren"},{"Выбрано","Ausgewählt"},{"Выбранные","Ausgewählte"},{"Запуск","Start"},{"Запусков","Starts"},{"Предпросмотр","Vorschau"},{"Награда","Belohnung"},{"Награды","Belohnungen"},{"Сотрудник","Mitarbeiter"},{"Сотрудники","Mitarbeiter"},{"Журнал","Protokoll"},{"Действий","Aktionen"},{"Действие","Aktion"},{"Проверка","Prüfung"},{"Мир","Welt"},{"Погода","Wetter"},{"Шлем","Helm"},{"Требование","Anforderung"},{"Сообщение","Nachricht"},{"Сохранено","Gespeichert"},{"Сохранить","Speichern"},{"Добавить","Hinzufügen"},{"Очистить","Leeren"},{"Сбросить","Zurücksetzen"},{"Ежедневный","Täglich"},{"Ежедневное","Täglich"},{"Еженедельный","Wöchentlich"},{"Еженедельное","Wöchentlich"},{"День","Tag"},{"Нажмите","Klicken"},{"Текущий","Aktuell"},{"Текущее","Aktuell"},{"Сейчас","Jetzt"},{"Следующий","Nächster"},{"Последний","Letzter"},{"Последнее","Letztes"},{"История","Verlauf"},{"Состояние","Status"},{"Активно","Aktiv"},{"Завершено","Beendet"},{"Ожидает","Wartet"},{"Свой","Eigen"},{"Своих","Eigene"},{"Ваши","Ihre"},{"Ваших","Ihrer"},{"Координаты","Koordinaten"},{"Поставил","Platziert von"},{"Кто","Wer"},{"Тестовый","Test"},{"Тест","Test"},{"Режим","Modus"},{"Случайный","Zufällig"},{"Смешанный","Gemischt"},{"Награждение","Belohnung"},{"Сбор","Sammlung"},{"Собрать","Sammeln"},{"Доступ","Zugriff"},{"Доступен","Verfügbar"},{"Недоступно","Nicht verfügbar"},{"Разрешено","Erlaubt"},{"Запрещено","Verboten"},{"Вода","Wasser"},{"Лава","Lava"},{"Применить","Anwenden"},{"Палитра","Palette"},{"Размер","Größe"},{"Скорость","Geschwindigkeit"},{"Количество","Anzahl"},{"Длительность","Dauer"},{"Громкость","Lautstärke"},{"Тон","Tonhöhe"},{"Еженедельный запуск","Wöchentlicher Start"},{"Ежедневный запуск","Täglicher Start"},{"Суббота","Samstag"},{"суббота","Samstag"},{"Понедельник","Montag"},{"понедельник","Montag"},{"Вторник","Dienstag"},{"вторник","Dienstag"},{"Среда","Mittwoch"},{"среда","Mittwoch"},{"Четверг","Donnerstag"},{"четверг","Donnerstag"},{"Пятница","Freitag"},{"пятница","Freitag"},{"Воскресенье","Sonntag"},{"воскресенье","Sonntag"}};
                for(String[] x:r)m.put(x[0],x[1]);
            }
            case "uk" -> {
                String[][] r={{"Отмена","Скасувати"},{"ЛКМ","ЛКМ"},{"ПКМ","ПКМ"},{"Роли","Ролі"},{"Роль","Роль"},{"Точки","Точки"},{"точек","точок"},{"точки","точки"},{"точку","точку"},{"Изменить","Змінити"},{"Изменено","Змінено"},{"Закрыть","Закрити"},{"Страница","Сторінка"},{"Введите","Введіть"},{"Открыть","Відкрити"},{"Настройки","Налаштування"},{"Частицы","Частинки"},{"Частиц","Частинок"},{"Центр","Центр"},{"Права","Права"},{"Ивент","Івент"},{"Ивенты","Івенти"},{"Ивента","івенту"},{"Событие","Подія"},{"Событий","Подій"},{"Звук","Звук"},{"Спавн","Спавн"},{"Спавна","Спавну"},{"Меню","Меню"},{"Список","Список"},{"Время","Час"},{"Расписание","Розклад"},{"Предмет","Предмет"},{"Предыдущая","Попередня"},{"Следующая","Наступна"},{"Пример","Приклад"},{"Назад","Назад"},{"Радиус","Радіус"},{"Лимит","Ліміт"},{"Игрок","Гравець"},{"Игрока","Гравця"},{"Игроки","Гравці"},{"Вернуться","Повернутися"},{"Название","Назва"},{"Формат","Формат"},{"Монеты","Монети"},{"Инструмент","Інструмент"},{"Чат","Чат"},{"Тип","Тип"},{"Количество","Кількість"},{"Максимум","Максимум"},{"Минимум","Мінімум"},{"Жизни","Тривалість"},{"Блоков","Блоків"},{"Постановки","Встановлення"},{"Анимации","Анімації"},{"Задано","Задано"},{"Длительность","Тривалість"},{"Значение","Значення"},{"Главное","Головне"},{"События","Події"},{"Удалить","Видалити"},{"Условия","Умови"},{"Цвет","Колір"},{"Вес","Вага"},{"Статистика","Статистика"},{"Поиск","Пошук"},{"Шанс","Шанс"},{"Задержка","Затримка"},{"Создать","Створити"},{"Шаблон","Шаблон"},{"Шаблоны","Шаблони"},{"Регион","Регіон"},{"Место","Місце"},{"Выбрать","Вибрати"},{"Включён","Увімкнено"},{"Выключен","Вимкнено"},{"Выключить","Вимкнути"},{"Включить","Увімкнути"},{"Выбрано","Вибрано"},{"Выбранные","Вибрані"},{"Запуск","Запуск"},{"Запусков","Запусків"},{"Предпросмотр","Попередній перегляд"},{"Награда","Нагорода"},{"Награды","Нагороди"},{"Сотрудник","Працівник"},{"Сотрудники","Працівники"},{"Журнал","Журнал"},{"Действий","Дій"},{"Действие","Дія"},{"Проверка","Перевірка"},{"Мир","Світ"},{"Погода","Погода"},{"Шлем","Шолом"},{"Требование","Вимога"},{"Сообщение","Повідомлення"},{"Сохранено","Збережено"},{"Сохранить","Зберегти"},{"Добавить","Додати"},{"Очистить","Очистити"},{"Сбросить","Скинути"},{"Ежедневный","Щоденний"},{"Ежедневное","Щоденне"},{"Еженедельный","Щотижневий"},{"Еженедельное","Щотижневе"},{"День","День"},{"Нажмите","Натисніть"},{"Текущий","Поточний"},{"Текущее","Поточне"},{"Сейчас","Зараз"},{"Следующий","Наступний"},{"Последний","Останній"},{"Последнее","Останнє"},{"История","Історія"},{"Состояние","Стан"},{"Активно","Активно"},{"Завершено","Завершено"},{"Ожидает","Очікує"},{"Свой","Власний"},{"Своих","Власних"},{"Ваши","Ваші"},{"Ваших","Ваших"},{"Координаты","Координати"},{"Поставил","Встановив"},{"Кто","Хто"},{"Тестовый","Тестовий"},{"Тест","Тест"},{"Режим","Режим"},{"Случайный","Випадковий"},{"Смешанный","Змішаний"},{"Сбор","Збір"},{"Собрать","Зібрати"},{"Доступ","Доступ"},{"Доступен","Доступний"},{"Недоступно","Недоступно"},{"Разрешено","Дозволено"},{"Запрещено","Заборонено"},{"Вода","Вода"},{"Лава","Лава"},{"Применить","Застосувати"},{"Палитра","Палітра"},{"Размер","Розмір"},{"Скорость","Швидкість"},{"Количество","Кількість"},{"Длительность","Тривалість"},{"Громкость","Гучність"},{"Тон","Тон"},{"Суббота","Субота"},{"суббота","субота"},{"Понедельник","Понеділок"},{"понедельник","понеділок"},{"Вторник","Вівторок"},{"вторник","вівторок"},{"Среда","Середа"},{"среда","середа"},{"Четверг","Четвер"},{"четверг","четвер"},{"Пятница","П’ятниця"},{"пятница","п’ятниця"},{"Воскресенье","Неділя"},{"воскресенье","неділя"}};
                for(String[] x:r)m.put(x[0],x[1]);
            }
            case "es" -> {
                String[][] r={{"Отмена","Cancelar"},{"ЛКМ","Clic izq."},{"ПКМ","Clic der."},{"Роли","Roles"},{"Роль","Rol"},{"Точки","Puntos"},{"точек","puntos"},{"точки","puntos"},{"точку","punto"},{"Изменить","Editar"},{"Закрыть","Cerrar"},{"Страница","Página"},{"Введите","Introduzca"},{"Открыть","Abrir"},{"Настройки","Ajustes"},{"Частицы","Partículas"},{"Центр","Centro"},{"Права","Permisos"},{"Ивент","Evento"},{"Ивенты","Eventos"},{"Событие","Evento"},{"Звук","Sonido"},{"Спавн","Aparición"},{"Меню","Menú"},{"Список","Lista"},{"Время","Hora"},{"Расписание","Programación"},{"Предмет","Objeto"},{"Предыдущая","Anterior"},{"Следующая","Siguiente"},{"Пример","Ejemplo"},{"Назад","Atrás"},{"Радиус","Radio"},{"Лимит","Límite"},{"Игрок","Jugador"},{"Игроки","Jugadores"},{"Название","Nombre"},{"Формат","Formato"},{"Монеты","Monedas"},{"Инструмент","Herramienta"},{"Тип","Tipo"},{"Количество","Cantidad"},{"Максимум","Máximo"},{"Минимум","Mínimo"},{"Длительность","Duración"},{"Значение","Valor"},{"Удалить","Eliminar"},{"Условия","Condiciones"},{"Цвет","Color"},{"Статистика","Estadísticas"},{"Поиск","Búsqueda"},{"Шанс","Probabilidad"},{"Задержка","Retraso"},{"Создать","Crear"},{"Шаблон","Plantilla"},{"Шаблоны","Plantillas"},{"Выбрать","Seleccionar"},{"Включён","Activado"},{"Выключен","Desactivado"},{"Выключить","Desactivar"},{"Включить","Activar"},{"Выбрано","Seleccionado"},{"Предпросмотр","Vista previa"},{"Награда","Recompensa"},{"Награды","Recompensas"},{"Сотрудники","Personal"},{"Журнал","Registro"},{"Действие","Acción"},{"Мир","Mundo"},{"Погода","Clima"},{"Шлем","Casco"},{"Требование","Requisito"},{"Сообщение","Mensaje"},{"Сохранить","Guardar"},{"Добавить","Añadir"},{"Очистить","Limpiar"},{"Сбросить","Restablecer"},{"Ежедневный","Diario"},{"Еженедельный","Semanal"},{"День","Día"},{"Текущий","Actual"},{"Сейчас","Ahora"},{"Следующий","Siguiente"},{"Последний","Último"},{"История","Historial"},{"Состояние","Estado"},{"Активно","Activo"},{"Завершено","Finalizado"},{"Свой","Propio"},{"Координаты","Coordenadas"},{"Поставил","Colocado por"},{"Режим","Modo"},{"Случайный","Aleatorio"},{"Смешанный","Mixto"},{"Сбор","Recogida"},{"Собрать","Recoger"},{"Доступ","Acceso"},{"Недоступно","No disponible"},{"Разрешено","Permitido"},{"Запрещено","Prohibido"},{"Вода","Agua"},{"Лава","Lava"},{"Применить","Aplicar"},{"Палитра","Paleta"},{"Размер","Tamaño"},{"Скорость","Velocidad"},{"Громкость","Volumen"},{"Тон","Tono"},{"Суббота","Sábado"},{"суббота","sábado"},{"Понедельник","Lunes"},{"понедельник","lunes"},{"Вторник","Martes"},{"вторник","martes"},{"Среда","Miércoles"},{"среда","miércoles"},{"Четверг","Jueves"},{"четверг","jueves"},{"Пятница","Viernes"},{"пятница","viernes"},{"Воскресенье","Domingo"},{"воскресенье","domingo"},{"ВЫКЛЮЧЕНО","DESACTIVADO"},{"АКТИВНО","ACTIVO"},{"ЕЖЕНЕДЕЛЬНО","SEMANAL"},{"НЕ НАЧАЛОСЬ","NO INICIADO"},{"ЗАВЕРШЕНО","FINALIZADO"},{"не задано","no establecido"}};
                for(String[] x:r)m.put(x[0],x[1]);
            }
            case "fr" -> {
                String[][] r={{"Отмена","Annuler"},{"ЛКМ","Clic gauche"},{"ПКМ","Clic droit"},{"Роли","Rôles"},{"Роль","Rôle"},{"Точки","Points"},{"точек","points"},{"точки","points"},{"точку","point"},{"Изменить","Modifier"},{"Закрыть","Fermer"},{"Страница","Page"},{"Введите","Entrez"},{"Открыть","Ouvrir"},{"Настройки","Réglages"},{"Частицы","Particules"},{"Центр","Centre"},{"Права","Permissions"},{"Ивент","Événement"},{"Ивенты","Événements"},{"Событие","Événement"},{"Звук","Son"},{"Спавн","Apparition"},{"Меню","Menu"},{"Список","Liste"},{"Время","Heure"},{"Расписание","Planning"},{"Предмет","Objet"},{"Предыдущая","Précédente"},{"Следующая","Suivante"},{"Пример","Exemple"},{"Назад","Retour"},{"Радиус","Rayon"},{"Лимит","Limite"},{"Игрок","Joueur"},{"Игроки","Joueurs"},{"Название","Nom"},{"Формат","Format"},{"Монеты","Pièces"},{"Инструмент","Outil"},{"Тип","Type"},{"Количество","Quantité"},{"Максимум","Maximum"},{"Минимум","Minimum"},{"Длительность","Durée"},{"Значение","Valeur"},{"Удалить","Supprimer"},{"Условия","Conditions"},{"Цвет","Couleur"},{"Статистика","Statistiques"},{"Поиск","Recherche"},{"Шанс","Chance"},{"Задержка","Délai"},{"Создать","Créer"},{"Шаблон","Modèle"},{"Шаблоны","Modèles"},{"Выбрать","Sélectionner"},{"Включён","Activé"},{"Выключен","Désactivé"},{"Выключить","Désactiver"},{"Включить","Activer"},{"Выбрано","Sélectionné"},{"Предпросмотр","Aperçu"},{"Награда","Récompense"},{"Награды","Récompenses"},{"Сотрудники","Personnel"},{"Журнал","Journal"},{"Действие","Action"},{"Мир","Monde"},{"Погода","Météo"},{"Шлем","Casque"},{"Требование","Condition"},{"Сообщение","Message"},{"Сохранить","Enregistrer"},{"Добавить","Ajouter"},{"Очистить","Vider"},{"Сбросить","Réinitialiser"},{"Ежедневный","Quotidien"},{"Еженедельный","Hebdomadaire"},{"День","Jour"},{"Текущий","Actuel"},{"Сейчас","Maintenant"},{"Следующий","Suivant"},{"Последний","Dernier"},{"История","Historique"},{"Состояние","État"},{"Активно","Actif"},{"Завершено","Terminé"},{"Свой","Personnel"},{"Координаты","Coordonnées"},{"Поставил","Placé par"},{"Режим","Mode"},{"Случайный","Aléatoire"},{"Смешанный","Mixte"},{"Сбор","Collecte"},{"Собрать","Collecter"},{"Доступ","Accès"},{"Недоступно","Indisponible"},{"Разрешено","Autorisé"},{"Запрещено","Interdit"},{"Вода","Eau"},{"Лава","Lave"},{"Применить","Appliquer"},{"Палитра","Palette"},{"Размер","Taille"},{"Скорость","Vitesse"},{"Громкость","Volume"},{"Тон","Hauteur"},{"Суббота","Samedi"},{"суббота","samedi"},{"Понедельник","Lundi"},{"понедельник","lundi"},{"Вторник","Mardi"},{"вторник","mardi"},{"Среда","Mercredi"},{"среда","mercredi"},{"Четверг","Jeudi"},{"четверг","jeudi"},{"Пятница","Vendredi"},{"пятница","vendredi"},{"Воскресенье","Dimanche"},{"воскресенье","dimanche"},{"ВЫКЛЮЧЕНО","DÉSACTIVÉ"},{"АКТИВНО","ACTIF"},{"ЕЖЕНЕДЕЛЬНО","HEBDOMADAIRE"},{"НЕ НАЧАЛОСЬ","PAS COMMENCÉ"},{"ЗАВЕРШЕНО","TERMINÉ"},{"не задано","non défini"}};
                for(String[] x:r)m.put(x[0],x[1]);
            }
            case "pl" -> {
                String[][] r={{"Отмена","Anuluj"},{"ЛКМ","LPM"},{"ПКМ","PPM"},{"Роли","Role"},{"Роль","Rola"},{"Точки","Punkty"},{"точек","punktów"},{"точки","punkty"},{"точку","punkt"},{"Изменить","Edytuj"},{"Закрыть","Zamknij"},{"Страница","Strona"},{"Введите","Wpisz"},{"Открыть","Otwórz"},{"Настройки","Ustawienia"},{"Частицы","Cząsteczki"},{"Центр","Centrum"},{"Права","Uprawnienia"},{"Ивент","Wydarzenie"},{"Ивенты","Wydarzenia"},{"Событие","Wydarzenie"},{"Звук","Dźwięk"},{"Спавн","Pojawianie"},{"Меню","Menu"},{"Список","Lista"},{"Время","Czas"},{"Расписание","Harmonogram"},{"Предмет","Przedmiot"},{"Предыдущая","Poprzednia"},{"Следующая","Następna"},{"Пример","Przykład"},{"Назад","Wstecz"},{"Радиус","Promień"},{"Лимит","Limit"},{"Игрок","Gracz"},{"Игроки","Gracze"},{"Название","Nazwa"},{"Формат","Format"},{"Монеты","Monety"},{"Инструмент","Narzędzie"},{"Тип","Typ"},{"Количество","Ilość"},{"Максимум","Maksimum"},{"Минимум","Minimum"},{"Длительность","Czas trwania"},{"Значение","Wartość"},{"Удалить","Usuń"},{"Условия","Warunki"},{"Цвет","Kolor"},{"Статистика","Statystyki"},{"Поиск","Wyszukiwanie"},{"Шанс","Szansa"},{"Задержка","Opóźnienie"},{"Создать","Utwórz"},{"Шаблон","Szablon"},{"Шаблоны","Szablony"},{"Выбрать","Wybierz"},{"Включён","Włączone"},{"Выключен","Wyłączone"},{"Выключить","Wyłącz"},{"Включить","Włącz"},{"Выбрано","Wybrano"},{"Предпросмотр","Podgląd"},{"Награда","Nagroda"},{"Награды","Nagrody"},{"Сотрудники","Personel"},{"Журнал","Dziennik"},{"Действие","Działanie"},{"Мир","Świat"},{"Погода","Pogoda"},{"Шлем","Hełm"},{"Требование","Wymaganie"},{"Сообщение","Wiadomość"},{"Сохранить","Zapisz"},{"Добавить","Dodaj"},{"Очистить","Wyczyść"},{"Сбросить","Resetuj"},{"Ежедневный","Codzienny"},{"Еженедельный","Tygodniowy"},{"День","Dzień"},{"Текущий","Bieżący"},{"Сейчас","Teraz"},{"Следующий","Następny"},{"Последний","Ostatni"},{"История","Historia"},{"Состояние","Status"},{"Активно","Aktywne"},{"Завершено","Zakończone"},{"Свой","Własny"},{"Координаты","Współrzędne"},{"Поставил","Utworzył"},{"Режим","Tryb"},{"Случайный","Losowy"},{"Смешанный","Mieszany"},{"Сбор","Zbieranie"},{"Собрать","Zbierz"},{"Доступ","Dostęp"},{"Недоступно","Niedostępne"},{"Разрешено","Dozwolone"},{"Запрещено","Zabronione"},{"Вода","Woda"},{"Лава","Lawa"},{"Применить","Zastosuj"},{"Палитра","Paleta"},{"Размер","Rozmiar"},{"Скорость","Prędkość"},{"Громкость","Głośność"},{"Тон","Ton"},{"Суббота","Sobota"},{"суббота","sobota"},{"Понедельник","Poniedziałek"},{"понедельник","poniedziałek"},{"Вторник","Wtorek"},{"вторник","wtorek"},{"Среда","Środa"},{"среда","środa"},{"Четверг","Czwartek"},{"четверг","czwartek"},{"Пятница","Piątek"},{"пятница","piątek"},{"Воскресенье","Niedziela"},{"воскресенье","niedziela"},{"ВЫКЛЮЧЕНО","WYŁĄCZONE"},{"АКТИВНО","AKTYWNE"},{"ЕЖЕНЕДЕЛЬНО","TYGODNIOWO"},{"НЕ НАЧАЛОСЬ","NIE ROZPOCZĘTO"},{"ЗАВЕРШЕНО","ZAKOŃCZONE"},{"не задано","nie ustawiono"}};
                for(String[] x:r)m.put(x[0],x[1]);
            }
            case "kk" -> {
                String[][] r={{"Отмена","Бас тарту"},{"ЛКМ","Сол жақ"},{"ПКМ","Оң жақ"},{"Роли","Рөлдер"},{"Роль","Рөл"},{"Точки","Нүктелер"},{"точек","нүкте"},{"точки","нүктелер"},{"точку","нүктені"},{"Изменить","Өзгерту"},{"Закрыть","Жабу"},{"Страница","Бет"},{"Введите","Енгізіңіз"},{"Открыть","Ашу"},{"Настройки","Баптаулар"},{"Частицы","Бөлшектер"},{"Центр","Орталық"},{"Права","Рұқсаттар"},{"Ивент","Ивент"},{"Ивенты","Ивенттер"},{"Событие","Оқиға"},{"Звук","Дыбыс"},{"Спавн","Спавн"},{"Меню","Мәзір"},{"Список","Тізім"},{"Время","Уақыт"},{"Расписание","Кесте"},{"Предмет","Зат"},{"Предыдущая","Алдыңғы"},{"Следующая","Келесі"},{"Пример","Мысал"},{"Назад","Артқа"},{"Радиус","Радиус"},{"Лимит","Лимит"},{"Игрок","Ойыншы"},{"Игроки","Ойыншылар"},{"Название","Атауы"},{"Формат","Формат"},{"Монеты","Монеталар"},{"Инструмент","Құрал"},{"Тип","Түрі"},{"Количество","Саны"},{"Максимум","Максимум"},{"Минимум","Минимум"},{"Длительность","Ұзақтығы"},{"Значение","Мәні"},{"Удалить","Жою"},{"Условия","Шарттар"},{"Цвет","Түс"},{"Статистика","Статистика"},{"Поиск","Іздеу"},{"Шанс","Мүмкіндік"},{"Задержка","Кідіріс"},{"Создать","Жасау"},{"Шаблон","Үлгі"},{"Шаблоны","Үлгілер"},{"Выбрать","Таңдау"},{"Включён","Қосулы"},{"Выключен","Өшірулі"},{"Выключить","Өшіру"},{"Включить","Қосу"},{"Выбрано","Таңдалды"},{"Предпросмотр","Алдын ала көру"},{"Награда","Сыйақы"},{"Награды","Сыйақылар"},{"Сотрудники","Қызметкерлер"},{"Журнал","Журнал"},{"Действие","Әрекет"},{"Мир","Әлем"},{"Погода","Ауа райы"},{"Шлем","Дулыға"},{"Требование","Талап"},{"Сообщение","Хабарлама"},{"Сохранить","Сақтау"},{"Добавить","Қосу"},{"Очистить","Тазалау"},{"Сбросить","Қалпына келтіру"},{"Ежедневный","Күнделікті"},{"Еженедельный","Апталық"},{"День","Күн"},{"Текущий","Ағымдағы"},{"Сейчас","Қазір"},{"Следующий","Келесі"},{"Последний","Соңғы"},{"История","Тарих"},{"Состояние","Күйі"},{"Активно","Белсенді"},{"Завершено","Аяқталды"},{"Свой","Өз ивентіңіз"},{"Координаты","Координаттар"},{"Поставил","Қойған"},{"Режим","Режим"},{"Случайный","Кездейсоқ"},{"Смешанный","Аралас"},{"Сбор","Жинау"},{"Собрать","Жинау"},{"Доступ","Қолжетімділік"},{"Недоступно","Қолжетімсіз"},{"Разрешено","Рұқсат етілген"},{"Запрещено","Тыйым салынған"},{"Вода","Су"},{"Лава","Лава"},{"Применить","Қолдану"},{"Палитра","Палитра"},{"Размер","Өлшем"},{"Скорость","Жылдамдық"},{"Громкость","Дыбыс деңгейі"},{"Тон","Тон"},{"Суббота","Сенбі"},{"суббота","сенбі"},{"Понедельник","Дүйсенбі"},{"понедельник","дүйсенбі"},{"Вторник","Сейсенбі"},{"вторник","сейсенбі"},{"Среда","Сәрсенбі"},{"среда","сәрсенбі"},{"Четверг","Бейсенбі"},{"четверг","бейсенбі"},{"Пятница","Жұма"},{"пятница","жұма"},{"Воскресенье","Жексенбі"},{"воскресенье","жексенбі"},{"ВЫКЛЮЧЕНО","ӨШІРУЛІ"},{"АКТИВНО","БЕЛСЕНДІ"},{"ЕЖЕНЕДЕЛЬНО","АПТАЛЫҚ"},{"НЕ НАЧАЛОСЬ","ӘЛІ БАСТАЛМАДЫ"},{"ЗАВЕРШЕНО","АЯҚТАЛДЫ"},{"не задано","орнатылмаған"}};
                for(String[] x:r)m.put(x[0],x[1]);
            }
            default -> {
                String[][] r={{"Отмена","Cancel"},{"ЛКМ","LMB"},{"ПКМ","RMB"},{"Роли","Roles"},{"Роль","Role"},{"Точки","Points"},{"точек","points"},{"точки","points"},{"точку","point"},{"Изменить","Edit"},{"Изменено","Changed"},{"Закрыть","Close"},{"Страница","Page"},{"Введите","Enter"},{"Открыть","Open"},{"Настройки","Settings"},{"Частицы","Particles"},{"Частиц","particles"},{"Центр","Center"},{"Права","Permissions"},{"Ивент","Event"},{"Ивенты","Events"},{"Ивента","event"},{"Событие","Event"},{"Событий","Events"},{"Звук","Sound"},{"Спавн","Spawn"},{"Спавна","Spawn"},{"Меню","Menu"},{"Список","List"},{"Время","Time"},{"Расписание","Schedule"},{"Предмет","Item"},{"Предыдущая","Previous"},{"Следующая","Next"},{"Пример","Example"},{"Назад","Back"},{"Радиус","Radius"},{"Лимит","Limit"},{"Игрок","Player"},{"Игрока","player"},{"Игроки","Players"},{"Вернуться","Return"},{"Название","Name"},{"Формат","Format"},{"Монеты","Coins"},{"Инструмент","Tool"},{"Чат","Chat"},{"Тип","Type"},{"Количество","Amount"},{"Максимум","Maximum"},{"Минимум","Minimum"},{"Жизни","Lifetime"},{"Блоков","Blocks"},{"Постановки","Placement"},{"Анимации","Animation"},{"Задано","Set"},{"Длительность","Duration"},{"Значение","Value"},{"Главное","Main"},{"События","Events"},{"Удалить","Delete"},{"Условия","Conditions"},{"Цвет","Color"},{"Вес","Weight"},{"Статистика","Statistics"},{"Поиск","Search"},{"Шанс","Chance"},{"Задержка","Delay"},{"Создать","Create"},{"Шаблон","Template"},{"Шаблоны","Templates"},{"Регион","Region"},{"Место","Location"},{"Выбрать","Select"},{"Включён","Enabled"},{"Выключен","Disabled"},{"Выключить","Disable"},{"Включить","Enable"},{"Выбрано","Selected"},{"Выбранные","Selected"},{"Запуск","Start"},{"Запусков","Starts"},{"Предпросмотр","Preview"},{"Награда","Reward"},{"Награды","Rewards"},{"Сотрудник","Staff member"},{"Сотрудники","Staff"},{"Журнал","Log"},{"Действий","actions"},{"Действие","Action"},{"Проверка","Check"},{"Мир","World"},{"Погода","Weather"},{"Шлем","Helmet"},{"Требование","Requirement"},{"Сообщение","Message"},{"Сохранено","Saved"},{"Сохранить","Save"},{"Добавить","Add"},{"Очистить","Clear"},{"Сбросить","Reset"},{"Ежедневный","Daily"},{"Ежедневное","Daily"},{"Еженедельный","Weekly"},{"Еженедельное","Weekly"},{"День","Day"},{"Нажмите","Click"},{"Текущий","Current"},{"Текущее","Current"},{"Сейчас","Now"},{"Следующий","Next"},{"Последний","Last"},{"Последнее","Latest"},{"История","History"},{"Состояние","Status"},{"Активно","Active"},{"Завершено","Finished"},{"Ожидает","Waiting"},{"Свой","Custom"},{"Своих","Own"},{"Ваши","Your"},{"Ваших","Your"},{"Координаты","Coordinates"},{"Поставил","Placed by"},{"Кто","Who"},{"Тестовый","Test"},{"Тест","Test"},{"Режим","Mode"},{"Случайный","Random"},{"Смешанный","Mixed"},{"Сбор","Collection"},{"Собрать","Collect"},{"Доступ","Access"},{"Доступен","Available"},{"Недоступно","Unavailable"},{"Разрешено","Allowed"},{"Запрещено","Denied"},{"Вода","Water"},{"Лава","Lava"},{"Применить","Apply"},{"Палитра","Palette"},{"Размер","Size"},{"Скорость","Speed"},{"Громкость","Volume"},{"Тон","Pitch"},{"Понедельник","Monday"},{"понедельник","Monday"},{"Вторник","Tuesday"},{"вторник","Tuesday"},{"Среда","Wednesday"},{"среда","Wednesday"},{"Четверг","Thursday"},{"четверг","Thursday"},{"Пятница","Friday"},{"пятница","Friday"},{"Суббота","Saturday"},{"суббота","Saturday"},{"Воскресенье","Sunday"},{"воскресенье","Sunday"}};
                for(String[] x:r)m.put(x[0],x[1]);
            }
        }
        String out=text;
        java.util.List<java.util.Map.Entry<String,String>> entries=new java.util.ArrayList<>(m.entrySet());
        entries.sort((a,b)->Integer.compare(b.getKey().length(),a.getKey().length()));
        for(var e:entries) out=out.replaceAll("(?<![А-Яа-яЁё])"+java.util.regex.Pattern.quote(e.getKey())+"(?![А-Яа-яЁё])",java.util.regex.Matcher.quoteReplacement(e.getValue()));
        return out;
    }

    private void addCoreGuiTranslations(java.util.Map<String,String> m,String family){
        if("ru".equals(family)) return;
        String[][] rows=switch(family){
            case "de" -> new String[][]{
                {"Свой","Eigen"},{"Свой ивент","Eigenes Event"},{"Создать ивент","Event erstellen"},{"Ежедневный запуск","Täglicher Start"},{"Еженедельный запуск","Wöchentlicher Start"},{"Следующий","Nächster"},{"История запусков","Startverlauf"},{"Последний запуск","Letzter Start"},{"Последнее завершение","Letztes Ende"},{"ЛКМ — настройки","LMT — Einstellungen"},{"ПКМ — выключить","RMT — deaktivieren"},{"Центр постановки","Punktzentrum"},{"Мои точки","Meine Punkte"},{"Выбор ивента для точки","Event für Punkt auswählen"},{"Не задано","Nicht festgelegt"},{"не задано","nicht gesetzt"},{"включено","aktiviert"},{"выключено","deaktiviert"}};
            case "uk" -> new String[][]{
                {"Свой","Свій"},{"Свой ивент","Власний івент"},{"Создать ивент","Створити івент"},{"Ежедневный запуск","Щоденний запуск"},{"Еженедельный запуск","Щотижневий запуск"},{"Следующий","Наступний"},{"История запусков","Історія запусків"},{"Последний запуск","Останній запуск"},{"Последнее завершение","Останнє завершення"},{"ЛКМ — настройки","ЛКМ — налаштувати"},{"ПКМ — выключить","ПКМ — вимкнути"},{"Центр постановки","Центр встановлення"},{"Мои точки","Мої точки"},{"Выбор ивента для точки","Вибір івенту для точки"},{"Не задано","Не задано"},{"не задано","не задано"},{"включено","увімкнено"},{"выключено","вимкнено"}};
            case "es" -> new String[][]{
                {"Свой","Propio"},{"Свой ивент","Evento propio"},{"Создать ивент","Crear evento"},{"Ежедневный запуск","Inicio diario"},{"Еженедельный запуск","Inicio semanal"},{"Следующий","Siguiente"},{"История запусков","Historial de ejecuciones"},{"Последний запуск","Última ejecución"},{"Последнее завершение","Última finalización"},{"ЛКМ — настройки","Clic izq. — configurar"},{"ПКМ — выключить","Clic der. — desactivar"},{"Центр постановки","Centro de puntos"},{"Мои точки","Mis puntos"},{"Выбор ивента для точки","Seleccionar evento para el punto"},{"Не задано","No establecido"},{"не задано","no establecido"},{"включено","activado"},{"выключено","desactivado"}};
            case "kk" -> new String[][]{
                {"Свой","Өзімдік"},{"Свой ивент","Өз ивентіңіз"},{"Создать ивент","Ивент жасау"},{"Ежедневный запуск","Күнделікті іске қосу"},{"Еженедельный запуск","Апталық іске қосу"},{"Следующий","Келесі"},{"История запусков","Іске қосу тарихы"},{"Последний запуск","Соңғы іске қосу"},{"Последнее завершение","Соңғы аяқталу"},{"ЛКМ — настройки","Сол жақ — баптау"},{"ПКМ — выключить","Оң жақ — өшіру"},{"Центр постановки","Нүкте қою орталығы"},{"Мои точки","Менің нүктелерім"},{"Выбор ивента для точки","Нүкте үшін ивент таңдау"},{"Не задано","Орнатылмаған"},{"не задано","орнатылмаған"},{"включено","қосулы"},{"выключено","өшірулі"}};
            case "fr" -> new String[][]{
                {"Свой","Personnel"},{"Свой ивент","Événement personnel"},{"Создать ивент","Créer un événement"},{"Ежедневный запуск","Lancement quotidien"},{"Еженедельный запуск","Lancement hebdomadaire"},{"Следующий","Suivant"},{"История запусков","Historique des lancements"},{"Последний запуск","Dernier lancement"},{"Последнее завершение","Dernière fin"},{"ЛКМ — настройки","Clic gauche — configurer"},{"ПКМ — выключить","Clic droit — désactiver"},{"Центр постановки","Centre des points"},{"Мои точки","Mes points"},{"Выбор ивента для точки","Choisir l’événement du point"},{"Не задано","Non défini"},{"не задано","non défini"},{"включено","activé"},{"выключено","désactivé"}};
            case "pl" -> new String[][]{
                {"Свой","Własne"},{"Свой ивент","Własne wydarzenie"},{"Создать ивент","Utwórz wydarzenie"},{"Ежедневный запуск","Uruchamianie codzienne"},{"Еженедельный запуск","Uruchamianie tygodniowe"},{"Следующий","Następny"},{"История запусков","Historia uruchomień"},{"Последний запуск","Ostatnie uruchomienie"},{"Последнее завершение","Ostatnie zakończenie"},{"ЛКМ — настройки","LPM — konfiguruj"},{"ПКМ — выключить","PPM — wyłącz"},{"Центр постановки","Centrum punktów"},{"Мои точки","Moje punkty"},{"Выбор ивента для точки","Wybierz wydarzenie dla punktu"},{"Не задано","Nie ustawiono"},{"не задано","nie ustawiono"},{"включено","włączone"},{"выключено","wyłączone"}};
            default -> new String[][]{
                {"Свой","Custom"},{"Свой ивент","Custom Event"},{"Создать ивент","Create Event"},{"Ежедневный запуск","Daily Start"},{"Еженедельный запуск","Weekly Start"},{"Следующий","Next"},{"История запусков","Run History"},{"Последний запуск","Last Run"},{"Последнее завершение","Last End"},{"ЛКМ — настройки","LMB — settings"},{"ПКМ — выключить","RMB — disable"},{"Центр постановки","Point Center"},{"Мои точки","My Points"},{"Выбор ивента для точки","Select Event for Point"},{"Не задано","Not set"},{"не задано","not set"},{"включено","enabled"},{"выключено","disabled"}};
        };
        for(String[] row:rows) m.put(row[0],row[1]);
    }

    private void addHighCoverageGuiTranslations(java.util.Map<String,String> m,String family){
        String[][] rows;
        switch(family){
            case "de" -> rows=new String[][]{
                {"Поиск","Suche"},{"Фильтр","Filter"},{"Страница","Seite"},{"Следующая →","Nächste →"},{"← Предыдущая","← Vorherige"},{"Список пуст","Liste leer"},{"Точки • Свои","Punkte • Eigene"},{"Точки • Все","Punkte • Alle"},{"Точки и блокировки","Punkte und Sperren"},{"Инструмент точки","Punktwerkzeug"},{"Дублировать ивент","Event duplizieren"},{"Режим спавна","Spawnmodus"},{"Минимальная награда","Mindestbelohnung"},{"Максимальная награда","Maximale Belohnung"},{"Минимальная сумма","Mindestbetrag"},{"Максимальная сумма","Höchstbetrag"},{"Сообщение при отказе","Ablehnungsmeldung"},{"Штраф при отказе","Strafe bei Ablehnung"},{"Ежедневный запуск","Täglicher Start"},{"Еженедельный запуск","Wöchentlicher Start"},{"История запусков","Startverlauf"},{"Последний запуск","Letzter Start"},{"Последнее завершение","Letztes Ende"},{"СЛУЧАЙНЫЙ","ZUFALL"},{"СМЕШАННЫЙ","GEMISCHT"},{"ТОЧКИ","PUNKTE"},{"Шанс спавна","Spawnchance"},{"Высота Y","Höhe Y"},{"Не спавнить рядом с игроками","Nicht in Spielnähe spawnen"},{"Интервал спавна","Spawnintervall"},{"Свой HEX-цвет","Eigene HEX-Farbe"},{"Всего выбрано","Insgesamt ausgewählt"},{"Выбрано нот","Noten ausgewählt"},{"Настройки частицы","Partikeleinstellungen"},{"Нет дополнительных параметров","Keine zusätzlichen Parameter"},{"Случайный поворот","Zufällige Drehung"},{"Твёрдая опора","Fester Boden"},{"Плавающий спавн","Schwebender Spawn"},{"Разрешение воды","Wasser erlauben"},{"Разрешение лавы","Lava erlauben"},{"Лимит точек","Punktlimit"},{"Радиус от центра","Radius vom Zentrum"},{"Центр постановки","Punktzentrum"},{"Основные настройки","Allgemeine Einstellungen"}};
            case "uk" -> rows=new String[][]{
                {"Поиск","Пошук"},{"Фильтр","Фільтр"},{"Страница","Сторінка"},{"Следующая →","Наступна →"},{"← Предыдущая","← Попередня"},{"Список пуст","Список порожній"},{"Точки • Свои","Точки • Власні"},{"Точки • Все","Точки • Усі"},{"Точки и блокировки","Точки та блокування"},{"Инструмент точки","Інструмент точки"},{"Дублировать ивент","Дублювати івент"},{"Режим спавна","Режим спавну"},{"Минимальная награда","Мінімальна нагорода"},{"Максимальная награда","Максимальна нагорода"},{"Минимальная сумма","Мінімальна сума"},{"Максимальная сумма","Максимальна сума"},{"Сообщение при отказе","Повідомлення про відмову"},{"Штраф при отказе","Штраф за відмову"},{"История запусков","Історія запусків"},{"Последний запуск","Останній запуск"},{"Последнее завершение","Останнє завершення"},{"СЛУЧАЙНЫЙ","ВИПАДКОВИЙ"},{"СМЕШАННЫЙ","ЗМІШАНИЙ"},{"ТОЧКИ","ТОЧКИ"},{"Шанс спавна","Шанс спавну"},{"Высота Y","Висота Y"},{"Не спавнить рядом с игроками","Не спавнити поруч з гравцями"},{"Интервал спавна","Інтервал спавну"},{"Еженедельное расписание шаблона","Щотижневий розклад шаблону"},{"Свой HEX-цвет","Власний HEX-колір"},{"Всего выбрано","Усього вибрано"},{"Выбрано нот","Вибрано нот"},{"Настройки частицы","Налаштування частинки"},{"Нет дополнительных параметров","Немає додаткових параметрів"},{"Случайный поворот","Випадковий поворот"},{"Твёрдая опора","Тверда опора"},{"Плавающий спавн","Плаваючий спавн"},{"Разрешение воды","Дозвіл води"},{"Разрешение лавы","Дозвіл лави"},{"Лимит точек","Ліміт точок"},{"Радиус от центра","Радіус від центру"},{"Центр постановки","Центр встановлення"},{"Основные настройки","Основні налаштування"}};
            case "es" -> rows=new String[][]{
                {"Поиск","Búsqueda"},{"Фильтр","Filtro"},{"Страница","Página"},{"Следующая →","Siguiente →"},{"← Предыдущая","← Anterior"},{"Список пуст","Lista vacía"},{"Точки • Свои","Puntos • Propios"},{"Точки • Все","Puntos • Todos"},{"Точки и блокировки","Puntos y bloqueos"},{"Инструмент точки","Herramienta de puntos"},{"Дублировать ивент","Duplicar evento"},{"Режим спавна","Modo de aparición"},{"Минимальная награда","Recompensa mínima"},{"Максимальная награда","Recompensa máxima"},{"Минимальная сумма","Cantidad mínima"},{"Максимальная сумма","Cantidad máxima"},{"Сообщение при отказе","Mensaje de rechazo"},{"Штраф при отказе","Penalización por rechazo"},{"История запусков","Historial de ejecuciones"},{"Последний запуск","Última ejecución"},{"Последнее завершение","Última finalización"},{"СЛУЧАЙНЫЙ","ALEATORIO"},{"СМЕШАННЫЙ","MIXTO"},{"ТОЧКИ","PUNTOS"},{"Шанс спавна","Probabilidad de aparición"},{"Высота Y","Altura Y"},{"Не спавнить рядом с игроками","No aparecer cerca de jugadores"},{"Интервал спавна","Intervalo de aparición"},{"Свой HEX-цвет","Color HEX personalizado"},{"Всего выбрано","Total seleccionado"},{"Выбрано нот","Notas seleccionadas"},{"Настройки частицы","Ajustes de partícula"},{"Нет дополнительных параметров","Sin parámetros adicionales"},{"Случайный поворот","Rotación aleatoria"},{"Твёрдая опора","Suelo sólido"},{"Плавающий спавн","Aparición flotante"},{"Разрешение воды","Permitir agua"},{"Разрешение лавы","Permitir lava"},{"Лимит точек","Límite de puntos"},{"Радиус от центра","Radio desde el centro"},{"Центр постановки","Centro de puntos"},{"Основные настройки","Ajustes generales"}};
            case "kk" -> rows=new String[][]{
                {"Поиск","Іздеу"},{"Фильтр","Сүзгі"},{"Страница","Бет"},{"Следующая →","Келесі →"},{"← Предыдущая","← Алдыңғы"},{"Список пуст","Тізім бос"},{"Точки • Свои","Нүктелер • Өзімнің"},{"Точки • Все","Нүктелер • Барлығы"},{"Точки и блокировки","Нүктелер және бұғаттар"},{"Инструмент точки","Нүкте құралы"},{"Дублировать ивент","Ивентті көшіру"},{"Режим спавна","Спавн режимі"},{"Минимальная награда","Ең аз сыйақы"},{"Максимальная награда","Ең көп сыйақы"},{"Минимальная сумма","Ең аз сома"},{"Максимальная сумма","Ең көп сома"},{"Сообщение при отказе","Бас тарту хабары"},{"Штраф при отказе","Бас тарту айыбы"},{"История запусков","Іске қосу тарихы"},{"Последний запуск","Соңғы іске қосу"},{"Последнее завершение","Соңғы аяқталу"},{"СЛУЧАЙНЫЙ","КЕЗДЕЙСОҚ"},{"СМЕШАННЫЙ","АРАЛАС"},{"ТОЧКИ","НҮКТЕЛЕР"},{"Шанс спавна","Спавн ықтималдығы"},{"Высота Y","Y биіктігі"},{"Не спавнить рядом с игроками","Ойыншылардың жанында спавн жасамау"},{"Интервал спавна","Спавн аралығы"},{"Свой HEX-цвет","Өз HEX түсі"},{"Всего выбрано","Барлығы таңдалды"},{"Выбрано нот","Ноталар таңдалды"},{"Настройки частицы","Бөлшек параметрлері"},{"Нет дополнительных параметров","Қосымша параметрлер жоқ"},{"Случайный поворот","Кездейсоқ бұрылыс"},{"Твёрдая опора","Қатты тірек"},{"Плавающий спавн","Қалқымалы спавн"},{"Разрешение воды","Суға рұқсат"},{"Разрешение лавы","Лаваға рұқсат"},{"Лимит точек","Нүкте лимиті"},{"Радиус от центра","Орталықтан радиус"},{"Центр постановки","Нүкте қою орталығы"},{"Основные настройки","Негізгі баптаулар"}};
            case "fr" -> rows=new String[][]{
                {"Поиск","Recherche"},{"Фильтр","Filtre"},{"Страница","Page"},{"Следующая →","Suivante →"},{"← Предыдущая","← Précédente"},{"Список пуст","Liste vide"},{"Точки • Свои","Points • Mes points"},{"Точки • Все","Points • Tous"},{"Точки и блокировки","Points et blocages"},{"Инструмент точки","Outil de points"},{"Дублировать ивент","Dupliquer l’événement"},{"Режим спавна","Mode d’apparition"},{"Минимальная награда","Récompense minimale"},{"Максимальная награда","Récompense maximale"},{"Минимальная сумма","Montant minimal"},{"Максимальная сумма","Montant maximal"},{"Сообщение при отказе","Message de refus"},{"Штраф при отказе","Pénalité de refus"},{"История запусков","Historique des lancements"},{"Последний запуск","Dernier lancement"},{"Последнее завершение","Dernière fin"},{"СЛУЧАЙНЫЙ","ALÉATOIRE"},{"СМЕШАННЫЙ","MIXTE"},{"ТОЧКИ","POINTS"},{"Шанс спавна","Chance d’apparition"},{"Высота Y","Hauteur Y"},{"Не спавнить рядом с игроками","Ne pas apparaître près des joueurs"},{"Интервал спавна","Intervalle d’apparition"},{"Свой HEX-цвет","Couleur HEX personnalisée"},{"Всего выбрано","Total sélectionné"},{"Выбрано нот","Notes sélectionnées"},{"Настройки частицы","Réglages de particule"},{"Нет дополнительных параметров","Aucun paramètre supplémentaire"},{"Случайный поворот","Rotation aléatoire"},{"Твёрдая опора","Sol solide"},{"Плавающий спавн","Apparition flottante"},{"Разрешение воды","Autoriser l’eau"},{"Разрешение лавы","Autoriser la lave"},{"Лимит точек","Limite de points"},{"Радиус от центра","Rayon depuis le centre"},{"Центр постановки","Centre des points"},{"Основные настройки","Réglages généraux"}};
            case "pl" -> rows=new String[][]{
                {"Поиск","Wyszukiwanie"},{"Фильтр","Filtr"},{"Страница","Strona"},{"Следующая →","Następna →"},{"← Предыдущая","← Poprzednia"},{"Список пуст","Lista pusta"},{"Точки • Свои","Punkty • Własne"},{"Точки • Все","Punkty • Wszystkie"},{"Точки и блокировки","Punkty i blokady"},{"Инструмент точки","Narzędzie punktów"},{"Дублировать ивент","Duplikuj wydarzenie"},{"Режим спавна","Tryb pojawiania"},{"Минимальная награда","Minimalna nagroda"},{"Максимальная награда","Maksymalna nagroda"},{"Минимальная сумма","Minimalna kwota"},{"Максимальная сумма","Maksymalna kwota"},{"Сообщение при отказе","Komunikat odmowy"},{"Штраф при отказе","Kara za odmowę"},{"История запусков","Historia uruchomień"},{"Последний запуск","Ostatnie uruchomienie"},{"Последнее завершение","Ostatnie zakończenie"},{"СЛУЧАЙНЫЙ","LOSOWY"},{"СМЕШАННЫЙ","MIESZANY"},{"ТОЧКИ","PUNKTY"},{"Шанс спавна","Szansa pojawienia"},{"Высота Y","Wysokość Y"},{"Не спавнить рядом с игроками","Nie pojawiaj w pobliżu graczy"},{"Интервал спавна","Interwał pojawiania"},{"Свой HEX-цвет","Własny kolor HEX"},{"Всего выбрано","Wybrano łącznie"},{"Выбрано нот","Wybrane nuty"},{"Настройки частицы","Ustawienia cząsteczki"},{"Нет дополнительных параметров","Brak dodatkowych parametrów"},{"Случайный поворот","Losowy obrót"},{"Твёрдая опора","Stałe podłoże"},{"Плавающий спавн","Pływające pojawianie"},{"Разрешение воды","Zezwól na wodę"},{"Разрешение лавы","Zezwól na lawę"},{"Лимит точек","Limit punktów"},{"Радиус от центра","Promień od środka"},{"Центр постановки","Centrum punktów"},{"Основные настройки","Ustawienia ogólne"}};
            default -> rows=new String[][]{
                {"Поиск","Search"},{"Фильтр","Filter"},{"Страница","Page"},{"Следующая →","Next →"},{"← Предыдущая","← Previous"},{"Список пуст","List is empty"},{"Точки и блокировки","Points and blocks"},{"Инструмент точки","Point tool"},{"Дублировать ивент","Duplicate event"},{"Режим спавна","Spawn mode"},{"Минимальная награда","Minimum reward"},{"Максимальная награда","Maximum reward"},{"Минимальная сумма","Minimum amount"},{"Максимальная сумма","Maximum amount"},{"Сообщение при отказе","Failure message"},{"Штраф при отказе","Failure penalty"},{"История запусков","Run history"},{"Последний запуск","Last run"},{"Последнее завершение","Last end"},{"СЛУЧАЙНЫЙ","RANDOM"},{"СМЕШАННЫЙ","MIXED"},{"ТОЧКИ","POINTS"},{"Шанс спавна","Spawn chance"},{"Высота Y","Y height"},{"Не спавнить рядом с игроками","Do not spawn near players"},{"Интервал спавна","Spawn interval"},{"Еженедельное расписание шаблона","Template weekly schedule"},{"Свой HEX-цвет","Custom HEX color"},{"Всего выбрано","Total selected"},{"Выбрано нот","Notes selected"},{"Настройки частицы","Particle settings"},{"Нет дополнительных параметров","No additional parameters"},{"Случайный поворот","Random rotation"},{"Твёрдая опора","Solid ground"},{"Плавающий спавн","Floating spawn"},{"Разрешение воды","Allow water"},{"Разрешение лавы","Allow lava"},{"Лимит точек","Point limit"},{"Радиус от центра","Radius from center"},{"Центр постановки","Point center"},{"Основные настройки","General settings"}};
        }
        for(String[] row:rows)m.put(row[0],row[1]);
    }

    /** Additional high-frequency system words used by legacy command/chat paths. */
    private void addFallbackCommonTranslations(java.util.Map<String,String> m,String family){
        String[][] rows=switch(family){
            case "de" -> new String[][]{
                {"Не удалось","Fehlgeschlagen"},{"не удалось","fehlgeschlagen"},{"Недостаточно","Nicht genug"},{"недостаточно","nicht genug"},{"средств","Guthaben"},{"экономика","Wirtschaft"},{"доступна","verfügbar"},{"доступен","verfügbar"},{"доступно","verfügbar"},{"недоступно","nicht verfügbar"},
                {"Ошибка","Fehler"},{"ошибка","Fehler"},{"Неверный","Ungültig"},{"неверный","ungültig"},{"Некорректно","Ungültig"},{"некорректно","ungültig"},{"Некорректное","Ungültiges"},{"некорректное","ungültiges"},
                {"Удалено","Gelöscht"},{"удалено","gelöscht"},{"Удалён","Gelöscht"},{"удалён","gelöscht"},{"Удалена","Gelöscht"},{"удалена","gelöscht"},{"Удалены","Gelöscht"},{"удалены","gelöscht"},
                {"Создан","Erstellt"},{"создан","erstellt"},{"Создана","Erstellt"},{"создана","erstellt"},{"Создано","Erstellt"},{"создано","erstellt"},{"Добавлен","Hinzugefügt"},{"добавлен","hinzugefügt"},{"Добавлена","Hinzugefügt"},{"добавлена","hinzugefügt"},
                {"Сохранено","Gespeichert"},{"сохранено","gespeichert"},{"Сохранена","Gespeichert"},{"сохранена","gespeichert"},{"Сброшено","Zurückgesetzt"},{"сброшено","zurückgesetzt"},{"Установлен","Gesetzt"},{"установлен","gesetzt"},
                {"Установлена","Gesetzt"},{"установлена","gesetzt"},{"Выдан","Ausgegeben"},{"выдан","ausgegeben"},{"Выдана","Ausgegeben"},{"выдана","ausgegeben"},{"Выдано","Ausgegeben"},{"выдано","ausgegeben"},
                {"Свободного","Freier"},{"свободного","freier"},{"места","Platz"},{"Место","Ort"},{"место","Ort"},{"занято","belegt"},{"блоком","Block"},{"требуется","erforderlich"},{"Требуется","Erforderlich"},
                {"снизу","unten"},{"слева","links"},{"справа","rechts"},{"внутри","innen"},{"снаружи","außen"},{"рядом","in der Nähe"},{"слишком","zu"},{"близко","nah"},{"далеко","weit"},{"игроком","Spieler"},
                {"Игроком","Spieler"},{"Пропущены","Übersprungen"},{"пропущены","übersprungen"},{"Некоторые","Einige"},{"некоторые","einige"},{"Причина","Grund"},{"причина","Grund"},{"Изменён","Geändert"},{"изменён","geändert"},
                {"Изменено","Geändert"},{"изменено","geändert"},{"Изменить","Ändern"},{"изменить","ändern"},{"Нельзя","Nicht möglich"},{"нельзя","nicht möglich"},{"можно","möglich"},{"Можно","Möglich"},{"Нужно","Benötigt"},{"нужно","benötigt"},
                {"введите","Eingeben"},{"Введите","Eingeben"},{"напишите","Schreiben"},{"Напишите","Schreiben"},{"Нажмите","Klicken"},{"нажмите","klicken"},{"Повторите","Wiederholen"},{"повторите","wiederholen"},{"Подтвердите","Bestätigen"},{"подтвердите","bestätigen"},
                {"Отменено","Abgebrochen"},{"отменено","abgebrochen"},{"Отменить","Abbrechen"},{"отменить","abbrechen"},{"Поиск","Suche"},{"поиск","Suche"},{"Найден","Gefunden"},{"найден","gefunden"},{"Найдено","Gefunden"},{"найдено","gefunden"},
                {"не найден","nicht gefunden"},{"Не найден","Nicht gefunden"},{"Участник","Mitglied"},{"участник","Mitglied"},{"Участники","Mitglieder"},{"участники","Mitglieder"},{"включено","aktiviert"},{"Включено","Aktiviert"},{"выключено","deaktiviert"},{"Выключено","Deaktiviert"},
                {"работает","funktioniert"},{"Работает","Funktioniert"},{"сейчас","jetzt"},{"Сейчас","Jetzt"},{"уже","bereits"},{"Уже","Bereits"},{"только","nur"},{"Только","Nur"},{"все","alle"},{"Все","Alle"},
                {"действия","Aktionen"},{"Действия","Aktionen"},{"настройки","Einstellungen"},{"Настройки","Einstellungen"},{"условия","Bedingungen"},{"Условия","Bedingungen"},{"применить","anwenden"},{"Применить","Anwenden"},{"сохранить","speichern"},{"Сохранить","Speichern"}
            };
            case "uk" -> new String[][]{
                {"Не удалось","Не вдалося"},{"не удалось","не вдалося"},{"Недостаточно","Недостатньо"},{"недостаточно","недостатньо"},{"средств","коштів"},{"экономика","економіка"},{"доступна","доступна"},{"доступен","доступний"},{"доступно","доступно"},{"недоступно","недоступно"},
                {"Ошибка","Помилка"},{"ошибка","помилка"},{"Неверный","Невірний"},{"неверный","невірний"},{"Некорректно","Некоректно"},{"некорректно","некоректно"},{"Некорректное","Некоректне"},{"некорректное","некоректне"},
                {"Удалено","Видалено"},{"удалено","видалено"},{"Удалён","Видалено"},{"удалён","видалено"},{"Удалена","Видалена"},{"удалена","видалена"},{"Удалены","Видалено"},{"удалены","видалено"},
                {"Создан","Створено"},{"создан","створено"},{"Создана","Створена"},{"создана","створена"},{"Создано","Створено"},{"создано","створено"},{"Добавлен","Додано"},{"добавлен","додано"},{"Добавлена","Додана"},{"добавлена","додана"},
                {"Сохранено","Збережено"},{"сохранено","збережено"},{"Сохранена","Збережена"},{"сохранена","збережена"},{"Сброшено","Скинуто"},{"сброшено","скинуто"},{"Установлен","Встановлено"},{"установлен","встановлено"},
                {"Установлена","Встановлена"},{"установлена","встановлена"},{"Выдан","Видано"},{"выдан","видано"},{"Выдана","Видана"},{"выдана","видана"},{"Выдано","Видано"},{"выдано","видано"},
                {"Свободного","Вільного"},{"свободного","вільного"},{"места","місця"},{"Место","Місце"},{"место","місце"},{"занято","зайнято"},{"блоком","блоком"},{"требуется","потрібно"},{"Требуется","Потрібно"},
                {"снизу","знизу"},{"рядом","поруч"},{"слишком","занадто"},{"близко","близько"},{"далеко","далеко"},{"Пропущены","Пропущено"},{"пропущены","пропущено"},{"Некоторые","Деякі"},{"некоторые","деякі"},{"Причина","Причина"},{"причина","причина"},
                {"Изменён","Змінено"},{"изменён","змінено"},{"Изменено","Змінено"},{"изменено","змінено"},{"Изменить","Змінити"},{"изменить","змінити"},{"Нельзя","Не можна"},{"нельзя","не можна"},{"можно","можна"},{"Можно","Можна"},{"Нужно","Потрібно"},{"нужно","потрібно"},
                {"введите","введіть"},{"Введите","Введіть"},{"напишите","напишіть"},{"Напишите","Напишіть"},{"Нажмите","Натисніть"},{"нажмите","натисніть"},{"Повторите","Повторіть"},{"повторите","повторіть"},{"Подтвердите","Підтвердьте"},{"подтвердите","підтвердьте"},
                {"Отменено","Скасовано"},{"отменено","скасовано"},{"Отменить","Скасувати"},{"отменить","скасувати"},{"Найден","Знайдено"},{"найден","знайдено"},{"Найдено","Знайдено"},{"найдено","знайдено"},{"Не найден","Не знайдено"},{"не найден","не знайдено"},
                {"Участник","Учасник"},{"участник","учасник"},{"Участники","Учасники"},{"участники","учасники"},{"включено","увімкнено"},{"Включено","Увімкнено"},{"выключено","вимкнено"},{"Выключено","Вимкнено"},{"работает","працює"},{"Работает","Працює"},
                {"сейчас","зараз"},{"Сейчас","Зараз"},{"уже","вже"},{"Уже","Вже"},{"только","лише"},{"Только","Лише"},{"все","всі"},{"Все","Всі"},{"действия","дії"},{"Действия","Дії"},{"настройки","налаштування"},{"Настройки","Налаштування"},{"условия","умови"},{"Условия","Умови"}
            };
            case "es" -> new String[][]{
                {"Не удалось","No se pudo"},{"не удалось","no se pudo"},{"Недостаточно","Insuficiente"},{"недостаточно","insuficiente"},{"средств","fondos"},{"экономика","economía"},{"доступна","disponible"},{"доступен","disponible"},{"доступно","disponible"},{"недоступно","no disponible"},{"Ошибка","Error"},{"ошибка","error"},{"Неверный","Inválido"},{"неверный","inválido"},{"Некорректно","Incorrecto"},{"некорректно","incorrecto"},{"Удалено","Eliminado"},{"удалено","eliminado"},{"Удалён","Eliminado"},{"удалён","eliminado"},{"Удалена","Eliminada"},{"удалена","eliminada"},{"Удалены","Eliminados"},{"удалены","eliminados"},
                {"Создан","Creado"},{"создан","creado"},{"Создана","Creada"},{"создана","creada"},{"Создано","Creado"},{"создано","creado"},{"Добавлен","Añadido"},{"добавлен","añadido"},{"Добавлена","Añadida"},{"добавлена","añadida"},{"Сохранено","Guardado"},{"сохранено","guardado"},{"Сохранена","Guardada"},{"сохранена","guardada"},{"Сброшено","Restablecido"},{"сброшено","restablecido"},
                {"Установлен","Establecido"},{"установлен","establecido"},{"Установлена","Establecida"},{"установлена","establecida"},{"Выдан","Entregado"},{"выдан","entregado"},{"Выдана","Entregada"},{"выдана","entregada"},{"Выдано","Entregado"},{"выдано","entregado"},{"Свободного","Libre"},{"свободного","libre"},{"места","espacio"},{"Место","Lugar"},{"место","lugar"},{"занято","ocupado"},{"блоком","por el bloque"},{"требуется","se requiere"},{"Требуется","Se requiere"},
                {"рядом","cerca"},{"слишком","demasiado"},{"близко","cerca"},{"далеко","lejos"},{"Пропущены","Omitidos"},{"пропущены","omitidos"},{"Некоторые","Algunos"},{"некоторые","algunos"},{"Причина","Motivo"},{"причина","motivo"},{"Изменён","Modificado"},{"изменён","modificado"},{"Изменено","Modificado"},{"изменено","modificado"},{"Изменить","Editar"},{"изменить","editar"},{"Нельзя","No se puede"},{"нельзя","no se puede"},{"можно","se puede"},{"Можно","Se puede"},{"Нужно","Necesario"},{"нужно","necesario"},
                {"введите","introduzca"},{"Введите","Introduzca"},{"напишите","escriba"},{"Напишите","Escriba"},{"Нажмите","Haga clic"},{"нажмите","haga clic"},{"Повторите","Repita"},{"повторите","repita"},{"Подтвердите","Confirme"},{"подтвердите","confirme"},{"Отменено","Cancelado"},{"отменено","cancelado"},{"Отменить","Cancelar"},{"отменить","cancelar"},{"Найден","Encontrado"},{"найден","encontrado"},{"Найдено","Encontrado"},{"найдено","encontrado"},{"Не найден","No encontrado"},{"не найден","no encontrado"},{"Участник","Miembro"},{"участник","miembro"},{"Участники","Miembros"},{"участники","miembros"},{"включено","activado"},{"Включено","Activado"},{"выключено","desactivado"},{"Выключено","Desactivado"},{"сейчас","ahora"},{"Сейчас","Ahora"},{"уже","ya"},{"Уже","Ya"},{"только","solo"},{"Только","Solo"},{"все","todos"},{"Все","Todos"},{"действия","acciones"},{"Действия","Acciones"},{"настройки","ajustes"},{"Настройки","Ajustes"},{"условия","condiciones"},{"Условия","Condiciones"}
            };
            case "kk" -> new String[][]{
                {"Не удалось","Орындалмады"},{"не удалось","орындалмады"},{"Недостаточно","Жеткіліксіз"},{"недостаточно","жеткіліксіз"},{"средств","қаражат"},{"экономика","экономика"},{"доступна","қолжетімді"},{"доступен","қолжетімді"},{"доступно","қолжетімді"},{"недоступно","қолжетімсіз"},{"Ошибка","Қате"},{"ошибка","қате"},{"Неверный","Қате"},{"неверный","қате"},{"Некорректно","Қате"},{"некорректно","қате"},{"Удалено","Жойылды"},{"удалено","жойылды"},{"Удалён","Жойылды"},{"удалён","жойылды"},{"Удалена","Жойылды"},{"удалена","жойылды"},{"Удалены","Жойылды"},{"удалены","жойылды"},{"Создан","Жасалды"},{"создан","жасалды"},{"Создана","Жасалды"},{"создана","жасалды"},{"Создано","Жасалды"},{"создано","жасалды"},{"Добавлен","Қосылды"},{"добавлен","қосылды"},{"Добавлена","Қосылды"},{"добавлена","қосылды"},{"Сохранено","Сақталды"},{"сохранено","сақталды"},{"Сохранена","Сақталды"},{"сохранена","сақталды"},{"Сброшено","Қалпына келтірілді"},{"сброшено","қалпына келтірілді"},{"Установлен","Орнатылды"},{"установлен","орнатылды"},{"Выдан","Берілді"},{"выдан","берілді"},{"Выдана","Берілді"},{"выдана","берілді"},{"Свободного","Бос"},{"свободного","бос"},{"места","орын"},{"Место","Орын"},{"место","орын"},{"занято","бос емес"},{"блоком","блок"},{"требуется","қажет"},{"Требуется","Қажет"},{"рядом","жақын"},{"слишком","тым"},{"близко","жақын"},{"далеко","алыс"},{"Пропущены","Өткізілді"},{"пропущены","өткізілді"},{"Некоторые","Кейбір"},{"некоторые","кейбір"},{"Причина","Себеп"},{"причина","себеп"},{"Изменено","Өзгертілді"},{"изменено","өзгертілді"},{"Изменить","Өзгерту"},{"изменить","өзгерту"},{"Нельзя","Болмайды"},{"нельзя","болмайды"},{"можно","болады"},{"Можно","Болады"},{"Нужно","Қажет"},{"нужно","қажет"},{"Введите","Енгізіңіз"},{"введите","енгізіңіз"},{"Напишите","Жазыңыз"},{"напишите","жазыңыз"},{"Нажмите","Басыңыз"},{"нажмите","басыңыз"},{"Повторите","Қайта орындаңыз"},{"повторите","қайта орындаңыз"},{"Подтвердите","Растау"},{"подтвердите","растаңыз"},{"Отменено","Бас тартылды"},{"отменено","бас тартылды"},{"Отменить","Бас тарту"},{"отменить","бас тарту"},{"Найден","Табылды"},{"найден","табылды"},{"Найдено","Табылды"},{"найдено","табылды"},{"Не найден","Табылмады"},{"не найден","табылмады"},{"Участник","Қатысушы"},{"участник","қатысушы"},{"Участники","Қатысушылар"},{"участники","қатысушылар"},{"включено","қосулы"},{"Включено","Қосулы"},{"выключено","өшірулі"},{"Выключено","Өшірулі"},{"сейчас","қазір"},{"Сейчас","Қазір"},{"уже","қазірдің өзінде"},{"Уже","Қазірдің өзінде"},{"только","тек"},{"Только","Тек"},{"все","барлығы"},{"Все","Барлығы"},{"действия","әрекеттер"},{"Действия","Әрекеттер"},{"настройки","баптаулар"},{"Настройки","Баптаулар"},{"условия","шарттар"},{"Условия","Шарттар"}
            };
            case "fr" -> new String[][]{
                {"Не удалось","Échec"},{"не удалось","échec"},{"Недостаточно","Insuffisant"},{"недостаточно","insuffisant"},{"средств","fonds"},{"экономика","économie"},{"доступна","disponible"},{"доступен","disponible"},{"доступно","disponible"},{"недоступно","indisponible"},{"Ошибка","Erreur"},{"ошибка","erreur"},{"Неверный","Invalide"},{"неверный","invalide"},{"Некорректно","Incorrect"},{"некорректно","incorrect"},{"Удалено","Supprimé"},{"удалено","supprimé"},{"Удалён","Supprimé"},{"удалён","supprimé"},{"Удалена","Supprimée"},{"удалена","supprimée"},{"Удалены","Supprimés"},{"удалены","supprimés"},{"Создан","Créé"},{"создан","créé"},{"Создана","Créée"},{"создана","créée"},{"Создано","Créé"},{"создано","créé"},{"Добавлен","Ajouté"},{"добавлен","ajouté"},{"Добавлена","Ajoutée"},{"добавлена","ajoutée"},{"Сохранено","Enregistré"},{"сохранено","enregistré"},{"Сохранена","Enregistrée"},{"сохранена","enregistrée"},{"Сброшено","Réinitialisé"},{"сброшено","réinitialisé"},{"Установлен","Défini"},{"установлен","défini"},{"Выдан","Donné"},{"выдан","donné"},{"Выдана","Donnée"},{"выдана","donnée"},{"Свободного","Libre"},{"свободного","libre"},{"места","place"},{"Место","Emplacement"},{"место","emplacement"},{"занято","occupé"},{"блоком","bloc"},{"требуется","requis"},{"Требуется","Requis"},{"рядом","à proximité"},{"слишком","trop"},{"близко","proche"},{"далеко","loin"},{"Пропущены","Ignorés"},{"пропущены","ignorés"},{"Некоторые","Certains"},{"некоторые","certains"},{"Причина","Raison"},{"причина","raison"},{"Изменено","Modifié"},{"изменено","modifié"},{"Изменить","Modifier"},{"изменить","modifier"},{"Нельзя","Impossible"},{"нельзя","impossible"},{"можно","possible"},{"Можно","Possible"},{"Нужно","Nécessaire"},{"нужно","nécessaire"},{"Введите","Entrez"},{"введите","entrez"},{"Напишите","Écrivez"},{"напишите","écrivez"},{"Нажмите","Cliquez"},{"нажмите","cliquez"},{"Повторите","Répétez"},{"повторите","répétez"},{"Подтвердите","Confirmez"},{"подтвердите","confirmez"},{"Отменено","Annulé"},{"отменено","annulé"},{"Отменить","Annuler"},{"отменить","annuler"},{"Найден","Trouvé"},{"найден","trouvé"},{"Найдено","Trouvé"},{"найдено","trouvé"},{"Не найден","Introuvable"},{"не найден","introuvable"},{"Участник","Membre"},{"участник","membre"},{"Участники","Membres"},{"участники","membres"},{"включено","activé"},{"Включено","Activé"},{"выключено","désactivé"},{"Выключено","Désactivé"},{"сейчас","maintenant"},{"Сейчас","Maintenant"},{"уже","déjà"},{"Уже","Déjà"},{"только","seulement"},{"Только","Seulement"},{"все","tous"},{"Все","Tous"},{"действия","actions"},{"Действия","Actions"},{"настройки","réglages"},{"Настройки","Réglages"},{"условия","conditions"},{"Условия","Conditions"}
            };
            default -> new String[][]{
                {"Не удалось","Failed"},{"не удалось","failed"},{"Недостаточно","Insufficient"},{"недостаточно","insufficient"},{"средств","funds"},{"экономика","economy"},{"доступна","available"},{"доступен","available"},{"доступно","available"},{"недоступно","unavailable"},{"Ошибка","Error"},{"ошибка","error"},{"Неверный","Invalid"},{"неверный","invalid"},{"Некорректно","Invalid"},{"некорректно","invalid"},{"Удалено","Deleted"},{"удалено","deleted"},{"Удалён","Deleted"},{"удалён","deleted"},{"Удалена","Deleted"},{"удалена","deleted"},{"Удалены","Deleted"},{"удалены","deleted"},{"Создан","Created"},{"создан","created"},{"Создана","Created"},{"создана","created"},{"Создано","Created"},{"создано","created"},{"Добавлен","Added"},{"добавлен","added"},{"Добавлена","Added"},{"добавлена","added"},{"Сохранено","Saved"},{"сохранено","saved"},{"Сохранена","Saved"},{"сохранена","saved"},{"Сброшено","Reset"},{"сброшено","reset"},{"Установлен","Set"},{"установлен","set"},{"Выдан","Given"},{"выдан","given"},{"Выдана","Given"},{"выдана","given"},{"Свободного","Free"},{"свободного","free"},{"места","space"},{"Место","Place"},{"место","place"},{"занято","occupied"},{"блоком","block"},{"требуется","required"},{"Требуется","Required"},{"рядом","nearby"},{"слишком","too"},{"близко","close"},{"далеко","far"},{"Пропущены","Skipped"},{"пропущены","skipped"},{"Некоторые","Some"},{"некоторые","some"},{"Причина","Reason"},{"причина","reason"},{"Изменено","Changed"},{"изменено","changed"},{"Изменить","Edit"},{"изменить","edit"},{"Нельзя","Cannot"},{"нельзя","cannot"},{"можно","can"},{"Можно","Can"},{"Нужно","Need"},{"нужно","need"},{"Введите","Enter"},{"введите","enter"},{"Напишите","Write"},{"напишите","write"},{"Нажмите","Click"},{"нажмите","click"},{"Повторите","Repeat"},{"повторите","repeat"},{"Подтвердите","Confirm"},{"подтвердите","confirm"},{"Отменено","Cancelled"},{"отменено","cancelled"},{"Отменить","Cancel"},{"отменить","cancel"},{"Найден","Found"},{"найден","found"},{"Найдено","Found"},{"найдено","found"},{"Не найден","Not found"},{"не найден","not found"},{"Участник","Member"},{"участник","member"},{"Участники","Members"},{"участники","members"},{"включено","enabled"},{"Включено","Enabled"},{"выключено","disabled"},{"Выключено","Disabled"},{"сейчас","now"},{"Сейчас","Now"},{"уже","already"},{"Уже","Already"},{"только","only"},{"Только","Only"},{"все","all"},{"Все","All"},{"действия","actions"},{"Действия","Actions"},{"настройки","settings"},{"Настройки","Settings"},{"условия","conditions"},{"Условия","Conditions"}
            };
        };
        for(String[] row:rows) if(row.length==2)m.put(row[0],row[1]);
           if("pl".equals(family)){
            String[][] extra={{"Не удалось","Nie udało się"},{"не удалось","nie udało się"},{"Недостаточно","Niewystarczająco"},{"недостаточно","niewystarczająco"},{"средств","środków"},{"экономика","ekonomia"},{"доступна","dostępna"},{"доступен","dostępny"},{"доступно","dostępne"},{"недоступно","niedostępne"},{"Ошибка","Błąd"},{"ошибка","błąd"},{"Неверный","Nieprawidłowy"},{"неверный","nieprawidłowy"},{"Некорректно","Nieprawidłowo"},{"некорректно","nieprawidłowo"},{"Удалено","Usunięto"},{"удалено","usunięto"},{"Удалён","Usunięto"},{"удалён","usunięto"},{"Удалена","Usunięto"},{"удалена","usunięto"},{"Удалены","Usunięto"},{"удалены","usunięto"},{"Создан","Utworzono"},{"создан","utworzono"},{"Создана","Utworzono"},{"создана","utworzono"},{"Создано","Utworzono"},{"создано","utworzono"},{"Добавлен","Dodano"},{"добавлен","dodano"},{"Добавлена","Dodano"},{"добавлена","dodano"},{"Сохранено","Zapisano"},{"сохранено","zapisano"},{"Сохранена","Zapisano"},{"сохранена","zapisano"},{"Сброшено","Zresetowano"},{"сброшено","zresetowano"},{"Установлен","Ustawiono"},{"установлен","ustawiono"},{"Выдан","Wydano"},{"выдан","wydano"},{"Выдана","Wydano"},{"выдана","wydano"},{"Свободного","Wolnego"},{"свободного","wolnego"},{"места","miejsca"},{"Место","Miejsce"},{"место","miejsce"},{"занято","zajęte"},{"блоком","blokiem"},{"требуется","wymagane"},{"Требуется","Wymagane"},{"рядом","w pobliżu"},{"слишком","zbyt"},{"близко","blisko"},{"далеко","daleko"},{"Пропущены","Pominięto"},{"пропущены","pominięto"},{"Некоторые","Niektóre"},{"некоторые","niektóre"},{"Причина","Powód"},{"причина","powód"},{"Изменено","Zmieniono"},{"изменено","zmieniono"},{"Изменить","Edytuj"},{"изменить","edytuj"},{"Нельзя","Nie można"},{"нельзя","nie można"},{"можно","można"},{"Можно","Można"},{"Нужно","Trzeba"},{"нужно","trzeba"},{"Введите","Wpisz"},{"введите","wpisz"},{"Напишите","Napisz"},{"напишите","napisz"},{"Нажмите","Kliknij"},{"нажмите","kliknij"},{"Повторите","Powtórz"},{"повторите","powtórz"},{"Подтвердите","Potwierdź"},{"подтвердите","potwierdź"},{"Отменено","Anulowano"},{"отменено","anulowano"},{"Отменить","Anuluj"},{"отменить","anuluj"},{"Найден","Znaleziono"},{"найден","znaleziono"},{"Найдено","Znaleziono"},{"найдено","znaleziono"},{"Не найден","Nie znaleziono"},{"не найден","nie znaleziono"},{"Участник","Członek"},{"участник","członek"},{"Участники","Członkowie"},{"участники","członkowie"},{"включено","włączone"},{"Включено","Włączone"},{"выключено","wyłączone"},{"Выключено","Wyłączone"},{"сейчас","teraz"},{"Сейчас","Teraz"},{"уже","już"},{"Уже","Już"},{"только","tylko"},{"Только","Tylko"},{"все","wszyscy"},{"Все","Wszyscy"},{"действия","działania"},{"Действия","Działania"},{"настройки","ustawienia"},{"Настройки","Ustawienia"},{"условия","warunki"},{"Условия","Warunki"}};
            for(String[] row:extra)m.put(row[0],row[1]);
        }
    }

    private java.util.Map<String,String> guiMap(String family){
        java.util.Map<String,String> m=new java.util.LinkedHashMap<>();
        if("ru".equals(family))return m;
        String[][] rows=switch(family){
            case "de" -> new String[][]{
                {"Ивенты","Events"},{"Спавн","Spawn"},{"Настройки","Einstellungen"},{"Общие","Allgemein"},{"Точки","Punkte"},{"Роли","Rollen"},{"Сотрудники","Mitarbeiter"},{"Статистика","Statistik"},{"Награды","Belohnungen"},{"Анимация","Animation"},{"Частицы","Partikel"},{"Каталог частиц","Partikelkatalog"},{"Выбранные частицы","Ausgewählte Partikel"},{"Ноты","Noten"},{"Звук","Ton"},{"Предпросмотр","Vorschau"},{"Расписание","Zeitplan"},{"Условия сбора","Sammelbedingungen"},{"Шаблоны ивентов","Eventvorlagen"},{"Журнал действий","Aktionsprotokoll"},{"Анти-ESP приманки","Anti-ESP-Köder"},{"Чёрный список","Sperrliste"},{"Сотрудники и роли","Mitarbeiter und Rollen"},{"Главное меню","Hauptmenü"},{"Назад","Zurück"},{"Закрыть","Schließen"},{"Удалить","Löschen"},{"Сохранить","Speichern"},{"Добавить","Hinzufügen"},{"Выдача","Ausgabe"},{"Количество","Anzahl"},{"Радиус","Radius"},{"Цвет","Farbe"},{"Размер","Größe"},{"Скорость","Geschwindigkeit"},{"Длительность","Dauer"},{"Шанс","Chance"},{"Мир","Welt"},{"Погода","Wetter"},{"Роль","Rolle"},{"Название","Name"},{"Описание","Beschreibung"},{"Разрешено","Erlaubt"},{"Запрещено","Verboten"},{"Включён","Aktiv"},{"Выключен","Deaktiviert"},{"Выбрано","Ausgewählt"},{"Точки события","Eventpunkte"},{"Точка #","Punkt #"},{"Центр постановки","Punktzentrum"},{"Лимит точек","Punktlimit"},{"Радиус от центра","Radius vom Zentrum"},{"Время жизни","Lebensdauer"},{"Максимум активных","Maximal aktiv"},{"Минимальная дистанция","Mindestabstand"},{"Минимальная задержка спавна","Minimale Spawn-Verzögerung"},{"Максимальная задержка спавна","Maximale Spawn-Verzögerung"},{"Автоматический спавн включён","Automatischer Spawn aktiviert"},{"Автоматический спавн выключен","Automatischer Spawn deaktiviert"},{"ЛКМ —","LMT —"},{"ПКМ —","RMT —"},{"следующая страница","nächste Seite"},{"предыдущая","vorherige"},{"ЛКМ — открыть","LMT — öffnen"},{"ЛКМ — выбрать","LMT — auswählen"},{"ЛКМ — изменить","LMT — ändern"},{"ЛКМ — удалить","LMT — löschen"},{"ЛКМ — настроить","LMT — konfigurieren"},{"ЛКМ — продолжить выбор","LMT — Auswahl fortsetzen"},{"ПКМ — открыть настройки","RMT — Einstellungen öffnen"},{"ПКМ — удалить","RMT — löschen"},{"ПКМ — предыдущая страница","RMT — vorherige Seite"},{"ПКМ — мои точки","RMT — meine Punkte"},{"Текущее","Aktuell"},{"Сейчас","Jetzt"},{"не задано","nicht gesetzt"},{"не задана","nicht gesetzt"},{"не требуется","nicht erforderlich"},{"без ограничения","unbegrenzt"},{"Все","Alle"},{"Кастомный","Benutzerdefiniert"},{"Ярмарка","Jahrmarkt"},{"Поиск предметов","Items suchen"},{"Зимний ивент","Winter-Event"},{"Пасхальный ивент","Oster-Event"},{"Звук включён","Ton aktiviert"},{"Звук выключен","Ton deaktiviert"},{"Только выключить звук","Nur Ton ausschalten"} };
            case "uk" -> new String[][]{{"Ивенты","Івенти"},{"Спавн","Спавн"},{"Настройки","Налаштування"},{"Общие","Загальні"},{"Точки","Точки"},{"Роли","Ролі"},{"Сотрудники","Працівники"},{"Статистика","Статистика"},{"Награды","Нагороди"},{"Анимация","Анімація"},{"Частицы","Частинки"},{"Каталог частиц","Каталог частинок"},{"Выбранные частицы","Вибрані частинки"},{"Ноты","Ноти"},{"Звук","Звук"},{"Предпросмотр","Попередній перегляд"},{"Расписание","Розклад"},{"Условия сбора","Умови збору"},{"Шаблоны ивентов","Шаблони івентів"},{"Журнал действий","Журнал дій"},{"Сотрудники и роли","Працівники та ролі"},{"Главное меню","Головне меню"},{"Назад","Назад"},{"Закрыть","Закрити"},{"Удалить","Видалити"},{"Сохранить","Зберегти"},{"Добавить","Додати"},{"Количество","Кількість"},{"Радиус","Радіус"},{"Цвет","Колір"},{"Размер","Розмір"},{"Скорость","Швидкість"},{"Длительность","Тривалість"},{"Мир","Світ"},{"Погода","Погода"},{"Роль","Роль"},{"Название","Назва"},{"Описание","Опис"},{"Разрешено","Дозволено"},{"Запрещено","Заборонено"},{"Включён","Увімкнено"},{"Выключен","Вимкнено"},{"Лимит точек","Ліміт точок"},{"Центр постановки","Центр встановлення"},{"Радиус от центра","Радіус від центру"},{"ЛКМ — открыть","ЛКМ — відкрити"},{"ЛКМ — выбрать","ЛКМ — вибрати"},{"ЛКМ — изменить","ЛКМ — змінити"},{"ЛКМ — удалить","ЛКМ — видалити"},{"ПКМ — удалить","ПКМ — видалити"},{"Назад","Назад"},{"не задано","не задано"},{"не задана","не задана"},{"без ограничения","без обмежень"}};
            case "es" -> new String[][]{{"Ивенты","Eventos"},{"Спавн","Spawning"},{"Настройки","Ajustes"},{"Общие","General"},{"Точки","Puntos"},{"Роли","Roles"},{"Сотрудники","Personal"},{"Статистика","Estadísticas"},{"Награды","Recompensas"},{"Анимация","Animación"},{"Частицы","Partículas"},{"Каталог частиц","Catálogo de partículas"},{"Выбранные частицы","Partículas seleccionadas"},{"Ноты","Notas"},{"Звук","Sonido"},{"Предпросмотр","Vista previa"},{"Расписание","Programación"},{"Условия сбора","Condiciones de recogida"},{"Шаблоны ивентов","Plantillas de eventos"},{"Журнал действий","Registro de acciones"},{"Сотрудники и роли","Personal y roles"},{"Главное меню","Menú principal"},{"Назад","Atrás"},{"Закрыть","Cerrar"},{"Удалить","Eliminar"},{"Сохранить","Guardar"},{"Добавить","Añadir"},{"Количество","Cantidad"},{"Радиус","Radio"},{"Цвет","Color"},{"Размер","Tamaño"},{"Скорость","Velocidad"},{"Длительность","Duración"},{"Мир","Mundo"},{"Погода","Clima"},{"Роль","Rol"},{"Название","Nombre"},{"Описание","Descripción"},{"Разрешено","Permitido"},{"Запрещено","Prohibido"},{"Включён","Activado"},{"Выключен","Desactivado"},{"Лимит точек","Límite de puntos"},{"Центр постановки","Centro de puntos"},{"Радиус от центра","Radio desde el centro"},{"не задано","no establecido"},{"без ограничения","sin límite"}};
            case "kk" -> new String[][]{{"Ивенты","Ивенттер"},{"Спавн","Спавн"},{"Настройки","Баптаулар"},{"Общие","Жалпы"},{"Точки","Нүктелер"},{"Роли","Рөлдер"},{"Сотрудники","Қызметкерлер"},{"Статистика","Статистика"},{"Награды","Сыйлықтар"},{"Анимация","Анимация"},{"Частицы","Бөлшектер"},{"Каталог частиц","Бөлшектер каталогы"},{"Выбранные частицы","Таңдалған бөлшектер"},{"Ноты","Ноталар"},{"Звук","Дыбыс"},{"Предпросмотр","Алдын ала қарау"},{"Расписание","Кесте"},{"Условия сбора","Жинау шарттары"},{"Шаблоны ивентов","Ивент үлгілері"},{"Журнал действий","Әрекеттер журналы"},{"Сотрудники и роли","Қызметкерлер мен рөлдер"},{"Главное меню","Негізгі мәзір"},{"Назад","Артқа"},{"Закрыть","Жабу"},{"Удалить","Жою"},{"Сохранить","Сақтау"},{"Добавить","Қосу"},{"Количество","Саны"},{"Радиус","Радиус"},{"Цвет","Түс"},{"Размер","Өлшем"},{"Скорость","Жылдамдық"},{"Длительность","Ұзақтық"},{"Мир","Әлем"},{"Погода","Ауа райы"},{"Роль","Рөл"},{"Название","Атауы"},{"Описание","Сипаттама"},{"Разрешено","Рұқсат етілген"},{"Запрещено","Тыйым салынған"},{"Включён","Қосулы"},{"Выключен","Өшірулі"},{"Лимит точек","Нүкте лимиті"},{"Центр постановки","Нүкте қою орталығы"},{"Радиус от центра","Орталықтан радиус"},{"не задано","берілмеген"},{"без ограничения","шектеусіз"}};
            case "fr" -> new String[][]{{"Ивенты","Événements"},{"Спавн","Spawn"},{"Настройки","Paramètres"},{"Общие","Général"},{"Точки","Points"},{"Роли","Rôles"},{"Сотрудники","Employés"},{"Статистика","Statistiques"},{"Награды","Récompenses"},{"Анимация","Animation"},{"Частицы","Particules"},{"Каталог частиц","Catalogue de particules"},{"Выбранные частицы","Particules sélectionnées"},{"Ноты","Notes"},{"Звук","Son"},{"Предпросмотр","Aperçu"},{"Расписание","Planning"},{"Условия сбора","Conditions de collecte"},{"Шаблоны ивентов","Modèles d’événements"},{"Журнал действий","Journal des actions"},{"Сотрудники и роли","Employés et rôles"},{"Главное меню","Menu principal"},{"Назад","Retour"},{"Закрыть","Fermer"},{"Удалить","Supprimer"},{"Сохранить","Enregistrer"},{"Добавить","Ajouter"},{"Количество","Quantité"},{"Радиус","Rayon"},{"Цвет","Couleur"},{"Размер","Taille"},{"Скорость","Vitesse"},{"Длительность","Durée"},{"Мир","Monde"},{"Погода","Météo"},{"Роль","Rôle"},{"Название","Nom"},{"Описание","Description"},{"Разрешено","Autorisé"},{"Запрещено","Interdit"},{"Включён","Activé"},{"Выключен","Désactivé"},{"Лимит точек","Limite de points"},{"Центр постановки","Centre des points"},{"Радиус от центра","Rayon depuis le centre"},{"не задано","non défini"},{"без ограничения","illimité"}};
            case "pl" -> new String[][]{{"Ивенты","Wydarzenia"},{"Спавн","Spawn"},{"Настройки","Ustawienia"},{"Общие","Ogólne"},{"Точки","Punkty"},{"Роли","Role"},{"Сотрудники","Pracownicy"},{"Статистика","Statystyki"},{"Награды","Nagrody"},{"Анимация","Animacja"},{"Частицы","Cząsteczki"},{"Каталог частиц","Katalog cząsteczek"},{"Выбранные частицы","Wybrane cząsteczki"},{"Ноты","Nuty"},{"Звук","Dźwięk"},{"Предпросмотр","Podgląd"},{"Расписание","Harmonogram"},{"Условия сбора","Warunki zbierania"},{"Шаблоны ивентов","Szablony wydarzeń"},{"Журнал действий","Dziennik działań"},{"Сотрудники и роли","Pracownicy i role"},{"Главное меню","Menu główne"},{"Назад","Wstecz"},{"Закрыть","Zamknij"},{"Удалить","Usuń"},{"Сохранить","Zapisz"},{"Добавить","Dodaj"},{"Количество","Liczba"},{"Радиус","Promień"},{"Цвет","Kolor"},{"Размер","Rozmiar"},{"Скорость","Prędkość"},{"Длительность","Czas trwania"},{"Мир","Świat"},{"Погода","Pogoda"},{"Роль","Rola"},{"Название","Nazwa"},{"Описание","Opis"},{"Разрешено","Dozwolone"},{"Запрещено","Zabronione"},{"Включён","Włączone"},{"Выключен","Wyłączone"},{"Лимит точек","Limit punktów"},{"Центр постановки","Centrum punktów"},{"Радиус от центра","Promień od centrum"},{"не задано","nie ustawiono"},{"без ограничения","bez limitu"}};
            default -> new String[][]{{"Пасхальный ивент","Easter Event"},{"Зимний ивент","Winter Event"},{"Ярмарка","Fair"},{"Поиск предметов","Item Hunt"},{"Кастомный","Custom"},{"Центр управления","Control Center"},{"Центр постановки точек","Point Placement Center"},{"Мои точки","My Points"},{"Точки события","Event Points"},{"Выбор ивента для точки","Select Event for Point"},{"Обзор ивента","Event Overview"},{"Настройки анимации","Animation Settings"},{"Настройки частицы","Particle Settings"},{"Палитра DUST","DUST Palette"},{"Выбранные частицы","Selected Particles"},{"Выбрано нот","Notes Selected"},{"Всего выбрано","Total Selected"},{"Общее количество частиц","Total Particle Count"},{"Общий лимит частиц","Global Particle Limit"},{"Радиус от центра","Radius from Center"},{"Лимит своих точек","Own Point Limit"},{"Сотрудник","Staff Member"},{"Конструктор роли","Role Builder"},{"Сотрудники и роли","Staff and Roles"},{"Топ срабатываний","Trigger Top"},{"Состояние всех событий","All Event Status"},{"Главное меню","Main Menu"},{"← Назад","← Back"},{"← Ивенты","← Events"},{"← Частицы","← Particles"},{"← Каталог частиц","← Particle Catalog"},{"← Выбранные частицы","← Selected Particles"},{"← Настройки анимации","← Animation Settings"},{"ЛКМ — открыть","LMB — open"},{"ЛКМ — выбрать","LMB — select"},{"ЛКМ — изменить","LMB — edit"},{"ЛКМ — удалить","LMB — delete"},{"ЛКМ — настроить","LMB — configure"},{"ЛКМ — добавить","LMB — add"},{"ЛКМ — применить","LMB — apply"},{"ЛКМ — следующая страница","LMB — next page"},{"ЛКМ — следующая","LMB — next"},{"ПКМ — предыдущая страница","RMB — previous page"},{"ПКМ — открыть настройки","RMB — open settings"},{"ПКМ — удалить","RMB — delete"},{"ПКМ — мои точки","RMB — my points"},{"Ничего не найдено","Nothing found"},{"Ничего не выбрано","Nothing selected"},{"не выбраны","none selected"},{"неизвестно","unknown"},{"глобальная частица","global particle"},{"глобальный","global"},{"глобальное","global"},{"не доступно","not available"},{"Раздел временно отключён.","Section temporarily unavailable."},{"Пока нет данных","No data yet"},{"Событий пока нет","No events yet"},{"Сборов пока нет","No collections yet"},{"Точек не видно","No points visible"},{"Точек не видно","No points visible"},{"Шанс","Chance"},{"Поставил","Placed by"},{"Поставили","Placed by"},{"Кто","Who"},{"Координаты","Coordinates"},{"Текущая","Current"},{"Текущий","Current"},{"Сейчас","Now"},{"Создано","Created"},{"Последнее","Last"},{"Запрещено","Denied"},{"Разрешено","Allowed"},{"Включена","Enabled"},{"Выключена","Disabled"},{"Вес","Weight"},{"Тон","Pitch"},{"Громкость","Volume"},{"Время мира","World Time"},{"День / ночь","Day / Night"},{"Принудительное снятие","Force Remove"},{"Подбор предмета","Item Collection"},{"Плавающий спавн","Floating Spawn"},{"Тестовый спавн","Test Spawn"},{"Случайный поворот","Random Rotation"},{"Твёрдая опора","Solid Ground"},{"Вода","Water"},{"Лава","Lava"},{"Выдача","Give"},{"Опыт","Experience"},{"Импорт","Import"},{"Экспорт","Export"},{"Помощник","Helper"},{"Визер","Wither"},{"Фейерверк","Firework"},{"Шлем","Helmet"},{"Аметист","Amethyst"},{"Красный","Red"},{"Зелёный","Green"},{"Синий","Blue"},{"Жёлтый","Yellow"},{"Оранжевый","Orange"},{"Фиолетовый","Purple"},{"Розовый","Pink"},{"Коричневый","Brown"},{"Бирюзовый","Cyan"},{"Лаймовый","Lime"},{"Серый","Gray"},{"Светло-серый","Light Gray"},{"Голубой","Light Blue"},{"Белый","White"},{"Чёрный","Black"},{"Настройки выполняются через команды.","Some settings are command-based."},{"Сохраняется при перезагрузке.","Saved after reload."},{"Показывает только включённые типы,","Shows only enabled types,"},{"чтобы не искать их в общем каталоге.","so you do not need to find them in the main catalog."},{"Можно включить хоть все 25.","All 25 can be enabled."},{"Для каждой выбранной ноты хранится свой pitch и цвет.","Each selected note keeps its own pitch and color."},{"Например: наденьте PUMPKIN → можно собирать.","Example: wear PUMPKIN → collection is allowed."},{"При невыполненном условии событие не забирается.","When a condition is not met, the event is not collected."},{"Если списание невозможно, EventHead не засчитывается.","If the charge fails, the EventHead is not collected."},{"Полностью отключить звук у этого ивента.","Completely disable sound for this event."},{"Очистить выбранные звуки, не выключая звук.","Clear selected sounds without disabling sound."},{"Проиграть один случайный выбранный звук.","Play one random selected sound."},{"все 20 прав","all 20 permissions"},{"управлять спавном","manage spawning"},{"ставить точки, чёрный список и использовать инструменты","place points, manage blacklist and use tools"},{"получать уведомления о срабатываниях Anti-ESP","receive Anti-ESP trigger notifications"},{"просматривать меню и список ивентов","view the menu and event list"},{"просматривать журнал действий сотрудников в GUI","view the staff action log in the GUI"},{"редактировать и удалять любые точки, независимо от владельца","edit and delete any point regardless of owner"},{"управлять сотрудниками и ролями; выдавать доступ другим игрокам","manage staff and roles; grant access to other players"},{"для одного или нескольких событий","for one or more events"},{"Маленькая стойка во время анимации","Small stand during animation"},{"Снежные частицы и звук","Snow particles and sound"},{"Пасха, зима, ярмарка, поиск, босс.","Easter, winter, fair, hunt."},{"Готовая награда, точки, частицы и звук.","Ready reward, points, particles and sound."},{"Пошаговая инструкция для модератора.","Step-by-step moderator guide."},{"Команды, права, точки, спавн и сбор EventHead.","Commands, permissions, points, spawning and collection."},{"Особенно полезно при POINTS/MIXED.","Especially useful for POINTS/MIXED."},{"Источник используется как внешний вид EventHead.","The source item is used as the EventHead appearance."},{"Открывает редактор выбранного события.","Opens the selected event editor."},{"Открывает сотрудников и роли.","Opens staff and roles."},{"Открывает статистику конкретного события.","Opens statistics for the selected event."},{"Показывает список созданных событий.","Shows created events."},{"Получите инструмент точек.","Get the point tool."},{"Получите готовый шаблон.","Get a ready template."},{"Проверить текущие ограничения.","Check current restrictions."},{"Быстрое включение — без поиска в каталоге ниже.","Quick access without searching the catalog below."},{"Можно включить сразу несколько прав.","Multiple permissions can be enabled at once."},{"Назначение без OP через EventHeads/LuckPerms.","Assign without OP through EventHeads/LuckPerms."},{"Создать копию всех настроек, точек, частиц и анимации.","Copy all settings, points, particles and animation."},{"Создать новый ивент или шаблон прямо из меню.","Create a new event or template directly from the menu."},{"Записать все изменения.","Record all changes."},{"Кто и что менял в EventHeads.","Who changed what in EventHeads."},{"Журнал хранится только в данных EventHeads.","The log is stored only in EventHeads data."},{"Встроенная роль доступна только для просмотра.","The built-in role is view-only."},{"Для встроенной роли ограничение не задано.","No limit is set for the built-in role."},{"Для каждого выбранной ноты хранится свой pitch и цвет.","Each selected note keeps its own pitch and color."},{"Здесь можно искать игроков и назначать роли.","You can search players and assign roles here."},{"Создаёт роль с выбранными правами.","Creates a role with the selected permissions."},{"Удаляет только кастомную роль после двойного подтверждения.","Deletes only the custom role after double confirmation."},{"Удаляет участника из EventHeads после двойного подтверждения.","Removes a member from EventHeads after double confirmation."},{"Создайте первый ивент в меню «Ивенты».","Create your first event in the Events menu."},{"Создаёт новый шаблон события и сразу открывает редактор.","Creates a new event template and opens the editor."},{"Импортирует ранее экспортированный YAML.","Imports a previously exported YAML."},{"Экспортирует настройки, точки, блокировки и визуальный предмет.","Exports settings, points, blocks and the visual item."},{"Файл попадает в plugins/EventHeads/exports/.","The file is saved to plugins/EventHeads/exports/."},{"Только ваши точки EventHeads.","Only your EventHeads points."},{"Администратор/points-all видит все.","Admin/points-all can see all."},{"Можно менять шанс и удалять свои точки.","You can change the chance and delete your own points."},{"Удаляет точку по номеру.","Deletes a point by number."},{"Команда специально НЕ выводит список всех плагинов сервера.","This command deliberately does NOT list all server plugins."},{"Проверка состояния EventHeads и данных.","Checks EventHeads status and data."},{"Показывает состояние EventHeads и его интеграций.","Shows EventHeads status and integrations."},{"Включает режим постановки точек. Одна шкурка может быть привязана к нескольким событиям.","Enables point placement mode. One tool can be linked to multiple events."}};
        };
        for(String[] row:rows) if(row.length==2)m.put(row[0],row[1]);
        addCoreGuiTranslations(m,family);
        addHighCoverageGuiTranslations(m,family);
        addFallbackCommonTranslations(m,family);
        return m;
    }
}

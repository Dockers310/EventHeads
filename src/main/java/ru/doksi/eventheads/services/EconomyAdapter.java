package ru.doksi.eventheads.services;

import ru.doksi.eventheads.EventHeadsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.doksi.eventheads.util.SchedulerUtil;
import java.util.concurrent.CompletableFuture;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Economy adapter for Vault/CMI/console commands with transaction verification.
 *
 * RU: Одна операция — один канал. Следующий канал пробуется, только если предыдущий недоступен
 * (нет плагина, нет команды) или ответил явным отказом. Если деньги могли уйти, а подтверждения нет,
 * другие каналы не используются: иначе награда выдавалась бы дважды.
 */
public final class EconomyAdapter {
    private final EventHeadsPlugin plugin;
    private boolean vaultErrorLogged;

    public EconomyAdapter(EventHeadsPlugin plugin){ this.plugin=plugin; }

    /**
     * Итог попытки по одному каналу.
     * @param used true — канал сработал или мог изменить баланс; дальше по цепочке идти нельзя
     */
    private record Attempt(Result result,boolean used){
        static Attempt unavailable(){return new Attempt(new Result(false,0,"none"),false);}
        static Attempt refused(String provider){return new Attempt(new Result(false,0,provider),false);}
        static Attempt done(Result result){return new Attempt(result,true);}
        /** Выплата консольной командой: такие команды (CMI, Essentials) сами пишут игроку о деньгах. */
        static Attempt byCommand(Result result){return new Attempt(new Result(result.success(),result.actualAmount(),result.provider(),true),true);}
    }

    public CompletableFuture<Result> giveAsync(Player player,double amount,String eventId){CompletableFuture<Result> f=new CompletableFuture<>();SchedulerUtil.runGlobal(plugin,()->{try{f.complete(giveNow(player,amount,eventId));}catch(Throwable t){f.completeExceptionally(t);}});return f;}

    public CompletableFuture<Result> takeAsync(Player player,double amount,String eventId){CompletableFuture<Result> f=new CompletableFuture<>();SchedulerUtil.runGlobal(plugin,()->{try{f.complete(takeNow(player,amount,eventId));}catch(Throwable t){f.completeExceptionally(t);}});return f;}

    private Result giveNow(Player player,double amount,String eventId){
        if(amount<0) return takeNow(player,-amount,eventId);
        if(amount<=0) return new Result(true,0,"none");
        String mode=plugin.getConfig().getString("economy.provider","AUTO").toUpperCase(Locale.ROOT);

        // Vault отвечает явным результатом транзакции, поэтому в AUTO он первый. Если CMI
        // зарегистрирован как экономика Vault, выплата идёт в него же.
        if(mode.equals("AUTO")||mode.equals("VAULT")){
            Attempt vault=vault(player,amount,"depositPlayer");
            if(vault.used() || mode.equals("VAULT")) return vault.result();
        }

        if((mode.equals("AUTO")||mode.equals("CMI")) && Bukkit.getPluginManager().isPluginEnabled("CMI")){
            Attempt cmi=dispatch(plugin.getConfig().getString("economy.cmi-command","cmi money give {player} {amount}"),player,amount,eventId,"CMI");
            if(cmi.used() || mode.equals("CMI")) return cmi.result();
        }

        if(mode.equals("AUTO")||mode.equals("COMMAND")){
            Attempt command=dispatch(plugin.getConfig().getString("economy.command",""),player,amount,eventId,"command");
            if(command.used()) return command.result();
        }

        if(plugin.getConfig().getBoolean("economy.crazy-vouchers.enabled",false)
                && Bukkit.getPluginManager().isPluginEnabled("CrazyVouchers")){
            Attempt voucher=dispatch(plugin.getConfig().getString("economy.crazy-vouchers.command",""),player,amount,eventId,"CrazyVouchers");
            if(voucher.used()) return voucher.result();
        }
        return new Result(false,0,"none");
    }

    private Result takeNow(Player player,double amount,String eventId){
        if(amount<=0) return new Result(true,0,"none");
        String mode=plugin.getConfig().getString("economy.provider","AUTO").toUpperCase(Locale.ROOT);

        if(mode.equals("AUTO")||mode.equals("VAULT")){
            Attempt vault=vault(player,amount,"withdrawPlayer");
            if(vault.used() || mode.equals("VAULT")) return vault.result();
        }

        if((mode.equals("AUTO")||mode.equals("CMI")) && Bukkit.getPluginManager().isPluginEnabled("CMI")){
            String template=plugin.getConfig().getString("economy.withdraw-command","cmi money take {player} {amount}");
            Attempt cmi=dispatchWithdraw(template,player,amount,eventId,"CMI");
            if(cmi.used() || mode.equals("CMI")) return cmi.result();
        }

        if(mode.equals("COMMAND")){
            Attempt command=dispatchWithdraw(plugin.getConfig().getString("economy.withdraw-command",""),player,amount,eventId,"command");
            if(command.used()) return command.result();
        }
        return new Result(false,0,"none");
    }

    /** Vault: depositPlayer или withdrawPlayer. Результат транзакции приходит от самой экономики. */
    private Attempt vault(Player player,double amount,String method){
        Object provider; Class<?> ecoClass; Method call;
        try{
            ecoClass=Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration=Bukkit.getServicesManager().getRegistration(ecoClass);
            if(registration==null) return Attempt.unavailable();
            provider=registration.getClass().getMethod("getProvider").invoke(registration);
            if(provider==null) return Attempt.unavailable();
            call=economyMethod(provider,method,ecoClass);
        }catch(Throwable ex){
            // До вызова экономики дело не дошло: деньги не тронуты, можно пробовать другой канал.
            logVaultError(ex);
            return Attempt.unavailable();
        }
        String name=providerName(provider);
        Object response;
        try{
            Object target=call.getParameterTypes()[0]==String.class?player.getName():player;
            response=call.invoke(provider,target,amount);
        }catch(IllegalAccessException|IllegalArgumentException ex){
            // Метод не удалось вызвать: операция не начиналась.
            logVaultError(ex);
            return Attempt.unavailable();
        }catch(InvocationTargetException ex){
            // Экономика упала во время операции: прошла ли она, неизвестно. Повторять нельзя.
            logVaultError(ex.getCause()==null?ex:ex.getCause());
            return Attempt.done(new Result(false,0,name+"_UNVERIFIED"));
        }
        try{
            if(response==null) return Attempt.done(new Result(false,0,name+"_UNVERIFIED"));
            boolean success=(boolean)response.getClass().getMethod("transactionSuccess").invoke(response);
            // Явный отказ экономики: деньги не тронуты.
            if(!success) return Attempt.refused(name);
            double actual=((Number)response.getClass().getField("amount").get(response)).doubleValue();
            return Attempt.done(new Result(true,(int)Math.max(0,Math.round(actual)),name));
        }catch(Throwable ex){
            // Операция выполнена, но ответ прочитать не удалось.
            logVaultError(ex);
            return Attempt.done(new Result(false,0,name+"_UNVERIFIED"));
        }
    }

    private Method economyMethod(Object provider,String method,Class<?> ecoClass) throws NoSuchMethodException{
        Class<?> providerClass=provider.getClass();
        // Сначала методы интерфейса Vault: они доступны, даже если класс экономики не публичный.
        try{return ecoClass.getMethod(method,org.bukkit.OfflinePlayer.class,double.class);}catch(NoSuchMethodException ignored){}
        try{return ecoClass.getMethod(method,String.class,double.class);}catch(NoSuchMethodException ignored){}
        try{return providerClass.getMethod(method,org.bukkit.OfflinePlayer.class,double.class);}catch(NoSuchMethodException ignored){}
        try{return providerClass.getMethod(method,Player.class,double.class);}catch(NoSuchMethodException ignored){}
        try{return providerClass.getMethod(method,String.class,double.class);}catch(NoSuchMethodException ignored){}
        throw new NoSuchMethodException(ecoClass.getName()+"."+method+" for provider "+providerClass.getName());
    }

    private Attempt dispatchWithdraw(String template,Player player,double amount,String eventId,String provider){
        if(template==null||template.isBlank()) return Attempt.unavailable();
        String cmd=template.replace("{player}",sanitize(player.getName()))
                .replace("{amount}",String.valueOf((int)Math.round(amount)))
                .replace("{event_id}",sanitize(eventId));
        Double before=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        // Денег заведомо не хватает: команду не отправляем.
        if(before!=null && before+0.000001D<amount) return Attempt.byCommand(new Result(false,0,provider+"_INSUFFICIENT"));
        boolean dispatched=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);
        if(!dispatched) return Attempt.unavailable();
        Double after=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        if(before!=null && after!=null){
            double actual=Math.max(0,before-after);
            if(actual+0.000001D>=amount) return Attempt.byCommand(new Result(true,(int)Math.round(actual),provider));
            return Attempt.byCommand(new Result(false,0,provider+"_UNVERIFIED"));
        }
        boolean allow=plugin.getConfig().getBoolean("economy.allow-unverified-withdraw-success",false);
        return Attempt.byCommand(allow?new Result(true,(int)Math.round(amount),provider+"_UNVERIFIED"):new Result(false,0,provider+"_UNVERIFIED"));
    }

    private Attempt dispatch(String template,Player player,double amount,String eventId,String provider){
        if(template==null||template.isBlank()) return Attempt.unavailable();
        String cmd=template.replace("{player}",sanitize(player.getName()))
                .replace("{amount}",String.valueOf((int)Math.round(amount)))
                .replace("{event_id}",sanitize(eventId));
        Double before=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        boolean dispatched=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);
        // Команда не найдена: ничего не выдано.
        if(!dispatched) return Attempt.unavailable();
        Double after=provider.equalsIgnoreCase("CMI")?tryCmiBalance(player):balanceOf(player);
        if(before!=null&&after!=null){
            double actual=after-before;
            if(actual+0.000001D>=amount) return Attempt.byCommand(new Result(true,(int)Math.round(actual),provider));
            // Команда отправлена, но баланс не вырос. Она могла сработать с задержкой, поэтому
            // по другому каналу не повторяем: это и была двойная выплата.
            return Attempt.byCommand(new Result(false,0,provider+"_UNVERIFIED"));
        }
        boolean allow=provider.equalsIgnoreCase("CMI")
                || plugin.getConfig().getBoolean("economy.allow-unverified-command-success",true);
        return Attempt.byCommand(allow?new Result(true,(int)Math.round(amount),provider+"_UNVERIFIED"):new Result(false,0,provider+"_UNVERIFIED"));
    }

    private Double tryCmiBalance(Player player){
        try{
            Class<?> eco=Class.forName("com.Zrips.CMI.Modules.Economy.Economy");
            for(Class<?> t:new Class<?>[]{org.bukkit.OfflinePlayer.class,String.class,Player.class}){
                try{
                    Method m=eco.getMethod("getBalance",t);
                    Object value=m.invoke(null,t==String.class?player.getName():player);
                    if(value instanceof Number n)return n.doubleValue();
                }catch(NoSuchMethodException ignored){}
            }
        }catch(Throwable ignored){}
        return null;
    }

    public Double balance(Player player){
        String mode=plugin.getConfig().getString("economy.provider","AUTO").toUpperCase(Locale.ROOT);
        if((mode.equals("AUTO")||mode.equals("CMI")) && Bukkit.getPluginManager().isPluginEnabled("CMI")){
            Double value=tryCmiBalance(player);
            if(value!=null)return value;
        }
        return balanceOf(player);
    }

    private Double balanceOf(Player player){
        try{
            Class<?> ecoClass=Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration=Bukkit.getServicesManager().getRegistration(ecoClass);
            if(registration==null)return null;
            Object provider=registration.getClass().getMethod("getProvider").invoke(registration);
            if(provider==null)return null;
            for(Class<?> t:new Class<?>[]{provider.getClass(),ecoClass}){
                try{
                    Object value=t.getMethod("getBalance",org.bukkit.OfflinePlayer.class).invoke(provider,player);
                    if(value instanceof Number n)return n.doubleValue();
                }catch(NoSuchMethodException ignored){}
                try{
                    Object value=t.getMethod("getBalance",String.class).invoke(provider,player.getName());
                    if(value instanceof Number n)return n.doubleValue();
                }catch(NoSuchMethodException ignored){}
            }
        }catch(Throwable ignored){}
        return null;
    }

    private void logVaultError(Throwable ex){
        if(!vaultErrorLogged){
            vaultErrorLogged=true;
            plugin.getLogger().warning("Vault economy integration could not be used: "+ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage()));
        }
    }

    private String sanitize(String raw){return raw==null?"":raw.replaceAll("[;&|\\n\\r]","");}
    private String providerName(Object provider){
        try{return String.valueOf(provider.getClass().getMethod("getName").invoke(provider));}
        catch(Throwable ignored){return provider.getClass().getSimpleName();}
    }

    /** @param viaCommand выплата прошла консольной командой, которая сама сообщает игроку о деньгах */
    public record Result(boolean success,int actualAmount,String provider,boolean viaCommand){
        public Result(boolean success,int actualAmount,String provider){this(success,actualAmount,provider,false);}
    }

    public String providerName(){
        try{
            Class<?> ecoClass=Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration=Bukkit.getServicesManager().getRegistration(ecoClass);
            if(registration!=null){
                Object provider=registration.getClass().getMethod("getProvider").invoke(registration);
                if(provider!=null)return providerName(provider);
            }
        }catch(Throwable ignored){}
        if(Bukkit.getPluginManager().isPluginEnabled("CMI"))return "CMI";
        return null;
    }
}

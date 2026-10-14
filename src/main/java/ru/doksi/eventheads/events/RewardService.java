package ru.doksi.eventheads.events;

import ru.doksi.eventheads.EventHeadsPlugin;
import ru.doksi.eventheads.services.EconomyAdapter;
import ru.doksi.eventheads.util.SchedulerUtil;
import org.bukkit.entity.Player;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;

/** Reward generation and explicit localized economy notifications. */
public final class RewardService {
    private final EventHeadsPlugin plugin;
    public RewardService(EventHeadsPlugin plugin){this.plugin=plugin;}

    public CompletableFuture<EconomyAdapter.Result> grantAsync(Player p,EventDefinition e){
        int amount=e.randomReward(new java.util.Random(ThreadLocalRandom.current().nextLong()));
        // Старые события и импортированные файлы могли сохранить награду выше economy.reward-limit.
        int limit=plugin.rewardLimit();
        amount=Math.max(-limit,Math.min(limit,amount));
        final boolean charge=amount<0;
        CompletableFuture<EconomyAdapter.Result> payout=charge?plugin.economy().takeAsync(p,-(double)amount,e.id):plugin.economy().giveAsync(p,amount,e.id);
        return payout.whenComplete((result,error)->{if(error==null&&result!=null&&result.success())notify(p,result,charge);});
    }

    /** Штраф за невыполненное условие сбора. Игрок видит, сколько списано. */
    public CompletableFuture<EconomyAdapter.Result> chargePenalty(Player p,int penalty,String eventId){
        return plugin.economy().takeAsync(p,penalty,eventId).whenComplete((result,error)->{if(error==null&&result!=null&&result.success())notify(p,result,true);});
    }

    /**
     * RU: Сообщение игроку о начислении или списании. Раньше его писала команда CMI (cmi money give/take);
     * транзакция Vault проходит молча, поэтому теперь сообщение пишет EventHeads. Если деньги прошли
     * консольной командой, она сообщает сама, и второе сообщение не отправляется.
     */
    private void notify(Player p,EconomyAdapter.Result result,boolean charge){
        try{
            int amount=result.actualAmount();
            if(amount<=0||result.viaCommand()||!plugin.getConfig().getBoolean("economy.notify-player",true))return;
            // Баланс читается здесь, в потоке экономики; сообщение отправляется в потоке игрока.
            String balance=charge?formatBalance(plugin.economy().balance(p)):"";
            SchedulerUtil.runEntity(plugin,p,()->{
                if(!p.isOnline())return;
                if(charge)plugin.lang().send(p,plugin.lang().tr(p,"economy-spent").replace("{amount}",String.valueOf(amount)).replace("{balance}",balance));
                else plugin.lang().send(p,plugin.lang().tr(p,"collect").replace("{amount}",String.valueOf(amount)));
            });
        }catch(Throwable t){
            // Сообщение не должно влиять на результат выплаты.
            plugin.getLogger().fine("Не удалось сообщить игроку о выплате: "+t);
        }
    }

    private String formatBalance(Double balance){
        if(balance==null)return "?";
        return java.math.BigDecimal.valueOf(balance).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public CompletableFuture<EconomyAdapter.Result> grant(Player p,EventDefinition e){return grantAsync(p,e);}

    public void announceFailure(Player p){
        plugin.lang().send(p, plugin.lang().tr(p,"economy-failed"));
    }

}

package ru.doksi.eventheads.localization;

import org.bukkit.ChatColor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatColorUtil {
    private static final Pattern HEX=Pattern.compile("#([0-9a-fA-F]{6})");
    private ChatColorUtil(){}
    public static String color(String s){
        if(s==null)return "";
        Matcher m=HEX.matcher(s); StringBuffer out=new StringBuffer();
        while(m.find()){
            String h=m.group(1); StringBuilder x=new StringBuilder("§x");
            for(char c:h.toCharArray())x.append('§').append(c);
            m.appendReplacement(out,Matcher.quoteReplacement(x.toString()));
        }
        m.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&',out.toString());
    }
    public static String strip(String s){return ChatColor.stripColor(color(s));}
}

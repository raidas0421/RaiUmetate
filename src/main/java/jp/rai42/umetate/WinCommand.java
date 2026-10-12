package jp.rai42.umetate;
import java.util.*;
import org.bukkit.command.*;
public final class WinCommand implements TabExecutor {
    private final WinService service;
    WinCommand(WinService service){this.service=service;}
    @Override public boolean onCommand(CommandSender s,Command c,String label,String[] args) {
        if(!s.hasPermission("raiumetate.admin")){s.sendMessage("管理者権限が必要です。");return true;}
        try {
            if(args.length==1 && args[0].equalsIgnoreCase("status")) s.sendMessage(service.status());
            else service.submit(WinRequest.parse(args,service.maximum()),WinService.Reason.COMMAND,s);
        } catch(IllegalArgumentException e){s.sendMessage(e.getMessage()+" 使い方: /win <win|lose> [n] または /win status");}
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String label,String[] args) {
        if(!s.hasPermission("raiumetate.admin") || args.length==0)return List.of();
        List<String> values=args.length==1?List.of("win","lose","status"):
            args.length==2 && List.of("win","lose","add","remove").contains(args[0].toLowerCase(Locale.ROOT))?List.of("1","5","10"):List.of();
        return values.stream().filter(v -> v.startsWith(args[args.length-1].toLowerCase(Locale.ROOT))).toList();
    }
}

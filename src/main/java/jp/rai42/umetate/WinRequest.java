package jp.rai42.umetate;

import java.util.Locale;

public record WinRequest(String action, int count) {
   public WinRequest(String action, int count) {
      if (("win".equals(action) || "lose".equals(action)) && count >= 1) {
         this.action = action;
         this.count = count;
      } else {
         throw new IllegalArgumentException("win/lose と正の整数を指定してください。");
      }
   }

   public static WinRequest parse(String[] args, int maximum) {
      if (args.length >= 1 && args.length <= 2) {
         String action = switch(args[0].toLowerCase(Locale.ROOT)) { case "add" -> "win"; case "remove" -> "lose"; default -> args[0].toLowerCase(Locale.ROOT); };
         int count = 1;
         if (args.length == 2) {
            if (!args[1].matches("[0-9]+")) {
               throw new IllegalArgumentException("数字は1〜" + maximum + "の整数を指定してください。");
            }

            try {
               count = Integer.parseInt(args[1]);
            } catch (NumberFormatException var5) {
               throw new IllegalArgumentException("数字が大きすぎます。上限: " + maximum);
            }
         }

         if (count >= 1 && count <= maximum) {
            return new WinRequest(action, count);
         } else {
            throw new IllegalArgumentException("数字は1〜" + maximum + "で指定してください。");
         }
      } else {
         throw new IllegalArgumentException("使い方: /win <win|lose> [数字]（省略時1）");
      }
   }

   public String payload() {
      return "{\"type\":\"" + this.action + "\"}";
   }

   public String description() {
      return (this.action.equals("win") ? "+" : "-") + this.count;
   }
}

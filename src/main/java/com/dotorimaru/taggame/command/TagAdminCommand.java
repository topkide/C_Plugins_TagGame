package com.dotorimaru.taggame.command;

import com.dotorimaru.taggame.TagGamePlugin;
import com.dotorimaru.taggame.game.GameManager;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TagAdminCommand implements TabExecutor {

    private final TagGamePlugin plugin;

    public TagAdminCommand(TagGamePlugin plugin) {
        this.plugin = plugin;
    }

    private GameManager g() { return plugin.game(); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("suljab.admin")) {
            g().send0(s, "no-permission", Map.of());
            return true;
        }
        if (a.length == 0) { help(s); return true; }

        switch (a[0]) {
            case "시민처리" -> {
                if (a.length < 2) { s.sendMessage("§e/술잡관리 시민처리 <관전|제외>"); return true; }
                switch (a[1]) {
                    case "관전" -> {
                        g().setCaughtMode(GameManager.CaughtMode.SPECTATE);
                        g().send0(s, "caught-mode-set", Map.of("mode", "관전"));
                    }
                    case "제외" -> {
                        g().setCaughtMode(GameManager.CaughtMode.EXCLUDE);
                        g().send0(s, "caught-mode-set", Map.of("mode", "제외"));
                    }
                    default -> s.sendMessage("§e/술잡관리 시민처리 <관전|제외>");
                }
            }
            case "공지" -> {
                if (a.length < 2) { s.sendMessage("§e/술잡관리 공지 <켜기|끄기>"); return true; }
                switch (a[1]) {
                    case "켜기" -> {
                        g().setCatchBroadcast(true);
                        g().send0(s, "broadcast-on", Map.of());
                    }
                    case "끄기" -> {
                        g().setCatchBroadcast(false);
                        g().send0(s, "broadcast-off", Map.of());
                    }
                    default -> s.sendMessage("§e/술잡관리 공지 <켜기|끄기>");
                }
            }
            case "술래퇴장" -> {
                if (a.length < 2) { s.sendMessage("§e/술잡관리 술래퇴장 <종료|재선정>"); return true; }
                switch (a[1]) {
                    case "종료" -> {
                        g().setTaggerQuitAction(GameManager.TaggerQuitAction.END);
                        g().send0(s, "quit-action-set", Map.of("mode", "게임 종료"));
                    }
                    case "재선정" -> {
                        g().setTaggerQuitAction(GameManager.TaggerQuitAction.REASSIGN);
                        g().send0(s, "quit-action-set", Map.of("mode", "새 술래 재선정"));
                    }
                    default -> s.sendMessage("§e/술잡관리 술래퇴장 <종료|재선정>");
                }
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        String caught = switch (g().caughtMode()) {
            case SPECTATE -> "관전";
            case EXCLUDE -> "제외";
            case NONE -> "§c미설정";
        };
        s.sendMessage("§6§l[ 술래잡기 관리 ]");
        s.sendMessage("§e/술잡관리 시민처리 <관전|제외> §7- 잡힌 시민 처리 방식 §f(현재: " + caught + "§f)");
        s.sendMessage("§e/술잡관리 공지 <켜기|끄기> §7- 잡힘 전체 공지 §f(현재: "
                + (g().catchBroadcast() ? "켜짐" : "꺼짐") + ")");
        s.sendMessage("§e/술잡관리 술래퇴장 <종료|재선정> §7- 술래 전원 퇴장 시 처리 §f(현재: "
                + (g().taggerQuitAction() == GameManager.TaggerQuitAction.REASSIGN ? "재선정" : "종료") + ")");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("suljab.admin")) return List.of();
        if (a.length == 1) return filter(Arrays.asList("시민처리", "공지", "술래퇴장"), a[0]);
        if (a.length == 2) {
            switch (a[0]) {
                case "시민처리" -> { return filter(Arrays.asList("관전", "제외"), a[1]); }
                case "공지" -> { return filter(Arrays.asList("켜기", "끄기"), a[1]); }
                case "술래퇴장" -> { return filter(Arrays.asList("종료", "재선정"), a[1]); }
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(prefix)) out.add(o);
        return out;
    }
}

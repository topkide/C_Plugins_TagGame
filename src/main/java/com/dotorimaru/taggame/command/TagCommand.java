package com.dotorimaru.taggame.command;

import com.dotorimaru.taggame.TagGamePlugin;
import com.dotorimaru.taggame.game.GameManager;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TagCommand implements TabExecutor {

    private final TagGamePlugin plugin;

    public TagCommand(TagGamePlugin plugin) {
        this.plugin = plugin;
    }

    private GameManager g() { return plugin.game(); }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("suljab.admin")) return true;
        g().send0(s, "no-permission", Map.of());
        return false;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }

        switch (a[0]) {
            case "참가" -> {
                if (!(s instanceof Player p)) { g().send0(s, "player-only", Map.of()); return true; }
                g().join(p);
            }
            case "나가기" -> {
                if (!(s instanceof Player p)) { g().send0(s, "player-only", Map.of()); return true; }
                g().leave(p);
            }
            case "시작" -> {
                if (!admin(s)) return true;
                g().start(s);
            }
            case "중단" -> {
                if (!admin(s)) return true;
                g().pause(s);
            }
            case "재개" -> {
                if (!admin(s)) return true;
                g().resume(s);
            }
            case "종료" -> {
                if (!admin(s)) return true;
                g().end(s, false);
            }
            case "강제종료" -> {
                if (!admin(s)) return true;
                g().end(s, true);
            }
            case "설정" -> {
                if (!admin(s)) return true;
                setting(s, a);
            }
            case "상태" -> g().status(s);
            case "도움말" -> help(s);
            default -> help(s);
        }
        return true;
    }

    private void setting(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage("§e/술잡 설정 <타이머|준비시간|최소인원|술래수> <값>");
            return;
        }
        Integer v = parsePositive(a[2], s);
        if (v == null) return;
        switch (a[1]) {
            case "타이머" -> {
                g().setGameSeconds(v);
                g().send0(s, "timer-set", Map.of("sec", String.valueOf(v)));
            }
            case "준비시간" -> {
                g().setPrepSeconds(v);
                g().send0(s, "prep-set", Map.of("sec", String.valueOf(v)));
            }
            case "최소인원" -> {
                if (v < 2) { g().send0(s, "bad-number", Map.of()); return; }
                g().setMinPlayers(v);
                g().send0(s, "min-set", Map.of("count", String.valueOf(v)));
            }
            case "술래수" -> {
                g().setTaggerCount(v);
                g().send0(s, "taggers-set", Map.of("count", String.valueOf(v)));
            }
            default -> s.sendMessage("§e/술잡 설정 <타이머|준비시간|최소인원|술래수> <값>");
        }
    }

    private Integer parsePositive(String raw, CommandSender s) {
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) { g().send0(s, "bad-number", Map.of()); return null; }
            return v;
        } catch (NumberFormatException e) {
            g().send0(s, "bad-number", Map.of());
            return null;
        }
    }

    private void help(CommandSender s) {
        s.sendMessage("§6§l[ 술래잡기 ]");
        s.sendMessage("§e/술잡 참가 §7- 게임 참가 (대기 중에만)");
        s.sendMessage("§e/술잡 나가기 §7- 참가 취소 (대기 중에만)");
        s.sendMessage("§e/술잡 상태 §7- 현재 게임 상태 확인");
        s.sendMessage("§e/술잡 도움말 §7- 도움말");
        if (s.hasPermission("suljab.admin")) {
            s.sendMessage("§c-- 관리자 --");
            s.sendMessage("§e/술잡 시작 §7- 게임 시작");
            s.sendMessage("§e/술잡 중단 §7- 게임 일시정지");
            s.sendMessage("§e/술잡 재개 §7- 일시정지된 게임 재개");
            s.sendMessage("§e/술잡 종료 §7- 게임 완전 종료");
            s.sendMessage("§e/술잡 강제종료 §7- 게임 즉시 강제 종료");
            s.sendMessage("§e/술잡 설정 타이머 <초> §7- 게임 제한시간");
            s.sendMessage("§e/술잡 설정 준비시간 <초> §7- 술래 준비시간");
            s.sendMessage("§e/술잡 설정 최소인원 <숫자> §7- 시작 최소 인원");
            s.sendMessage("§e/술잡 설정 술래수 <숫자> §7- 술래 수");
            s.sendMessage("§e/술잡관리 §7- 게임 정책 설정 (시민처리/공지/술래퇴장)");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        boolean admin = s.hasPermission("suljab.admin");
        if (a.length == 1) {
            List<String> base = new ArrayList<>(Arrays.asList("참가", "나가기", "상태", "도움말"));
            if (admin) base.addAll(Arrays.asList("시작", "중단", "재개", "종료", "강제종료", "설정"));
            return filter(base, a[0]);
        }
        if (admin && a.length == 2 && a[0].equals("설정")) {
            return filter(Arrays.asList("타이머", "준비시간", "최소인원", "술래수"), a[1]);
        }
        if (admin && a.length == 3 && a[0].equals("설정")) {
            if (a[1].equals("타이머") || a[1].equals("준비시간")) {
                return filter(Arrays.asList("30", "60", "120", "180", "300", "600"), a[2]);
            }
            if (a[1].equals("최소인원") || a[1].equals("술래수")) {
                return filter(Arrays.asList("1", "2", "3", "4", "5"), a[2]);
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

package com.dotorimaru.taggame;

import com.dotorimaru.taggame.command.TagAdminCommand;
import com.dotorimaru.taggame.command.TagCommand;
import com.dotorimaru.taggame.game.GameManager;
import com.dotorimaru.taggame.listener.TagListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class TagGamePlugin extends JavaPlugin {

    private GameManager game;

    public GameManager game() {
        return game;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        game = new GameManager(this);
        game.loadConfig();

        getServer().getPluginManager().registerEvents(new TagListener(this), this);

        var cmd = getCommand("술잡");
        if (cmd != null) {
            var exec = new TagCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }
        var adminCmd = getCommand("술잡관리");
        if (adminCmd != null) {
            var exec = new TagAdminCommand(this);
            adminCmd.setExecutor(exec);
            adminCmd.setTabCompleter(exec);
        }

        // 1초마다: 준비시간/게임 타이머, 보스바, 검 자동 재지급 (PAUSED 상태에서는 아무것도 하지 않음)
        getServer().getScheduler().runTaskTimer(this, () -> game.secondTick(), 20L, 20L);

        getLogger().info("TagGame 활성화 (Paper 1.21.4)");
    }

    @Override
    public void onDisable() {
        if (game != null) game.shutdown();
    }

    public void reload() {
        game.loadConfig();
    }
}

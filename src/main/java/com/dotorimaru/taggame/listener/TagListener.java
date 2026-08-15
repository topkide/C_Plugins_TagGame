package com.dotorimaru.taggame.listener;

import com.dotorimaru.taggame.TagGamePlugin;
import com.dotorimaru.taggame.game.GameManager;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TagListener implements Listener {

    private final TagGamePlugin plugin;

    public TagListener(TagGamePlugin plugin) {
        this.plugin = plugin;
    }

    private GameManager g() { return plugin.game(); }

    // 게임 검 공격: 데미지는 항상 캔슬, 게임 중 술래→생존 시민이면 잡기 판정
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!g().isGameSword(attacker.getInventory().getItemInMainHand())) return;
        e.setCancelled(true); // 태그 판정 전용 - 데미지/넉백 없음 (비참가자 포함)
        if (!(e.getEntity() instanceof Player victim)) return;
        g().tryCatch(attacker, victim);
    }

    // 이동 제한: 준비시간 술래 / 일시정지 중 참가자 전원 (시선 회전은 허용)
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!g().isMovementLocked(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        e.setTo(fixed);
    }

    // 이동 제한 중 엔더진주/후렴과 텔레포트 우회 방지
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!g().isMovementLocked(e.getPlayer().getUniqueId())) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || e.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            e.setCancelled(true);
        }
    }

    // 게임 검 버리기 방지
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!g().isGameActive()) return;
        if (g().isGameSword(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    // 게임 검을 상자 등 다른 인벤토리로 옮기는 것 방지
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!g().isGameActive()) return;
        boolean topIsExternal = e.getView().getTopInventory().getType() != InventoryType.CRAFTING;
        // 커서에 든 게임 검을 외부 인벤토리에 놓는 경우
        if (g().isGameSword(e.getCursor())
                && e.getClickedInventory() != null
                && e.getClickedInventory().getType() != InventoryType.PLAYER) {
            e.setCancelled(true);
            return;
        }
        // 쉬프트 클릭으로 게임 검을 외부 인벤토리로 옮기는 경우
        if (topIsExternal && e.isShiftClick() && g().isGameSword(e.getCurrentItem())) {
            e.setCancelled(true);
        }
    }

    // 사망: 드롭에서 게임 검 제거 + 시민이면 잡힘 처리
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(it -> g().isGameSword(it));
        g().onCitizenDeath(e.getEntity());
    }

    // 리스폰: 술래 검 재지급 / 잡힌 시민 관전 적용
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        g().resupplyOnRespawn(e.getPlayer());
        g().applySpectateOnRespawn(e.getPlayer());
    }

    // 접속 종료: 시민 → 잡힘 처리 / 술래 → 알림 + 종료 또는 재선정
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        g().handleQuit(e.getPlayer());
    }

    // 접속: 보스바 추가, 관전 재적용/복구, 남은 게임 검 회수
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        g().handleJoin(e.getPlayer());
    }
}

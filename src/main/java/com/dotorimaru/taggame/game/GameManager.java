package com.dotorimaru.taggame.game;

import com.dotorimaru.taggame.TagGamePlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.*;

public class GameManager {

    public enum Phase { WAITING, STARTING, PREPARING, PLAYING, PAUSED, ENDING }
    public enum CaughtMode { NONE, SPECTATE, EXCLUDE }
    public enum TaggerQuitAction { END, REASSIGN }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final TagGamePlugin plugin;
    private final NamespacedKey swordKey;

    private Phase phase = Phase.WAITING;
    private Phase pausedFrom = Phase.PLAYING;

    private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> taggers = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> aliveCitizens = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> caughtCitizens = new LinkedHashSet<>();

    // 관전 처리된 시민의 원상복구 정보 (게임모드/잡힌 위치)
    private final Map<UUID, GameMode> restoreModes = new HashMap<>();
    private final Map<UUID, Location> restoreLocs = new HashMap<>();

    private int prepRemain;
    private int gameRemain;
    private BossBar bossBar;
    private int tickCount;

    // ===== config cache =====
    private int gameSeconds, prepSeconds, minPlayers, taggerCount;
    private CaughtMode caughtMode;
    private TaggerQuitAction taggerQuitAction;
    private boolean catchBroadcast, prepBlindness;
    private String swordName, prepBarTitle, gameBarTitle;
    private BarColor prepBarColor, gameBarColor, lowBarColor;
    private int lowSeconds;

    public GameManager(TagGamePlugin plugin) {
        this.plugin = plugin;
        this.swordKey = new NamespacedKey(plugin, "game-sword");
    }

    public void loadConfig() {
        plugin.reloadConfig();
        var c = plugin.getConfig();
        gameSeconds = Math.max(1, c.getInt("game-seconds", 300));
        prepSeconds = Math.max(1, c.getInt("prep-seconds", 60));
        minPlayers = Math.max(2, c.getInt("min-players", 2));
        taggerCount = Math.max(1, c.getInt("tagger-count", 1));
        caughtMode = parseCaughtMode(c.getString("caught-mode", "none"));
        taggerQuitAction = "reassign".equalsIgnoreCase(c.getString("tagger-quit-action", "end"))
                ? TaggerQuitAction.REASSIGN : TaggerQuitAction.END;
        catchBroadcast = c.getBoolean("catch-broadcast", true);
        prepBlindness = c.getBoolean("prep-blindness", true);
        swordName = c.getString("sword.name", "&c술래의 검");
        prepBarTitle = c.getString("bossbar.prep-title", "&b준비시간");
        gameBarTitle = c.getString("bossbar.game-title", "&e술래잡기");
        prepBarColor = parseColor(c.getString("bossbar.prep-color"), BarColor.BLUE);
        gameBarColor = parseColor(c.getString("bossbar.game-color"), BarColor.YELLOW);
        lowBarColor = parseColor(c.getString("bossbar.low-color"), BarColor.RED);
        lowSeconds = c.getInt("bossbar.low-seconds", 30);
    }

    private static CaughtMode parseCaughtMode(String s) {
        if ("spectate".equalsIgnoreCase(s)) return CaughtMode.SPECTATE;
        if ("exclude".equalsIgnoreCase(s)) return CaughtMode.EXCLUDE;
        return CaughtMode.NONE;
    }

    private static BarColor parseColor(String s, BarColor def) {
        if (s == null) return def;
        try { return BarColor.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return def; }
    }

    // =================== 조회 ===================
    public Phase phase() { return phase; }
    public Phase pausedFrom() { return pausedFrom; }
    public boolean isParticipant(UUID id) { return participants.contains(id); }
    public boolean isTagger(UUID id) { return taggers.contains(id); }
    public boolean isAliveCitizen(UUID id) { return aliveCitizens.contains(id); }
    public boolean isCaught(UUID id) { return caughtCitizens.contains(id); }
    public boolean isGameActive() {
        return phase == Phase.PREPARING || phase == Phase.PLAYING || phase == Phase.PAUSED;
    }

    // =================== 참가 / 나가기 ===================
    public void join(Player p) {
        if (phase != Phase.WAITING) { send(p, "cannot-join-now", Map.of()); return; }
        if (!participants.add(p.getUniqueId())) { send(p, "already-joined", Map.of()); return; }
        broadcast("joined", Map.of("player", p.getName(), "count", String.valueOf(participants.size())));
    }

    public void leave(Player p) {
        if (phase != Phase.WAITING) { send(p, "cannot-leave-now", Map.of()); return; }
        if (!participants.remove(p.getUniqueId())) { send(p, "not-joined", Map.of()); return; }
        broadcast("left", Map.of("player", p.getName(), "count", String.valueOf(participants.size())));
    }

    // =================== 게임 시작 ===================
    public void start(CommandSender s) {
        if (phase != Phase.WAITING) { send0(s, "already-running", Map.of()); return; }
        if (caughtMode == CaughtMode.NONE) { send0(s, "caught-mode-unset", Map.of()); return; }

        participants.removeIf(id -> Bukkit.getPlayer(id) == null); // 오프라인 정리
        if (participants.size() < minPlayers) {
            send0(s, "need-more", Map.of("min", String.valueOf(minPlayers),
                    "count", String.valueOf(participants.size())));
            return;
        }
        if (taggerCount >= participants.size()) {
            send0(s, "bad-tagger-count", Map.of("count", String.valueOf(taggerCount),
                    "players", String.valueOf(participants.size())));
            return;
        }

        phase = Phase.STARTING;
        taggers.clear();
        aliveCitizens.clear();
        caughtCitizens.clear();

        List<UUID> pool = new ArrayList<>(participants);
        Collections.shuffle(pool);
        for (int i = 0; i < pool.size(); i++) {
            if (i < taggerCount) taggers.add(pool.get(i));
            else aliveCitizens.add(pool.get(i));
        }

        // 역할 안내 + 술래 검 지급
        for (UUID id : taggers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            showTitle(p, "tagger-title", "tagger-subtitle", Map.of("sec", String.valueOf(prepSeconds)));
            giveSword(p);
            if (prepBlindness) applyBlindness(p, prepSeconds);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }
        for (UUID id : aliveCitizens) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            showTitle(p, "citizen-title", "citizen-subtitle", Map.of());
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }

        broadcast("game-start", Map.of("taggers", names(taggers)));
        broadcast("prep-info", Map.of("sec", String.valueOf(prepSeconds)));

        prepRemain = prepSeconds;
        phase = Phase.PREPARING;
        createBossBar();
        updateBossBar(prepRemain, prepSeconds, prepBarTitle, prepBarColor);
    }

    // =================== 매 초 틱 (plugin 스케줄러에서 호출) ===================
    public void secondTick() {
        tickCount++;
        switch (phase) {
            case PREPARING -> {
                resupplyTick();
                prepRemain--;
                if (prepRemain <= 0) beginPlaying();
                else updateBossBar(prepRemain, prepSeconds, prepBarTitle, prepBarColor);
            }
            case PLAYING -> {
                resupplyTick();
                gameRemain--;
                updateBossBar(gameRemain, gameSeconds, gameBarTitle, gameBarColor);
                if (gameRemain <= 0) citizensWin();
            }
            default -> { /* WAITING / PAUSED / 그 외에는 아무것도 하지 않음 */ }
        }
    }

    private void beginPlaying() {
        phase = Phase.PLAYING;
        gameRemain = gameSeconds;
        for (UUID id : taggers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.removePotionEffect(PotionEffectType.DARKNESS);
        }
        titleAll("start-title", null, Map.of());
        broadcast("start-broadcast", Map.of());
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.6f);
        }
        updateBossBar(gameRemain, gameSeconds, gameBarTitle, gameBarColor);
    }

    // =================== 잡기 판정 ===================
    /** 술래가 게임 검으로 시민을 때렸을 때 (데미지는 리스너에서 이미 캔슬됨) */
    public void tryCatch(Player tagger, Player victim) {
        if (phase != Phase.PLAYING) return;
        if (!taggers.contains(tagger.getUniqueId())) return;
        if (!aliveCitizens.contains(victim.getUniqueId())) return;
        markCaught(victim, "caught", Map.of("victim", victim.getName(), "tagger", tagger.getName()));
    }

    /** 시민 사망 → 잡힘 처리 (관전 전환은 리스폰 시점에 적용) */
    public void onCitizenDeath(Player victim) {
        if (!isGameActive()) return;
        if (!aliveCitizens.contains(victim.getUniqueId())) return;
        markCaught(victim, "caught-death", Map.of("victim", victim.getName()));
    }

    private void markCaught(Player victim, String msgKey, Map<String, String> ph) {
        UUID id = victim.getUniqueId();
        aliveCitizens.remove(id);
        caughtCitizens.add(id);

        if (catchBroadcast) {
            Map<String, String> all = new HashMap<>(ph);
            all.put("count", String.valueOf(aliveCitizens.size()));
            broadcast(msgKey, all);
        }
        showTitle(victim, "caught-title", "caught-subtitle", Map.of());
        victim.playSound(victim.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.6f);

        if (caughtMode == CaughtMode.SPECTATE) {
            restoreModes.put(id, victim.getGameMode());
            restoreLocs.put(id, victim.getLocation().clone());
            if (!victim.isDead()) victim.setGameMode(GameMode.SPECTATOR);
            // 사망한 경우에는 리스폰 이벤트에서 관전 적용
        }
        checkTaggerWin();
    }

    /** 리스폰한 잡힌 시민에게 관전 적용 */
    public void applySpectateOnRespawn(Player p) {
        if (!isGameActive()) return;
        if (caughtMode != CaughtMode.SPECTATE) return;
        if (!caughtCitizens.contains(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isGameActive() && caughtCitizens.contains(p.getUniqueId()) && p.isOnline()) {
                p.setGameMode(GameMode.SPECTATOR);
            }
        });
    }

    private void checkTaggerWin() {
        if (aliveCitizens.isEmpty()) taggersWin();
    }

    // =================== 승리 처리 ===================
    private void taggersWin() {
        phase = Phase.ENDING;
        titleAll("tagger-win-title", null, Map.of());
        broadcast("tagger-win", Map.of());
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 0.8f);
        }
        cleanup();
    }

    private void citizensWin() {
        phase = Phase.ENDING;
        titleAll("citizen-win-title", null, Map.of());
        broadcast("citizen-win", Map.of());
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
        cleanup();
    }

    // =================== 일시정지 / 재개 ===================
    public void pause(CommandSender s) {
        if (phase != Phase.PREPARING && phase != Phase.PLAYING) { send0(s, "cannot-pause", Map.of()); return; }
        pausedFrom = phase;
        phase = Phase.PAUSED;
        // 준비시간 중 일시정지면 술래 실명 유지
        if (pausedFrom == Phase.PREPARING && prepBlindness) {
            for (UUID id : taggers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) applyBlindness(p, 3600);
            }
        }
        broadcast("paused", Map.of("time", formatTime(remainingSeconds())));
    }

    public void resume(CommandSender s) {
        if (phase != Phase.PAUSED) { send0(s, "not-paused", Map.of()); return; }
        phase = pausedFrom;
        if (phase == Phase.PREPARING) {
            for (UUID id : taggers) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.removePotionEffect(PotionEffectType.BLINDNESS);
                if (prepBlindness) applyBlindness(p, prepRemain);
            }
            updateBossBar(prepRemain, prepSeconds, prepBarTitle, prepBarColor);
        } else {
            updateBossBar(gameRemain, gameSeconds, gameBarTitle, gameBarColor);
        }
        broadcast("resumed", Map.of("time", formatTime(remainingSeconds())));
    }

    public int remainingSeconds() {
        Phase base = (phase == Phase.PAUSED) ? pausedFrom : phase;
        return base == Phase.PREPARING ? prepRemain : gameRemain;
    }

    // =================== 종료 ===================
    public void end(CommandSender s, boolean forced) {
        if (phase == Phase.WAITING) { send0(s, "no-game", Map.of()); return; }
        broadcast(forced ? "force-stopped" : "stopped", Map.of());
        cleanup();
    }

    /** 모든 게임 상태 초기화 → WAITING 복귀 */
    public void cleanup() {
        removeBossBar();
        // 검 회수 + 효과 제거
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeGameSwords(p);
            if (taggers.contains(p.getUniqueId())) {
                p.removePotionEffect(PotionEffectType.BLINDNESS);
                p.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
        // 관전 처리된 시민 복구 (오프라인이면 다음 접속 시 복구)
        for (UUID id : new ArrayList<>(caughtCitizens)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restorePlayer(p);
        }
        taggers.clear();
        aliveCitizens.clear();
        caughtCitizens.clear();
        participants.clear();
        prepRemain = 0;
        gameRemain = 0;
        phase = Phase.WAITING;
    }

    /** 관전 전환됐던 플레이어를 원래 게임모드/위치로 복구 */
    public void restorePlayer(Player p) {
        UUID id = p.getUniqueId();
        GameMode gm = restoreModes.remove(id);
        Location loc = restoreLocs.remove(id);
        if (gm == null) return;
        if (loc != null) p.teleport(loc);
        p.setGameMode(gm);
    }

    public boolean hasPendingRestore(UUID id) { return restoreModes.containsKey(id); }

    public void shutdown() { if (phase != Phase.WAITING) cleanup(); }

    // =================== 접속 종료 / 재접속 처리 ===================
    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        if (phase == Phase.WAITING) {
            if (participants.remove(id)) {
                broadcast("left", Map.of("player", p.getName(), "count", String.valueOf(participants.size())));
            }
            return;
        }
        if (!isGameActive()) return;

        if (aliveCitizens.contains(id)) {
            // 시민 퇴장 → 잡힌 처리
            aliveCitizens.remove(id);
            caughtCitizens.add(id);
            broadcast("caught-quit", Map.of("victim", p.getName(),
                    "count", String.valueOf(aliveCitizens.size())));
            checkTaggerWin();
        } else if (taggers.contains(id)) {
            taggers.remove(id);
            notifyAdmins("tagger-quit-notify", Map.of("player", p.getName()));
            if (taggers.isEmpty()) {
                if (taggerQuitAction == TaggerQuitAction.REASSIGN && aliveCitizens.size() >= 2) {
                    reassignTagger();
                } else {
                    broadcast("tagger-quit-end", Map.of());
                    cleanup();
                }
            }
        }
    }

    private void reassignTagger() {
        List<UUID> pool = new ArrayList<>(aliveCitizens);
        UUID chosen = pool.get(new Random().nextInt(pool.size()));
        aliveCitizens.remove(chosen);
        taggers.add(chosen);
        Player p = Bukkit.getPlayer(chosen);
        if (p != null) {
            showTitle(p, "tagger-reassign-title", null, Map.of());
            giveSword(p);
            broadcast("tagger-reassigned", Map.of("player", p.getName()));
        }
    }

    public void handleJoin(Player p) {
        UUID id = p.getUniqueId();
        if (bossBar != null) bossBar.addPlayer(p);
        if (isGameActive() && caughtCitizens.contains(id) && caughtMode == CaughtMode.SPECTATE) {
            // 잡힌 시민이 재접속 → 다시 관전 적용
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (isGameActive() && caughtCitizens.contains(id) && p.isOnline()) {
                    p.setGameMode(GameMode.SPECTATOR);
                }
            });
            return;
        }
        if (hasPendingRestore(id) && !caughtCitizens.contains(id)) {
            // 게임이 끝난 뒤 접속한 관전자 복구
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) restorePlayer(p); });
        }
        // 게임 검이 남아있는데 현재 술래가 아니면 회수
        if (!(isGameActive() && taggers.contains(id))) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) removeGameSwords(p); });
        }
    }

    // =================== 이동 제한 ===================
    public boolean isMovementLocked(UUID id) {
        if (phase == Phase.PREPARING) return taggers.contains(id);
        if (phase == Phase.PAUSED) {
            if (taggers.contains(id) || aliveCitizens.contains(id)) return true;
        }
        return false;
    }

    private void applyBlindness(Player p, int seconds) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, seconds * 20 + 20, 0, false, false, false));
    }

    // =================== 술래의 검 ===================
    public ItemStack createSword() {
        ItemStack it = new ItemStack(Material.WOODEN_SWORD);
        var meta = it.getItemMeta();
        meta.displayName(LEGACY.deserialize(swordName)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public boolean isGameSword(ItemStack it) {
        if (it == null || it.getType() != Material.WOODEN_SWORD || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    public boolean hasGameSword(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isGameSword(it)) return true;
        }
        return false;
    }

    public void giveSword(Player p) {
        if (hasGameSword(p)) return;
        var leftover = p.getInventory().addItem(createSword());
        if (!leftover.isEmpty()) send(p, "inv-full", Map.of());
    }

    public void removeGameSwords(Player p) {
        var inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isGameSword(contents[i])) inv.setItem(i, null);
        }
    }

    /** 술래가 검을 잃어버렸으면 자동 재지급 (매 초, 경고는 10초마다) */
    private void resupplyTick() {
        for (UUID id : taggers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) continue;
            if (hasGameSword(p)) continue;
            var leftover = p.getInventory().addItem(createSword());
            if (!leftover.isEmpty() && tickCount % 10 == 0) send(p, "inv-full", Map.of());
        }
    }

    /** 리스폰한 술래에게 검 재지급 */
    public void resupplyOnRespawn(Player p) {
        if (!isGameActive() || !taggers.contains(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && isGameActive() && taggers.contains(p.getUniqueId())) giveSword(p);
        });
    }

    // =================== 보스바 ===================
    private void createBossBar() {
        removeBossBar();
        bossBar = Bukkit.createBossBar("", prepBarColor, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
    }

    private void updateBossBar(int remain, int total, String title, BarColor color) {
        if (bossBar == null) return;
        if (remain < 0) remain = 0;
        double prog = total <= 0 ? 0 : Math.max(0.0, Math.min(1.0, (double) remain / total));
        bossBar.setProgress(prog);
        bossBar.setColor(remain <= lowSeconds ? lowBarColor : color);
        bossBar.setTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                title + " &f| " + formatTime(remain)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
        }
    }

    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }

    public static String formatTime(int sec) {
        if (sec < 0) sec = 0;
        int m = sec / 60, s = sec % 60;
        return (m < 10 ? "0" + m : String.valueOf(m)) + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    // =================== 설정 ===================
    public void setGameSeconds(int s) {
        gameSeconds = s;
        plugin.getConfig().set("game-seconds", s);
        plugin.saveConfig();
    }

    public void setPrepSeconds(int s) {
        prepSeconds = s;
        plugin.getConfig().set("prep-seconds", s);
        plugin.saveConfig();
    }

    public void setMinPlayers(int n) {
        minPlayers = n;
        plugin.getConfig().set("min-players", n);
        plugin.saveConfig();
    }

    public void setTaggerCount(int n) {
        taggerCount = n;
        plugin.getConfig().set("tagger-count", n);
        plugin.saveConfig();
    }

    public void setCaughtMode(CaughtMode m) {
        caughtMode = m;
        plugin.getConfig().set("caught-mode", m == CaughtMode.SPECTATE ? "spectate"
                : m == CaughtMode.EXCLUDE ? "exclude" : "none");
        plugin.saveConfig();
    }

    public void setCatchBroadcast(boolean b) {
        catchBroadcast = b;
        plugin.getConfig().set("catch-broadcast", b);
        plugin.saveConfig();
    }

    public void setTaggerQuitAction(TaggerQuitAction a) {
        taggerQuitAction = a;
        plugin.getConfig().set("tagger-quit-action", a == TaggerQuitAction.REASSIGN ? "reassign" : "end");
        plugin.saveConfig();
    }

    public int gameSeconds() { return gameSeconds; }
    public int prepSeconds() { return prepSeconds; }
    public int minPlayers() { return minPlayers; }
    public int taggerCount() { return taggerCount; }
    public CaughtMode caughtMode() { return caughtMode; }
    public boolean catchBroadcast() { return catchBroadcast; }
    public TaggerQuitAction taggerQuitAction() { return taggerQuitAction; }

    // =================== 상태 출력 ===================
    public void status(CommandSender s) {
        if (phase == Phase.WAITING) {
            s.sendMessage(LEGACY.deserialize("&7현재 게임이 진행 중이지 않습니다."));
            s.sendMessage(LEGACY.deserialize("&e대기 중 참가자(" + participants.size() + "): &f"
                    + (participants.isEmpty() ? "없음" : names(participants))));
            return;
        }
        String stateName = switch (phase) {
            case PREPARING -> "준비시간 (술래 이동 불가)";
            case PLAYING -> "게임 진행 중";
            case PAUSED -> "일시정지" + (pausedFrom == Phase.PREPARING ? " (준비시간)" : " (진행 중)");
            default -> phase.name();
        };
        s.sendMessage(LEGACY.deserialize("&6&l[ 술래잡기 상태 ]"));
        s.sendMessage(LEGACY.deserialize("&e현재 상태: &f" + stateName));
        s.sendMessage(LEGACY.deserialize("&e술래: &c" + (taggers.isEmpty() ? "없음" : names(taggers))));
        s.sendMessage(LEGACY.deserialize("&e생존 시민(" + aliveCitizens.size() + "): &a"
                + (aliveCitizens.isEmpty() ? "없음" : names(aliveCitizens))));
        s.sendMessage(LEGACY.deserialize("&e잡힌 시민(" + caughtCitizens.size() + "): &7"
                + (caughtCitizens.isEmpty() ? "없음" : names(caughtCitizens))));
        Phase base = (phase == Phase.PAUSED) ? pausedFrom : phase;
        String label = base == Phase.PREPARING ? "남은 준비시간" : "남은 시간";
        s.sendMessage(LEGACY.deserialize("&e" + label + ": &f" + formatTime(remainingSeconds())));
    }

    private String names(Collection<UUID> ids) {
        List<String> out = new ArrayList<>();
        for (UUID id : ids) {
            var op = Bukkit.getOfflinePlayer(id);
            out.add(op.getName() != null ? op.getName() : id.toString().substring(0, 8));
        }
        return String.join(", ", out);
    }

    // =================== 메시지 유틸 ===================
    public String raw(String key, String def) {
        return plugin.getConfig().getString("messages." + key, def);
    }

    public Component fmt(String s, Map<String, String> ph) {
        for (var e : ph.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return LEGACY.deserialize(s);
    }

    public void send(Player p, String key, Map<String, String> ph) {
        p.sendMessage(fmt(raw(key, key), ph));
    }

    public void send0(CommandSender s, String key, Map<String, String> ph) {
        s.sendMessage(fmt(raw(key, key), ph));
    }

    public void broadcast(String key, Map<String, String> ph) {
        Bukkit.broadcast(fmt(raw(key, key), ph));
    }

    private void notifyAdmins(String key, Map<String, String> ph) {
        Component msg = fmt(raw(key, key), ph);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("suljab.admin")) p.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private void showTitle(Player p, String titleKey, String subKey, Map<String, String> ph) {
        Component title = fmt(raw(titleKey, titleKey), ph);
        Component sub = subKey == null ? Component.empty() : fmt(raw(subKey, subKey), ph);
        p.showTitle(Title.title(title, sub,
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
    }

    private void titleAll(String titleKey, String subKey, Map<String, String> ph) {
        for (Player p : Bukkit.getOnlinePlayers()) showTitle(p, titleKey, subKey, ph);
    }
}

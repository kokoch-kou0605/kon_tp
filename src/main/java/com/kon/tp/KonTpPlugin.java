package com.kon.tp;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class KonTpPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final int MAX_RTP_ATTEMPTS = 60;

    private final Map<UUID, List<TeleportRequest>> pendingByTarget = new HashMap<>();
    private final Set<UUID> tpaDisabled = new HashSet<>();
    private final Set<UUID> tpaAutoAccept = new HashSet<>();

    private long requestTimeoutMillis;
    private int rtpMin;
    private int rtpMax;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        requestTimeoutMillis = getConfig().getLong("request-timeout-seconds", 60L) * 1000L;
        rtpMin = getConfig().getInt("rtp.min-coordinate", -10_000_000);
        rtpMax = getConfig().getInt("rtp.max-coordinate", 10_000_000);

        register("rtp");
        register("tpa");
        register("tpahere");
        register("tpatoggle");
        register("tpaauto");
        register("tpaccept");
        register("tpdeny");

        getServer().getPluginManager().registerEvents(this, this);
    }

    private void register(String name) {
        Objects.requireNonNull(getCommand(name), () -> "Missing command: " + name).setExecutor(this);
        Objects.requireNonNull(getCommand(name), () -> "Missing command: " + name).setTabCompleter(this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingByTarget.remove(id);
        tpaDisabled.remove(id);
        tpaAutoAccept.remove(id);
        pendingByTarget.values().forEach(list -> list.removeIf(req -> req.sender().equals(id)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "rtp" -> handleRtp(player);
            case "tpa" -> handleRequest(player, args, RequestType.TPA);
            case "tpahere" -> handleRequest(player, args, RequestType.TPA_HERE);
            case "tpatoggle" -> handleToggle(player);
            case "tpaauto" -> handleAuto(player);
            case "tpaccept" -> handleAccept(player, args);
            case "tpdeny" -> handleDeny(player, args);
            default -> false;
        };
    }

    private boolean handleRtp(Player player) {
        World world = player.getWorld();
        for (int i = 0; i < MAX_RTP_ATTEMPTS; i++) {
            int x = ThreadLocalRandom.current().nextInt(rtpMin, rtpMax + 1);
            int z = ThreadLocalRandom.current().nextInt(rtpMin, rtpMax + 1);
            int y = world.getHighestBlockYAt(x, z);
            Location target = new Location(world, x + 0.5, y + 1.0, z + 0.5);
            if (!target.getBlock().getType().isAir()) {
                continue;
            }
            if (!target.clone().add(0.0, 1.0, 0.0).getBlock().getType().isAir()) {
                continue;
            }
            player.teleportAsync(target);
            player.sendMessage(color("&aRTP完了: &f" + world.getName() + " &7(" + x + ", " + y + ", " + z + ")"));
            return true;
        }
        player.sendMessage(color("&c安全な場所が見つかりませんでした。"));
        return true;
    }

    private boolean handleRequest(Player sender, String[] args, RequestType type) {
        if (args.length != 1) {
            sender.sendMessage(color("&e使い方: /" + (type == RequestType.TPA ? "tpa" : "tpahere") + " <player>"));
            return true;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(color("&cプレイヤーが見つかりません。"));
            return true;
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(color("&c自分自身には送れません。"));
            return true;
        }
        if (tpaDisabled.contains(target.getUniqueId())) {
            sender.sendMessage(color("&c相手はTPA申請を受け付けていません。"));
            return true;
        }

        List<TeleportRequest> list = pendingByTarget.computeIfAbsent(target.getUniqueId(), key -> new ArrayList<>());
        list.removeIf(r -> r.sender().equals(sender.getUniqueId()));
        TeleportRequest req = new TeleportRequest(sender.getUniqueId(), target.getUniqueId(), type, System.currentTimeMillis());
        list.add(req);

        sender.sendMessage(color("&a" + target.getName() + " に" + type.displayName + "を送信しました。"));
        target.sendMessage(color("&e" + sender.getName() + " から " + type.displayName + " が届きました。"));
        target.sendMessage(color("&7承認: &a/tpaccept " + sender.getName() + "&7  拒否: &c/tpdeny " + sender.getName()));

        if (type == RequestType.TPA && tpaAutoAccept.contains(target.getUniqueId())) {
            target.sendMessage(color("&a/tpa 自動承認: 即時承認しました。"));
            processAccept(target, req);
        }
        return true;
    }

    private boolean handleToggle(Player player) {
        UUID id = player.getUniqueId();
        if (tpaDisabled.remove(id)) {
            player.sendMessage(color("&aTPA申請を受け付けます。"));
        } else {
            tpaDisabled.add(id);
            player.sendMessage(color("&cTPA申請を受け付けません。"));
        }
        return true;
    }

    private boolean handleAuto(Player player) {
        UUID id = player.getUniqueId();
        if (tpaAutoAccept.remove(id)) {
            player.sendMessage(color("&e/tpa の自動承認をオフにしました。"));
        } else {
            tpaAutoAccept.add(id);
            player.sendMessage(color("&a/tpa は自動承認になります。&e/tpahere は手動承認です。"));
        }
        return true;
    }

    private boolean handleAccept(Player target, String[] args) {
        TeleportRequest req = pickRequest(target, args);
        if (req == null) {
            target.sendMessage(color("&c承認できる申請がありません。"));
            return true;
        }
        processAccept(target, req);
        return true;
    }

    private boolean handleDeny(Player target, String[] args) {
        TeleportRequest req = pickRequest(target, args);
        if (req == null) {
            target.sendMessage(color("&c拒否できる申請がありません。"));
            return true;
        }
        removeRequest(req);
        Player sender = getServer().getPlayer(req.sender());
        target.sendMessage(color("&e申請を拒否しました。"));
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(color("&c" + target.getName() + " が申請を拒否しました。"));
        }
        return true;
    }

    private void processAccept(Player target, TeleportRequest req) {
        Player sender = getServer().getPlayer(req.sender());
        removeRequest(req);
        if (sender == null || !sender.isOnline()) {
            target.sendMessage(color("&c送信元がオフラインです。"));
            return;
        }
        if (isExpired(req)) {
            target.sendMessage(color("&cこの申請は期限切れです。"));
            sender.sendMessage(color("&c申請は期限切れでした。"));
            return;
        }
        if (req.type() == RequestType.TPA) {
            sender.teleportAsync(target.getLocation());
            sender.sendMessage(color("&aTPAが承認されました。"));
            target.sendMessage(color("&a" + sender.getName() + " をあなたの場所へTPさせました。"));
        } else {
            target.teleportAsync(sender.getLocation());
            sender.sendMessage(color("&aTPAHEREが承認されました。"));
            target.sendMessage(color("&a" + sender.getName() + " の場所へTPしました。"));
        }
    }

    private TeleportRequest pickRequest(Player target, String[] args) {
        List<TeleportRequest> list = pendingByTarget.get(target.getUniqueId());
        if (list == null || list.isEmpty()) {
            return null;
        }
        list.removeIf(this::isExpired);
        if (list.isEmpty()) {
            pendingByTarget.remove(target.getUniqueId());
            return null;
        }
        if (args.length == 0) {
            return list.stream().max(Comparator.comparingLong(TeleportRequest::createdAtMillis)).orElse(null);
        }
        Player sender = getServer().getPlayerExact(args[0]);
        if (sender == null) {
            return null;
        }
        UUID senderId = sender.getUniqueId();
        return list.stream().filter(r -> r.sender().equals(senderId)).findFirst().orElse(null);
    }

    private void removeRequest(TeleportRequest req) {
        List<TeleportRequest> list = pendingByTarget.get(req.target());
        if (list == null) {
            return;
        }
        list.remove(req);
        if (list.isEmpty()) {
            pendingByTarget.remove(req.target());
        }
    }

    private boolean isExpired(TeleportRequest req) {
        return (System.currentTimeMillis() - req.createdAtMillis()) > requestTimeoutMillis;
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length != 1 || !(sender instanceof Player player)) {
            return List.of();
        }
        if (name.equals("tpa") || name.equals("tpahere")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> !n.equalsIgnoreCase(player.getName()))
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (name.equals("tpaccept") || name.equals("tpdeny")) {
            List<TeleportRequest> list = pendingByTarget.getOrDefault(player.getUniqueId(), List.of());
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return list.stream()
                    .map(req -> getServer().getPlayer(req.sender()))
                    .filter(Objects::nonNull)
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private enum RequestType {
        TPA("TPA"),
        TPA_HERE("TPAHERE");
        private final String displayName;

        RequestType(String displayName) {
            this.displayName = displayName;
        }
    }

    private record TeleportRequest(UUID sender, UUID target, RequestType type, long createdAtMillis) {}
}

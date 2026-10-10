package de.louis.economy;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class EconomyCommands implements CommandExecutor, TabCompleter {

    private record Entry(UUID id, String name, double money) {}

    private static final double MAX_AMOUNT = 1_000_000_000_000.0;

    private final LouisEconomy plugin;
    private List<Entry> topCache;
    private long topCacheTime;

    public EconomyCommands(LouisEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        try {
            switch (cmd.getName().toLowerCase(Locale.ROOT)) {
                case "pay" -> pay(sender, args);
                case "balance" -> balance(sender, args);
                case "baltop" -> baltop(sender, args);
                case "eco" -> eco(sender, args);
                default -> { }
            }
        } catch (IllegalStateException e) {
            sender.sendMessage(plugin.msg("jobs-missing"));
            plugin.getLogger().warning("Fehler beim Zugriff auf das JobsPlugin: " + e);
        }
        return true;
    }

    // ---------------------------------------------------------------- /pay

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("only-players"));
            return;
        }
        if (args.length != 2) {
            p.sendMessage(plugin.msg("pay-usage"));
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[0]);
        if (target == null) {
            p.sendMessage(plugin.msg("no-player", Placeholder.unparsed("player", args[0])));
            return;
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(plugin.msg("self-pay"));
            return;
        }
        Double parsed = parseAmount(args[1]);
        if (parsed == null || parsed <= 0) {
            p.sendMessage(plugin.msg("invalid-amount"));
            return;
        }
        double amount = parsed;
        double min = plugin.getConfig().getDouble("min-pay", 0.01);
        if (amount < min) {
            p.sendMessage(plugin.msg("min-amount", Placeholder.unparsed("min", plugin.formatMoney(min))));
            return;
        }

        JobsBridge bridge = plugin.bridge();
        double own = bridge.getMoney(p.getUniqueId());
        if (own < amount) {
            p.sendMessage(plugin.msg("not-enough", Placeholder.unparsed("money", plugin.formatMoney(own))));
            return;
        }
        double theirs = bridge.getMoney(target.getUniqueId());
        bridge.setMoney(p.getUniqueId(), round(own - amount));
        bridge.setMoney(target.getUniqueId(), round(theirs + amount));
        invalidateTop();

        String targetName = target.getName() != null ? target.getName() : args[0];
        String money = plugin.formatMoney(amount);
        p.sendMessage(plugin.msg("pay-sent",
                Placeholder.unparsed("money", money), Placeholder.unparsed("player", targetName)));
        Player online = Bukkit.getPlayer(target.getUniqueId());
        if (online != null) {
            online.sendMessage(plugin.msg("pay-received",
                    Placeholder.unparsed("money", money), Placeholder.unparsed("player", p.getName())));
        }
    }

    // ---------------------------------------------------------- /balance

    private void balance(CommandSender sender, String[] args) {
        JobsBridge bridge = plugin.bridge();
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.msg("only-players"));
                return;
            }
            sender.sendMessage(plugin.msg("balance-self",
                    Placeholder.unparsed("money", plugin.formatMoney(bridge.getMoney(p.getUniqueId())))));
            return;
        }
        if (!sender.hasPermission("louiseconomy.balance.others")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.msg("no-player", Placeholder.unparsed("player", args[0])));
            return;
        }
        String name = target.getName() != null ? target.getName() : args[0];
        sender.sendMessage(plugin.msg("balance-other",
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("money", plugin.formatMoney(bridge.getMoney(target.getUniqueId())))));
    }

    // ------------------------------------------------------------ /baltop

    private void baltop(CommandSender sender, String[] args) {
        List<Entry> top = getTop();
        if (top.isEmpty()) {
            sender.sendMessage(plugin.msg("baltop-empty"));
            return;
        }
        int size = Math.max(1, plugin.getConfig().getInt("baltop-page-size", 10));
        int pages = (top.size() + size - 1) / size;
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        page = Math.max(1, Math.min(page, pages));

        sender.sendMessage(plugin.raw("baltop-header",
                Placeholder.unparsed("page", String.valueOf(page)),
                Placeholder.unparsed("pages", String.valueOf(pages))));
        int from = (page - 1) * size;
        int to = Math.min(top.size(), from + size);
        for (int i = from; i < to; i++) {
            Entry e = top.get(i);
            sender.sendMessage(plugin.raw("baltop-line",
                    Placeholder.unparsed("rank", String.valueOf(i + 1)),
                    Placeholder.unparsed("player", e.name()),
                    Placeholder.unparsed("money", plugin.formatMoney(e.money()))));
        }
    }

    private List<Entry> getTop() {
        if (topCache != null && System.currentTimeMillis() - topCacheTime < 30_000L) return topCache;

        List<Entry> list = new ArrayList<>();
        JobsBridge bridge = plugin.bridge();
        File[] files = bridge.playersFolder().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                try {
                    String n = f.getName();
                    UUID id = UUID.fromString(n.substring(0, n.length() - 4));
                    double money = Bukkit.getPlayer(id) != null
                            ? bridge.getMoney(id)
                            : YamlConfiguration.loadConfiguration(f).getDouble("money", 0.0);
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    list.add(new Entry(id, name != null ? name : "Unbekannt", money));
                } catch (IllegalArgumentException ignored) {
                    // Datei ist keine Spielerdatei
                }
            }
        }
        list.sort(Comparator.comparingDouble(Entry::money).reversed());
        topCache = list;
        topCacheTime = System.currentTimeMillis();
        return list;
    }

    private void invalidateTop() {
        topCache = null;
    }

    // --------------------------------------------------------------- /eco

    private void eco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("louiseconomy.admin")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            sender.sendMessage(plugin.msg("reloaded"));
            return;
        }
        if (args.length != 3) {
            sender.sendMessage(plugin.msg("eco-usage"));
            return;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        if (!mode.equals("give") && !mode.equals("take") && !mode.equals("set")) {
            sender.sendMessage(plugin.msg("eco-usage"));
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.msg("no-player", Placeholder.unparsed("player", args[1])));
            return;
        }
        Double amount = parseAmount(args[2]);
        if (amount == null || amount < 0) {
            sender.sendMessage(plugin.msg("invalid-amount"));
            return;
        }
        JobsBridge bridge = plugin.bridge();
        double current = bridge.getMoney(target.getUniqueId());
        double result = switch (mode) {
            case "give" -> current + amount;
            case "take" -> Math.max(0.0, current - amount);
            default -> amount;
        };
        result = round(Math.min(result, MAX_AMOUNT));
        bridge.setMoney(target.getUniqueId(), result);
        invalidateTop();

        String name = target.getName() != null ? target.getName() : args[1];
        sender.sendMessage(plugin.msg("eco-done",
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("money", plugin.formatMoney(result))));
    }

    // ------------------------------------------------------------ Helfer

    private static Double parseAmount(String text) {
        try {
            double v = Double.parseDouble(text.replace(',', '.'));
            if (!Double.isFinite(v) || v > MAX_AMOUNT) return null;
            return round(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("eco") && args.length == 1) {
            for (String s : List.of("give", "take", "set", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        boolean nameArg = (name.equals("eco") && args.length == 2)
                || ((name.equals("pay") || name.equals("balance")) && args.length == 1);
        if (nameArg) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}

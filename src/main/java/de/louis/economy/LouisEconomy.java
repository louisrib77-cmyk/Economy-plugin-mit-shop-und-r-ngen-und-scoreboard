package de.louis.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public class LouisEconomy extends JavaPlugin {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private JobsBridge bridge;
    private SellManager sell;
    private ShopManager shop;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bridge = JobsBridge.create(this);
        if (bridge == null) {
            getLogger().severe("JobsPlugin nicht gefunden - LouisEconomy wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EconomyCommands commands = new EconomyCommands(this);
        for (String name : new String[]{"pay", "balance", "baltop", "eco"}) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }
        sell = new SellManager(this);
        sell.loadPrices();
        getServer().getPluginManager().registerEvents(sell, this);
        for (String name : new String[]{"sell", "worth"}) {
            var cmd = getCommand(name);
            if (cmd != null) cmd.setExecutor(sell);
        }
        shop = new ShopManager(this);
        shop.reload();
        getServer().getPluginManager().registerEvents(shop, this);
        var shopCmd = getCommand("shop");
        if (shopCmd != null) shopCmd.setExecutor(shop);
        getLogger().info("Wirtschaft aktiv - nutzt das Geld aus dem JobsPlugin.");
    }

    @Override
    public void onDisable() {
        if (sell != null) sell.closeAll();
    }

    void reloadAll() {
        reloadConfig();
        if (sell != null) sell.loadPrices();
        if (shop != null) shop.reload();
    }

    SellManager sell() {
        return sell;
    }

    JobsBridge bridge() {
        return bridge;
    }

    /** Spieler per Name finden (online oder bekannt mit Jobs-Daten). */
    OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off != null && bridge.hasData(off.getUniqueId())) return off;
        return null;
    }

    String formatMoney(double amount) {
        String symbol = getConfig().getString("currency-symbol", "");
        return String.format(Locale.GERMANY, "%,.2f", amount) + (symbol.isEmpty() ? "" : " " + symbol);
    }

    Component msg(String key, TagResolver... resolvers) {
        String prefix = getConfig().getString("messages.prefix", "");
        String text = getConfig().getString("messages." + key, "<red>Fehlende Nachricht: " + key);
        return mm.deserialize(prefix + text, resolvers);
    }

    /** Wie msg(), aber ohne Präfix (für Listen). */
    Component raw(String key, TagResolver... resolvers) {
        String text = getConfig().getString("messages." + key, "<red>Fehlende Nachricht: " + key);
        return mm.deserialize(text, resolvers);
    }
}

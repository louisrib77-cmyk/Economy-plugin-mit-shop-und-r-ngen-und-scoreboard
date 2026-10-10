package de.louis.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /sell: Menü öffnen, Items hineinlegen, beim Schließen (oder Klick auf den Button) wird verkauft. */
public class SellManager implements Listener, CommandExecutor {

    private static final int SIZE = 54;
    private static final int ITEM_SLOTS = 45;   // Slots 0-44: hier legt man Items hinein
    private static final int BUTTON_SLOT = 49;  // unterste Reihe: Button und Füllmaterial

    private static final class SellHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final LouisEconomy plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<Material, Double> prices = new EnumMap<>(Material.class);

    public SellManager(LouisEconomy plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ Preise

    public void loadPrices() {
        prices.clear();
        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("prices");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            double price = section.getDouble(key, 0.0);
            if (material == null) {
                plugin.getLogger().warning("prices.yml: Unbekanntes Item '" + key + "' - übersprungen.");
            } else if (price > 0) {
                prices.put(material, price);
            }
        }
        plugin.getLogger().info(prices.size() + " Verkaufspreise geladen.");
    }

    /** Preis pro Stück, 0 = nicht verkäuflich. */
    private double unitPrice(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0.0;
        Double base = prices.get(item.getType());
        if (base == null) return 0.0;
        // Behälter mit Inhalt (z. B. gefüllte Shulkerboxen) nie verkaufen
        if (item.getItemMeta() instanceof BlockStateMeta meta && meta.hasBlockState()
                && meta.getBlockState() instanceof Container container
                && !container.getSnapshotInventory().isEmpty()) {
            return 0.0;
        }
        return base * plugin.getConfig().getDouble("sell-multiplier", 1.0);
    }

    /** Verkaufspreis pro Stück (inkl. sell-multiplier), 0 = nicht verkäuflich. Wird vom Shop genutzt. */
    double basePrice(Material material) {
        Double base = prices.get(material);
        return base == null ? 0.0 : base * plugin.getConfig().getDouble("sell-multiplier", 1.0);
    }

    private double stackValue(ItemStack item) {
        return unitPrice(item) * (item == null ? 0 : item.getAmount());
    }

    // ----------------------------------------------------------- Befehle

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("only-players"));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("worth")) {
            worth(p);
        } else {
            open(p);
        }
        return true;
    }

    private void worth(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        double unit = unitPrice(hand);
        if (unit <= 0) {
            p.sendMessage(plugin.msg("worth-none"));
            return;
        }
        String name = hand.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        p.sendMessage(plugin.msg("worth-line",
                Placeholder.unparsed("item", name),
                Placeholder.unparsed("money", plugin.formatMoney(unit)),
                Placeholder.unparsed("stack", plugin.formatMoney(unit * hand.getAmount()))));
    }

    // ------------------------------------------------------------- Menü

    private void open(Player p) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, plugin.raw("sell-title"));
        holder.inventory = inv;

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int i = ITEM_SLOTS; i < SIZE; i++) inv.setItem(i, filler);

        updateButton(inv);
        p.openInventory(inv);
    }

    private void updateButton(Inventory inv) {
        double total = 0.0;
        for (int i = 0; i < ITEM_SLOTS; i++) total += stackValue(inv.getItem(i));
        String money = plugin.formatMoney(total);

        ItemStack button = new ItemStack(Material.EMERALD);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(noItalic(plugin.raw("sell-button-name")));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("messages.sell-button-lore")) {
            lore.add(noItalic(mm.deserialize(line, Placeholder.unparsed("money", money))));
        }
        meta.lore(lore);
        button.setItemMeta(meta);
        inv.setItem(BUTTON_SLOT, button);
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private void refreshLater(Inventory inv) {
        Bukkit.getScheduler().runTask(plugin, () -> updateButton(inv));
    }

    /** Beim Abschalten alle offenen Verkaufsmenüs schließen (damit verkauft/zurückgegeben wird). */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder) {
                p.closeInventory();
            }
        }
    }

    // ---------------------------------------------------------- Events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        int raw = e.getRawSlot();
        if (raw >= ITEM_SLOTS && raw < SIZE) { // untere Reihe ist gesperrt
            e.setCancelled(true);
            if (raw == BUTTON_SLOT && e.getWhoClicked() instanceof Player p) {
                Bukkit.getScheduler().runTask(plugin, p::closeInventory);
            }
            return;
        }
        refreshLater(top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= ITEM_SLOTS && raw < SIZE) {
                e.setCancelled(true);
                return;
            }
        }
        refreshLater(top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        List<ItemStack> sold = new ArrayList<>();
        List<ItemStack> back = new ArrayList<>();
        double total = 0.0;
        int soldCount = 0;
        int backCount = 0;

        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(i, null);
            double value = stackValue(item);
            if (value > 0) {
                sold.add(item);
                total += value;
                soldCount += item.getAmount();
            } else {
                back.add(item);
                backCount += item.getAmount();
            }
        }

        if (total > 0) {
            try {
                JobsBridge bridge = plugin.bridge();
                double current = bridge.getMoney(p.getUniqueId());
                bridge.setMoney(p.getUniqueId(), Math.round((current + total) * 100.0) / 100.0);
                p.sendMessage(plugin.msg("sell-sold",
                        Placeholder.unparsed("count", String.valueOf(soldCount)),
                        Placeholder.unparsed("money", plugin.formatMoney(total))));
            } catch (IllegalStateException ex) {
                plugin.getLogger().warning("Verkauf fehlgeschlagen für " + p.getName() + ": " + ex);
                back.addAll(sold); // nichts verlieren
                backCount = 0;
                p.sendMessage(plugin.msg("sell-error"));
            }
        }

        if (!back.isEmpty()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(back.toArray(new ItemStack[0]));
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            if (backCount > 0) {
                p.sendMessage(plugin.msg("sell-returned",
                        Placeholder.unparsed("count", String.valueOf(backCount))));
            }
        }
    }
}

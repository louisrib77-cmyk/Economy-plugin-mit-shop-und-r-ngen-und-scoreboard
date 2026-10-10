package de.louis.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * /shop: Alle Survival-Items und -Blöcke kaufen (bezahlt mit dem Geld aus dem JobsPlugin, kein Vault).
 * End-Items (Elytra, Shulker, Purpur, ...) und alles, was es im Survival nicht gibt, ist ausgeschlossen.
 * Preise: shop.yml (manuell) > Verkaufspreis x buy-multiplier > aus dem Crafting-Rezept berechnet > default-price.
 */
public class ShopManager implements Listener, CommandExecutor {

    private static final int SIZE = 54;
    private static final int ITEM_SLOTS = 45;
    private static final int PREV_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    /** Teile von Material-Namen, die nie im Shop landen (End-Items). */
    private static final String[] END_PARTS = {
            "ELYTRA", "SHULKER", "DRAGON", "PURPUR", "CHORUS",
            "END_STONE", "END_ROD", "END_CRYSTAL", "END_PORTAL", "END_GATEWAY"
    };

    /** Teile von Material-Namen für Dinge, die es im Survival nicht (sinnvoll) gibt. */
    private static final String[] NO_SURVIVAL_PARTS = {
            "LEGACY_", "SPAWN_EGG", "COMMAND_BLOCK", "SPAWNER", "INFESTED_", "TRIAL_KEY",
            "TEST_BLOCK", "TEST_INSTANCE", "POTION", "TIPPED_ARROW"
    };

    /** Exakte Namen, die es im Survival nicht gibt. */
    private static final Set<String> NO_SURVIVAL_EXACT = Set.of(
            "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK",
            "KNOWLEDGE_BOOK", "FARMLAND", "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST", "PETRIFIED_OAK_SLAB",
            "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "SUSPICIOUS_STEW", "ENCHANTED_BOOK", "FILLED_MAP",
            "WRITTEN_BOOK", "PLAYER_HEAD", "EXPERIENCE_BOTTLE", "VAULT", "FROGSPAWN", "AIR",
            "CAVE_AIR", "VOID_AIR"
    );

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        private List<Material> items = List.of();
        private int page = 0;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final LouisEconomy plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private YamlConfiguration shopConfig = new YamlConfiguration();

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private final Map<Material, Double> cache = new EnumMap<>(Material.class);
    private final Set<Material> inProgress = EnumSet.noneOf(Material.class);
    private final Set<String> extraBlocked = new HashSet<>();
    private List<Material> allItems = List.of();

    public ShopManager(LouisEconomy plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------- Laden / Preise

    public void reload() {
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) plugin.saveResource("shop.yml", false);
        shopConfig = YamlConfiguration.loadConfiguration(file);

        extraBlocked.clear();
        for (String s : shopConfig.getStringList("blocked")) {
            extraBlocked.add(s.toUpperCase(Locale.ROOT));
        }

        List<Material> list = new ArrayList<>();
        for (Material m : Material.values()) {
            if (isBuyable(m)) list.add(m);
        }
        list.sort(Comparator.comparing(Material::name));
        allItems = List.copyOf(list);

        prices.clear();
        cache.clear();
        inProgress.clear();
        for (Material m : allItems) {
            double p = priceOf(m, 0);
            prices.put(m, roundMoney(Double.isInfinite(p) ? defaultPrice() : p));
        }
        plugin.getLogger().info(allItems.size() + " Items im Shop.");
    }

    private double defaultPrice() {
        return shopConfig.getDouble("default-price", 50.0);
    }

    private boolean isBuyable(Material m) {
        String name = m.name();
        if (!m.isItem() || m.isAir()) return false;
        if (NO_SURVIVAL_EXACT.contains(name) || extraBlocked.contains(name)) return false;
        for (String part : NO_SURVIVAL_PARTS) {
            if (name.contains(part)) return false;
        }
        for (String part : END_PARTS) {
            if (name.contains(part)) return false;
        }
        return true;
    }

    /** Preis pro Stück; unendlich, wenn er (noch) nicht bestimmt werden kann. */
    private double priceOf(Material m, int depth) {
        Double cached = cache.get(m);
        if (cached != null) return cached;

        double sellBase = plugin.sell() == null ? 0.0 : plugin.sell().basePrice(m);
        double result;

        ConfigurationSection overrides = shopConfig.getConfigurationSection("prices");
        if (overrides != null && overrides.contains(m.name()) && overrides.getDouble(m.name()) > 0) {
            result = overrides.getDouble(m.name());
        } else if (sellBase > 0) {
            result = sellBase * shopConfig.getDouble("buy-multiplier", 4.0);
        } else {
            result = Double.POSITIVE_INFINITY;
            if (depth < 12 && !inProgress.contains(m)) {
                inProgress.add(m);
                try {
                    result = fromRecipes(m, depth + 1);
                } finally {
                    inProgress.remove(m);
                }
            } else if (inProgress.contains(m)) {
                return Double.POSITIVE_INFINITY; // Rezept-Kreis: nicht cachen
            }
            if (Double.isInfinite(result)) {
                result = defaultPrice();
            } else {
                result *= 1.0 + shopConfig.getDouble("craft-markup", 0.10);
            }
        }

        // Kaufen darf nie billiger sein als Verkaufen
        result = Math.max(result, sellBase);
        cache.put(m, result);
        return result;
    }

    private double fromRecipes(Material m, int depth) {
        double best = Double.POSITIVE_INFINITY;
        try {
            for (Recipe recipe : Bukkit.getRecipesFor(new ItemStack(m))) {
                int amount = Math.max(1, recipe.getResult().getAmount());
                double cost = recipeCost(recipe, depth);
                if (!Double.isInfinite(cost)) best = Math.min(best, cost / amount);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Rezept für " + m + " nicht lesbar: " + e);
        }
        return best;
    }

    private double recipeCost(Recipe recipe, int depth) {
        List<RecipeChoice> choices = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> map = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (char c : row.toCharArray()) {
                    RecipeChoice choice = map.get(c);
                    if (choice != null) choices.add(choice);
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            choices.addAll(shapeless.getChoiceList());
        } else {
            return Double.POSITIVE_INFINITY;
        }

        double sum = 0.0;
        for (RecipeChoice choice : choices) {
            double cheapest = Double.POSITIVE_INFINITY;
            if (choice instanceof RecipeChoice.MaterialChoice mc) {
                for (Material mat : mc.getChoices()) {
                    cheapest = Math.min(cheapest, priceOf(mat, depth));
                }
            } else if (choice instanceof RecipeChoice.ExactChoice ec) {
                for (ItemStack stack : ec.getChoices()) {
                    cheapest = Math.min(cheapest, priceOf(stack.getType(), depth));
                }
            }
            if (Double.isInfinite(cheapest)) return Double.POSITIVE_INFINITY;
            sum += cheapest;
        }
        return sum;
    }

    private static double roundMoney(double v) {
        return Math.max(0.01, Math.round(v * 100.0) / 100.0);
    }

    // --------------------------------------------------------- Nachrichten

    private Component msg(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String text = shopConfig.getString("messages." + key, "<red>Fehlende Nachricht: " + key);
        return mm.deserialize(prefix + text, resolvers);
    }

    private Component raw(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        String text = shopConfig.getString("messages." + key, "<red>Fehlende Nachricht: " + key);
        return mm.deserialize(text, resolvers);
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ------------------------------------------------------------- Befehl

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("only-players"));
            return true;
        }

        List<Material> list = allItems;
        if (args.length > 0) {
            String query = String.join("_", args).toUpperCase(Locale.ROOT);
            List<Material> filtered = new ArrayList<>();
            for (Material m : allItems) {
                if (m.name().contains(query)) filtered.add(m);
            }
            if (filtered.isEmpty()) {
                p.sendMessage(msg("search-empty", Placeholder.unparsed("query", String.join(" ", args))));
                return true;
            }
            list = filtered;
        }
        open(p, list);
        return true;
    }

    // --------------------------------------------------------------- Menü

    private void open(Player p, List<Material> list) {
        ShopHolder holder = new ShopHolder();
        holder.items = list;
        Inventory inv = Bukkit.createInventory(holder, SIZE, raw("shop-title"));
        holder.inventory = inv;
        render(holder, p);
        p.openInventory(inv);
    }

    private int pages(ShopHolder holder) {
        return Math.max(1, (holder.items.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
    }

    private void render(ShopHolder holder, Player p) {
        Inventory inv = holder.inventory;
        inv.clear();

        int start = holder.page * ITEM_SLOTS;
        for (int i = 0; i < ITEM_SLOTS; i++) {
            int idx = start + i;
            if (idx >= holder.items.size()) break;
            Material m = holder.items.get(idx);
            inv.setItem(i, shopItem(m));
        }

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.text(" "));
        filler.setItemMeta(fm);
        for (int i = ITEM_SLOTS; i < SIZE; i++) inv.setItem(i, filler);

        if (holder.page > 0) inv.setItem(PREV_SLOT, button(Material.ARROW, "button-prev"));
        if (holder.page < pages(holder) - 1) inv.setItem(NEXT_SLOT, button(Material.ARROW, "button-next"));

        String money;
        try {
            money = plugin.formatMoney(plugin.bridge().getMoney(p.getUniqueId()));
        } catch (IllegalStateException e) {
            money = "?";
        }
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.displayName(noItalic(raw("info-name",
                Placeholder.unparsed("page", String.valueOf(holder.page + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages(holder))))));
        List<Component> lore = new ArrayList<>();
        for (String line : shopConfig.getStringList("messages.info-lore")) {
            lore.add(noItalic(mm.deserialize(line, Placeholder.unparsed("money", money))));
        }
        im.lore(lore);
        info.setItemMeta(im);
        inv.setItem(INFO_SLOT, info);
    }

    private ItemStack button(Material material, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(raw(key)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack shopItem(Material m) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String price = plugin.formatMoney(prices.getOrDefault(m, defaultPrice()));
        List<Component> lore = new ArrayList<>();
        for (String line : shopConfig.getStringList("messages.item-lore")) {
            lore.add(noItalic(mm.deserialize(line, Placeholder.unparsed("price", price))));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------- Kaufen

    private void buy(Player p, Material m, int requested, ShopHolder holder) {
        int amount = Math.min(requested, Math.max(1, m.getMaxStackSize()));
        double unit = prices.getOrDefault(m, defaultPrice());
        double cost = roundMoney(unit * amount);
        JobsBridge bridge = plugin.bridge();

        double balance;
        try {
            balance = bridge.getMoney(p.getUniqueId());
            if (balance + 0.0001 < cost) {
                p.sendMessage(msg("not-enough",
                        Placeholder.unparsed("price", plugin.formatMoney(cost)),
                        Placeholder.unparsed("money", plugin.formatMoney(balance))));
                return;
            }
            bridge.setMoney(p.getUniqueId(), Math.round((balance - cost) * 100.0) / 100.0);
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("Kauf fehlgeschlagen für " + p.getName() + ": " + e);
            p.sendMessage(msg("error"));
            return;
        }

        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m, amount));
        int notGiven = 0;
        for (ItemStack rest : left.values()) notGiven += rest.getAmount();
        int given = amount - notGiven;

        if (notGiven > 0) {
            double refund = roundMoney(unit * notGiven);
            try {
                double now = bridge.getMoney(p.getUniqueId());
                bridge.setMoney(p.getUniqueId(), Math.round((now + refund) * 100.0) / 100.0);
            } catch (IllegalStateException e) {
                plugin.getLogger().warning("Rückerstattung fehlgeschlagen für " + p.getName() + ": " + e);
                for (ItemStack rest : left.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
                }
                given = amount;
                notGiven = 0;
            }
        }

        if (given <= 0) {
            p.sendMessage(msg("inventory-full"));
        } else {
            double paid = roundMoney(unit * given);
            p.sendMessage(msg("bought",
                    Placeholder.unparsed("count", String.valueOf(given)),
                    Placeholder.unparsed("item", pretty(m)),
                    Placeholder.unparsed("money", plugin.formatMoney(paid))));
            if (notGiven > 0) p.sendMessage(msg("inventory-full"));
        }
        render(holder, p);
    }

    // ------------------------------------------------------------ Events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopHolder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getRawSlot();
        if (slot == PREV_SLOT) {
            if (holder.page > 0) {
                holder.page--;
                render(holder, p);
            }
            return;
        }
        if (slot == NEXT_SLOT) {
            if (holder.page < pages(holder) - 1) {
                holder.page++;
                render(holder, p);
            }
            return;
        }
        if (slot < 0 || slot >= ITEM_SLOTS) return;

        int idx = holder.page * ITEM_SLOTS + slot;
        if (idx >= holder.items.size()) return;
        Material m = holder.items.get(idx);

        int amount = switch (e.getClick()) {
            case LEFT -> 1;
            case RIGHT -> 16;
            case SHIFT_LEFT, SHIFT_RIGHT -> 64;
            default -> 0;
        };
        if (amount > 0) buy(p, m, amount, holder);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder) {
            e.setCancelled(true);
        }
    }
}

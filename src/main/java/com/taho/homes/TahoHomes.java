package com.taho.homes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public final class TahoHomes extends JavaPlugin implements Listener {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, LinkedHashMap<String, Location>> homes = new HashMap<>();
    private final Set<UUID> bedrockPlayers = new HashSet<>();
    private File homesFile;
    private YamlConfiguration homesData;

    @Override public void onEnable() {
        saveDefaultConfig();
        homesFile = new File(getDataFolder(), "homes.yml");
        loadHomes();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("TahoHomes enabled.");
    }

    @Override public void onDisable() { saveHomes(); }

    private void loadHomes() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!homesFile.exists()) try { homesFile.createNewFile(); } catch (IOException e) { getLogger().severe("Could not create homes.yml: " + e.getMessage()); }
        homesData = YamlConfiguration.loadConfiguration(homesFile);
        homes.clear();
        ConfigurationSection users = homesData.getConfigurationSection("players");
        if (users == null) return;
        for (String id : users.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(id); } catch (IllegalArgumentException ignored) { continue; }
            LinkedHashMap<String, Location> playerHomes = new LinkedHashMap<>();
            ConfigurationSection section = users.getConfigurationSection(id);
            if (section == null) continue;
            for (String name : section.getKeys(false)) {
                String path = "players." + id + "." + name;
                String worldName = homesData.getString(path + ".world");
                World world = worldName == null ? null : Bukkit.getWorld(worldName);
                if (world == null) continue;
                Location loc = new Location(world, homesData.getDouble(path + ".x"), homesData.getDouble(path + ".y"),
                        homesData.getDouble(path + ".z"), (float) homesData.getDouble(path + ".yaw"), (float) homesData.getDouble(path + ".pitch"));
                playerHomes.put(name.toLowerCase(Locale.ROOT), loc);
            }
            homes.put(uuid, playerHomes);
        }
    }

    private void saveHomes() {
        if (homesData == null) homesData = new YamlConfiguration();
        homesData.set("players", null);
        for (Map.Entry<UUID, LinkedHashMap<String, Location>> entry : homes.entrySet()) {
            for (Map.Entry<String, Location> home : entry.getValue().entrySet()) {
                Location l = home.getValue();
                String p = "players." + entry.getKey() + "." + home.getKey();
                homesData.set(p + ".world", l.getWorld() == null ? null : l.getWorld().getName());
                homesData.set(p + ".x", l.getX()); homesData.set(p + ".y", l.getY()); homesData.set(p + ".z", l.getZ());
                homesData.set(p + ".yaw", l.getYaw()); homesData.set(p + ".pitch", l.getPitch());
            }
        }
        try { homesData.save(homesFile); } catch (IOException e) { getLogger().severe("Could not save homes.yml: " + e.getMessage()); }
    }

    private LinkedHashMap<String, Location> getHomes(Player p) {
        return homes.computeIfAbsent(p.getUniqueId(), ignored -> new LinkedHashMap<>());
    }

    private int homeLimit(Player p) {
        if (p.hasPermission("tahohomes.bypass")) return Integer.MAX_VALUE;
        if (p.hasPermission(getConfig().getString("rank-permissions.booster", "tahohomes.rank.booster")))
            return getConfig().getInt("home-limits.booster", 40);
        if (p.hasPermission(getConfig().getString("rank-permissions.tahoplus", "tahohomes.rank.tahoplus")))
            return getConfig().getInt("home-limits.tahoplus", 20);
        return getConfig().getInt("home-limits.default", 5);
    }

    private boolean isBedrock(Player p) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Method method = api.getMethod("isFloodgatePlayer", UUID.class);
            return Boolean.TRUE.equals(method.invoke(instance, p.getUniqueId()));
        } catch (ReflectiveOperationException ignored) { return false; }
    }

    private String msg(String key, Map<String, String> replacements) {
        String raw = getConfig().getString("messages." + key, "&cMissing message: " + key);
        for (Map.Entry<String, String> e : replacements.entrySet()) raw = raw.replace("%" + e.getKey() + "%", e.getValue());
        return getConfig().getString("messages.prefix", "&8[&6TahoHomes&8] ") + raw;
    }
    private void send(Player p, String key) { p.sendMessage(LEGACY.deserialize(msg(key, Map.of()))); }
    private void send(Player p, String key, String token, String value) { p.sendMessage(LEGACY.deserialize(msg(key, Map.of(token, value)))); }
    private Component text(String value) { return LEGACY.deserialize(value); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tahohomes")) {
            if (!sender.hasPermission("tahohomes.admin")) { sender.sendMessage(LEGACY.deserialize(msg("no-permission", Map.of()))); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) { reloadConfig(); sender.sendMessage(LEGACY.deserialize(msg("reload", Map.of()))); }
            else sender.sendMessage(LEGACY.deserialize("&eUsage: /tahohomes reload"));
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("This command can only be used in-game."); return true; }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("homes") || cmd.equals("homelist")) { openHomes(p); return true; }
        if (cmd.equals("sethome")) {
            if (args.length != 1) { send(p, "usage-set"); return true; }
            String name = args[0].toLowerCase(Locale.ROOT);
            if (!VALID_NAME.matcher(name).matches()) { send(p, "invalid-name"); return true; }
            LinkedHashMap<String, Location> map = getHomes(p);
            if (!map.containsKey(name) && map.size() >= homeLimit(p)) { send(p, "limit", "limit", String.valueOf(homeLimit(p))); return true; }
            map.put(name, p.getLocation().clone()); saveHomes(); send(p, "set", "home", name); return true;
        }
        if (cmd.equals("home")) {
            if (args.length == 0) { openHomes(p); return true; }
            if (args.length == 1) { teleportHome(p, args[0]); return true; }
            send(p, "usage-home"); return true;
        }
        if (cmd.equals("delhome") || cmd.equals("deletehome")) {
            if (args.length != 1) { send(p, "usage-delete"); return true; }
            String name = args[0].toLowerCase(Locale.ROOT);
            if (getHomes(p).remove(name) == null) { send(p, "missing", "home", name); return true; }
            saveHomes(); send(p, "deleted", "home", name); return true;
        }
        return false;
    }

    private void teleportHome(Player p, String name) {
        Location location = getHomes(p).get(name.toLowerCase(Locale.ROOT));
        if (location == null || location.getWorld() == null) { send(p, "missing", "home", name); return; }
        p.closeInventory(); p.teleportAsync(location.clone()).thenAccept(ok -> {
            if (ok) p.sendMessage(LEGACY.deserialize(msg("teleported", Map.of("home", name))));
        });
    }

    private void openHomes(Player p) {
        if (isBedrock(p)) { openBedrockMenu(p); return; }
        try {
            openNativeDialog(p);
        } catch (Throwable ex) {
            getLogger().warning("Could not open Paper native homes dialog for " + p.getName() + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            p.sendMessage(LEGACY.deserialize("&cYour client/server does not support the native homes screen. Opening the compatibility menu."));
            openBedrockMenu(p);
        }
    }

    private void openNativeDialog(Player p) {
        LinkedHashMap<String, Location> playerHomes = getHomes(p);
        List<ActionButton> actions = new ArrayList<>();

        for (String name : playerHomes.keySet()) {
            DialogAction action = DialogAction.staticAction(ClickEvent.runCommand("/home " + name));
            actions.add(ActionButton.builder(Component.text("⌂  " + name))
                    .tooltip(Component.text("Teleport to " + name))
                    .width(180)
                    .action(action)
                    .build());
        }

        if (playerHomes.size() < homeLimit(p)) {
            String nextName = nextHomeName(playerHomes);
            actions.add(ActionButton.builder(Component.text("＋  Set home " + nextName))
                    .tooltip(Component.text("Save your current location as a new home"))
                    .width(180)
                    .action(DialogAction.staticAction(ClickEvent.runCommand("/sethome " + nextName)))
                    .build());
        }

        if (actions.isEmpty()) {
            actions.add(ActionButton.builder(Component.text("No homes yet"))
                    .tooltip(Component.text("Use the button below to create your first home"))
                    .width(180)
                    .build());
        }

        DialogBase base = DialogBase.builder(Component.text("Your Homes"))
                .body(List.of(DialogBody.plainMessage(
                        Component.text("Select a home to teleport instantly.\nHomes: " + playerHomes.size() + " / " +
                                (homeLimit(p) == Integer.MAX_VALUE ? "∞" : homeLimit(p)) +
                                "\nUse /delhome <name> to delete a home."),
                        350)))
                .canCloseWithEscape(true)
                .build();

        DialogType type = DialogType.multiAction(actions, null, 2);
        Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(type));
        p.showDialog(dialog);
    }

    private String nextHomeName(Map<String, Location> playerHomes) {
        int index = 1;
        while (playerHomes.containsKey(String.valueOf(index))) index++;
        return String.valueOf(index);
    }

    private void openBedrockMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, LEGACY.deserialize("&8Your Homes"));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&r", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        LinkedHashMap<String, Location> map = getHomes(p);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int index = 0;
        for (String name : map.keySet()) {
            if (index >= slots.length) break;
            inv.setItem(slots[index++], item(Material.RED_BED, "&6" + name, List.of("&7Click to teleport", "&8Left-click: Teleport", "&8Right-click: Delete")));
        }
        if (map.size() < homeLimit(p)) inv.setItem(49, item(Material.LIME_BED, "&aSet a home", List.of("&7Use &f/sethome <name>", "&7to create a new home.")));
        inv.setItem(45, item(Material.BARRIER, "&cClose", List.of()));
        p.openInventory(inv);
        bedrockPlayers.add(p.getUniqueId());
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LEGACY.deserialize(name));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(LEGACY::deserialize).toList());
        stack.setItemMeta(meta); return stack;
    }

    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !bedrockPlayers.contains(p.getUniqueId())) return;
        if (!LEGACY.serialize(e.getView().title()).equals(LEGACY.serialize(LEGACY.deserialize("&8Your Homes")))) return;
        e.setCancelled(true);
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || clicked.getItemMeta().displayName() == null) return;
        String display = LEGACY.serialize(clicked.getItemMeta().displayName());
        if (display.contains("Close")) { p.closeInventory(); bedrockPlayers.remove(p.getUniqueId()); return; }
        if (display.contains("Set a home")) { p.closeInventory(); p.sendMessage(LEGACY.deserialize("&eUse &f/sethome <name> &eto create a home.")); return; }
        String name = display.replace("§6", "").replace("&6", "").trim().toLowerCase(Locale.ROOT);
        if (!getHomes(p).containsKey(name)) return;
        if (e.isRightClick()) {
            getHomes(p).remove(name); saveHomes(); p.sendMessage(LEGACY.deserialize(msg("deleted", Map.of("home", name)))); openBedrockMenu(p);
        } else teleportHome(p, name);
    }

    @EventHandler public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getView().title().equals(LEGACY.deserialize("&8Your Homes"))) bedrockPlayers.remove(e.getPlayer().getUniqueId());
    }
}
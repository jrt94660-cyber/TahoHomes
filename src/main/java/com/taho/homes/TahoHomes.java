package com.taho.homes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.ClickCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
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
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

public final class TahoHomes extends JavaPlugin implements Listener {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, LinkedHashMap<String, Location>> homes = new HashMap<>();
    private final Set<UUID> bedrockPlayers = new HashSet<>();
    private final Map<UUID, Map<String, Material>> homeIcons = new HashMap<>();
    private final Map<UUID, String> pendingRename = new HashMap<>();
    private final Map<UUID, String> bedrockSelectedHome = new HashMap<>();
    private final Set<UUID> javaMenuPlayers = new HashSet<>();
    private final Map<UUID, String> javaSelectedHome = new HashMap<>();
    private final Map<UUID, IconPickerState> iconPickerStates = new HashMap<>();
    private record IconPickerState(String homeName, String query, int page) {}
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
                String homeName = name.toLowerCase(Locale.ROOT);
                playerHomes.put(homeName, loc);
                String iconName = homesData.getString(path + ".icon", "RED_BED");
                Material icon = Material.matchMaterial(iconName == null ? "RED_BED" : iconName);
                homeIcons.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(homeName, icon != null && icon.isItem() ? icon : Material.RED_BED);
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
                homesData.set(p + ".icon", homeIcons.getOrDefault(entry.getKey(), Map.of()).getOrDefault(home.getKey(), Material.RED_BED).name());
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
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("tahohomes.admin")) { sender.sendMessage(LEGACY.deserialize(msg("no-permission", Map.of()))); return true; }
                reloadConfig(); sender.sendMessage(LEGACY.deserialize(msg("reload", Map.of())));
            } else if (sender instanceof Player player && args.length >= 3 && args[0].equalsIgnoreCase("action")) {
                handleHomeAction(player, args);
            } else if (sender instanceof Player player && args.length == 2 && args[0].equalsIgnoreCase("rename")) {
                beginRename(player, args[1]);
            } else sender.sendMessage(LEGACY.deserialize("&eUsage: /tahohomes reload"));
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
            map.put(name, p.getLocation().clone());
            homeIcons.computeIfAbsent(p.getUniqueId(), ignored -> new HashMap<>()).putIfAbsent(name, Material.RED_BED);
            saveHomes(); send(p, "set", "home", name); return true;
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
        openJavaHomesMenu(p);
    }

    private void openJavaHomesMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, LEGACY.deserialize("&8Your Homes"));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&r", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        LinkedHashMap<String, Location> playerHomes = getHomes(p);
        int slot = 0;
        for (String name : playerHomes.keySet()) {
            if (slot >= 45) break;
            Material icon = homeIcons.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(name, Material.RED_BED);
            inv.setItem(slot++, item(icon, "&6" + name, List.of("&7Saved home", "&eClick to manage this home")));
        }
        inv.setItem(49, item(Material.LIME_BED, "&aSet a home", List.of("&7Use &f/sethome <name>")));
        inv.setItem(45, item(Material.BARRIER, "&cClose", List.of()));
        p.openInventory(inv);
        javaMenuPlayers.add(p.getUniqueId());
    }

    private void openJavaHomeActions(Player p, String name) {
        if (!getHomes(p).containsKey(name)) { openJavaHomesMenu(p); return; }
        Inventory inv = Bukkit.createInventory(null, 27, LEGACY.deserialize("&8Home: " + name));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&r", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        Material icon = homeIcons.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(name, Material.RED_BED);
        inv.setItem(4, item(icon, "&6" + name, List.of("&7This home's icon")));
        inv.setItem(10, item(Material.ENDER_PEARL, "&bTeleport", List.of("&7Teleport to this home")));
        inv.setItem(12, item(Material.NAME_TAG, "&eRename", List.of("&7Rename this home")));
        inv.setItem(14, item(Material.COMPASS, "&dChange Icon", List.of("&7Search all Minecraft items")));
        inv.setItem(16, item(Material.RED_DYE, "&cDelete", List.of("&7Delete this home")));
        inv.setItem(22, item(Material.ARROW, "&fBack", List.of("&7Return to your homes")));
        javaSelectedHome.put(p.getUniqueId(), name);
        p.openInventory(inv);
        javaMenuPlayers.add(p.getUniqueId());
    }

    private void openIconSearch(Player p, String homeName, String initial) {
        DialogBase base = DialogBase.builder(Component.text("Search Minecraft Items"))
                .body(List.of(DialogBody.plainMessage(
                        Component.text("Type an item name, then press Search. Leave it empty to browse every item."), 300)))
                .inputs(List.of(DialogInput.text("query", Component.text("Item name"))
                        .width(300).labelVisible(true).initial(initial == null ? "" : initial)
                        .maxLength(64).build()))
                .canCloseWithEscape(true).build();
        ActionButton search = ActionButton.builder(Component.text("Search"))
                .tooltip(Component.text("Search all Minecraft item icons"))
                .width(180)
                .action(DialogAction.customClick((view, audience) -> {
                    if (!(audience instanceof Player player)) return;
                    String query = view.getText("query");
                    openIconResults(player, homeName, query == null ? "" : query.trim(), 0);
                }, ClickCallback.Options.builder().uses(1).build()))
                .build();
        ActionButton back = ActionButton.builder(Component.text("Cancel"))
                .width(180)
                .action(DialogAction.staticAction(ClickEvent.runCommand("/tahohomes action " + homeName + " open")))
                .build();
        p.showDialog(Dialog.create(factory -> factory.empty().base(base).type(DialogType.confirmation(search, back))));
    }

    private void openIconResults(Player p, String homeName, String query, int page) {
        if (!getHomes(p).containsKey(homeName)) { openJavaHomesMenu(p); return; }
        String normalized = query.toLowerCase(Locale.ROOT).replace(' ', '_');
        List<Material> matches = Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(m -> normalized.isBlank()
                        || m.name().toLowerCase(Locale.ROOT).contains(normalized)
                        || prettyMaterial(m).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Material::name))
                .toList();
        int maxPage = Math.max(0, (matches.size() - 1) / 45);
        int currentPage = Math.max(0, Math.min(page, maxPage));
        Inventory inv = Bukkit.createInventory(null, 54, LEGACY.deserialize("&8Choose Icon: " + homeName));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&r", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        int from = currentPage * 45;
        int to = Math.min(from + 45, matches.size());
        for (int i = from; i < to; i++) {
            Material material = matches.get(i);
            inv.setItem(i - from, item(material, "&f" + prettyMaterial(material),
                    List.of("&7Minecraft item: &f" + material.name(), "&eClick to use this icon")));
        }
        inv.setItem(45, item(Material.OAK_SIGN, "&eSearch Items",
                List.of("&7Current search: &f" + (query.isBlank() ? "All items" : query), "&eClick to search again")));
        inv.setItem(48, item(Material.ARROW, "&fPrevious Page", List.of("&7Page " + (currentPage + 1) + " / " + (maxPage + 1))));
        inv.setItem(49, item(Material.PAPER, "&bPage " + (currentPage + 1) + " / " + (maxPage + 1),
                List.of("&7" + matches.size() + " matching items")));
        inv.setItem(50, item(Material.ARROW, "&fNext Page", List.of("&7Page " + (currentPage + 1) + " / " + (maxPage + 1))));
        inv.setItem(53, item(Material.BARRIER, "&cBack", List.of("&7Return to home options")));
        iconPickerStates.put(p.getUniqueId(), new IconPickerState(homeName, query, currentPage));
        p.openInventory(inv);
        javaMenuPlayers.add(p.getUniqueId());
        if (matches.isEmpty()) p.sendMessage(LEGACY.deserialize("&cNo Minecraft items matched that search."));
    }

    private void selectHomeIcon(Player p, String homeName, Material icon) {
        if (!icon.isItem() || !getHomes(p).containsKey(homeName)) return;
        homeIcons.computeIfAbsent(p.getUniqueId(), ignored -> new HashMap<>()).put(homeName, icon);
        saveHomes();
        p.sendMessage(LEGACY.deserialize("&aIcon updated to &f" + prettyMaterial(icon) + "&a."));
        openJavaHomeActions(p, homeName);
    }

    private void handleJavaMenuClick(Player p, InventoryClickEvent e, String title) {
        e.setCancelled(true);
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || clicked.getItemMeta().displayName() == null) return;
        String display = LEGACY.serialize(clicked.getItemMeta().displayName());
        if (title.equals(LEGACY.serialize(LEGACY.deserialize("&8Your Homes")))) {
            if (display.contains("Close")) { p.closeInventory(); javaMenuPlayers.remove(p.getUniqueId()); return; }
            if (display.contains("Set a home")) {
                p.closeInventory(); javaMenuPlayers.remove(p.getUniqueId());
                p.sendMessage(LEGACY.deserialize("&eUse &f/sethome <name> &eto create a home."));
                return;
            }
            String name = display.replace("§6", "").replace("&6", "").trim().toLowerCase(Locale.ROOT);
            if (getHomes(p).containsKey(name)) openJavaHomeActions(p, name);
            return;
        }
        if (title.startsWith(LEGACY.serialize(LEGACY.deserialize("&8Home: ")))) {
            String name = javaSelectedHome.get(p.getUniqueId());
            if (name == null || !getHomes(p).containsKey(name)) { openJavaHomesMenu(p); return; }
            if (display.contains("Back")) { javaSelectedHome.remove(p.getUniqueId()); openJavaHomesMenu(p); }
            else if (display.contains("Teleport")) { javaSelectedHome.remove(p.getUniqueId()); teleportHome(p, name); }
            else if (display.contains("Rename")) { javaSelectedHome.remove(p.getUniqueId()); beginRename(p, name); }
            else if (display.contains("Change Icon")) openIconSearch(p, name, "");
            else if (display.contains("Delete")) {
                getHomes(p).remove(name);
                Map<String, Material> icons = homeIcons.get(p.getUniqueId());
                if (icons != null) icons.remove(name);
                javaSelectedHome.remove(p.getUniqueId());
                saveHomes();
                p.sendMessage(LEGACY.deserialize(msg("deleted", Map.of("home", name))));
                openJavaHomesMenu(p);
            }
            return;
        }
        if (title.startsWith(LEGACY.serialize(LEGACY.deserialize("&8Choose Icon: ")))) {
            IconPickerState state = iconPickerStates.get(p.getUniqueId());
            if (state == null) return;
            if (raw < 45 && !display.equals("§r") && !display.isBlank() && clicked.getType().isItem()) {
                selectHomeIcon(p, state.homeName(), clicked.getType());
                return;
            }
            if (raw == 45) { openIconSearch(p, state.homeName(), state.query()); return; }
            if (raw == 48) { openIconResults(p, state.homeName(), state.query(), state.page() - 1); return; }
            if (raw == 50) { openIconResults(p, state.homeName(), state.query(), state.page() + 1); return; }
            if (raw == 53) { iconPickerStates.remove(p.getUniqueId()); openJavaHomeActions(p, state.homeName()); }
        }
    }

    private void beginRename(Player p, String oldName) {
        oldName = oldName.toLowerCase(Locale.ROOT);
        if (!getHomes(p).containsKey(oldName)) { send(p, "missing", "home", oldName); return; }
        pendingRename.put(p.getUniqueId(), oldName);
        p.closeInventory();
        p.sendMessage(LEGACY.deserialize("&eType the new name for home &f" + oldName + "&e in chat. Type &fcancel &eto abort."));
    }

    @EventHandler public void onRenameChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        String oldName = pendingRename.get(p.getUniqueId());
        if (oldName == null) return;
        event.setCancelled(true);
        String newName = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase(Locale.ROOT);
        Bukkit.getScheduler().runTask(this, () -> {
            pendingRename.remove(p.getUniqueId());
            if (newName.equalsIgnoreCase("cancel")) { p.sendMessage(LEGACY.deserialize("&7Rename cancelled.")); return; }
            if (!VALID_NAME.matcher(newName).matches()) { p.sendMessage(LEGACY.deserialize("&cInvalid name. Use 1-16 letters, numbers, _ or -.")); return; }
            LinkedHashMap<String, Location> map = getHomes(p);
            if (!map.containsKey(oldName)) { send(p, "missing", "home", oldName); return; }
            if (map.containsKey(newName) && !newName.equals(oldName)) { p.sendMessage(LEGACY.deserialize("&cThat home name already exists.")); return; }
            Location loc = map.remove(oldName);
            map.put(newName, loc);
            Map<String, Material> icons = homeIcons.computeIfAbsent(p.getUniqueId(), ignored -> new HashMap<>());
            Material icon = icons.remove(oldName);
            if (icon != null) icons.put(newName, icon);
            saveHomes();
            p.sendMessage(LEGACY.deserialize(msg("renamed", Map.of("home", newName))));
            openJavaHomesMenu(p);
        });
    }

    private String iconDisplay(Material material) {
        return switch (material) {
            case RED_BED, BLUE_BED, GREEN_BED -> "⌂";
            case CHEST, ENDER_CHEST -> "▣";
            case DIAMOND, DIAMOND_BLOCK -> "◆";
            case EMERALD -> "♦";
            case NETHER_STAR, BEACON -> "✦";
            case TNT -> "✹";
            default -> "●";
        };
    }

    private String prettyMaterial(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        return result.toString().trim();
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
        int limit = homeLimit(p);
        int[] slots = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39};
        for (int index = 0; index < slots.length; index++) {
            if (index < map.size()) {
                String name = new ArrayList<>(map.keySet()).get(index);
                Material icon = homeIcons.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(name, Material.RED_BED);
                inv.setItem(slots[index], item(Material.BROWN_BED, "&6" + name,
                        List.of("&7Home is set", "&7Icon: &f" + prettyMaterial(icon), "&eClick to manage this home")));
            } else if (index < limit) {
                inv.setItem(slots[index], item(Material.WHITE_BED, "&fEmpty Home Slot",
                        List.of("&7No home set here", "&7Use &f/sethome <name> &7to create one")));
            } else {
                inv.setItem(slots[index], item(Material.RED_BED, "&cLocked Home Slot",
                        List.of("&7This slot is locked", "&7Your rank allows &f" + limit + " &7homes")));
            }
        }
        inv.setItem(49, item(Material.LIME_BED, "&aSet a home", List.of("&7Use &f/sethome <name>", "&7to create a new home.")));
        inv.setItem(45, item(Material.BARRIER, "&cClose", List.of()));
        p.openInventory(inv);
        bedrockPlayers.add(p.getUniqueId());
    }

    private void openBedrockHomeActions(Player p, String name) {
        if (!getHomes(p).containsKey(name)) { openBedrockMenu(p); return; }
        Inventory inv = Bukkit.createInventory(null, 27, LEGACY.deserialize("&8Home: " + name));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&r", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        inv.setItem(11, item(Material.BLUE_DYE, "&9Teleport", List.of("&7Teleport to this home")));
        inv.setItem(13, item(Material.YELLOW_DYE, "&eRename", List.of("&7Rename this home using chat")));
        inv.setItem(15, item(Material.RED_DYE, "&cDelete", List.of("&7Delete this home")));
        inv.setItem(22, item(Material.BARRIER, "&cBack", List.of("&7Return to your homes")));
        bedrockSelectedHome.put(p.getUniqueId(), name);
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
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = LEGACY.serialize(e.getView().title());
        if (javaMenuPlayers.contains(p.getUniqueId()) &&
                (title.equals(LEGACY.serialize(LEGACY.deserialize("&8Your Homes")))
                || title.startsWith(LEGACY.serialize(LEGACY.deserialize("&8Home: ")))
                || title.startsWith(LEGACY.serialize(LEGACY.deserialize("&8Choose Icon: "))))) {
            handleJavaMenuClick(p, e, title);
            return;
        }
        if (!bedrockPlayers.contains(p.getUniqueId())) return;
        boolean homesMenu = title.equals(LEGACY.serialize(LEGACY.deserialize("&8Your Homes")));
        boolean actionMenu = title.startsWith(LEGACY.serialize(LEGACY.deserialize("&8Home: ")));
        if (!homesMenu && !actionMenu) return;
        e.setCancelled(true);
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || clicked.getItemMeta().displayName() == null) return;
        String display = LEGACY.serialize(clicked.getItemMeta().displayName());
        if (display.contains("Close")) { p.closeInventory(); bedrockPlayers.remove(p.getUniqueId()); bedrockSelectedHome.remove(p.getUniqueId()); return; }
        if (homesMenu) {
            if (display.contains("Set a home")) { p.closeInventory(); p.sendMessage(LEGACY.deserialize("&eUse &f/sethome <name> &eto create a home.")); return; }
            if (display.contains("Empty Home Slot") || display.contains("Locked Home Slot") || display.contains("GRAY_STAINED_GLASS")) return;
            String name = display.replace("§6", "").replace("&6", "").trim().toLowerCase(Locale.ROOT);
            if (getHomes(p).containsKey(name)) openBedrockHomeActions(p, name);
            return;
        }
        String name = bedrockSelectedHome.get(p.getUniqueId());
        if (name == null || !getHomes(p).containsKey(name)) { openBedrockMenu(p); return; }
        if (display.contains("Back")) { bedrockSelectedHome.remove(p.getUniqueId()); openBedrockMenu(p); return; }
        if (display.contains("Teleport")) { bedrockSelectedHome.remove(p.getUniqueId()); teleportHome(p, name); return; }
        if (display.contains("Rename")) { bedrockSelectedHome.remove(p.getUniqueId()); beginRename(p, name); return; }
        if (display.contains("Delete")) {
            getHomes(p).remove(name);
            Map<String, Material> icons = homeIcons.get(p.getUniqueId());
            if (icons != null) icons.remove(name);
            saveHomes();
            bedrockSelectedHome.remove(p.getUniqueId());
            p.sendMessage(LEGACY.deserialize(msg("deleted", Map.of("home", name))));
            openBedrockMenu(p);
        }
    }

    @EventHandler public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        String title = LEGACY.serialize(e.getView().title());
        if (title.equals(LEGACY.serialize(LEGACY.deserialize("&8Your Homes")))
                || title.startsWith(LEGACY.serialize(LEGACY.deserialize("&8Home: ")))
                || title.startsWith(LEGACY.serialize(LEGACY.deserialize("&8Choose Icon: ")))) {
            if (!javaSelectedHome.containsKey(uuid) && !iconPickerStates.containsKey(uuid)) javaMenuPlayers.remove(uuid);
        }
        if (title.equals(LEGACY.serialize(LEGACY.deserialize("&8Your Homes")))) bedrockPlayers.remove(uuid);
    }
}
package de.playercannon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PlayerCannon extends JavaPlugin implements Listener, TabExecutor {

    private static final long COOLDOWN_MS = 1000;

    private NamespacedKey rodKey;
    private File dataFile;
    /** Besitzer der Angel -> verlinkte Spieler */
    private final Map<UUID, Set<UUID>> links = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        rodKey = new NamespacedKey(this, "cannon_rod");
        dataFile = new File(getDataFolder(), "links.yml");
        loadLinks();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("playercannon");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        saveLinks();
    }

    // ---------------------------------------------------------------- Commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl nutzen.");
            return true;
        }
        if (args.length == 0) {
            msg(player, "/playercannon link <spieler> | unlink <spieler> | list | clear | rod", NamedTextColor.GRAY);
            return true;
        }

        Set<UUID> set = links.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashSet<>());

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "rod" -> {
                player.getInventory().addItem(createRod());
                msg(player, "Du hast die Player Cannon Angel erhalten.", NamedTextColor.LIGHT_PURPLE);
            }
            case "link" -> {
                if (args.length < 2) {
                    msg(player, "Benutzung: /playercannon link <spieler>", NamedTextColor.RED);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    msg(player, "Spieler nicht gefunden (muss online sein).", NamedTextColor.RED);
                    return true;
                }
                if (set.add(target.getUniqueId())) {
                    saveLinks();
                    msg(player, target.getName() + " wurde verlinkt.", NamedTextColor.GREEN);
                } else {
                    msg(player, target.getName() + " ist bereits verlinkt.", NamedTextColor.YELLOW);
                }
            }
            case "unlink" -> {
                if (args.length < 2) {
                    msg(player, "Benutzung: /playercannon unlink <spieler>", NamedTextColor.RED);
                    return true;
                }
                UUID id = resolveUuid(args[1], set);
                if (id != null && set.remove(id)) {
                    saveLinks();
                    msg(player, args[1] + " wurde entlinkt.", NamedTextColor.GREEN);
                } else {
                    msg(player, args[1] + " ist nicht verlinkt.", NamedTextColor.RED);
                }
            }
            case "list" -> {
                if (set.isEmpty()) {
                    msg(player, "Keine Spieler verlinkt.", NamedTextColor.GRAY);
                } else {
                    String names = set.stream()
                            .map(u -> Optional.ofNullable(Bukkit.getOfflinePlayer(u).getName()).orElse(u.toString()))
                            .collect(Collectors.joining(", "));
                    msg(player, "Verlinkt: " + names, NamedTextColor.AQUA);
                }
            }
            case "clear" -> {
                set.clear();
                saveLinks();
                msg(player, "Alle Links entfernt.", NamedTextColor.GREEN);
            }
            default -> msg(player, "Unbekannter Unterbefehl.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("link", "unlink", "list", "clear", "rod"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("link") || args[0].equalsIgnoreCase("unlink"))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private UUID resolveUuid(String name, Set<UUID> candidates) {
        for (UUID u : candidates) {
            String n = Bukkit.getOfflinePlayer(u).getName();
            if (n != null && n.equalsIgnoreCase(name)) return u;
        }
        return null;
    }

    // ---------------------------------------------------------------- Rod

    private ItemStack createRod() {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.displayName(Component.text("Player Cannon", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);
        rod.setItemMeta(meta);
        return rod;
    }

    private boolean isCannonRod(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(rodKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- Event

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        PlayerFishEvent.State state = e.getState();
        // Nur beim Einholen (Pullen) der Angel
        if (state != PlayerFishEvent.State.REEL_IN
                && state != PlayerFishEvent.State.IN_GROUND
                && state != PlayerFishEvent.State.CAUGHT_ENTITY
                && state != PlayerFishEvent.State.CAUGHT_FISH) return;

        Player owner = e.getPlayer();
        if (!isCannonRod(owner.getInventory().getItemInMainHand())
                && !isCannonRod(owner.getInventory().getItemInOffHand())) return;

        if (state == PlayerFishEvent.State.CAUGHT_FISH) {
            if (e.getCaught() != null) e.getCaught().remove();
            e.setExpToDrop(0);
        }

        Set<UUID> set = links.get(owner.getUniqueId());
        if (set == null || set.isEmpty()) {
            owner.sendActionBar(Component.text("Keine Spieler verlinkt!", NamedTextColor.RED));
            return;
        }

        long now = System.currentTimeMillis();
        if (now - cooldowns.getOrDefault(owner.getUniqueId(), 0L) < COOLDOWN_MS) return;
        cooldowns.put(owner.getUniqueId(), now);

        Location target = e.getHook().getLocation();
        int launched = 0;
        for (UUID id : set) {
            Player t = Bukkit.getPlayer(id);
            if (t == null || !t.isOnline() || t.isDead()) continue;
            if (!t.getWorld().equals(target.getWorld())) continue;
            launchPearl(t, target);
            launched++;
        }
        owner.sendActionBar(Component.text(launched + " Pearl(s) abgefeuert", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldowns.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- Ballistik

    private void launchPearl(Player shooter, Location target) {
        Location start = shooter.getEyeLocation();
        Vector velocity = solveVelocity(start.toVector(), target.toVector());
        shooter.getWorld().spawn(start, EnderPearl.class, pearl -> {
            pearl.setShooter(shooter);
            pearl.setVelocity(velocity);
        });
    }

    /**
     * Sucht Abwurfwinkel und -geschwindigkeit, mit der die Pearl (Gravitation 0.03, Luftwiderstand 0.99)
     * dem Ziel am nächsten kommt.
     */
    private Vector solveVelocity(Vector s, Vector target) {
        double yaw = Math.atan2(target.getZ() - s.getZ(), target.getX() - s.getX());
        double hx = Math.cos(yaw), hz = Math.sin(yaw);

        Vector best = new Vector(hx, 0.5, hz).multiply(1.5);
        double bestScore = Double.MAX_VALUE;

        for (double speed = 1.0; speed <= 5.0; speed += 0.25) {
            for (double pitch = -60; pitch <= 85; pitch += 0.5) {
                double rad = Math.toRadians(pitch);
                double vx = hx * Math.cos(rad) * speed;
                double vy = Math.sin(rad) * speed;
                double vz = hz * Math.cos(rad) * speed;
                double x = s.getX(), y = s.getY(), z = s.getZ();
                double min = Double.MAX_VALUE;

                for (int t = 0; t < 300; t++) {
                    x += vx; y += vy; z += vz;
                    vx *= 0.99; vy *= 0.99; vz *= 0.99;
                    vy -= 0.03;
                    double dx = x - target.getX(), dy = y - target.getY(), dz = z - target.getZ();
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < min) min = d;
                    if (vy < 0 && y < target.getY() - 30) break;
                }

                double score = min + speed * 0.05; // bei Gleichstand lieber langsamer
                if (score < bestScore) {
                    bestScore = score;
                    best = new Vector(hx * Math.cos(rad) * speed, Math.sin(rad) * speed, hz * Math.cos(rad) * speed);
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- Persistenz

    private void loadLinks() {
        links.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        var section = cfg.getConfigurationSection("links");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                Set<UUID> set = new LinkedHashSet<>();
                for (String s : section.getStringList(key)) set.add(UUID.fromString(s));
                links.put(owner, set);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveLinks() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var entry : links.entrySet()) {
            cfg.set("links." + entry.getKey(), entry.getValue().stream().map(UUID::toString).toList());
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Konnte links.yml nicht speichern: " + ex.getMessage());
        }
    }

    private void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[PlayerCannon] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text(text, color)));
    }
}

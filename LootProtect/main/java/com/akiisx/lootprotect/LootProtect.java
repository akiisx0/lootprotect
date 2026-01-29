package com.akiisx.lootprotect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class LootProtect extends JavaPlugin implements Listener {

    private final Map<UUID, ProtectedLoot> protectedLoots = new HashMap<>();
    private final Map<String, UUID> locationKeyMap = new HashMap<>();
    private final Map<UUID, Long> lastMessageTime = new HashMap<>();
    private NamespacedKey lootKey;
    private int protectionTime;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigValues();

        // Initialize namespaced key
        lootKey = new NamespacedKey(this, "protected_loot");

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Register command
        Objects.requireNonNull(getCommand("lootprotect")).setExecutor((sender, command, label, args) -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (sender.hasPermission("lootprotect.admin")) {
                    reloadConfig();
                    reloadConfigValues();
                    sender.sendMessage(ChatColor.GREEN + "✅ LootProtect configuration reloaded!");
                } else {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                }
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "LootProtect Commands:");
            sender.sendMessage(ChatColor.GRAY + "/lootprotect reload" + ChatColor.WHITE + " - Reload configuration");
            return true;
        });

        // Cleanup task - runs every second
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, ProtectedLoot>> iterator = protectedLoots.entrySet().iterator();
                while (iterator.hasNext()) {
                    ProtectedLoot loot = iterator.next().getValue();

                    // Check if expired
                    if (now > loot.expireTime) {
                        // Protection expired - remove everything
                        removeProtectionTags(loot);

                        // Clean up holograms
                        if (loot.mainHologram != null && !loot.mainHologram.isDead()) {
                            loot.mainHologram.remove();
                        }
                        for (ArmorStand hologram : loot.itemHolograms.values()) {
                            if (hologram != null && !hologram.isDead()) {
                                hologram.remove();
                            }
                        }

                        // Remove from location map
                        String locKey = getLocationKey(loot.location);
                        locationKeyMap.remove(locKey);

                        iterator.remove();
                        getLogger().info("Cleaned up expired loot protection for " + loot.victimId);
                    } else {
                        // Update holograms while active
                        updateHolograms(loot);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L); // Run every second

        getLogger().info("LootProtect 1.21.4 enabled!");
    }

    private String getLocationKey(Location location) {
        return location.getWorld().getName() + ":" +
                (int)location.getX() + ":" +
                (int)location.getY() + ":" +
                (int)location.getZ();
    }

    private void reloadConfigValues() {
        protectionTime = getConfig().getInt("protection-time", 30);
    }

    @Override
    public void onDisable() {
        // Clean up all holograms
        for (ProtectedLoot loot : protectedLoots.values()) {
            if (loot.mainHologram != null && !loot.mainHologram.isDead()) {
                loot.mainHologram.remove();
            }
            for (ArmorStand hologram : loot.itemHolograms.values()) {
                if (hologram != null && !hologram.isDead()) {
                    hologram.remove();
                }
            }
        }
        protectedLoots.clear();
        locationKeyMap.clear();
        lastMessageTime.clear();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) return;

        long expireTime = System.currentTimeMillis() + (protectionTime * 1000L);

        // Store loot data
        Location deathLoc = victim.getLocation();
        ProtectedLoot loot = new ProtectedLoot();
        loot.killerId = killer.getUniqueId();
        loot.victimId = victim.getUniqueId();
        loot.location = deathLoc.clone();
        loot.expireTime = expireTime;
        loot.items = new ArrayList<>(event.getDrops());

        // Clean up any existing protection at this location first
        String locKey = getLocationKey(deathLoc);
        UUID existingVictimId = locationKeyMap.get(locKey);
        if (existingVictimId != null) {
            ProtectedLoot existingLoot = protectedLoots.get(existingVictimId);
            if (existingLoot != null) {
                // Remove old protection
                removeProtectionTags(existingLoot);
                if (existingLoot.mainHologram != null && !existingLoot.mainHologram.isDead()) {
                    existingLoot.mainHologram.remove();
                }
                for (ArmorStand hologram : existingLoot.itemHolograms.values()) {
                    if (hologram != null && !hologram.isDead()) {
                        hologram.remove();
                    }
                }
                protectedLoots.remove(existingVictimId);
            }
            locationKeyMap.remove(locKey);
        }

        // Add new protection
        protectedLoots.put(victim.getUniqueId(), loot);
        locationKeyMap.put(locKey, victim.getUniqueId());

        // Tag items and spawn them
        event.getDrops().clear(); // Clear original drops

        for (ItemStack itemStack : loot.items) {
            if (itemStack != null) {
                // Tag the item
                ItemMeta meta = itemStack.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(lootKey,
                            PersistentDataType.STRING,
                            killer.getUniqueId().toString() + ":" + expireTime + ":" + victim.getUniqueId().toString());
                    itemStack.setItemMeta(meta);
                }

                // Drop the item
                Item droppedItem = deathLoc.getWorld().dropItemNaturally(deathLoc, itemStack);
                loot.droppedItems.add(droppedItem);
            }
        }

        // Message to killer
        String message = getConfig().getString("messages.loot-protected",
                "&e🗡 Loot Protected! You have &c%time% &eseconds to collect the loot.");
        message = ChatColor.translateAlternateColorCodes('&', message)
                .replace("%time%", String.valueOf(protectionTime))
                .replace("%killer%", killer.getName());
        killer.sendMessage(message);

        // Create initial holograms
        updateHolograms(loot);

        getLogger().info("Loot protected for " + killer.getName() + " at " + locKey);
    }

    @EventHandler
    public void onPlayerAttemptPickupItem(PlayerAttemptPickupItemEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItem();
        ItemStack itemStack = item.getItemStack();
        ItemMeta meta = itemStack.getItemMeta();

        if (meta == null) {
            // Allow pickup if no meta
            return;
        }

        String protectionData = meta.getPersistentDataContainer().get(lootKey, PersistentDataType.STRING);
        if (protectionData == null) {
            // Item nije zaštićen - dozvoli pickup
            return;
        }

        String[] data = protectionData.split(":");
        if (data.length < 3) {
            // Invalid data format - remove tag and allow pickup
            meta.getPersistentDataContainer().remove(lootKey);
            itemStack.setItemMeta(meta);
            return;
        }

        try {
            UUID killerId = UUID.fromString(data[0]);
            long expireTime = Long.parseLong(data[1]);
            UUID victimId = UUID.fromString(data[2]);

            // Check if protection expired
            if (System.currentTimeMillis() > expireTime) {
                // Protection expired - remove tag i dozvoli pickup
                meta.getPersistentDataContainer().remove(lootKey);
                itemStack.setItemMeta(meta);

                // Clean up the loot data
                ProtectedLoot loot = protectedLoots.get(victimId);
                if (loot != null) {
                    // Check if all items are picked up or expired
                    boolean allItemsGone = true;
                    for (Item droppedItem : loot.droppedItems) {
                        if (!droppedItem.isDead() && droppedItem != item) {
                            allItemsGone = false;
                            break;
                        }
                    }

                    if (allItemsGone) {
                        // Clean up holograms
                        if (loot.mainHologram != null && !loot.mainHologram.isDead()) {
                            loot.mainHologram.remove();
                        }
                        for (ArmorStand hologram : loot.itemHolograms.values()) {
                            if (hologram != null && !hologram.isDead()) {
                                hologram.remove();
                            }
                        }

                        String locKey = getLocationKey(loot.location);
                        locationKeyMap.remove(locKey);
                        protectedLoots.remove(victimId);
                    }
                }
                return;
            }

            // Check if player can loot
            boolean canLoot = player.getUniqueId().equals(killerId) ||
                    player.hasPermission("lootprotect.bypass");

            if (!canLoot) {
                event.setCancelled(true);

                // Calculate remaining time
                int timeLeft = (int) ((expireTime - System.currentTimeMillis()) / 1000);
                timeLeft = Math.max(0, timeLeft);

                // Anti-spam: Send message only once every 2 seconds
                long currentTime = System.currentTimeMillis();
                Long lastTime = lastMessageTime.get(player.getUniqueId());

                if (lastTime == null || (currentTime - lastTime) > 2000) {
                    String killerName = getPlayerName(killerId);
                    String message = getConfig().getString("messages.cannot-loot",
                            "&cOnly &e%killer% &ccan loot this for &a%time% &cmore seconds!");
                    message = ChatColor.translateAlternateColorCodes('&', message)
                            .replace("%killer%", killerName)
                            .replace("%time%", String.valueOf(timeLeft));

                    player.sendMessage(message);
                    lastMessageTime.put(player.getUniqueId(), currentTime);
                }
            } else {
                // Killer or bypass player can loot - remove protection tag from THIS ITEM ONLY
                meta.getPersistentDataContainer().remove(lootKey);
                itemStack.setItemMeta(meta);
            }
        } catch (IllegalArgumentException e) {
            // Invalid UUID or number format - remove tag and allow pickup
            meta.getPersistentDataContainer().remove(lootKey);
            itemStack.setItemMeta(meta);
        }
    }

    private void updateHolograms(ProtectedLoot loot) {
        // Calculate remaining time
        int timeLeft = (int) ((loot.expireTime - System.currentTimeMillis()) / 1000);
        timeLeft = Math.max(0, timeLeft);

        String killerName = getPlayerName(loot.killerId);

        // Update main hologram
        updateMainHologram(loot, timeLeft, killerName);

        // Update item holograms
        updateItemHolograms(loot, timeLeft);
    }

    private void updateMainHologram(ProtectedLoot loot, int timeLeft, String killerName) {
        String hologramText = getConfig().getString("hologram.main-text",
                "&6&l🗡 Loot Protected &7(&c%time%s remaining&7) &7Killer: &a%killer%");
        hologramText = ChatColor.translateAlternateColorCodes('&', hologramText)
                .replace("%time%", String.valueOf(timeLeft))
                .replace("%killer%", killerName);

        Location holoLoc = loot.location.clone().add(0, 3.0, 0);

        // Remove old hologram if exists and is dead
        if (loot.mainHologram != null && loot.mainHologram.isDead()) {
            loot.mainHologram = null;
        }

        // Create new hologram if doesn't exist
        if (loot.mainHologram == null) {
            ArmorStand hologram = holoLoc.getWorld().spawn(holoLoc, ArmorStand.class);
            hologram.setCustomName(hologramText);
            hologram.setCustomNameVisible(true);
            hologram.setVisible(false);
            hologram.setGravity(false);
            hologram.setInvulnerable(true);
            hologram.setMarker(true);

            loot.mainHologram = hologram;
        } else {
            // Update existing hologram
            loot.mainHologram.setCustomName(hologramText);
        }
    }

    private void updateItemHolograms(ProtectedLoot loot, int timeLeft) {
        // Clean up dead items and their holograms
        Iterator<Item> itemIterator = loot.droppedItems.iterator();
        while (itemIterator.hasNext()) {
            Item item = itemIterator.next();
            if (item.isDead()) {
                ArmorStand hologram = loot.itemHolograms.remove(item);
                if (hologram != null && !hologram.isDead()) {
                    hologram.remove();
                }
                itemIterator.remove();
            }
        }

        // Clean up holograms for items that no longer exist
        Iterator<Map.Entry<Item, ArmorStand>> holoIterator = loot.itemHolograms.entrySet().iterator();
        while (holoIterator.hasNext()) {
            Map.Entry<Item, ArmorStand> entry = holoIterator.next();
            if (entry.getKey().isDead() || entry.getValue() == null || entry.getValue().isDead()) {
                if (entry.getValue() != null && !entry.getValue().isDead()) {
                    entry.getValue().remove();
                }
                holoIterator.remove();
            }
        }

        // Create/update holograms for existing items
        for (Item item : loot.droppedItems) {
            if (!item.isDead()) {
                String itemHologramText = getConfig().getString("hologram.item-text",
                        "&c⛔ &7Item Protected &c%time%s");
                itemHologramText = ChatColor.translateAlternateColorCodes('&', itemHologramText)
                        .replace("%time%", String.valueOf(timeLeft));

                Location itemLoc = item.getLocation().clone().add(0, 0.5, 0);

                // Update existing hologram or create new one
                if (loot.itemHolograms.containsKey(item)) {
                    ArmorStand existingHolo = loot.itemHolograms.get(item);
                    if (existingHolo != null && !existingHolo.isDead()) {
                        existingHolo.teleport(itemLoc);
                        existingHolo.setCustomName(itemHologramText);
                    } else {
                        // Create new hologram
                        ArmorStand itemHologram = itemLoc.getWorld().spawn(itemLoc, ArmorStand.class);
                        itemHologram.setCustomName(itemHologramText);
                        itemHologram.setCustomNameVisible(true);
                        itemHologram.setVisible(false);
                        itemHologram.setGravity(false);
                        itemHologram.setInvulnerable(true);
                        itemHologram.setMarker(true);

                        loot.itemHolograms.put(item, itemHologram);
                    }
                } else {
                    // Create new hologram
                    ArmorStand itemHologram = itemLoc.getWorld().spawn(itemLoc, ArmorStand.class);
                    itemHologram.setCustomName(itemHologramText);
                    itemHologram.setCustomNameVisible(true);
                    itemHologram.setVisible(false);
                    itemHologram.setGravity(false);
                    itemHologram.setInvulnerable(true);
                    itemHologram.setMarker(true);

                    loot.itemHolograms.put(item, itemHologram);
                }
            }
        }
    }

    private void removeProtectionTags(ProtectedLoot loot) {
        for (Item item : loot.droppedItems) {
            if (!item.isDead()) {
                ItemStack itemStack = item.getItemStack();
                ItemMeta meta = itemStack.getItemMeta();
                if (meta != null && meta.getPersistentDataContainer().has(lootKey, PersistentDataType.STRING)) {
                    meta.getPersistentDataContainer().remove(lootKey);
                    itemStack.setItemMeta(meta);
                    item.setItemStack(itemStack);
                }
            }
        }
    }

    private String getPlayerName(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }

        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }

    private static class ProtectedLoot {
        UUID killerId;
        UUID victimId;
        Location location;
        long expireTime;
        ArmorStand mainHologram;
        Map<Item, ArmorStand> itemHolograms = new HashMap<>();
        List<ItemStack> items = new ArrayList<>();
        List<Item> droppedItems = new ArrayList<>();
    }
}
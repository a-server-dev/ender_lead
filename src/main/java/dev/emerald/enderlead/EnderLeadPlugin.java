package dev.emerald.enderlead;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** One capture + one release, potentially across worlds in the same Paper server. */
public final class EnderLeadPlugin extends JavaPlugin implements Listener, TabExecutor {
    private NamespacedKey itemKey;
    private NamespacedKey snapshotKey;
    private NamespacedKey captureIdKey;
    private NamespacedKey mobTypeKey;
    private NamespacedKey mobNameKey;
    private NamespacedKey specialKindKey;
    private NamespacedKey capturedAtKey;
    private RedemptionLedger ledger;
    private Set<String> blockedMobTypes;
    private Set<String> blockedTags;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "ender_lead");
        snapshotKey = new NamespacedKey(this, "entity_snapshot");
        captureIdKey = new NamespacedKey(this, "capture_id");
        mobTypeKey = new NamespacedKey(this, "mob_type");
        mobNameKey = new NamespacedKey(this, "mob_name");
        specialKindKey = new NamespacedKey(this, "special_kind");
        capturedAtKey = new NamespacedKey(this, "captured_at");
        try {
            ledger = new RedemptionLedger(getDataFolder().toPath());
        } catch (IOException error) {
            getLogger().severe("Cannot open redemption ledger; disabling to prevent duplication: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadRules();
        refreshRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("enderlead") != null) getCommand("enderlead").setExecutor(this);
        getLogger().info("Ender Lead 1.0.6 enabled for Paper 26.3");
    }

    private void loadRules() {
        blockedMobTypes = getConfig().getStringList("blocked-mobs").stream()
                .map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        blockedTags = getConfig().getStringList("blocked-scoreboard-tags").stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Register or remove the recipe according to config.yml (also used by /enderlead reload). */
    private void refreshRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "ender_lead_recipe");
        Bukkit.removeRecipe(recipeKey);
        if (!getConfig().getBoolean("crafting.enabled", true)) {
            getLogger().info("EnderLead crafting recipe disabled by config.");
            return;
        }
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, newEmptyLead());
        recipe.addIngredient(Material.LEAD);
        recipe.addIngredient(5, Material.ENDER_PEARL);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack newEmptyLead() {
        ItemStack item = new ItemStack(Material.LEAD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("エンダーリード", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(
                Component.text("1体だけモブを格納できる", NamedTextColor.GRAY),
                Component.text("モブを左右クリック：捕獲", NamedTextColor.GRAY),
                Component.text("異なるワールドでも解放可能", NamedTextColor.DARK_PURPLE),
                Component.text("解放すると必ず壊れる（使い捨て）", NamedTextColor.DARK_GRAY)
        ));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.setMaxStackSize(1);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isEnderLead(ItemStack item) {
        if (item == null || item.getType() != Material.LEAD || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isFilled(ItemStack item) {
        return isEnderLead(item) && item.getItemMeta().getPersistentDataContainer().has(snapshotKey, PersistentDataType.STRING);
    }

    private ItemStack getHeldItem(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
    }

    private void setHeldItem(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(item);
        else player.getInventory().setItemInOffHand(item);
    }

    private String canCapture(LivingEntity mob) {
        if (PveProtection.isPveEntity(mob)) return "PvEのモブは捕獲できません。";
        if (mob instanceof Player || mob instanceof ArmorStand || mob instanceof Boss) return "このモブは捕獲できません。";
        if (MercenarySupport.isProtectedOther(mob)) return "メイドやそのAIは捕獲できません。";
        if (MercenarySupport.isMercenaryBody(mob) && !getConfig().getBoolean("mercenary.enabled", true))
            return "設定で傭兵の捕獲は無効です。";
        if (!mob.isValid() || mob.isDead()) return "このモブは現在捕獲できません。";
        if (mob.isInsideVehicle() || !mob.getPassengers().isEmpty()) return "騎乗中・乗客ありのモブは捕獲できません。";
        if (blockedMobTypes.contains(mob.getType().name())) return "強敵・ボスは捕獲できません。";
        if (getConfig().getBoolean("prevent-npc-metadata", true) &&
                (mob.hasMetadata("NPC") || mob.hasMetadata("CitizensNPC"))) {
            return "NPCは捕獲できません。";
        }
        for (String tag : mob.getScoreboardTags()) {
            if (blockedTags.contains(tag.toLowerCase(Locale.ROOT))) return "保護されたモブは捕獲できません。";
        }
        if (mob instanceof Enemy) {
            double limit = getConfig().getDouble("max-hostile-health", 40.0);
            if (limit >= 0) {
                AttributeInstance attribute = mob.getAttribute(Attribute.MAX_HEALTH);
                if (attribute != null && attribute.getValue() > limit) return "強敵は捕獲できません。";
            }
        }
        return null;
    }

    /** Protect PvE entities before their own right-click handlers can interact with an Ender Lead. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPveMobRightClick(PlayerInteractEntityEvent event) {
        if (!isEnderLead(getHeldItem(event.getPlayer(), event.getHand()))) return;
        if (!PveProtection.isPveEntity(event.getRightClicked())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("§cPvEのモブは捕獲できません。");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClickMob(PlayerInteractEntityEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        ItemStack item = getHeldItem(player, hand);
        if (!isEnderLead(item)) return;
        // Already rejected at LOWEST; do not repeat the warning or process the click.
        if (PveProtection.isPveEntity(event.getRightClicked())) return;
        // Mercenary cancels its own right-click at HIGH to open a GUI; we still need to capture it.
        if (event.isCancelled() && !MercenarySupport.isMercenaryBody(event.getRightClicked())
                && !MercenarySupport.isMercenaryBrain(event.getRightClicked())) return;
        event.setCancelled(true); // Don't apply the vanilla lead interaction.
        if (!player.hasPermission("enderlead.use")) {
            player.sendMessage("§cエンダーリードを使う権限がありません。");
            return;
        }
        if (!(event.getRightClicked() instanceof LivingEntity living)) {
            player.sendMessage("§c生きているモブだけを捕獲できます。");
            return;
        }
        captureMob(player, hand, living, item);
    }

    /** Left click uses the main hand only. Cancel the hit so capture never damages the mob. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onLeftClickMob(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isEnderLead(item)) return;
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        if (event.isCancelled()) return;
        // Sweep attacks must not capture or harm additional nearby mobs.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("enderlead.use")) {
            player.sendMessage("§cエンダーリードを使う権限がありません。");
            return;
        }
        captureMob(player, EquipmentSlot.HAND, living, item);
    }

    /** Shared capture path for both right-click interaction and left-click attacks. */
    private void captureMob(Player player, EquipmentSlot hand, LivingEntity clicked, ItemStack item) {
        if (isFilled(item)) {
            player.sendMessage("§e格納済みです。ブロックを左右クリックして解放してください。");
            return;
        }
        if (item.getAmount() != 1) {
            player.sendMessage("§cエンダーリードは1個ずつ使ってください。");
            return;
        }
        MercenarySupport bridge = null;
        MercenarySupport.Target merc = null;
        LivingEntity living = clicked;
        boolean untracked = false;
        try {
            if (PveProtection.isPveEntity(clicked)) {
                player.sendMessage("§cPvEのモブは捕獲できません。");
                return;
            }
            if (MercenarySupport.isProtectedOther(clicked)) {
                player.sendMessage("§cMercenaryのメイドはこのエンダーリードでは捕獲できません。");
                return;
            }
            if (MercenarySupport.isMercenaryBody(clicked) || MercenarySupport.isMercenaryBrain(clicked)) {
                if (!getConfig().getBoolean("mercenary.enabled", true)) {
                    player.sendMessage("§c傭兵の捕獲は設定で無効です。");
                    return;
                }
                bridge = new MercenarySupport();
                merc = bridge.resolve(clicked);
                if (merc == null) throw new IllegalStateException("Mercenary lookup failed");
                if (!merc.canCapture(player)) {
                    player.sendMessage("§c他のプレイヤーが雇っている傭兵は捕獲できません。");
                    return;
                }
                living = merc.body();
            }
            String denied = canCapture(living);
            if (denied != null) {
                player.sendMessage("§c" + denied);
                return;
            }
            // Mercenary keeps parts of its state in memory while in equipment/food GUIs.
            // Flush that state BEFORE taking the snapshot; also undo its GUI opened by right-click.
            if (merc != null) bridge.prepareCapture(merc);
            EntitySnapshot snapshot = living.createSnapshot();
            if (snapshot == null) {
                player.sendMessage("§cこのモブは状態を保存できないため捕獲できません。");
                return;
            }
            String serialized = snapshot.getAsString();
            int maxChars = getConfig().getInt("max-snapshot-chars", 65536);
            if (serialized == null || serialized.isEmpty() || serialized.length() > maxChars) {
                player.sendMessage("§cこのモブの保存データが大きすぎるため捕獲できません。");
                return;
            }
            EntitySnapshot parsed = Bukkit.getEntityFactory().createEntitySnapshot(serialized);
            if (merc != null) {
                Entity preview = parsed.createEntity(living.getWorld());
                MercenarySupport.verifySnapshotRoundTrip(merc.body(), preview);
            }
            UUID id = UUID.randomUUID();
            String type = living.getType().name();
            String customName = living.customName() == null ? ""
                    : PlainTextComponentSerializer.plainText().serialize(living.customName());
            String display = customName.isBlank() ? type.toLowerCase(Locale.ROOT) : customName;
            ItemStack filled = item.clone();
            ItemMeta meta = filled.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(snapshotKey, PersistentDataType.STRING, serialized);
            pdc.set(captureIdKey, PersistentDataType.STRING, id.toString());
            pdc.set(mobTypeKey, PersistentDataType.STRING, type);
            pdc.set(mobNameKey, PersistentDataType.STRING, display);
            if (merc != null) {
                pdc.set(specialKindKey, PersistentDataType.STRING, "mercenary");
                pdc.set(capturedAtKey, PersistentDataType.LONG, System.currentTimeMillis());
            }
            meta.displayName(Component.text("エンダーリード【格納済】", NamedTextColor.LIGHT_PURPLE));
            meta.lore(List.of(
                    Component.text("格納中：" + display, NamedTextColor.AQUA),
                    Component.text(merc == null ? "ブロックを左右クリック：解放" : "Mercenary傭兵：雇用情報と装備を保持", NamedTextColor.GRAY),
                    Component.text("解放成功時に必ず壊れる", NamedTextColor.DARK_GRAY)
            ));
            filled.setItemMeta(meta);
            if (merc != null) {
                // Remove the location from Mercenary's own registry before removing the body.
                // Otherwise it keeps loading its old chunk looking for a nonexistent mercenary.
                untracked = true; // also restore tracking if untrackBeforeCapture fails partway through
                bridge.untrackBeforeCapture(merc);
            }
            setHeldItem(player, hand, filled);
            try {
                if (merc != null) bridge.completeCapture(merc); // removes the brain too
                else living.remove();
            } catch (RuntimeException removeError) {
                if (living.isValid()) {
                    setHeldItem(player, hand, item);
                    if (merc != null && untracked) bridge.restoreTracking(merc);
                }
                throw removeError;
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.35f);
            player.sendMessage("§d" + display + "§aをエンダーリードに格納しました。");
        } catch (Exception error) {
            if (merc != null && untracked && living.isValid()) {
                try { bridge.restoreTracking(merc); } catch (RuntimeException ignored) { }
            }
            getLogger().warning("Capture failed for " + clicked.getType() + ": " + error);
            player.sendMessage("§c捕獲に失敗しました。傭兵データの整合性とサーバーログを確認してください。");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClickBlock(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        // Minecraft's left-click block interaction is a main-hand action.
        if (action == Action.LEFT_CLICK_BLOCK && hand != EquipmentSlot.HAND) return;
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        ItemStack item = getHeldItem(player, hand);
        if (!isFilled(item)) return;
        event.setCancelled(true);
        if (!player.hasPermission("enderlead.use")) {
            player.sendMessage("§cエンダーリードを使う権限がありません。");
            return;
        }
        if (item.getAmount() != 1) {
            player.sendMessage("§c格納済みエンダーリードは1個ずつ使ってください。");
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Location spawnLocation = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        if (!isSafeLocation(spawnLocation)) {
            player.sendMessage("§c解放できる十分な空間がありません。地面の広い場所を選んでください。");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String rawId = pdc.get(captureIdKey, PersistentDataType.STRING);
        String serialized = pdc.get(snapshotKey, PersistentDataType.STRING);
        String type = pdc.get(mobTypeKey, PersistentDataType.STRING);
        if (rawId == null || serialized == null || type == null) {
            player.sendMessage("§cデータが壊れているエンダーリードです。");
            return;
        }
        final UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException invalid) {
            player.sendMessage("§cエンダーリードのIDが不正です。");
            return;
        }
        if (ledger.isReleased(id)) {
            player.sendMessage("§cこのモブは既に解放済みです（複製アイテムも使用不可）。");
            return;
        }
        if (serialized.length() > getConfig().getInt("max-snapshot-chars", 65536)) {
            player.sendMessage("§c保存データが大きすぎるため解放できません。");
            return;
        }
        Entity spawned = null;
        MercenarySupport mercBridge = null;
        boolean redemptionCommitted = false;
        try {
            String specialKind = pdc.get(specialKindKey, PersistentDataType.STRING);
            if ("mercenary".equals(specialKind)) {
                mercBridge = new MercenarySupport(); // Fail closed if Mercenary is disabled or incompatible
            } else if (specialKind != null) {
                throw new IllegalArgumentException("Unknown special entity type: " + specialKind);
            }
            EntitySnapshot snapshot = Bukkit.getEntityFactory().createEntitySnapshot(serialized);
            if (!snapshot.getEntityType().name().equals(type) || snapshot.getEntityType().name().equals("PLAYER")) {
                player.sendMessage("§c保存データとモブの種類が一致しません。");
                return;
            }
            // Create the entity without spawning, then spawn with event/cancellation support.
            spawned = snapshot.createEntity(spawnLocation.getWorld());
            // Backward compatibility: old EnderLead items may already contain a Mercenary mannequin.
            if (mercBridge == null && MercenarySupport.isMercenaryBody(spawned))
                mercBridge = new MercenarySupport();
            if (!(spawned instanceof LivingEntity) || spawned instanceof Boss) {
                player.sendMessage("§c保存されたモブの種類は復元できません。");
                return;
            }
            if (mercBridge != null) {
                if (!MercenarySupport.isMercenaryBody(spawned))
                    throw new IllegalStateException("Mercenary PDC missing in restored snapshot");
                Long captured = pdc.get(capturedAtKey, PersistentDataType.LONG);
                MercenarySupport.relocateWaitingPlace(spawned, spawnLocation, captured == null ? 0L : captured);
            }
            if (!spawned.spawnAt(spawnLocation, CreatureSpawnEvent.SpawnReason.CUSTOM)) {
                player.sendMessage("§cモブのスポーンが禁止されているか、解放に失敗しました。");
                return;
            }
            if (mercBridge != null) {
                mercBridge.registerRestored(spawned);
            }
            // Persist before giving the player credit for redemption. If it fails, undo the spawn.
            ledger.markReleased(id);
            redemptionCommitted = true;
            // One-time item: after the captured mob has been restored and marked as redeemed,
            // always consume the Ender Lead in either hand, including Creative mode.
            setHeldItem(player, hand, new ItemStack(Material.AIR));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.85f);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            String name = pdc.get(mobNameKey, PersistentDataType.STRING);
            player.sendMessage("§a" + (name == null ? type : name) + "§fを解放しました。§eエンダーリードは壊れました。");
        } catch (Exception error) {
            if (redemptionCommitted) {
                // The mob was committed and the anti-duplication ledger has already been saved.
                // Never despawn it now. Consume the lead even if a later effect/message failed.
                setHeldItem(player, hand, new ItemStack(Material.AIR));
                getLogger().warning("Release committed, but post-release action failed for " + id + ": " + error);
                player.sendMessage("§eモブは解放済みです。エンダーリードを消費しました。");
                return;
            }
            if (spawned != null && spawned.isInWorld()) {
                try {
                    if (mercBridge != null) mercBridge.rollbackRestored(spawned);
                    else spawned.remove();
                } catch (Exception rollbackError) {
                    getLogger().severe("FAILED TO ROLLBACK spawned entity " + spawned.getUniqueId() + ": " + rollbackError);
                }
            }
            getLogger().warning("Release failed for " + id + ": " + error);
            player.sendMessage("§c解放に失敗しました。エンダーリードは残っています。");
        }
    }

    /** An intentionally conservative check to avoid releasing entities inside solid blocks. */
    private boolean isSafeLocation(Location at) {
        if (at.getWorld() == null) return false;
        int y = at.getBlockY();
        if (y <= at.getWorld().getMinHeight() || y + 2 >= at.getWorld().getMaxHeight()) return false;
        for (int offset = 0; offset < 3; offset++) {
            if (!at.getWorld().getBlockAt(at.getBlockX(), y + offset, at.getBlockZ()).isPassable()) return false;
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§c権限がありません。");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadRules();
            refreshRecipe();
            sender.sendMessage("§aEnderLead 設定を再読み込みしました。クラフト: "
                    + (getConfig().getBoolean("crafting.enabled", true) ? "§a有効" : "§c無効"));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§c対象プレイヤーがオンラインではありません。");
                return true;
            }
            int amount = 1;
            if (args.length > 2) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException error) {
                    sender.sendMessage("§c個数は1～16の整数にしてください。");
                    return true;
                }
            }
            if (amount < 1 || amount > 16) {
                sender.sendMessage("§c個数は1～16です。");
                return true;
            }
            int given = 0;
            for (int i = 0; i < amount; i++) {
                if (!target.getInventory().addItem(newEmptyLead()).isEmpty()) break;
                given++;
            }
            sender.sendMessage("§a" + target.getName() + " にエンダーリードを " + given + " 個渡しました。");
            return true;
        }
        sender.sendMessage("§e/enderlead give <プレイヤー> [1～16]  または  /enderlead reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return List.of();
        if (args.length == 1) return List.of("give", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return List.of("1", "5", "16");
        return new ArrayList<>();
    }
}

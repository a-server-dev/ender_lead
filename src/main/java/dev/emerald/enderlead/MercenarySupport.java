package dev.emerald.enderlead;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.Objects;
import java.util.Arrays;

/**
 * Read-only source integration for a-server-dev/Mercenary (main as of 2026-11-13).
 *
 * This class does not compile against Mercenary, change its code, or write to its repo.
 * It calls its existing in-memory manager methods so that its private bookkeeping,
 * Snowman 'brain', food/equipment menus, and mercenaries.yml stay consistent.
 * All methods are called on the server thread.
 */
final class MercenarySupport {
    private static final NamespacedKey BODY_KEY = new NamespacedKey("mercenary", "mercenary");
    private static final NamespacedKey OWNER_KEY = new NamespacedKey("mercenary", "owner");
    private static final NamespacedKey BRAIN_KEY = new NamespacedKey("mercenary", "brain");
    private static final NamespacedKey MAID_KEY = new NamespacedKey("mercenary", "maid");
    private static final NamespacedKey MAID_BRAIN_KEY = new NamespacedKey("mercenary", "maid_brain");
    private static final NamespacedKey STAY_KEY = new NamespacedKey("mercenary", "stay");
    private static final NamespacedKey EXPIRES_KEY = new NamespacedKey("mercenary", "expires");

    static boolean isMercenaryBody(Entity entity) {
        if (!(entity instanceof Mannequin)) return false;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(BODY_KEY) || pdc.has(OWNER_KEY);
    }

    static boolean isMercenaryBrain(Entity entity) {
        return entity.getPersistentDataContainer().has(BRAIN_KEY);
    }

    static boolean isProtectedOther(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(MAID_KEY) || pdc.has(MAID_BRAIN_KEY);
    }

    private final Plugin plugin;
    private final Object manager;
    private final Object registry;
    private final Method getByBody;
    private final Method getByBrain;
    private final Method onEntityLoad;
    private final Method save;
    private final Method cancelEating;
    private final Method remove;
    private final Method registryRemove;
    private final Method registryUpdate;
    private final Method registrySave;
    private final Method closeFor;
    private final Class<?> mercClass;
    private final Field bodyField;
    private final Field ownerField;
    private final File registryFile;

    MercenarySupport() throws ReflectiveOperationException {
        plugin = Bukkit.getPluginManager().getPlugin("Mercenary");
        if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("Mercenary is not enabled");
        Field managerField = field(plugin.getClass(), "manager");
        manager = managerField.get(plugin);
        if (manager == null) throw new IllegalStateException("MercenaryManager is not initialized");
        Class<?> managerClass = manager.getClass();
        mercClass = Class.forName("jp.helperplugin.mercenary.Mercenary", false, plugin.getClass().getClassLoader());
        bodyField = field(mercClass, "body");
        ownerField = field(mercClass, "owner");
        getByBody = method(managerClass, "getByBody", Entity.class);
        getByBrain = method(managerClass, "getByBrain", Entity.class);
        onEntityLoad = method(managerClass, "onEntityLoad", Entity.class);
        save = method(managerClass, "save", mercClass);
        cancelEating = method(managerClass, "cancelEating", mercClass);
        remove = method(managerClass, "remove", mercClass, boolean.class);
        registry = field(managerClass, "registry").get(manager);
        if (registry == null) throw new IllegalStateException("MercenaryRegistry is not initialized");
        registryRemove = method(registry.getClass(), "remove", UUID.class);
        registryUpdate = method(registry.getClass(), "update", mercClass);
        registrySave = method(registry.getClass(), "save");
        Method getMenus = method(plugin.getClass(), "getMenus");
        Object menus = invoke(getMenus, plugin);
        if (menus == null) throw new IllegalStateException("MercenaryMenus is not initialized");
        closeFor = method(menus.getClass(), "closeFor", mercClass);
        this.menus = menus;
        registryFile = new File(plugin.getDataFolder(), "mercenaries.yml");
    }

    private final Object menus;

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static Object invoke(Method method, Object instance, Object... args) {
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException("Mercenary integration failed at " + method.getName(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mercenary integration failed at " + method.getName(), e);
        }
    }

    static final class Target {
        private final Object merc;
        private final Mannequin body;
        private final UUID owner;
        Target(Object merc, Mannequin body, UUID owner) {
            this.merc = merc;
            this.body = body;
            this.owner = owner;
        }
        Mannequin body() { return body; }
        boolean canCapture(Player player) { return owner == null || owner.equals(player.getUniqueId()); }
    }

    Target resolve(Entity clicked) throws ReflectiveOperationException {
        if (!isMercenaryBody(clicked) && !isMercenaryBrain(clicked)) return null;
        Object merc = isMercenaryBrain(clicked)
                ? invoke(getByBrain, manager, clicked)
                : invoke(getByBody, manager, clicked);
        if (merc == null) throw new IllegalStateException("Mercenary body/brain is not registered; capture refused to prevent data loss");
        Entity body = (Entity) bodyField.get(merc);
        if (!(body instanceof Mannequin mannequin) || !mannequin.isValid())
            throw new IllegalStateException("Mercenary body is missing");
        return new Target(merc, mannequin, (UUID) ownerField.get(merc));
    }

    /** Close any equipment/food GUI, stop eating, and flush Mercenary's in-memory state into the body's PDC. */
    void prepareCapture(Target target) {
        invoke(closeFor, menus, target.merc);
        invoke(cancelEating, manager, target.merc);
        invoke(save, manager, target.merc);
    }

    /**
     * Remove the stored location BEFORE deleting the mannequin. If the location
     * still exists on disk after save, abort capture with the original mob intact.
     */
    void untrackBeforeCapture(Target target) {
        UUID id = target.body.getUniqueId();
        invoke(registryRemove, registry, id);
        invoke(registrySave, registry);
        if (registryFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(registryFile);
            if (yaml.contains("mercenaries." + id)) {
                invoke(registryUpdate, registry, target.merc);
                invoke(registrySave, registry);
                throw new IllegalStateException("Mercenary location still present in mercenaries.yml: " + id);
            }
        }
    }

    /** Revert bookkeeping if capture could not be completed (body is still present). */
    void restoreTracking(Target target) {
        if (target.body.isValid()) {
            Object tracked = invoke(getByBody, manager, target.body);
            if (tracked == null) {
                invoke(onEntityLoad, manager, target.body);
                tracked = invoke(getByBody, manager, target.body);
            }
            if (tracked == null) throw new IllegalStateException("Failed to restore Mercenary tracking for " + target.body.getUniqueId());
            invoke(registryUpdate, registry, tracked);
            invoke(registrySave, registry);
        }
    }

    /** Properly removes both body and non-persistent AI Snowman, not just Entity.remove(). */
    void completeCapture(Target target) {
        invoke(remove, manager, target.merc, true);
        invoke(registrySave, registry);
        if (target.body.isValid()) throw new IllegalStateException("Mercenary body still exists after removal");
    }

    /**
     * Fail closed if an EntitySnapshot round trip loses any of Mercenary's known
     * durable PDC state, inventory equipment, or health. The snapshot is only
     * tested in-memory (the preview entity is NOT spawned).
     */
    static void verifySnapshotRoundTrip(Mannequin source, Entity preview) {
        if (!(preview instanceof Mannequin copy) || !isMercenaryBody(copy))
            throw new IllegalStateException("Mercenary mannequin type/marker missing after snapshot");
        PersistentDataContainer a = source.getPersistentDataContainer();
        PersistentDataContainer b = copy.getPersistentDataContainer();
        for (String name : new String[]{"owner", "rank", "name", "stay"})
            requireEqual(name, a, b, PersistentDataType.STRING);
        for (String name : new String[]{"price"})
            requireEqual(name, a, b, PersistentDataType.DOUBLE);
        requireEqual("expires", a, b, PersistentDataType.LONG);
        requireEqual("mercenary", a, b, PersistentDataType.BYTE);
        NamespacedKey food = new NamespacedKey("mercenary", "food");
        if (a.has(food) != b.has(food) || !Arrays.equals(
                a.get(food, PersistentDataType.BYTE_ARRAY),
                b.get(food, PersistentDataType.BYTE_ARRAY)))
            throw new IllegalStateException("Mercenary food inventory was lost in snapshot");
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            if (!Objects.equals(source.getEquipment().getItem(slot), copy.getEquipment().getItem(slot)))
                throw new IllegalStateException("Mercenary equipment was lost in snapshot: " + slot);
        }
        if (Math.abs(source.getHealth() - copy.getHealth()) > 0.0001)
            throw new IllegalStateException("Mercenary health was changed during snapshot");
    }

    private static <T, Z> void requireEqual(String name, PersistentDataContainer a,
                                            PersistentDataContainer b, PersistentDataType<T, Z> type) {
        NamespacedKey key = new NamespacedKey("mercenary", name);
        if (a.has(key) != b.has(key) || !Objects.equals(a.get(key, type), b.get(key, type)))
            throw new IllegalStateException("Mercenary data was lost in snapshot: " + name);
    }

    static void relocateWaitingPlace(Entity body, Location location, long capturedAt) {
        PersistentDataContainer pdc = body.getPersistentDataContainer();
        if (pdc.has(STAY_KEY, PersistentDataType.STRING)) {
            pdc.set(STAY_KEY, PersistentDataType.STRING,
                    location.getWorld().getName() + ";" + location.getX() + ";" + location.getY() + ";" + location.getZ());
        }
        // Pause the natural despawn timer of an uncontracted travelling mercenary while captured.
        if (capturedAt > 0 && pdc.has(EXPIRES_KEY, PersistentDataType.LONG)) {
            Long expiry = pdc.get(EXPIRES_KEY, PersistentDataType.LONG);
            if (expiry != null && expiry > 0) {
                long duration = Math.max(0L, System.currentTimeMillis() - capturedAt);
                pdc.set(EXPIRES_KEY, PersistentDataType.LONG,
                        Long.MAX_VALUE - expiry < duration ? Long.MAX_VALUE : expiry + duration);
            }
        }
    }

    void registerRestored(Entity body) throws ReflectiveOperationException {
        if (!isMercenaryBody(body)) throw new IllegalStateException("Restored mannequin is missing Mercenary PDC");
        invoke(onEntityLoad, manager, body);
        Object merc = invoke(getByBody, manager, body);
        if (merc == null) throw new IllegalStateException("Restored mercenary was not registered");
        invoke(registryUpdate, registry, merc);
        invoke(registrySave, registry);
    }

    void rollbackRestored(Entity body) {
        Object merc = invoke(getByBody, manager, body);
        if (merc != null) invoke(remove, manager, merc, true);
        else if (body.isInWorld()) body.remove();
        invoke(registrySave, registry);
    }
}

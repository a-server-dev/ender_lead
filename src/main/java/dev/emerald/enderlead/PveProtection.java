package dev.emerald.enderlead;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * pipi_pve-0.1.0 compatibility without linking to its classes or modifying its JAR.
 *
 * Wave mobs have keys such as pipi_pve:tier. Other combat entities (summons,
 * mounts, auxiliaries, and bosses) may only be registered in PipiPve.runs,
 * so we consult the live Run UUID collections as an additional safeguard.
 * Unrelated vanilla mobs of the same species are not excluded.
 */
final class PveProtection {
    private static final String PVE_NAMESPACE = "pipi_pve";
    private static boolean warnedReflectionFailure;

    private PveProtection() { }

    static boolean isPveEntity(Entity entity) {
        if (entity == null) return false;
        for (NamespacedKey key : entity.getPersistentDataContainer().getKeys()) {
            if (PVE_NAMESPACE.equals(key.getNamespace())) return true;
        }

        Plugin pve = Bukkit.getPluginManager().getPlugin(PVE_NAMESPACE);
        if (pve == null || !pve.isEnabled()) return false;

        try {
            Field runsField = pve.getClass().getDeclaredField("runs");
            runsField.setAccessible(true);
            Object value = runsField.get(pve);
            if (!(value instanceof Map<?, ?> runs))
                throw new IllegalStateException("PipiPve.runs is not a Map");
            UUID entityId = entity.getUniqueId();
            for (Object run : runs.values()) {
                if (run == null) continue;
                // Inspect Run's UUID membership collections rather than names/types.
                // Includes alive, spawnedEntities, auxiliaryEntities, waveBosses,
                // mountsByRider, and additional combat bookkeeping maps.
                for (Field field : run.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    Class<?> type = field.getType();
                    if (!Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)) continue;
                    field.setAccessible(true);
                    Object stored = field.get(run);
                    if (stored instanceof Collection<?> collection && collection.contains(entityId)) return true;
                    if (stored instanceof Map<?, ?> map
                            && (map.containsKey(entityId) || map.containsValue(entityId))) return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Compatibility failure must not allow capture of a PvE encounter.
            // Warn once instead of spamming the log on every click.
            if (!warnedReflectionFailure) {
                warnedReflectionFailure = true;
                Bukkit.getLogger().warning("[EnderLead] pipi_pve run detection failed; "
                        + "preventing capture while it is running: " + error);
            }
            return true;
        }
    }
}

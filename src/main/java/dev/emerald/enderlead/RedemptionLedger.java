package dev.emerald.enderlead;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Durable one-shot redemption records for cloned or duplicated filled leads. */
final class RedemptionLedger {
    private final Path file;
    private final Set<UUID> released = new HashSet<>();

    RedemptionLedger(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        file = dataDirectory.resolve("released-ids.txt");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    try {
                        released.add(UUID.fromString(line.trim()));
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException("Invalid UUID in release ledger: " + line, invalid);
                    }
                }
            }
        }
    }

    boolean isReleased(UUID id) {
        return released.contains(id);
    }

    /** Returns true only after the new ledger content has been persisted to disk. */
    void markReleased(UUID id) throws IOException {
        if (released.contains(id)) {
            throw new IllegalStateException("Already released: " + id);
        }
        Set<UUID> next = new HashSet<>(released);
        next.add(id);
        Path temp = Files.createTempFile(file.getParent(), "redeemed-", ".tmp");
        boolean moved = false;
        try {
            StringBuilder content = new StringBuilder(next.size() * 37);
            for (UUID uuid : next) {
                content.append(uuid).append('\n');
            }
            Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            released.add(id);
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }
}

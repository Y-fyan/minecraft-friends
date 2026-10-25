package io.github.yfyan.minecraftfriends.companion;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CompanionData extends SavedData {
    public static final String FILE_NAME = "minecraft_friends_companions";
    private static final int CURRENT_SCHEMA = 1;
    public static final Factory<CompanionData> FACTORY = new Factory<>(
            CompanionData::new,
            CompanionData::load,
            null
    );

    private final Map<UUID, Entry> byOwner = new HashMap<>();

    public static CompanionData load(CompoundTag tag, HolderLookup.Provider registries) {
        CompanionData data = new CompanionData();
        ListTag entries = tag.getList("companions", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag stored = entries.getCompound(index);
            try {
                CompanionIdentity identity = new CompanionIdentity(
                        stored.getUUID("companion"),
                        stored.getUUID("owner"),
                        stored.getString("name")
                );
                data.byOwner.put(identity.ownerId(), new Entry(identity, stored.getBoolean("active")));
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Ignore malformed records; never create an entity from incomplete ownership data.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", CURRENT_SCHEMA);
        ListTag entries = new ListTag();
        for (Entry entry : byOwner.values()) {
            CompoundTag stored = new CompoundTag();
            stored.putUUID("companion", entry.identity().companionId());
            stored.putUUID("owner", entry.identity().ownerId());
            stored.putString("name", entry.identity().name());
            stored.putBoolean("active", entry.active());
            entries.add(stored);
        }
        tag.put("companions", entries);
        return tag;
    }

    public Optional<Entry> find(UUID ownerId) {
        return Optional.ofNullable(byOwner.get(ownerId));
    }

    public Optional<Entry> findByCompanion(UUID companionId) {
        return byOwner.values().stream()
                .filter(entry -> entry.identity().companionId().equals(companionId))
                .findFirst();
    }

    public Collection<Entry> entries() {
        return java.util.List.copyOf(byOwner.values());
    }

    public Entry create(CompanionIdentity identity) {
        Entry existing = byOwner.get(identity.ownerId());
        if (existing != null) {
            return existing;
        }
        Entry created = new Entry(identity, true);
        byOwner.put(identity.ownerId(), created);
        setDirty();
        return created;
    }

    public Entry setActive(UUID ownerId, boolean active) {
        Entry current = byOwner.get(ownerId);
        if (current == null) {
            throw new IllegalArgumentException("No companion is registered for owner " + ownerId);
        }
        if (current.active() != active) {
            Entry updated = new Entry(current.identity(), active);
            byOwner.put(ownerId, updated);
            setDirty();
            return updated;
        }
        return current;
    }

    public record Entry(CompanionIdentity identity, boolean active) {
    }
}

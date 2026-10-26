package io.github.yfyan.minecraftfriends.companion;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        if (!tag.contains("schemaVersion", Tag.TAG_INT)) {
            throw new IllegalStateException("Companion data is missing its schema version; refusing to restore records.");
        }
        int schemaVersion = tag.getInt("schemaVersion");
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalStateException("Unsupported companion data schema " + schemaVersion
                    + "; expected " + CURRENT_SCHEMA + ". Refusing to restore records.");
        }
        if (!tag.contains("companions", Tag.TAG_LIST)) {
            throw new IllegalStateException("Companion data has no valid records list; refusing to restore records.");
        }
        Tag rawEntries = tag.get("companions");
        if (!(rawEntries instanceof ListTag entries)) {
            throw new IllegalStateException("Companion data records have an invalid type; refusing to restore records.");
        }
        byte elementType = entries.getElementType();
        if (elementType != Tag.TAG_COMPOUND
                && !(entries.isEmpty() && elementType == Tag.TAG_END)) {
            throw new IllegalStateException("Companion data records have an invalid type; refusing to restore records.");
        }

        CompanionData data = new CompanionData();
        Set<UUID> allIdentityIds = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag stored = entries.getCompound(index);
            if (!stored.hasUUID("companion") || !stored.hasUUID("owner")
                    || !stored.contains("name", Tag.TAG_STRING)
                    || !stored.contains("active", Tag.TAG_BYTE)
                    || (stored.getByte("active") != 0 && stored.getByte("active") != 1)) {
                throw invalidRecord(index);
            }

            CompanionIdentity identity;
            try {
                identity = new CompanionIdentity(
                        stored.getUUID("companion"),
                        stored.getUUID("owner"),
                        stored.getString("name")
                );
            } catch (RuntimeException malformed) {
                throw invalidRecord(index);
            }

            String normalizedName = normalizeName(identity.name());
            if (data.byOwner.containsKey(identity.ownerId())
                    || !allIdentityIds.add(identity.ownerId())
                    || !allIdentityIds.add(identity.companionId())
                    || !names.add(normalizedName)) {
                throw invalidRecord(index);
            }
            data.byOwner.put(identity.ownerId(), new Entry(identity, stored.getBoolean("active")));
        }
        return data;
    }

    private static IllegalStateException invalidRecord(int index) {
        return new IllegalStateException("Companion data record " + index
                + " is malformed or duplicated; refusing to restore records.");
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
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

    public boolean hasName(String name) {
        String normalized = normalizeName(name);
        return byOwner.values().stream()
                .anyMatch(entry -> normalizeName(entry.identity().name()).equals(normalized));
    }

    public Collection<Entry> entries() {
        return java.util.List.copyOf(byOwner.values());
    }

    public Entry create(CompanionIdentity identity) {
        Entry existing = byOwner.get(identity.ownerId());
        if (existing != null) {
            return existing;
        }
        for (Entry entry : byOwner.values()) {
            CompanionIdentity registered = entry.identity();
            if (registered.companionId().equals(identity.ownerId())
                    || registered.ownerId().equals(identity.companionId())) {
                throw new IllegalArgumentException("Companion identity conflicts with an existing owner or companion.");
            }
            if (registered.companionId().equals(identity.companionId())) {
                throw new IllegalArgumentException("Companion UUID is already registered.");
            }
            if (normalizeName(registered.name()).equals(normalizeName(identity.name()))) {
                throw new IllegalArgumentException("That companion name is already registered.");
            }
        }
        Entry created = new Entry(identity, true);
        byOwner.put(identity.ownerId(), created);
        setDirty();
        return created;
    }

    public Entry setActive(UUID ownerId, boolean active) {
        Entry current = byOwner.get(ownerId);
        if (current == null) {
            throw new IllegalArgumentException("No companion is registered for this owner.");
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

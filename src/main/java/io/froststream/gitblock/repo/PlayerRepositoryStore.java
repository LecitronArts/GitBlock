package io.froststream.gitblock.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerRepositoryStore {
    private static final String ROOT_PLAYERS = "players";
    private static final String KEY_REPOSITORIES = "repositories";
    private static final String KEY_ACTIVE_REPOSITORY = "active-repository";
    private static final String KEY_COMMIT_MESSAGE_TEMPLATE = "commit-message-template";

    private final JavaPlugin plugin;
    private final Path storeFile;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Map<String, UUID> ownerByRepository = new HashMap<>();

    public PlayerRepositoryStore(JavaPlugin plugin, Path storeFile) {
        this.plugin = plugin;
        this.storeFile = storeFile;
        load();
    }

    public synchronized List<String> listOwnedRepositories(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        if (profile == null) {
            return List.of();
        }
        return List.copyOf(profile.repositories);
    }

    public synchronized Set<String> allKnownRepositories() {
        return Set.copyOf(ownerByRepository.keySet());
    }

    public synchronized int ownedRepositoryCount(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        return profile == null ? 0 : profile.repositories.size();
    }

    public synchronized boolean repositoryExists(String repositoryName) {
        return ownerByRepository.containsKey(normalizeRepositoryName(repositoryName));
    }

    public synchronized UUID repositoryOwner(String repositoryName) {
        return ownerByRepository.get(normalizeRepositoryName(repositoryName));
    }

    public synchronized boolean isRepositoryOwner(UUID playerId, String repositoryName) {
        return playerId != null && playerId.equals(repositoryOwner(repositoryName));
    }

    public synchronized String activeRepository(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        if (profile == null || profile.repositories.isEmpty()) {
            return null;
        }
        if (profile.activeRepository != null && profile.repositories.contains(profile.activeRepository)) {
            return profile.activeRepository;
        }
        return profile.repositories.iterator().next();
    }

    public synchronized boolean setActiveRepository(UUID playerId, String repositoryName) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        PlayerProfile profile = profiles.get(playerId);
        if (profile == null || !profile.repositories.contains(normalizedRepositoryName)) {
            return false;
        }
        profile.activeRepository = normalizedRepositoryName;
        save();
        return true;
    }

    public synchronized CreateRepositoryResult createRepository(UUID playerId, String repositoryName) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        UUID existingOwner = ownerByRepository.get(normalizedRepositoryName);
        if (existingOwner != null) {
            if (existingOwner.equals(playerId)) {
                return CreateRepositoryResult.ALREADY_OWNED;
            }
            return CreateRepositoryResult.NAME_TAKEN;
        }
        PlayerProfile profile = profiles.computeIfAbsent(playerId, ignored -> new PlayerProfile());
        profile.repositories.add(normalizedRepositoryName);
        if (profile.activeRepository == null || profile.activeRepository.isBlank()) {
            profile.activeRepository = normalizedRepositoryName;
        }
        ownerByRepository.put(normalizedRepositoryName, playerId);
        save();
        return CreateRepositoryResult.CREATED;
    }

    public synchronized String ensurePersonalDefaultRepository(UUID playerId, String preferredBaseName) {
        PlayerProfile profile = profiles.computeIfAbsent(playerId, ignored -> new PlayerProfile());
        if (!profile.repositories.isEmpty()) {
            if (profile.activeRepository == null || !profile.repositories.contains(profile.activeRepository)) {
                profile.activeRepository = profile.repositories.iterator().next();
                save();
            }
            return profile.activeRepository;
        }

        String normalizedBase = normalizeRepositoryName(preferredBaseName);
        if (normalizedBase.isBlank()) {
            normalizedBase = "repo";
        }
        String candidate = normalizedBase;
        int suffix = 1;
        while (ownerByRepository.containsKey(candidate)) {
            candidate = normalizedBase + "-" + suffix;
            suffix++;
        }
        profile.repositories.add(candidate);
        profile.activeRepository = candidate;
        ownerByRepository.put(candidate, playerId);
        save();
        return candidate;
    }

    public synchronized String commitMessageTemplate(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        if (profile == null) {
            return null;
        }
        return profile.commitMessageTemplate;
    }

    public synchronized void setCommitMessageTemplate(UUID playerId, String template) {
        PlayerProfile profile = profiles.computeIfAbsent(playerId, ignored -> new PlayerProfile());
        profile.commitMessageTemplate = template == null ? null : template.trim();
        save();
    }

    public synchronized void clearCommitMessageTemplate(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        if (profile == null) {
            return;
        }
        profile.commitMessageTemplate = null;
        save();
    }

    private void load() {
        profiles.clear();
        ownerByRepository.clear();

        if (!Files.exists(storeFile)) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storeFile.toFile());
        ConfigurationSection playersSection = yaml.getConfigurationSection(ROOT_PLAYERS);
        if (playersSection == null) {
            return;
        }

        for (String rawPlayerId : playersSection.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(rawPlayerId);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection profileSection = playersSection.getConfigurationSection(rawPlayerId);
            if (profileSection == null) {
                continue;
            }

            PlayerProfile profile = new PlayerProfile();
            List<String> repositories = profileSection.getStringList(KEY_REPOSITORIES);
            for (String repository : repositories) {
                String normalizedRepositoryName = normalizeRepositoryName(repository);
                if (normalizedRepositoryName.isBlank()) {
                    continue;
                }
                if (ownerByRepository.containsKey(normalizedRepositoryName)) {
                    plugin.getLogger()
                            .warning(
                                    "Repository name collision in player store: "
                                            + normalizedRepositoryName
                                            + " (keeping first owner)");
                    continue;
                }
                profile.repositories.add(normalizedRepositoryName);
                ownerByRepository.put(normalizedRepositoryName, playerId);
            }

            String activeRepository =
                    normalizeRepositoryName(profileSection.getString(KEY_ACTIVE_REPOSITORY, ""));
            if (!activeRepository.isBlank() && profile.repositories.contains(activeRepository)) {
                profile.activeRepository = activeRepository;
            } else if (!profile.repositories.isEmpty()) {
                profile.activeRepository = profile.repositories.iterator().next();
            }

            String messageTemplate = profileSection.getString(KEY_COMMIT_MESSAGE_TEMPLATE, "");
            if (messageTemplate != null && !messageTemplate.isBlank()) {
                profile.commitMessageTemplate = messageTemplate.trim();
            }

            if (!profile.repositories.isEmpty()
                    || (profile.commitMessageTemplate != null && !profile.commitMessageTemplate.isBlank())) {
                profiles.put(playerId, profile);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerProfile> entry : profiles.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerProfile profile = entry.getValue();
            boolean hasRepositories = !profile.repositories.isEmpty();
            boolean hasTemplate =
                    profile.commitMessageTemplate != null && !profile.commitMessageTemplate.isBlank();
            if (!hasRepositories && !hasTemplate) {
                continue;
            }
            String base = ROOT_PLAYERS + "." + playerId;
            yaml.set(base + "." + KEY_REPOSITORIES, new ArrayList<>(profile.repositories));
            yaml.set(base + "." + KEY_ACTIVE_REPOSITORY, hasRepositories ? profile.activeRepository : null);
            if (profile.commitMessageTemplate == null || profile.commitMessageTemplate.isBlank()) {
                yaml.set(base + "." + KEY_COMMIT_MESSAGE_TEMPLATE, null);
            } else {
                yaml.set(base + "." + KEY_COMMIT_MESSAGE_TEMPLATE, profile.commitMessageTemplate);
            }
        }
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            yaml.save(storeFile.toFile());
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to save player repository store", ioException);
        }
    }

    private String normalizeRepositoryName(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.trim().replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (sanitized.isBlank()) {
            return "";
        }
        return sanitized.toLowerCase(Locale.ROOT);
    }

    public enum CreateRepositoryResult {
        CREATED,
        ALREADY_OWNED,
        NAME_TAKEN
    }

    private static final class PlayerProfile {
        private final LinkedHashSet<String> repositories = new LinkedHashSet<>();
        private String activeRepository;
        private String commitMessageTemplate;
    }
}

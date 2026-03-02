package io.froststream.gitblock.repo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

public final class RepositoryLimitResolver {
    private final int defaultMaxRepositories;
    private final Map<String, Integer> maxByPermission = new HashMap<>();

    public RepositoryLimitResolver(
            int defaultMaxRepositories, ConfigurationSection permissionOverridesSection) {
        this.defaultMaxRepositories = Math.max(0, defaultMaxRepositories);
        if (permissionOverridesSection == null) {
            return;
        }
        for (String key : permissionOverridesSection.getKeys(false)) {
            String permission = normalizePermissionNode(key);
            if (permission.isBlank()) {
                continue;
            }
            int limit = Math.max(0, permissionOverridesSection.getInt(key, this.defaultMaxRepositories));
            maxByPermission.put(permission, limit);
        }
    }

    public int resolveMaxRepositories(CommandSender sender) {
        if (sender == null) {
            return defaultMaxRepositories;
        }
        int resolved = defaultMaxRepositories;
        for (Map.Entry<String, Integer> entry : maxByPermission.entrySet()) {
            if (sender.hasPermission(entry.getKey())) {
                resolved = Math.max(resolved, entry.getValue());
            }
        }
        return resolved;
    }

    private static String normalizePermissionNode(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}

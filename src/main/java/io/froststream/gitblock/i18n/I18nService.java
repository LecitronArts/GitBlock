package io.froststream.gitblock.i18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class I18nService {
    private final JavaPlugin plugin;
    private final String defaultLocale;
    private final String fallbackLocale;
    private final String forcedLocale;
    private final String prefix;
    private final Map<String, Map<String, String>> bundles = new ConcurrentHashMap<>();

    public I18nService(
            JavaPlugin plugin,
            String defaultLocale,
            String fallbackLocale,
            String forcedLocale,
            String prefix) {
        this.plugin = plugin;
        this.defaultLocale = LocaleSupport.normalizeLocale(defaultLocale);
        this.fallbackLocale = LocaleSupport.normalizeLocale(fallbackLocale);
        this.forcedLocale = forcedLocale == null || forcedLocale.isBlank()
                ? null
                : LocaleSupport.normalizeLocale(forcedLocale);
        this.prefix = colorize(prefix == null ? "" : prefix);
    }

    public void load() {
        ensureDefaultBundles();
        Path langDir = plugin.getDataFolder().toPath().resolve("lang");
        try {
            Files.createDirectories(langDir);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to create lang directory", ioException);
        }

        bundles.clear();
        try (Stream<Path> paths = Files.list(langDir)) {
            paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .forEach(this::loadBundleFile);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to load language files", ioException);
        }

        if (bundles.isEmpty()) {
            throw new IllegalStateException("No language bundles were loaded.");
        }
    }

    public String tr(CommandSender sender, String key, Object... args) {
        return prefix + trRaw(sender, key, args);
    }

    public String trRaw(CommandSender sender, String key, Object... args) {
        String locale = resolveSenderLocale(sender);
        String template = resolveTemplate(locale, key);
        return LocaleSupport.formatTemplate(colorize(template), args);
    }

    public static String normalizeLocale(String raw) {
        return LocaleSupport.normalizeLocale(raw);
    }

    private void ensureDefaultBundles() {
        saveResourceIfMissing("lang/en_us.yml");
        saveResourceIfMissing("lang/zh_cn.yml");
    }

    private void saveResourceIfMissing(String resourcePath) {
        Path target = plugin.getDataFolder().toPath().resolve(resourcePath);
        if (Files.exists(target)) {
            return;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to prepare i18n resource path " + resourcePath, ioException);
        }
        plugin.saveResource(resourcePath, false);
    }

    private void loadBundleFile(Path path) {
        String fileName = path.getFileName().toString();
        String locale = LocaleSupport.normalizeLocale(fileName.substring(0, fileName.length() - 4));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
        Map<String, String> map = new ConcurrentHashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) {
                map.put(key, yaml.getString(key, ""));
            }
        }
        bundles.put(locale, map);
    }

    private String resolveSenderLocale(CommandSender sender) {
        if (forcedLocale != null) {
            return selectClosestLocale(forcedLocale);
        }
        if (sender instanceof Player player) {
            return selectClosestLocale(LocaleSupport.normalizeLocale(player.getLocale()));
        }
        return selectClosestLocale(defaultLocale);
    }

    private String resolveTemplate(String locale, String key) {
        String value = lookup(locale, key);
        if (value != null) {
            return value;
        }

        value = lookup(defaultLocale, key);
        if (value != null) {
            return value;
        }

        value = lookup(fallbackLocale, key);
        if (value != null) {
            return value;
        }

        return key;
    }

    private String lookup(String locale, String key) {
        Map<String, String> bundle = bundles.get(locale);
        if (bundle == null) {
            return null;
        }
        return bundle.get(key);
    }

    private String selectClosestLocale(String requestedLocale) {
        String normalized = LocaleSupport.normalizeLocale(requestedLocale);
        if (bundles.containsKey(normalized)) {
            return normalized;
        }

        int separator = normalized.indexOf('_');
        String language = separator > 0 ? normalized.substring(0, separator) : normalized;
        for (String candidate : new TreeSet<>(bundles.keySet())) {
            if (candidate.equals(language) || candidate.startsWith(language + "_")) {
                return candidate;
            }
        }

        if (bundles.containsKey(defaultLocale)) {
            return defaultLocale;
        }
        if (bundles.containsKey(fallbackLocale)) {
            return fallbackLocale;
        }
        return bundles.keySet().stream().findFirst().orElse("en_us");
    }

    private static String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}

package io.froststream.untitled8.plotgit.i18n;

import java.util.Locale;

final class LocaleSupport {
    private LocaleSupport() {}

    static String normalizeLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en_us";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    static String formatTemplate(String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        String formatted = template;
        for (int i = 0; i < args.length; i++) {
            formatted = formatted.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return formatted;
    }
}

package cn.folotoy.passportnotify;

import java.util.Locale;

final class CatalogSearch {
    private CatalogSearch() { }

    static boolean matches(String query, String... fields) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) return true;

        StringBuilder searchable = new StringBuilder();
        if (fields != null) {
            for (String field : fields) {
                String normalizedField = normalize(field);
                if (!normalizedField.isEmpty()) searchable.append(' ').append(normalizedField);
            }
        }
        String haystack = searchable.toString();
        for (String token : normalizedQuery.split("\\s+")) {
            if (!haystack.contains(token)) return false;
        }
        return true;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

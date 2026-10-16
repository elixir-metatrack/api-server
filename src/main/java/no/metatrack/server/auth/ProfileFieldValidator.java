package no.metatrack.server.auth;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProfileFieldValidator {
    private static final Set<String> COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));

    private ProfileFieldValidator() {}

    public static UserProfilePatch normalize(UserProfilePatch patch) {
        Map<String, String> normalized = new LinkedHashMap<>();
        patch.fields().forEach((field, raw) -> normalized.put(field, normalize(field, raw)));
        return new UserProfilePatch(normalized);
    }

    private static String normalize(String field, String raw) {
        String value = raw == null ? null : raw.strip();
        if (value == null || value.isBlank()) {
            return null;
        }
        switch (field) {
            case "firstName", "lastName", "institution" -> {
                int limit = field.equals("institution") ? 512 : 255;
                if (value.length() > limit) {
                    throw UserProfilePatch.invalid(field, "Maximum length is " + limit);
                }
            }
            case "country" -> {
                if (!COUNTRIES.contains(value)) {
                    throw UserProfilePatch.invalid(field, "Use an uppercase ISO 3166-1 alpha-2 code");
                }
            }
            case "orcid" -> {
                if (value.startsWith("https://orcid.org/")) {
                    value = value.substring("https://orcid.org/".length());
                }
                if (!value.matches("[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]")) {
                    throw UserProfilePatch.invalid(field, "Use a checksum-valid ORCID identifier or canonical URL");
                }
                String digits = value.replace("-", "");
                int total = 0;
                for (int i = 0; i < 15; i++) {
                    total = (total + digits.charAt(i) - '0') * 2;
                }
                int check = (12 - total % 11) % 11;
                char expected = check == 10 ? 'X' : (char) ('0' + check);
                if (digits.charAt(15) != expected) {
                    throw UserProfilePatch.invalid(field, "Invalid ORCID checksum");
                }
            }
            default -> throw UserProfilePatch.invalid("profile", "Only editable profile fields are accepted");
        }
        return value;
    }
}
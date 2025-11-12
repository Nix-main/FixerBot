package amber.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static amber.io.FixerBot.jsons;

/**
 * Everything in this class is public to show on javadocs.
 * Various utilities for calculating nearest mod from input and finding important values
 */
public class BotUtils {

    /**
     * Returns the Levenshtein distance of two strings so long as it's below a given max value.
     * @param first  The first string, always longer.
     * @param second The second string, always shorter.
     * @param max    A limit on the distance. If the method exceeds this during calculation, it'll stop for efficiency.
     * @return The Levenshtein distance of the two input strings.
     */
    public static int calculateDistance(String first, String second, int max) {
        int lenA = first.length();
        int lenB = second.length();

        if (lenA < lenB) return calculateDistance(second, first, max);
        if (lenB == 0) return Math.min(lenA, max + 1);

        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];

        for (int j = 0; j <= lenB; j++) prev[j] = j;

        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            int minJ = 1;

            for (int j = minJ; j <= lenB; j++) {
                int cost = (first.charAt(i - 1) == second.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }

            if (Arrays.stream(curr).min().orElse(max + 1) > max) return max + 1;
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[lenB];
    }

    /**
     * Used to fetch all important values of a given mod from its name.
     * @param input The mod name to find values for.
     * @return A string[] of the values in this order: downloadUrl, description, dependencies, deprecated, author, icon, latest version, name, thunderstore page, website
     * Should probably not be a hardcoded array like this (perhaps a class or record for ModInfo), but it doesn't really matter.
     */
    public static String[] getAllValues(String input) {
        String title = FixerBot.clean(input);
        JsonObject pkg = findByTitle(title);
        if (pkg == null) return null;

        String author = Objects.toString(ModFetcher.first(pkg, "owner", "namespace", "author"), "");
        String description = Objects.toString(ModFetcher.first(pkg, "description"), "");
        String icon = Objects.toString(ModFetcher.first(pkg, "icon"), "");

        String downloadUrl = "";
        String deprecated = "";
        String dependenciesCsv = "";
        String latestVersion = "";
        String name = "";
        String page = "";
        String website = "";

        final JsonArray versions = pkg.has("versions") && pkg.get("versions").isJsonArray() ? pkg.getAsJsonArray("versions") : null;
        if (versions == null || versions.isEmpty()) {
            return null;
        }

        JsonObject chosenVersion = versions.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(v -> !v.has("is_active") || v.get("is_active").getAsBoolean())
                .max(Comparator.comparing(c -> c.has("date_created") && !c.get("date_created").isJsonNull() ? OffsetDateTime.parse(c.get("date_created").getAsString()) : OffsetDateTime.MIN))
                .orElseGet(() -> versions.get(versions.size() - 1).getAsJsonObject());

        if (chosenVersion != null) {
            downloadUrl = ModFetcher.first(chosenVersion, "download_url", "package_url", "website_url");
            description = Objects.toString(ModFetcher.first(chosenVersion, "description"), description);
            icon = Objects.toString(ModFetcher.first(chosenVersion, "icon"), icon);
            latestVersion = ModFetcher.first(chosenVersion, "version_number", "name");
            name = ModFetcher.first(chosenVersion, "name");
            page = pkg.has("package_url") ? pkg.get("package_url").getAsString() : "";
            website = ModFetcher.first(chosenVersion, "website_url");
            deprecated = pkg.has("is_deprecated") ? pkg.get("is_deprecated").getAsString() : "";
            if (chosenVersion.has("dependencies") && chosenVersion.get("dependencies").isJsonArray()) {
                dependenciesCsv = StreamSupport.stream(chosenVersion.getAsJsonArray("dependencies").spliterator(), false)
                        .filter(JsonElement::isJsonPrimitive)
                        .map(JsonElement::getAsString)
                        .filter(s -> !s.contains("BepInEx-BepInExPack"))
                        .collect(Collectors.joining(","));
            }
        }
        return new String[]{
                downloadUrl, description, dependenciesCsv, deprecated,
                author, icon, latestVersion, name, page, website
        };
    }

    /**
     * Finds the JsonObject of a mod in the json from its name.
     * @param title The name of the mod.
     * @return The JsonObject of the mod containing the needed values.
     */
    public static JsonObject findByTitle(String title) {
        if (title == null) return null;
        String want = title.trim().toLowerCase();
        String normWant = FixerBot.clean(title);

        for (JsonObject m : jsons) {
            String t = ModFetcher.first(m, "name");
            if (t != null && t.trim().toLowerCase().equals(want)) return m;
            if (m.has("name") && !m.get("name").isJsonNull()) {
                if (FixerBot.clean(m.get("name").getAsString()).equals(normWant)) return m;
            }
            if (fieldContains(m, "name", want) || fieldContains(m, "description", want)) return m;
        }

        return null;
    }

    /**
     * Safe method to check if a given field of a JsonObject contains a given value.
     * @param o         The JsonObject to check.
     * @param key       The field of that object to check.
     * @param wantLower The value wanted.
     * @return Whether the field contains that value. Returns false if the object doesn't have that field or if the object is null.
     */
    public static boolean fieldContains(JsonObject o, String key, String wantLower) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsString().toLowerCase().contains(wantLower);
    }

    /**
     * Gets the mod package in the json with a name closest to the given input.
     * @param input The input string to compare against package names.
     * @return The closest package found, null if none is found or if the distance is too high.
     */
    public static ClosestPackage getClosestPackage(String input) {
        if (input == null || jsons == null || jsons.isEmpty()) return null;

        String want = input.trim().toLowerCase();
        String wantClean = want.replaceAll("[^a-z0-9]", "");
        if (wantClean.isEmpty()) return null;

        JsonObject bestPkg = null;
        String candidate = null;
        int bestDist = Integer.MAX_VALUE;

        for (JsonObject m : jsons) {
            for (String candidateRaw : getKeys(m)) {
                if (candidateRaw == null) continue;
                String cd = candidateRaw.trim().toLowerCase();
                if (cd.isEmpty()) continue;

                int d = calculateDistance(want, cd, bestDist);
                if (d < bestDist || (d == bestDist && cd.length() < (candidate == null ? Integer.MAX_VALUE : candidate.length()))) {
                    bestDist = d;
                    bestPkg = m;
                    candidate = cd;
                }
            }
        }

        if (candidate == null) return null;

        if (wantClean.length() == 1) {
            String[] candTokens = candidate.split("[^A-Za-z0-9]+");
            for (String t : candTokens) {
                if (t.equalsIgnoreCase(wantClean)) return new ClosestPackage(bestDist, bestPkg);
            }
            return null;
        }

        String candidateClean = candidate.replaceAll("[^a-z0-9]", "");
        double normalized = (double) bestDist / Math.max(wantClean.length(), Math.max(1, candidateClean.length()));

        return (normalized <= 0.25) ? new ClosestPackage(bestDist, bestPkg) : null;
    }

    /**
     * Gets the title of an existing mod which is closest to the input string.
     * @param input The input string to compare against mod names.
     * @return The title of the closest match found.
     */
    public static ClosestTitle getClosestTitle(String input) {
        ClosestPackage pkg = getClosestPackage(input);
        if (pkg == null) return new ClosestTitle(0, "");
        for (String k : getKeys(pkg.json))
            if (k != null && !k.trim().isEmpty())
                return new ClosestTitle(pkg.distance, k);
        return new ClosestTitle(0, "");
    }

    /**
     * Gets the values of the name and full_name json keys. Used by the above two methods.
     * @param obj The JsonObject to fetch values from.
     * @return The values of name and full_name.
     */
    public static List<String> getKeys(JsonObject obj) {
        List<String> keys = new ArrayList<>();
        keys.add(ModFetcher.first(obj, "name"));
        keys.add(ModFetcher.first(obj, "full_name"));
        if (obj.has("versions") && obj.get("versions").isJsonArray() && !obj.getAsJsonArray("versions").isEmpty()) {
            JsonObject v = obj.getAsJsonArray("versions").get(0).getAsJsonObject();
            keys.add(ModFetcher.first(v, "name"));
        }
        return keys;
    }

    /**
     * A record to store the closest package from the getClosestPackage method.
     * @param distance The distance from the original input to the name of the found JsonObject.
     * @param json     The JsonObject found.
     */
    public record ClosestPackage(int distance, JsonObject json) {
    }

    /**
     * A record to store the closest title from the getClosestTitle method.
     * @param distance The distance from the original input to the title.
     * @param title    The title found.
     */
    public record ClosestTitle(int distance, String title) {
    }
}
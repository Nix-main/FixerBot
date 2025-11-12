package amber.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Everything in this class is public to show on javadocs.
 * Primarily fetches the latest mod list with one other operation.
 */
public class ModFetcher {
    /**
     * The URL to pull the Thunderstore package list from.
     */
    public static final String Url = "https://thunderstore.io/c/hollow-knight-silksong/api/v1/package-listing-index/";
    /**
     * The list to fall back on if an error occurs in getAllMods.
     */
    public static List<JsonObject> fallbackList = new ArrayList<>();

    /**
     * Refreshes the cache by calling Thunderstore api. Returns the previous cache if an error occurs.
     *
     * @return The list of mods found from the api.
     */
    public static List<JsonObject> getAllMods() {
        List<JsonObject> all = new ArrayList<>();
        String content;
        try (GZIPInputStream gzip = new GZIPInputStream(URL.of(URI.create(Url), null).openStream());
             GZIPInputStream gzip1 = new GZIPInputStream(URL.of(URI.create(new String(gzip.readAllBytes()).split("\"")[1]), null).openStream())
        ) {
            content = new String(gzip1.readAllBytes());
        } catch (IOException e) {
            e.printStackTrace();
            return fallbackList;
        }

        JsonArray items = JsonParser.parseString(content).getAsJsonArray();
        for (JsonElement el : items)
            all.add(el.getAsJsonObject());

        fallbackList = all;
        return all;
    }

    /**
     * Takes in a list of json keys and returns the value of the first one that exists.
     *
     * @param o    The JsonObject to use when checking existence of the given keys.
     * @param keys The keys to check for/return.
     * @return The first found value, an empty string if none are found.
     */
    public static String first(JsonObject o, String... keys) {
        for (String k : keys) if (o.has(k) && !o.get(k).isJsonNull()) return o.get(k).getAsString();
        return "";
    }
}

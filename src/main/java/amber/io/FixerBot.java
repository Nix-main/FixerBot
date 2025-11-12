package amber.io;

import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything in this class is public to show on javadocs.
 * The primary class which creates the bot and performs other important tasks.
 */
public class FixerBot {
    /**
     * An slf4j logger for the purpose of outputting messages.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger("FixerBot");
    /**
     * A ScheduledExecutorService for periodic cache refreshes.
     */
    public static final ScheduledExecutorService SCHEDULED = Executors.newScheduledThreadPool(1);
    /**
     * A worker for handling received messages asynchronously.
     */
    public static final ExecutorService WORKER = Executors.newCachedThreadPool();
    /**
     * The pattern to match in order to provide a response. Matches {{[\w ]+}}, which matches two opening braces, then any amount of characters that are either
     * alphanumeric (a-zA-Z0-9), an underscore, or a space. Finally, matches two closing braces.
     */
    public static final Pattern PATTERN = Pattern.compile("\\{\\{([\\w ]+)}}");
    /**
     * The cache of mods.
     */
    public static volatile List<JsonObject> jsons = Collections.emptyList();

    /**
     * Creates the bot, adds the listener.
     */
    public static void main(String[] args) {
        JDA bot = BotInit.createBot();
        bot.addEventListener(new MessageListener());
        addSchedule();
    }

    // Could be called within main but separated for javadoc purposes.

    /**
     * Schedules a task on the ScheduledExecutorService to update the mod cache and log the timestamp of the action.
     */
    public static void addSchedule() {
        SCHEDULED.scheduleWithFixedDelay(() -> {
            List<JsonObject> list = ModFetcher.getAllMods();
            jsons = List.copyOf(list);
            LOGGER.info("Fetching all mods at [{}]", DateTimeFormatter.ofPattern("yyyy-MM-dd, HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.now()));
        }, 0, 30, TimeUnit.MINUTES);
    }

    /**
     * Creates the proper embed with all values from BotUtils.getAllValues(modName).
     *
     * @param modName The mod name to pass to methods like BotUtils.getAllValues.
     * @return The fully constructed embed or the value of createNotFoundEmbed if getAllValues returns null.
     */
    public static MessageEmbed createEmbedFromModName(String modName) {
        String[] vals = BotUtils.getAllValues(modName);
        if (vals != null) {
            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(0x7E0923);
            boolean deprecated = Boolean.parseBoolean(vals[3]);
            if (!deprecated) {
                eb.setDescription(vals[1]);
            } else {
                eb.setDescription(String.format("~~%s~~\n\nThis mod is deprecated. Alternative packages should be used whenever possible.", vals[1]));
            }
            eb.setTitle(vals[7].replaceAll("_", " ") + (deprecated ? (vals[7].endsWith(" ") ? "- " : " - ") + "Deprecated" : ""));
            eb.addField("Version", vals[6], false);
            String pageLink = "[Page](" + vals[8] + ")";
            String downloadLink = "[Download](" + vals[0] + ")";
            String siteLink = "";
            if (!vals[9].isEmpty()) {
                String site = URI.create(vals[9]).getHost();
                if (site.contains("github.com")) {
                    siteLink = "[Github](" + vals[9] + ")";
                } else {
                    siteLink = "[Website](" + vals[9] + ")";
                }
            }
            eb.addField("Links", pageLink + " | " + downloadLink + (vals[9].isEmpty() ? "" : " | " + siteLink), false);
            if (!vals[2].isEmpty()) {
                String[] depArr = vals[2].split("\\s*");
                StringBuilder sb = new StringBuilder();
                int addedDeps = 0;
                for (String d : depArr) {
                    if (d.trim().isEmpty()) continue;
                    if (addedDeps < 3) {
                        addedDeps++;
                        String[] strs = d.trim().replaceAll("_", " ").split("-");
                        if (strs.length > 2) {
                            sb.append("• ").append(strs[strs.length - 2]).append(" - ").append(strs[strs.length - 1]).append("\n");
                        } else {
                            sb.append("• ").append(strs[strs.length - 1]).append("\n");
                        }
                    }
                }
                if (addedDeps >= 3) {
                    sb.append("...");
                }
                if (!sb.isEmpty()) {
                    eb.addField("Dependencies", sb.toString().trim(), true);
                }
            }
            eb.addField("Author", vals[4].isEmpty() ? "Unknown" : vals[4].replaceAll("_", " "), false);
            eb.setThumbnail(vals[5]);
            return eb.build();
        }
        return createNotFoundEmbed(modName);
    }

    /**
     * Returns either createEmbedFromModName's value for a close match, an embed with a suggestion for a closer match, or an embed with no suggestion with no match.
     *
     * @param modName The name to create the embed with, used when finding the closest match and when constructing the 'not found' embed.
     * @return If a mod with a name a Levenshtein distance of 2 or less is found, this method returns the value of createEmbedFromModName for that mod. If a mod with a normalized distance less than or equal to 0.25 but total distance greater than 2
     * is found, this returns an embed with a suggestion for that mod. If neither of the previous conditions are met, this method returns an embed simply saying "Could not find a mod named {modName}".
     */
    public static MessageEmbed createNotFoundEmbed(String modName) {

        BotUtils.ClosestTitle closest = BotUtils.getClosestTitle(modName);
        if (!closest.title().isEmpty() && closest.distance() <= 2) {
            return createEmbedFromModName(closest.title());
        }
        return new EmbedBuilder()
                .setColor(0x7E0923)
                .setTitle("Mod Not Found")
                .setDescription("Could not find a mod named " + modName + "." +
                        (closest.title().isEmpty() ? "" : " Did you mean " + closest.title().replaceAll("_", " ") + "?"))
                .build();
    }

    /**
     * Checks if a mod exists by its name.
     *
     * @param raw The name of the mod to check for.
     * @return Whether the mod exists.
     */
    public static boolean exists(String raw) {
        String want = clean(raw);
        if (want.isEmpty()) return false;

        for (JsonObject m : jsons) {
            if (clean(m.get("name").getAsString()).equals(want) || clean(m.get("full_name").getAsString()).equals(want))
                return true;

            if (m.has("full_name") && !m.get("full_name").isJsonNull()) {
                String full = m.get("full_name").getAsString();
                int dash = full.indexOf('-');
                if (dash >= 0) {
                    String suffix = clean(full.substring(dash + 1));
                    if (!suffix.isEmpty() && suffix.equals(want)) return true;
                }
            }

            if (m.has("versions") && m.get("versions").isJsonArray() && !m.getAsJsonArray("versions").isEmpty()) {
                JsonObject v = m.getAsJsonArray("versions").get(0).getAsJsonObject();
                if (clean(v.get("name").getAsString()).equals(want)) return true;
            }

            String rawName = m.has("name") && !m.get("name").isJsonNull() ? m.get("name").getAsString() : "";
            if (Arrays.stream(rawName.split("[^A-Za-z0-9]+"))
                    .map(FixerBot::clean)
                    .anyMatch(tok -> !tok.isEmpty() && tok.equals(want))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cleans/normalizes a string.
     *
     * @param s The input string.
     * @return The cleaned string, which is the input but all non-alphanumeric characters removed and converted it to lowercase.
     */
    public static String clean(String s) {
        return s.replaceAll("[^A-Za-z0-9]+", "").toLowerCase();
    }

    /**
     * A simple class to listen for messages to respond to.
     */
    public static class MessageListener extends ListenerAdapter {
        /**
         * Listens for messages and constructs one of two embeds in response asynchronously. Can also respond to {{reloadcache}} by reloading the mod cache manually.
         *
         * @param event The JDA MessageReceivedEvent instance that this method fetches values from.
         */
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            String msg = event.getMessage().getContentRaw();
            Matcher matcher = PATTERN.matcher(msg);

            while (matcher.find()) {
                String modName = matcher.group(1).trim();
                WORKER.submit(() -> {
                    if (modName.replaceAll(" ", "").equalsIgnoreCase("reloadcache")) {
                        if (event.getMember() != null) {
                            if (!event.getMember().hasPermission(Permission.MESSAGE_MANAGE)) return;
                            jsons = ModFetcher.getAllMods();
                            event.getMessage().reply("Reloaded mod cache.").mentionRepliedUser(false).queue();
                        }
                        return;
                    }
                    if (exists(modName)) {
                        event.getMessage().getChannel().sendMessageEmbeds(createEmbedFromModName(modName)).queue();
                    } else {
                        event.getMessage().getChannel().sendMessageEmbeds(createNotFoundEmbed(modName)).queue();
                    }
                });
            }
        }
    }
}
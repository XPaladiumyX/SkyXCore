package net.skyxnetwork.core.hologram;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floodgate's legacy "*" username prefix is not valid unquoted in Brigadier
 * player arguments. The Floodgate documentation requires quoting those names.
 * This listener applies that quoting automatically for player and console
 * commands, so legacy-style commands such as /tp *Name or /pay *Name keep
 * working without installing EssentialsX.
 */
public final class BedrockCommandNameFixListener implements Listener {
    // A star-prefixed Bedrock name, not already inside quotes.
    private static final Pattern STAR_NAME = Pattern.compile("(?<![\\w\"'])(\\*[A-Za-z0-9_.-]{2,})(?=\\s|$)");

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String rewritten = quoteStarNames(event.getMessage());
        if (!rewritten.equals(event.getMessage())) event.setMessage(rewritten);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        String rewritten = quoteStarNames(event.getCommand());
        if (!rewritten.equals(event.getCommand())) event.setCommand(rewritten);
    }

    private String quoteStarNames(String command) {
        Matcher matcher = STAR_NAME.matcher(command);
        StringBuffer out = new StringBuffer(command.length() + 8);
        while (matcher.find()) {
            matcher.appendReplacement(out, "\"" + Matcher.quoteReplacement(matcher.group(1)) + "\"");
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

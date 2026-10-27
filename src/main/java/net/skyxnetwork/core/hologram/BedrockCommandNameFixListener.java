package net.skyxnetwork.core.hologram;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
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
        String rewritten = rewrite(event.getMessage(), true);
        if (!rewritten.equals(event.getMessage())) event.setMessage(rewritten);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        String rewritten = rewrite(event.getCommand(), false);
        if (!rewritten.equals(event.getCommand())) event.setCommand(rewritten);
    }

    private String rewrite(String command, boolean playerCommand) {
        String withoutSlash = playerCommand && command.startsWith("/") ? command.substring(1) : command;
        int space = withoutSlash.indexOf(' ');
        String label = (space < 0 ? withoutSlash : withoutSlash.substring(0, space)).toLowerCase();
        Command bukkitCommand = Bukkit.getCommandMap().getCommand(label);
        // Legacy plugin commands receive Bukkit args with quotes kept literally.
        // They generally resolve players through Bukkit/their database themselves,
        // so only vanilla/Brigadier commands need the Floodgate name quoting.
        if (bukkitCommand instanceof PluginCommand) return command;

        Matcher matcher = STAR_NAME.matcher(command);
        StringBuffer out = new StringBuffer(command.length() + 8);
        while (matcher.find()) {
            matcher.appendReplacement(out, "\"" + Matcher.quoteReplacement(matcher.group(1)) + "\"");
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

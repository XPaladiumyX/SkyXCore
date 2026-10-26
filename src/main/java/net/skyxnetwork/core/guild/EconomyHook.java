package net.skyxnetwork.core.guild;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Thin optional Vault bridge used for guild creation, bank and deposits. */
public final class EconomyHook {

    private final Economy economy;

    public EconomyHook() {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
    }

    public boolean available() {
        return economy != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public double balance(OfflinePlayer player) {
        return economy == null ? 0 : economy.getBalance(player);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return economy != null && amount >= 0 && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        return economy != null && amount >= 0 && economy.depositPlayer(player, amount).transactionSuccess();
    }
}

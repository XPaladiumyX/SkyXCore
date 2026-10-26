package net.skyxnetwork.core.guild;

import java.util.UUID;

public final class Guild {

    public enum Role {
        LEADER,
        OFFICER,
        MEMBER;

        public static Role parse(String value) {
            try {
                return Role.valueOf(value == null ? "MEMBER" : value.toUpperCase());
            } catch (IllegalArgumentException exception) {
                return MEMBER;
            }
        }

        public boolean atLeast(Role other) {
            return ordinal() <= other.ordinal();
        }
    }

    public record Info(int id, String name, String tag, UUID leader, String description,
                       long createdAt, double bank, double power, boolean open, int maxMembers,
                       String homeWorld, Double homeX, Double homeY, Double homeZ,
                       Float homeYaw, Float homePitch) {
    }

    public record Member(int guildId, UUID uuid, String name, Role role, long joinedAt,
                         double contribution) {
    }

    public record Invite(int guildId, String guildName, UUID uuid, UUID invitedBy, long expires) {
    }
}

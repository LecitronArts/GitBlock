package io.froststream.gitblock.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;

class RepositoryLimitResolverTest {
    @Test
    void fallsBackToDefaultWhenSenderIsNull() {
        RepositoryLimitResolver resolver = new RepositoryLimitResolver(3, null);

        assertEquals(3, resolver.resolveMaxRepositories(null));
    }

    @Test
    void resolvesHighestMatchingPermissionLimit() {
        ConfigurationSection overrides = mock(ConfigurationSection.class);
        when(overrides.getKeys(false)).thenReturn(java.util.Set.of("gitblock.repo.limit.2", "GitBlock.Repo.Limit.5"));
        when(overrides.getInt("gitblock.repo.limit.2", 1)).thenReturn(2);
        when(overrides.getInt("GitBlock.Repo.Limit.5", 1)).thenReturn(5);

        RepositoryLimitResolver resolver = new RepositoryLimitResolver(1, overrides);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("gitblock.repo.limit.2")).thenReturn(true);
        when(sender.hasPermission("gitblock.repo.limit.5")).thenReturn(true);

        assertEquals(5, resolver.resolveMaxRepositories(sender));
    }

    @Test
    void clampsNegativeLimitsToZero() {
        ConfigurationSection overrides = mock(ConfigurationSection.class);
        when(overrides.getKeys(false)).thenReturn(java.util.Set.of("gitblock.repo.limit.2"));
        when(overrides.getInt("gitblock.repo.limit.2", 0)).thenReturn(-10);

        RepositoryLimitResolver resolver = new RepositoryLimitResolver(-3, overrides);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("gitblock.repo.limit.2")).thenReturn(true);

        assertEquals(0, resolver.resolveMaxRepositories(sender));
    }
}

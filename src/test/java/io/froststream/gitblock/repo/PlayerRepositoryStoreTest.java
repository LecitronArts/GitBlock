package io.froststream.gitblock.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerRepositoryStoreTest {
    @TempDir Path tempDir;

    @Test
    void createRepositoryAndTemplatePersistAcrossReload() {
        Path storeFile = tempDir.resolve("player-repositories.yml");
        UUID playerId = UUID.randomUUID();

        PlayerRepositoryStore store = new PlayerRepositoryStore(mockPlugin(), storeFile);
        PlayerRepositoryStore.CreateRepositoryResult createResult =
                store.createRepository(playerId, "My Repo 01");
        store.setCommitMessageTemplate(playerId, "commit by {player}");

        assertEquals(PlayerRepositoryStore.CreateRepositoryResult.CREATED, createResult);
        assertEquals(List.of("myrepo01"), store.listOwnedRepositories(playerId));
        assertEquals("myrepo01", store.activeRepository(playerId));

        PlayerRepositoryStore reloaded = new PlayerRepositoryStore(mockPlugin(), storeFile);
        assertEquals(List.of("myrepo01"), reloaded.listOwnedRepositories(playerId));
        assertEquals("myrepo01", reloaded.activeRepository(playerId));
        assertEquals("commit by {player}", reloaded.commitMessageTemplate(playerId));
    }

    @Test
    void commitTemplateWithoutRepositoriesStillPersists() {
        Path storeFile = tempDir.resolve("player-repositories.yml");
        UUID playerId = UUID.randomUUID();

        PlayerRepositoryStore store = new PlayerRepositoryStore(mockPlugin(), storeFile);
        store.setCommitMessageTemplate(playerId, "template only");

        PlayerRepositoryStore reloaded = new PlayerRepositoryStore(mockPlugin(), storeFile);
        assertEquals(0, reloaded.ownedRepositoryCount(playerId));
        assertEquals("template only", reloaded.commitMessageTemplate(playerId));
    }

    @Test
    void repositoryNameIsSanitizedWhenCreated() {
        Path storeFile = tempDir.resolve("player-repositories.yml");
        UUID playerId = UUID.randomUUID();

        PlayerRepositoryStore store = new PlayerRepositoryStore(mockPlugin(), storeFile);
        store.createRepository(playerId, "../Team Repo!!");

        assertTrue(store.repositoryExists("teamrepo"));
        assertNotNull(store.repositoryOwner("teamrepo"));
        assertEquals("teamrepo", store.activeRepository(playerId));
    }

    @Test
    void createRepositoryRejectsBlankNormalizedName() {
        Path storeFile = tempDir.resolve("player-repositories.yml");
        UUID playerId = UUID.randomUUID();

        PlayerRepositoryStore store = new PlayerRepositoryStore(mockPlugin(), storeFile);
        PlayerRepositoryStore.CreateRepositoryResult result =
                store.createRepository(playerId, "../!!!");

        assertEquals(PlayerRepositoryStore.CreateRepositoryResult.INVALID_NAME, result);
        assertEquals(0, store.ownedRepositoryCount(playerId));
        assertNull(store.activeRepository(playerId));
    }

    private static JavaPlugin mockPlugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PlayerRepositoryStoreTest"));
        return plugin;
    }
}

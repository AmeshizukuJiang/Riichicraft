package dev.riichicraft.test;

import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.lobby.LobbyRegistry.Result;
import dev.riichicraft.lobby.LobbyRegistry.Seat;
import dev.riichicraft.lobby.LobbyRegistry.Snapshot;
import dev.riichicraft.lobby.LobbyRegistry.Status;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Lobby state checks; actual packet, GUI and disconnect-event wiring need separate integration tests. */
public final class LobbyGameTests {
    @GameTest
    public void automaticJoinSerializesCompetingViewsAndRejectsAFullTable(GameTestHelper helper) {
        LobbyRegistry registry = new LobbyRegistry();
        UUID table = id(100);
        registry.createTable(table);
        helper.assertTrue(registry.join(table, id(1), "Player1", 0).status() == Status.UPDATED,
                "The first join must succeed");
        Result staleJoin = registry.join(table, id(2), "Player2", 0);
        helper.assertTrue(staleJoin.status() == Status.STALE_REVISION,
                "A competing join from the old view must refresh before taking a seat");
        helper.assertTrue(registry.tableForPlayer(id(2)).isEmpty(), "A rejected join cannot create a player index");

        for (int player = 2; player <= 4; player++) {
            Result result = registry.join(table, id(player), "Player" + player, current(registry, table).revision());
            helper.assertTrue(result.status() == Status.UPDATED, "The next available seat must be allocated");
        }
        Snapshot full = current(registry, table);
        helper.assertTrue(full.seats().size() == 4, "The snapshot must always contain four seats");
        for (int seat = 0; seat < 4; seat++) {
            helper.assertTrue(full.seats().get(seat).playerId().equals(id(seat + 1)),
                    "Automatic seats must follow east/south/west/north index order");
        }
        Result fifth = registry.join(table, id(5), "Player5", full.revision());
        helper.assertTrue(fifth.status() == Status.TABLE_FULL && !fifth.changed(),
                "A fifth player must be rejected without changing a full table");
        helper.assertTrue(current(registry, table).equals(full) && registry.tableForPlayer(id(5)).isEmpty(),
                "A full-table rejection must preserve seats, readiness, revision and the player index");
        Result repeatedJoin = registry.join(table, id(1), "Player1", full.revision());
        helper.assertTrue(repeatedJoin.status() == Status.UNCHANGED && repeatedJoin.snapshot().equals(full),
                "Joining one's existing full table must not allocate a second seat or advance its revision");
        helper.succeed();
    }

    @GameTest
    public void aPlayerCannotOccupyOrMutateAnotherTable(GameTestHelper helper) {
        LobbyRegistry registry = new LobbyRegistry();
        UUID firstTable = id(100);
        UUID secondTable = id(200);
        registry.createTable(firstTable);
        registry.createTable(secondTable);
        registry.join(firstTable, id(1), "Player1", 0);
        registry.join(secondTable, id(2), "Player2", 0);
        Snapshot firstBefore = current(registry, firstTable);
        Snapshot secondBefore = current(registry, secondTable);

        helper.assertTrue(registry.join(secondTable, id(1), "Player1", secondBefore.revision()).status()
                        == Status.ALREADY_SEATED_ELSEWHERE,
                "The server-wide player index must prevent occupying a second table");
        helper.assertTrue(registry.setReady(secondTable, id(1), true, secondBefore.revision()).status()
                        == Status.NOT_SEATED,
                "A player cannot prepare an occupant of a different table");
        helper.assertTrue(registry.leave(secondTable, id(1), secondBefore.revision()).status() == Status.NOT_SEATED,
                "A player cannot leave on behalf of an occupant of a different table");
        helper.assertTrue(current(registry, firstTable).equals(firstBefore)
                        && current(registry, secondTable).equals(secondBefore),
                "Rejected cross-table actions must preserve both lobbies");
        helper.assertTrue(registry.tableForPlayer(id(1)).orElseThrow().equals(firstTable),
                "A rejected cross-table join must keep the player's original seat");
        helper.succeed();
    }

    @GameTest
    public void readinessUsesDesiredStateAndRejectsStaleActions(GameTestHelper helper) {
        LobbyRegistry registry = new LobbyRegistry();
        UUID table = id(100);
        registry.createTable(table);
        Result joined = registry.join(table, id(1), "Player1", 0);
        Result ready = registry.setReady(table, id(1), true, joined.snapshot().revision());
        helper.assertTrue(ready.status() == Status.UPDATED && ready.snapshot().seats().getFirst().ready(),
                "Preparing a seated player must record the requested true state");
        Result repeated = registry.setReady(table, id(1), true, ready.snapshot().revision());
        helper.assertTrue(repeated.status() == Status.UNCHANGED && repeated.snapshot().equals(ready.snapshot()),
                "Repeating the desired ready state must not toggle it or advance the revision");
        helper.assertTrue(registry.setReady(table, id(1), false, joined.snapshot().revision()).status()
                        == Status.STALE_REVISION,
                "A stale ready cancellation must not mutate the current state");
        helper.assertTrue(registry.leave(table, id(1), joined.snapshot().revision()).status() == Status.STALE_REVISION,
                "A stale leave must not remove a currently seated player");
        helper.assertTrue(registry.setReady(table, id(2), true, ready.snapshot().revision()).status() == Status.NOT_SEATED,
                "An unseated player cannot modify another player's readiness");
        helper.assertTrue(current(registry, table).equals(ready.snapshot()),
                "All rejected and idempotent actions must preserve the ready snapshot");
        helper.assertTrue(registry.join(id(999), id(2), "Player2", 0).status() == Status.TABLE_NOT_FOUND
                        && registry.snapshot(id(999)).isEmpty(),
                "An action cannot implicitly create an unknown table");
        helper.succeed();
    }

    @GameTest
    public void allReadyRequiresFourOccupiedPreparedSeats(GameTestHelper helper) {
        LobbyRegistry registry = new LobbyRegistry();
        UUID table = id(100);
        registry.createTable(table);
        for (int player = 1; player <= 4; player++) {
            registry.join(table, id(player), "Player" + player, current(registry, table).revision());
        }
        for (int player = 1; player <= 4; player++) {
            Snapshot snapshot = registry.setReady(table, id(player), true, current(registry, table).revision()).snapshot();
            helper.assertTrue(snapshot.allReady() == (player == 4),
                    "The lobby can report ready only after every occupied seat is prepared");
        }
        Snapshot allReady = current(registry, table);
        Result canceled = registry.setReady(table, id(2), false, allReady.revision());
        helper.assertTrue(canceled.changed() && !canceled.snapshot().allReady(),
                "Canceling one player's preparation must immediately clear the all-ready indication");
        helper.assertTrue(canceled.snapshot().seats().stream().allMatch(Seat::occupied),
                "Canceling preparation must preserve all four seats");
        helper.succeed();
    }

    @GameTest
    public void leavingAndDisconnectingReleaseSeatsAndAllowReuse(GameTestHelper helper) {
        LobbyRegistry registry = new LobbyRegistry();
        UUID table = id(100);
        registry.createTable(table);
        for (int player = 1; player <= 4; player++) {
            registry.join(table, id(player), "Player" + player, current(registry, table).revision());
            registry.setReady(table, id(player), true, current(registry, table).revision());
        }
        Snapshot ready = current(registry, table);
        Snapshot left = registry.leave(table, id(2), ready.revision()).snapshot();
        helper.assertTrue(!left.seats().get(1).occupied() && !left.seats().get(1).ready() && !left.allReady(),
                "Leaving a prepared seat must clear its occupant, readiness and the all-ready indication");
        helper.assertTrue(registry.tableForPlayer(id(2)).isEmpty(), "Leaving must release the global player index");
        Snapshot replacement = registry.join(table, id(5), "Player5", left.revision()).snapshot();
        helper.assertTrue(replacement.seats().get(1).playerId().equals(id(5))
                        && !replacement.seats().get(1).ready(),
                "The first empty seat must be reusable without inheriting its previous readiness");
        Snapshot disconnected = registry.disconnect(id(5)).orElseThrow();
        helper.assertTrue(!disconnected.seats().get(1).occupied()
                        && disconnected.revision() == replacement.revision() + 1,
                "Disconnect cleanup must release the seat and publish one new revision");
        helper.assertTrue(registry.disconnect(id(5)).isEmpty()
                        && current(registry, table).equals(disconnected),
                "Repeated disconnect cleanup must be harmless and must not advance the revision");
        helper.assertTrue(registry.tableForPlayer(id(5)).isEmpty(), "Disconnect must release the global player index");
        helper.succeed();
    }

    @GameTest
    public void removingATableAndStoppingDoNotLeaveGlobalSeats(GameTestHelper helper) {
        LobbyRegistry registry = new LobbyRegistry();
        UUID firstTable = id(100);
        UUID secondTable = id(200);
        registry.createTable(firstTable);
        registry.createTable(secondTable);
        registry.join(firstTable, id(1), "Player1", 0);
        registry.join(firstTable, id(2), "Player2", 1);
        registry.join(secondTable, id(3), "Player3", 0);
        Snapshot secondBefore = current(registry, secondTable);
        helper.assertTrue(registry.removeTable(firstTable), "The removed table must have existed");
        helper.assertTrue(registry.snapshot(firstTable).isEmpty()
                        && registry.tableForPlayer(id(1)).isEmpty() && registry.tableForPlayer(id(2)).isEmpty(),
                "Removing a table must release every seat and corresponding global index");
        helper.assertTrue(current(registry, secondTable).equals(secondBefore),
                "Removing a table cannot change another table's seats or revision");
        helper.assertTrue(!registry.removeTable(firstTable), "Removing an absent table must be harmless");
        helper.assertTrue(registry.join(secondTable, id(1), "Player1", secondBefore.revision()).successful(),
                "A player released by table removal must be free to join a different table");
        registry.clear();
        helper.assertTrue(registry.snapshot(secondTable).isEmpty()
                        && registry.tableForPlayer(id(1)).isEmpty() && registry.tableForPlayer(id(3)).isEmpty(),
                "Server-stop cleanup must clear tables and all player indexes");
        helper.assertTrue(registry.createTable(secondTable).revision() == 0,
                "A new temporary lobby must not inherit the cleared session's state");
        helper.succeed();
    }

    @GameTest
    public void publishedSnapshotsRemainImmutableAfterLaterActions(GameTestHelper helper) {
        LobbyRegistry registry = new LobbyRegistry();
        UUID table = id(100);
        Snapshot empty = registry.createTable(table);
        Snapshot seated = registry.join(table, id(1), "Player1", 0).snapshot();
        registry.setReady(table, id(1), true, seated.revision());
        registry.disconnect(id(1));
        registry.join(table, id(2), "Player2", current(registry, table).revision());
        helper.assertTrue(!empty.seats().getFirst().occupied() && empty.revision() == 0,
                "A previously published empty snapshot must remain empty");
        helper.assertTrue(seated.seats().getFirst().playerId().equals(id(1))
                        && seated.seats().getFirst().name().equals("Player1")
                        && !seated.seats().getFirst().ready(),
                "Later readiness and seat replacement must not mutate a previously published snapshot");
        boolean modificationRejected = false;
        try {
            seated.seats().clear();
        } catch (UnsupportedOperationException expected) {
            modificationRejected = true;
        }
        helper.assertTrue(modificationRejected, "Callers must not be able to mutate a published seat list");
        helper.succeed();
    }

    private static Snapshot current(LobbyRegistry registry, UUID table) {
        return registry.snapshot(table).orElseThrow();
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}

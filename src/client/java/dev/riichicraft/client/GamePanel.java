package dev.riichicraft.client;

import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Game widgets inside the existing view, so starting or pausing never closes the view. */
final class GamePanel {
    private static final String[] WINDS = {"east", "south", "west", "north"};
    private final LobbyScreen screen;
    private final Font font;
    private final List<GameTileButton> tiles = new ArrayList<>();
    private UUID gameId;
    private int selectedTileId = -1;
    private boolean awaitingReply;
    private boolean abortArmed;
    private Button discardButton;
    private Button resumeButton;
    private Button abortButton;
    private Button cancelAbortButton;
    private int width;
    private int handLeft;
    private int handTileWidth;
    private int handGap;
    private int drawnX;

    GamePanel(LobbyScreen screen) {
        this.screen = screen;
        font = screen.getFont();
    }

    int selectedTileId() {
        return selectedTileId;
    }

    void acceptSnapshot() {
        GameSnapshot game = game();
        if (!game.gameId().equals(gameId)) {
            gameId = game.gameId();
            selectedTileId = -1;
        }
        if (!game.ownHand().contains(selectedTileId) && game.drawnTile() != selectedTileId) {
            selectedTileId = -1;
        }
        awaitingReply = false;
        abortArmed = false;
    }

    void init(int viewWidth) {
        width = viewWidth;
        tiles.clear();
        handTileWidth = width - 16 >= 366 ? 24 : 19;
        handGap = handTileWidth == 24 ? 2 : 1;
        int closedWidth = 13 * handTileWidth + 12 * handGap;
        int allWidth = closedWidth + 6 + handTileWidth;
        handLeft = (width - allWidth) / 2;
        drawnX = handLeft + closedWidth + 6;
        List<Integer> hand = game().ownHand();
        for (int index = 0; index < hand.size(); index++) {
            addTile(hand.get(index), handLeft + index * (handTileWidth + handGap));
        }
        if (game().drawnTile() >= 0) {
            addTile(game().drawnTile(), drawnX);
        }

        int left = 8;
        int available = width - 16;
        int actionWidth = (available - 8) / 3;
        int closeWidth = available - actionWidth * 2 - 8;
        discardButton = screen.addGameButton(Button.builder(text("discard"),
                button -> request(GameActionPayload.DISCARD, selectedTileId))
                .bounds(left, 192, actionWidth, 20).build());
        resumeButton = screen.addGameButton(Button.builder(text("resume"),
                button -> request(GameActionPayload.RESUME, -1))
                .bounds(left, 192, actionWidth, 20).build());
        abortButton = screen.addGameButton(Button.builder(text("abort"), button -> {
            if (abortArmed) {
                request(GameActionPayload.ABORT, -1);
            } else {
                abortArmed = true;
                updateButtons();
            }
        }).bounds(left + actionWidth + 4, 192, actionWidth, 20).build());
        cancelAbortButton = screen.addGameButton(Button.builder(text("cancel_abort"), button -> {
            abortArmed = false;
            updateButtons();
        }).bounds(left, 192, actionWidth, 20).build());
        screen.addGameButton(Button.builder(Component.translatable("screen.riichicraft.lobby.close"),
                button -> screen.onClose()).bounds(left + (actionWidth + 4) * 2, 192, closeWidth, 20).build());
        updateButtons();
    }

    private void addTile(int tileId, int x) {
        GameTileButton button = new GameTileButton(tileId, x, 154, handTileWidth,
                selected -> select(tileId));
        tiles.add(button);
        screen.addGameButton(button);
    }

    private void select(int tileId) {
        selectedTileId = tileId;
        updateButtons();
    }

    private void request(int action, int tileId) {
        if (!awaitingReply && LobbyClient.requestGame(screen.snapshot(), action, tileId)) {
            awaitingReply = true;
            abortArmed = false;
            updateButtons();
        }
    }

    private void updateButtons() {
        boolean available = !awaitingReply && LobbyClient.canSendGame();
        boolean seated = screen.snapshot().ownSeat() >= 0;
        boolean ownConfirmed = seated && game().seats().get(screen.snapshot().ownSeat()).confirmed();
        boolean allPresent = game().seats().stream().allMatch(GameSnapshot.Seat::present);
        boolean mayDiscard = available && seated && !abortArmed && !game().paused()
                && game().turnSeat() == screen.snapshot().ownSeat() && game().drawnTile() >= 0;
        for (GameTileButton tile : tiles) {
            tile.active = mayDiscard;
            tile.setSelected(tile.tileId() == selectedTileId);
        }
        if (discardButton == null) {
            return;
        }
        discardButton.visible = !game().paused() && !abortArmed;
        discardButton.active = mayDiscard && selectedTileId >= 0;
        resumeButton.visible = game().paused() && !abortArmed;
        resumeButton.active = available && seated && allPresent && !ownConfirmed;
        resumeButton.setMessage(text(ownConfirmed ? "confirmed" : "resume"));
        abortButton.visible = true;
        abortButton.active = available && seated;
        abortButton.setMessage(text(abortArmed ? "confirm_abort" : "abort"));
        cancelAbortButton.visible = abortArmed;
        cancelAbortButton.active = !awaitingReply;
    }

    void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, screen.height, 0xEE12271F);
        graphics.centeredText(font, text("title"), width / 2, 4, 0xFFF0E4C9);
        graphics.centeredText(font, text("turn", wind(game().turnSeat()), game().remainingTiles()),
                width / 2, 17, 0xFFD6E4D9);
        for (int seat = 0; seat < 4; seat++) {
            drawRiver(graphics, seat, mouseX, mouseY);
        }
        Component handTitle = screen.snapshot().ownSeat() < 0 ? text("spectating")
                : selectedTileId < 0 ? text("hand", wind(screen.snapshot().ownSeat()))
                : text("selected", TileFaces.fullName(selectedTileId));
        graphics.text(font, font.plainSubstrByWidth(handTitle.getString(), Math.max(142, width / 2) - 16),
                8, 141, 0xFFF0E4C9);
        graphics.text(font, text("red_legend"), Math.max(142, width / 2), 141, 0xFFE2BCB2);
        if (game().drawnTile() >= 0) {
            graphics.centeredText(font, text("drawn"), drawnX + handTileWidth / 2, 141, 0xFF9BE3AD);
        }
        Component note;
        if (abortArmed) {
            note = text("abort_prompt");
        } else if (awaitingReply) {
            note = text("processing");
        } else if (!screen.snapshot().feedbackKey().isEmpty()) {
            note = Component.translatable(screen.snapshot().feedbackKey());
        } else if (!game().statusKey().isEmpty()) {
            note = Component.translatable(game().statusKey());
        } else {
            note = text(game().paused() ? "paused" : "select_hint");
        }
        List<FormattedCharSequence> lines = font.split(note, width - 16);
        for (int line = 0; line < Math.min(2, lines.size()); line++) {
            graphics.text(font, lines.get(line), 8, 217 + line * font.lineHeight, 0xFFE8D3A5);
        }
    }

    private void drawRiver(GuiGraphicsExtractor graphics, int seat, int mouseX, int mouseY) {
        int columnWidth = (width - 24) / 2;
        int left = 8 + (seat % 2) * (columnWidth + 8);
        int top = 31 + (seat / 2) * 54;
        GameSnapshot.Seat state = game().seats().get(seat);
        boolean current = game().turnSeat() == seat;
        graphics.fill(left, top, left + columnWidth, top + 51, current ? 0xFF315440 : 0xFF203F31);
        Component header = text("river", wind(seat), state.handCount());
        graphics.text(font, header, left + 3, top + 1, current ? 0xFFFFD76E : 0xFFC3D5C8);
        if (!state.present()) {
            Component absent = text("absent");
            graphics.text(font, absent, left + columnWidth - font.width(absent) - 3, top + 1, 0xFFFFB59D);
        } else if (game().paused() && state.confirmed()) {
            Component confirmed = text("confirmed");
            graphics.text(font, confirmed, left + columnWidth - font.width(confirmed) - 3, top + 1, 0xFF9BE3AD);
        }
        int tileWidth = Math.min(22, (columnWidth - 11) / 6);
        for (int index = 0; index < Math.min(18, state.discards().size()); index++) {
            int x = left + 3 + (index % 6) * (tileWidth + 1);
            int y = top + 12 + (index / 6) * 13;
            int tile = state.discards().get(index);
            TileFaces.drawDiscard(graphics, font, tile, x, y, tileWidth, 12);
            if (mouseX >= x && mouseX < x + tileWidth && mouseY >= y && mouseY < y + 12) {
                graphics.setTooltipForNextFrame(TileFaces.fullName(tile), mouseX, mouseY);
            }
        }
    }

    private GameSnapshot game() {
        return screen.snapshot().game();
    }

    private static Component wind(int seat) {
        return Component.translatable("screen.riichicraft.lobby." + WINDS[seat]);
    }

    private static Component text(String suffix, Object... arguments) {
        return Component.translatable("screen.riichicraft.game." + suffix, arguments);
    }
}

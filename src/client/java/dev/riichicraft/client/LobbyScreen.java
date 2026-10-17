package dev.riichicraft.client;

import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;

/** Four read-only seats and server-authoritative lobby actions, without inventory slots. */
public final class LobbyScreen extends Screen {
    private static final String[] SEAT_LABELS = {"east", "south", "west", "north"};
    private LobbySnapshotPayload snapshot;
    private final ResourceKey<Level> dimension;
    private boolean awaitingReply;
    private Button joinButton;
    private Button readyButton;
    private Button leaveButton;
    private Button startButton;
    private Button modeButton;
    private Button playerCountButton;
    private GamePanel gamePanel;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int seatTop;
    private int seatRowHeight;

    public LobbyScreen(LobbySnapshotPayload snapshot) {
        super(text("title"));
        this.snapshot = snapshot;
        dimension = minecraft.level == null ? null : minecraft.level.dimension();
    }

    /** Current server snapshot, also available to client integration tests. */
    public LobbySnapshotPayload snapshot() {
        return snapshot;
    }

    public int selectedTileId() {
        return gamePanel == null || snapshot.game() == null ? -1 : gamePanel.selectedTileId();
    }

    /** Stable localized label; physical copies are available through GameTileButton.tileId(). */
    public static Component tileButtonLabel(int tileId) {
        return Component.translatable("screen.riichicraft.game.tile", TileFaces.fullName(tileId));
    }

    void acceptSnapshot(LobbySnapshotPayload updated) {
        if (!snapshot.viewId().equals(updated.viewId()) || !snapshot.tableId().equals(updated.tableId())
                || updated.revision() < snapshot.revision()) {
            return;
        }
        if (snapshot.game() != null && updated.game() != null) {
            boolean sameSession=snapshot.game().gameId().equals(updated.game().gameId())
                    || snapshot.game().match()!=null && updated.game().match()!=null
                    && snapshot.game().match().matchId().equals(updated.game().match().matchId());
            if (sameSession && updated.game().revision() < snapshot.game().revision()) return;
        }
        boolean hadGame = snapshot.game() != null;
        boolean changedCount=snapshot.seats().size()!=updated.seats().size();
        snapshot = updated;
        awaitingReply = false;
        if (snapshot.game() != null) {
            if (gamePanel == null) {
                gamePanel = new GamePanel(this);
            }
            gamePanel.acceptSnapshot();
            rebuildWidgets();
            return;
        }
        if (hadGame) {
            gamePanel = null;
            rebuildWidgets();
            return;
        }
        if(changedCount) rebuildWidgets(); else updateButtons();
    }

    @Override
    protected void init() {
        if (snapshot.game() != null) {
            if (gamePanel == null) {
                gamePanel = new GamePanel(this);
                gamePanel.acceptSnapshot();
            }
            gamePanel.init(width);
            return;
        }
        panelWidth = Math.min(360, width - 16);
        panelHeight = Math.min(228, height - 8);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        seatTop = panelTop + 54;

        int actionsY = panelTop + panelHeight - 48;
        seatRowHeight = Math.max(14, Math.min(24, (actionsY - seatTop - 46) / 4));
        int innerLeft = panelLeft + 12;
        int innerWidth = panelWidth - 24;
        modeButton=addRenderableWidget(Button.builder(modeLabel(),button->{
            if (!awaitingReply && LobbyClient.request(snapshot,LobbyActionPayload.SET_MODE,false,snapshot.mode()/3*3+(snapshot.mode()+1)%3)) {
                awaitingReply=true; updateButtons();
            }
        }).bounds(innerLeft+83,panelTop+32,innerWidth-83,18).build());
        playerCountButton=addRenderableWidget(Button.builder(text("players."+snapshot.seats().size()),button->{
            if(!awaitingReply && LobbyClient.request(snapshot,LobbyActionPayload.SET_MODE,false,(snapshot.mode()+3)%6)) { awaitingReply=true; updateButtons(); }
        }).bounds(innerLeft,panelTop+32,79,18).build());
        int buttonWidth = (innerWidth - 8) / 3;
        joinButton = addRenderableWidget(Button.builder(text("join"),
                button -> request(LobbyActionPayload.JOIN, false))
                .bounds(innerLeft, actionsY, buttonWidth, 20).build());
        readyButton = addRenderableWidget(Button.builder(text("prepare"),
                button -> request(LobbyActionPayload.SET_READY, !ownReady()))
                .bounds(innerLeft + buttonWidth + 4, actionsY, buttonWidth, 20).build());
        leaveButton = addRenderableWidget(Button.builder(text("leave"),
                button -> request(LobbyActionPayload.LEAVE, false))
                .bounds(innerLeft + (buttonWidth + 4) * 2, actionsY, buttonWidth, 20).build());
        int halfWidth = (innerWidth - 4) / 2;
        startButton = addRenderableWidget(Button.builder(text("start"),
                button -> request(LobbyActionPayload.START, false))
                .bounds(innerLeft, actionsY + 24, halfWidth, 20).build());
        addRenderableWidget(Button.builder(text("close"), button -> onClose())
                .bounds(innerLeft + halfWidth + 4, actionsY + 24, innerWidth - halfWidth - 4, 20).build());
        updateButtons();
    }

    Button addGameButton(Button button) {
        return addRenderableWidget(button);
    }

    void rebuildGameWidgets() {
        rebuildWidgets();
    }

    private void request(int action, boolean ready) {
        if (!awaitingReply && LobbyClient.request(snapshot, action, ready)) {
            awaitingReply = true;
            updateButtons();
        }
    }

    private boolean ownReady() {
        return snapshot.ownSeat() >= 0 && snapshot.seats().get(snapshot.ownSeat()).ready();
    }

    private void updateButtons() {
        if (joinButton == null) {
            return;
        }
        boolean available = !awaitingReply && LobbyClient.canSend();
        boolean seated = snapshot.ownSeat() >= 0;
        boolean full = snapshot.seats().stream().noneMatch(seat -> seat.playerId() == null);
        boolean layoutValid=layoutValid();
        joinButton.active = available && layoutValid && !seated && !full;
        readyButton.active = available && seated && (layoutValid || ownReady());
        readyButton.setMessage(text(ownReady() ? "cancel_ready" : "prepare"));
        leaveButton.active = available && seated;
        startButton.active = available && layoutValid && snapshot.allReady() && snapshot.ownSeat() == 0;
        modeButton.active=available && layoutValid && snapshot.ownSeat()==0;
        modeButton.setMessage(modeLabel());
        playerCountButton.active=available && snapshot.ownSeat()==0 && snapshot.structurePlayers()<0;
        playerCountButton.setMessage(text(snapshot.structurePlayers()<0?"players."+snapshot.seats().size():"chairs."+snapshot.structurePlayers()));
    }

    private boolean layoutValid() { return snapshot.structurePlayers()<0 || snapshot.structurePlayers()==snapshot.seats().size(); }

    private Component modeLabel() {
        return text("mode",text("mode."+new String[]{"single","east","south"}[snapshot.mode()%3]));
    }

    @Override
    public void tick() {
        if (minecraft.level == null || minecraft.player == null
                || !minecraft.level.dimension().equals(dimension)) {
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (snapshot.game() != null) {
            gamePanel.extractRenderState(graphics, mouseX, mouseY);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }
        graphics.fill(0, 0, width, height, 0xA0000000);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF01A2925);
        graphics.centeredText(font, title, width / 2, panelTop + 8, 0xFFF0E4C9);
        graphics.centeredText(font, text("position", snapshot.pos().getX(), snapshot.pos().getY(),
                snapshot.pos().getZ()), width / 2, panelTop + 21, 0xFFACBDB4);

        for (int index = 0; index < snapshot.seats().size(); index++) {
            drawSeat(graphics, index);
        }

        int statusY = seatTop + seatRowHeight * 4 + 3;
        Component status = text(awaitingReply ? "processing" : !layoutValid() ? "layout_invalid" : snapshot.allReady() ? "all_ready" : "waiting");
        drawWrapped(graphics, status, statusY, snapshot.allReady() ? 0xFF9BE3AD : 0xFFC8D3CB);
        if (!snapshot.feedbackKey().isEmpty()) {
            drawWrapped(graphics, Component.translatable(snapshot.feedbackKey()), statusY + 21, 0xFFFFCF8A);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSeat(GuiGraphicsExtractor graphics, int index) {
        LobbySnapshotPayload.Seat seat = snapshot.seats().get(index);
        int left = panelLeft + 12;
        int top = seatTop + index * seatRowHeight;
        int rowWidth = panelWidth - 24;
        int textY = top + Math.max(2, (seatRowHeight - font.lineHeight) / 2);
        graphics.fill(left, top, left + rowWidth, top + seatRowHeight - 2,
                index == snapshot.ownSeat() ? 0xFF345846 : 0xFF263D33);
        graphics.text(font, text(SEAT_LABELS[index]), left + 5, textY, 0xFFC8D3CB);
        if (seat.playerId() == null) {
            graphics.text(font, text("empty"), left + 54, textY, 0xFF9AAEA1);
            return;
        }
        Component ready = text(seat.ready() ? "ready" : "not_ready");
        int readyWidth = font.width(ready);
        String name = seat.name() + (index == snapshot.ownSeat() ? " " + text("you").getString() : "");
        int nameWidth = rowWidth - 54 - readyWidth - 14;
        String shown = font.plainSubstrByWidth(name, Math.max(0, nameWidth));
        if (!shown.equals(name)) {
            shown = font.plainSubstrByWidth(name, Math.max(0, nameWidth - font.width("…"))) + "…";
        }
        graphics.text(font, shown, left + 54, textY, 0xFFF2F3EA);
        graphics.text(font, ready, left + rowWidth - readyWidth - 5, textY,
                seat.ready() ? 0xFF9BE3AD : 0xFFACBDB4);
    }

    private void drawWrapped(GuiGraphicsExtractor graphics, Component message, int y, int color) {
        List<FormattedCharSequence> lines = font.split(message, panelWidth - 24);
        for (int index = 0; index < Math.min(2, lines.size()); index++) {
            graphics.text(font, lines.get(index), panelLeft + 12, y + index * font.lineHeight, color);
        }
    }

    @Override
    public void onClose() {
        LobbyClient.closeView(snapshot.viewId(), snapshot.revision());
        super.onClose();
    }

    @Override
    public void removed() {
        LobbyClient.closeView(snapshot.viewId(), snapshot.revision());
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component text(String suffix, Object... arguments) {
        return Component.translatable("screen.riichicraft.lobby." + suffix, arguments);
    }
}

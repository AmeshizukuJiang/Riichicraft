package dev.riichicraft.client;

import dev.riichicraft.item.TileAppearance;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import static dev.riichicraft.network.GameActionPayload.*;

/** Transparent controls over the actual table. No inventory or second copy of the hand. */
public final class TableScreen extends Screen {
    private static final String[] WINDS = {"east", "south", "west", "north"};
    private static final String[] ENDS = {"ron", "tsumo", "exhaustive", "nagashi", "nine_terminals", "four_winds", "four_riichi", "four_kans"};
    private final DoubleClickDiscardGate discardGate = new DoubleClickDiscardGate();
    private final List<Button> actionButtons = new ArrayList<>();
    private final List<Button> choiceButtons = new ArrayList<>();
    private LobbySnapshotPayload snapshot;
    private boolean awaitingReply, riichiArmed, abortArmed;
    private int selectedTile = -1, expandedAction = -1, resultPage;
    private int resultLeft, resultTop, resultWidth, resultHeight;
    private List<Component> resultLines = List.of();

    public TableScreen(LobbySnapshotPayload snapshot) {
        super(text("title"));
        this.snapshot = snapshot;
    }

    public UUID viewId() { return snapshot.viewId(); }
    public LobbySnapshotPayload snapshot() { return snapshot; }
    public int selectedTileId() { return selectedTile; }
    public boolean riichiArmed() { return riichiArmed; }

    void acceptSnapshot(LobbySnapshotPayload updated) {
        boolean sameGame = game() != null && updated.game() != null && game().gameId().equals(updated.game().gameId());
        boolean unchangedTurn = sameGame && game().revision() == updated.game().revision();
        if (!unchangedTurn) {
            discardGate.reset();
            riichiArmed = false;
            selectedTile = -1;
            expandedAction = -1;
        }
        if (!sameGame) resultPage = 0;
        snapshot = updated;
        awaitingReply = false;
        abortArmed = false;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        actionButtons.clear();
        choiceButtons.clear();
        addRenderableWidget(Button.builder(text("perspective"), button -> {
            discardGate.reset();
            TableCamera.toggle();
        }).bounds(width - 105, 7, 61, 18).build()).active = TableInteractionClient.scene() != null
                && (TableInteractionClient.scene().mounted() || snapshot.ownSeat() < 0);
        addRenderableWidget(Button.builder(text("hide"), button -> onClose()).bounds(width - 40, 7, 33, 18).build())
                .setTooltip(Tooltip.create(text("controls_hint")));
        if (game() == null) initLobby();
        else if (finished()) initResult();
        else initActions();
    }

    private void initLobby() {
        int left = 9, top = height - 62;
        boolean participant = snapshot.ownSeat() >= 0;
        boolean ownReady = participant && snapshot.seats().get(snapshot.ownSeat()).ready();
        Button ready = addRenderableWidget(Button.builder(lobbyText(ownReady ? "cancel_ready" : "prepare"),
                button -> requestLobby(LobbyActionPayload.SET_READY, !ownReady, 0)).bounds(left, top, 77, 20).build());
        ready.active = available() && participant && (structureValid() || ownReady);
        Button start = addRenderableWidget(Button.builder(lobbyText("start"), button -> requestLobby(LobbyActionPayload.START, false, 0))
                .bounds(left + 81, top, 73, 20).build());
        start.active = available() && snapshot.ownSeat() == 0 && snapshot.allReady() && structureValid();
        int nextMode = snapshot.mode() / 3 * 3 + (snapshot.mode() + 1) % 3;
        Button mode = addRenderableWidget(Button.builder(lobbyText("mode." + new String[]{"single", "east", "south"}[snapshot.mode() % 3]),
                button -> requestLobby(LobbyActionPayload.SET_MODE, false, nextMode)).bounds(left, top + 24, 77, 18).build());
        mode.active = available() && snapshot.ownSeat() == 0 && structureValid();
        Button leave = addRenderableWidget(Button.builder(text("leave_lobby"), button -> requestLobby(LobbyActionPayload.LEAVE, false, 0))
                .bounds(left + 81, top + 24, 73, 18).build());
        leave.active = available() && participant;
    }

    private void initActions() {
        if (snapshot.ownSeat() < 0) return;
        if (abortArmed) {
            addAction(gameText("confirm_abort"), () -> send(ABORT, -1, -1, -1, List.of()), true);
            addAction(gameText("cancel_abort"), () -> { abortArmed = false; rebuildWidgets(); }, true);
        } else if (game().paused()) {
            boolean confirmed = game().seats().get(snapshot.ownSeat()).confirmed();
            addAction(gameText(confirmed ? "confirmed" : "resume"), () -> send(RESUME, -1, -1, -1, List.of()),
                    !confirmed && structureValid() && game().seats().stream().allMatch(GameSnapshot.Seat::present));
        } else if (rules() != null) {
            if (!rules().riichiDiscards().isEmpty()) addAction(gameText(riichiArmed ? "cancel_riichi" : "riichi"), () -> {
                riichiArmed = !riichiArmed;
                selectedTile = -1;
                discardGate.reset();
                expandedAction = -1;
                rebuildWidgets();
            }, true);
            Map<Integer, List<GameSnapshot.ActionOption>> grouped = new LinkedHashMap<>();
            for (GameSnapshot.ActionOption option : options()) grouped.computeIfAbsent(option.action(), ignored -> new ArrayList<>()).add(option);
            for (var group : grouped.entrySet()) {
                int action = group.getKey();
                List<GameSnapshot.ActionOption> choices = group.getValue();
                addAction(actionLabel(action), () -> {
                    discardGate.reset();
                    riichiArmed = false;
                    if (choices.size() == 1) sendOption(choices.getFirst());
                    else { expandedAction = expandedAction == action ? -1 : action; rebuildWidgets(); }
                }, true);
            }
            if (expandedAction >= 0) for (GameSnapshot.ActionOption option : grouped.getOrDefault(expandedAction, List.of())) {
                Button choice = new ChoiceButton(option, button -> sendOption(option));
                choice.active = available();
                choiceButtons.add(addRenderableWidget(choice));
            }
        }
        if (!abortArmed) {
            Button abort = addRenderableWidget(Button.builder(gameText(game().match() == null ? "abort" : "abort_match"), button -> {
                abortArmed = true;
                discardGate.reset();
                rebuildWidgets();
            }).bounds(7, height - 23, 70, 16).build());
            abort.active = available();
        }
        layoutActions();
    }

    private void addAction(Component label, Runnable action, boolean enabled) {
        int buttonWidth = Math.max(36, Math.min(84, font.width(label) + 14));
        Button button = addRenderableWidget(Button.builder(label, ignored -> action.run()).bounds(0, 0, buttonWidth, 20).build());
        button.active = available() && enabled;
        actionButtons.add(button);
    }

    private void layoutActions() {
        TableCamera.Bounds bounds = TableCamera.handBounds();
        int right = bounds == null ? width - 9 : Math.min(width - 9, Math.max(80, (int) Math.ceil(bounds.right())));
        int rowY = bounds == null ? height - 67 : Math.max(34, Math.min(height - 44, (int) bounds.top() - 27));
        int nextX = right;
        for (Button button : actionButtons.reversed()) {
            if (nextX - button.getWidth() < 7) { nextX = right; rowY -= 24; }
            nextX -= button.getWidth();
            button.setPosition(nextX, rowY);
            nextX -= 4;
        }
        int choiceRight = right, choiceY = Math.max(27, rowY - 41);
        for (Button button : choiceButtons.reversed()) {
            if (choiceRight - button.getWidth() < 7) { choiceRight = right; choiceY -= 40; }
            choiceRight -= button.getWidth();
            button.setPosition(choiceRight, choiceY);
            choiceRight -= 4;
        }
    }

    private void initResult() {
        resultWidth = Math.max(144, Math.min(220, width * 2 / 5));
        resultLeft = width - resultWidth - 8;
        resultTop = 34;
        resultHeight = Math.max(88, Math.min(140, height - 67));
        resultLines = resultLines(snapshot);
        int pageCount = resultPageCount();
        resultPage = Math.min(resultPage, pageCount - 1);
        Button previous = addRenderableWidget(Button.builder(Component.literal("‹"), ignored -> { resultPage--; rebuildWidgets(); })
                .bounds(resultLeft + 5, resultTop + resultHeight - 21, 19, 17).build());
        previous.active = resultPage > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal("›"), ignored -> { resultPage++; rebuildWidgets(); })
                .bounds(resultLeft + 28, resultTop + resultHeight - 21, 19, 17).build());
        next.active = resultPage + 1 < pageCount;
        boolean intermission = game().match() != null && game().match().phase() == GameSnapshot.MATCH_ROUND_RESULT;
        boolean confirmed = intermission && snapshot.ownSeat() >= 0 && game().match().confirmations().get(snapshot.ownSeat());
        Button continueButton = addRenderableWidget(Button.builder(gameText(intermission ? confirmed ? "confirmed" : "next_round" : "return_lobby"), ignored -> {
            if (intermission) send(NEXT_ROUND, -1, game().match().continueEpoch(), -1, List.of());
            else send(RETURN_LOBBY, -1, -1, -1, List.of());
        }).bounds(resultLeft + 53, resultTop + resultHeight - 21, resultWidth - 58, 17).build());
        continueButton.active = available() && !abortArmed && snapshot.ownSeat() >= 0 && (!intermission
                || !confirmed && structureValid() && game().seats().stream().allMatch(GameSnapshot.Seat::present));
        if (intermission) {
            Button abort = addRenderableWidget(Button.builder(gameText(abortArmed ? "confirm_abort" : "abort_match"), ignored -> {
                if (abortArmed) send(ABORT, -1, -1, -1, List.of());
                else { abortArmed = true; rebuildWidgets(); }
            }).bounds(7, height - 23, 82, 16).build());
            abort.active = available() && snapshot.ownSeat() >= 0;
            if (abortArmed) addRenderableWidget(Button.builder(gameText("cancel_abort"), ignored -> { abortArmed = false; rebuildWidgets(); })
                    .bounds(93, height - 23, 60, 16).build()).active = available();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The live world is the board, including the laid-down result hands.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Component status = game() == null ? lobbyText(snapshot.allReady() ? "all_ready" : "waiting")
                : finished() ? gameText("end." + ENDS[rules().result().kind()])
                : game().paused() ? gameText("paused") : gameText("turn", wind(snapshot, game().turnSeat()), game().remainingTiles());
        graphics.fill(6, 5, Math.min(width - 110, 9 + font.width(status) + 8), 27, 0xB01A2925);
        graphics.text(font, font.plainSubstrByWidth(status.getString(), Math.max(20, width - 122)), 11, 12, 0xFFF2E9CE);
        if (game() == null) drawLobby(graphics);
        else if (finished()) drawResult(graphics, mouseX, mouseY);
        else {
            layoutActions();
            int hovered = TableCamera.pickTile(mouseX, mouseY);
            if (hovered >= 0 && game().ownHand().contains(hovered)) {
                graphics.setTooltipForNextFrame(Component.empty().append(TileFaces.fullName(hovered)).append(" · ")
                        .append(text(riichiArmed ? "riichi_hint" : "double_click_hint")), mouseX, mouseY);
                int previewX = Math.min(width - 37, Math.max(5, mouseX + 13)), previewY = Math.max(30, mouseY - 62);
                TileFaces.drawHand(graphics, font, hovered, previewX, previewY, 32, 47, hovered == selectedTile, true);
            }
            Component note = awaitingReply ? gameText("processing") : riichiArmed ? text("riichi_hint")
                    : selectedTile >= 0 ? text("selected", TileFaces.fullName(selectedTile)) : text("double_click_hint");
            graphics.text(font, font.plainSubstrByWidth(note.getString(), Math.max(40, width - 96)), 85, height - 24, 0xFFE8D3A5);
        }
        if (!snapshot.feedbackKey().isEmpty()) {
            Component feedback = Component.translatable(snapshot.feedbackKey());
            graphics.centeredText(font, feedback, width / 2, 29, 0xFFFFCF8A);
        }
        int hintLeft = game() == null ? 8 : 85;
        Component hint = text("controls_hint");
        graphics.text(font, font.plainSubstrByWidth(hint.getString(), Math.max(20, width - hintLeft - 7)), hintLeft, height - 10, 0xFFD1D6C8);
        if (mouseY >= height - 11) graphics.setTooltipForNextFrame(hint, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawLobby(GuiGraphicsExtractor graphics) {
        int top = Math.max(36, height - 132);
        graphics.fill(6, top - 4, 171, height - 8, 0xBF1A2925);
        for (int index = 0; index < snapshot.seats().size(); index++) {
            var seat = snapshot.seats().get(index);
            MutableComponent line = Component.empty().append(lobbyText(WINDS[index])).append(" ")
                    .append(seat.playerId() == null ? lobbyText("empty") : Component.literal(seat.name()))
                    .append(seat.ready() ? " ✓" : "");
            graphics.text(font, font.plainSubstrByWidth(line.getString(), 155), 11, top + index * 13,
                    index == snapshot.ownSeat() ? 0xFFB6EFC0 : 0xFFE1E5DB);
        }
    }

    private void drawResult(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.fill(resultLeft, resultTop, resultLeft + resultWidth, resultTop + resultHeight, 0xDB142A23);
        int perPage = resultLinesPerPage(), begin = resultPage * perPage;
        for (int index = begin; index < Math.min(resultLines.size(), begin + perPage); index++) {
            int y = resultTop + 7 + (index - begin) * 12;
            Component line = resultLines.get(index);
            graphics.text(font, font.plainSubstrByWidth(line.getString(), resultWidth - 12), resultLeft + 6, y, 0xFFF1E7CD);
            if (mouseX >= resultLeft && mouseX < resultLeft + resultWidth && mouseY >= y && mouseY < y + 12)
                graphics.setTooltipForNextFrame(line, mouseX, mouseY);
        }
        graphics.text(font, (resultPage + 1) + "/" + resultPageCount(), resultLeft + 5, resultTop - 11, 0xFFE3D7BB);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) { discardGate.reset(); return true; }
        if (event.button() != 0 || game() == null || finished()) { discardGate.reset(); return false; }
        int tile = TableCamera.pickTile(event.x(), event.y());
        boolean legal = !awaitingReply && snapshot.ownSeat() >= 0 && !game().paused() && rules() != null
                && game().ownHand().contains(tile) && (riichiArmed ? rules().riichiDiscards() : rules().legalDiscards()).contains(tile);
        selectedTile = legal ? tile : -1;
        boolean confirm = discardGate.click(game().gameId(), game().revision(), tile, riichiArmed, System.nanoTime(), legal);
        if (confirm) send(riichiArmed ? RIICHI : DISCARD, tile, -1, -1, List.of());
        return tile >= 0;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (minecraft.options.keyChat.matches(event) || minecraft.options.keyCommand.matches(event)) {
            discardGate.reset();
            boolean command=minecraft.options.keyCommand.matches(event);
            minecraft.gui.openChatAndAddText(command
                    ? net.minecraft.client.gui.components.ChatComponent.ChatMethod.COMMAND
                    : net.minecraft.client.gui.components.ChatComponent.ChatMethod.MESSAGE,command?"/":"");
            return true;
        }
        if (TableInteractionClient.controlsKey(event)) {
            TableInteractionClient.hideWithControlsKey();
            onClose();
            return true;
        }
        if (TableInteractionClient.perspectiveKey(event)) {
            discardGate.reset();
            if (TableInteractionClient.scene() != null && (TableInteractionClient.scene().mounted() || snapshot.ownSeat() < 0)) TableCamera.toggle();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        discardGate.reset();
        minecraft.gui.setScreen(null);
    }

    @Override
    public void removed() { discardGate.reset(); super.removed(); }

    @Override
    public void resize(int width, int height) { discardGate.reset(); super.resize(width, height); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean isInGameUi() { return true; }

    private void requestLobby(int action, boolean ready, int mode) {
        if (available() && LobbyClient.request(snapshot, action, ready, mode)) {
            awaitingReply = true;
            rebuildWidgets();
        }
    }

    private void send(int action, int tile, long window, int meld, List<Integer> hand) {
        discardGate.reset();
        if (!awaitingReply && snapshot.ownSeat() >= 0 && LobbyClient.requestGame(snapshot, action, tile, window, meld, hand)) {
            awaitingReply = true;
            riichiArmed = false;
            expandedAction = -1;
            rebuildWidgets();
        }
    }

    private void sendOption(GameSnapshot.ActionOption option) {
        if (rules() == null) return;
        if (isResponse()) send(option.action(), -1, rules().opportunity().serial(), option.meldIndex(), option.handTiles());
        else if (option.action() == KITA) send(KITA, option.handTiles().getFirst(), -1, -1, List.of());
        else send(option.action(), -1, -1, option.meldIndex(), option.handTiles());
    }

    private List<GameSnapshot.ActionOption> options() {
        if (rules() == null) return List.of();
        List<GameSnapshot.ActionOption> all = new ArrayList<>();
        if (isResponse()) all.addAll(rules().responses());
        else {
            if (rules().canTsumo()) all.add(new GameSnapshot.ActionOption(TSUMO, -1, List.of()));
            if (rules().canNineTerminals()) all.add(new GameSnapshot.ActionOption(NINE_TERMINALS, -1, List.of()));
            all.addAll(rules().kans());
            for (int north : rules().kitas()) all.add(new GameSnapshot.ActionOption(KITA, -1, List.of(north)));
        }
        Map<String, GameSnapshot.ActionOption> distinct = new LinkedHashMap<>();
        for (var option : all) distinct.putIfAbsent(option.action() + ":" + option.meldIndex() + ":"
                + option.handTiles().stream().map(TileAppearance::key).sorted().toList(), option);
        return List.copyOf(distinct.values());
    }

    /** Whole-hand point change: accepted declaration debit plus the actual settlement transfers. */
    public static List<Integer> settlementDeltas(GameSnapshot game) {
        int[] deltas = new int[game.seats().size()];
        if(game.rules()!=null) for(int seat=0;seat<deltas.length;seat++)
            if(game.rules().seats().get(seat).riichiPhase()==2) deltas[seat]-=1000;
        if (game.rules() != null && game.rules().result() != null) for (var payment : game.rules().result().transfers()) {
            if (payment.fromSeat() >= 0) deltas[payment.fromSeat()] -= payment.points();
            deltas[payment.toSeat()] += payment.points();
        }
        if (game.match() != null && game.match().result() != null && game.match().result().depositRecipient() >= 0)
            deltas[game.match().result().depositRecipient()] += game.match().result().depositPoints();
        List<Integer> result = new ArrayList<>();
        for (int delta : deltas) result.add(delta);
        return List.copyOf(result);
    }

    private static List<Component> resultLines(LobbySnapshotPayload snapshot) {
        GameSnapshot game = snapshot.game();
        GameSnapshot.Result result = game.rules().result();
        List<Component> lines = new ArrayList<>();
        for (var value : result.values()) {
            lines.add(value.yakumanUnits() > 0 ? gameText("value_yakuman", wind(snapshot, value.seat()), value.yakumanUnits(), value.totalPoints())
                    : gameText("value", wind(snapshot, value.seat()), value.han(), value.fu(), value.totalPoints()));
            for (var award : value.yaku()) lines.add(text(award.yakumanUnits() > 0 ? "award_yakuman" : "award",
                    Component.translatable("yaku.riichicraft." + award.yaku().toLowerCase(Locale.ROOT)),
                    award.yakumanUnits() > 0 ? award.yakumanUnits() : award.han()));
            if (value.yakumanUnits() == 0) lines.add(gameText("bonuses_kita", value.dora(), value.aka(), value.ura(), value.kita()));
        }
        List<Integer> deltas = settlementDeltas(game);
        lines.add(text("delta_heading"));
        for (int seat = 0; seat < game.seats().size(); seat++) {
            int points = game.rules().seats().get(seat).points();
            if (game.match() != null && game.match().result() != null) for (var standing : game.match().result().standings())
                if (standing.seat() == seat) points = standing.points();
            int delta = deltas.get(seat);
            lines.add(text("points", wind(snapshot, seat), snapshot.seats().get(seat).name(), points, (delta >= 0 ? "+" : "") + delta));
        }
        for (var payment : result.transfers()) lines.add(gameText("payment",
                payment.fromSeat() < 0 ? gameText("deposit_pool") : wind(snapshot, payment.fromSeat()), wind(snapshot, payment.toSeat()), payment.points()));
        if (!result.tenpai().isEmpty()) {
            MutableComponent tenpai = gameText("tenpai");
            for (int seat : result.tenpai()) tenpai.append(" ").append(wind(snapshot, seat));
            lines.add(tenpai);
        }
        if (game.match() != null && game.match().result() != null) for (var standing : game.match().result().standings())
            lines.add(gameText("standing", standing.rank(), snapshot.seats().get(standing.seat()).name(), standing.points(), tenths(standing.totalTenths())));
        return List.copyOf(lines);
    }

    private int resultLinesPerPage() { return Math.max(1, (resultHeight - 32) / 12); }
    private int resultPageCount() { return Math.max(1, (resultLines.size() + resultLinesPerPage() - 1) / resultLinesPerPage()); }
    private boolean available() { return !awaitingReply && (game() == null ? LobbyClient.canSend() : LobbyClient.canSendGame()); }
    private boolean structureValid() { return snapshot.structurePlayers() < 0 || snapshot.structurePlayers() == snapshot.seats().size(); }
    private boolean finished() { return rules() != null && rules().result() != null; }
    private boolean isResponse() { return rules() != null && rules().opportunity() != null && !rules().responses().isEmpty(); }
    private GameSnapshot game() { return snapshot.game(); }
    private GameSnapshot.RuleState rules() { return game() == null ? null : game().rules(); }
    private static String tenths(int value) { long amount = Math.abs((long) value); return (value < 0 ? "-" : "+") + amount / 10 + "." + amount % 10; }
    private static MutableComponent text(String key, Object... args) { return Component.translatable("screen.riichicraft.table." + key, args); }
    private static MutableComponent gameText(String key, Object... args) { return Component.translatable("screen.riichicraft.game." + key, args); }
    private static MutableComponent lobbyText(String key, Object... args) { return Component.translatable("screen.riichicraft.lobby." + key, args); }
    private static Component wind(LobbySnapshotPayload snapshot, int seat) {
        int dealer = snapshot.game() == null || snapshot.game().rules() == null ? 0 : snapshot.game().rules().dealerSeat();
        return lobbyText(WINDS[(seat - dealer + snapshot.seats().size()) % snapshot.seats().size()]);
    }
    private static Component actionLabel(int action) {
        return gameText(switch (action) {
            case TSUMO -> "tsumo"; case NINE_TERMINALS -> "nine_terminals"; case PASS -> "pass"; case RON -> "ron";
            case CHI -> "chi"; case PON -> "pon"; case OPEN_KAN -> "open_kan"; case CLOSED_KAN -> "closed_kan";
            case ADDED_KAN -> "added_kan"; case KITA -> "kita"; default -> "actions";
        });
    }

    private final class ChoiceButton extends Button {
        private final GameSnapshot.ActionOption option;
        ChoiceButton(GameSnapshot.ActionOption option, OnPress press) {
            super(0, 0, Math.max(54, option.handTiles().size() * 21 + 8), 35, actionLabel(option.action()), press, DEFAULT_NARRATION);
            this.option = option;
            MutableComponent label = Component.empty().append(actionLabel(option.action()));
            for (int tile : option.handTiles()) label.append(" ").append(TileFaces.fullName(tile));
            setTooltip(Tooltip.create(label));
        }
        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), isHoveredOrFocused() ? 0xED42654E : 0xDA1A3527);
            for (int index = 0; index < option.handTiles().size(); index++) TileFaces.drawHand(graphics, font,
                    option.handTiles().get(index), getX() + 4 + index * 21, getY() + 3, 19, 28, false, isHoveredOrFocused());
        }
    }
}

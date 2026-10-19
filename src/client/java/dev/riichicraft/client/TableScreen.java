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
import org.lwjgl.glfw.GLFW;
import static dev.riichicraft.network.GameActionPayload.*;

/** Transparent controls over the actual table. No inventory or second copy of the hand. */
public final class TableScreen extends Screen {
    private static final String[] WINDS = {"east", "south", "west", "north"};
    private static final String[] ENDS = {"ron", "tsumo", "exhaustive", "nagashi", "nine_terminals", "four_winds", "four_riichi", "four_kans"};
    private final DoubleClickDiscardGate discardGate = new DoubleClickDiscardGate();
    private final List<Button> actionButtons = new ArrayList<>();
    private final List<Button> choiceButtons = new ArrayList<>();
    private LobbySnapshotPayload snapshot;
    private boolean awaitingReply, riichiArmed, abortArmed, menuOpen;
    private int selectedTile = -1, hoveredTile = -1, expandedAction = -1, resultPage, detailPage;
    private int detail; // 0: menu, 1: help, 2: complete discard history
    private int resultLeft, resultTop, resultWidth, resultHeight;
    private List<Component> resultLines = List.of();
    private final List<ScoreBadge> scoreBadges = new ArrayList<>();
    private record ScoreBadge(int seat, int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) { return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height; }
    }

    public TableScreen(LobbySnapshotPayload snapshot) {
        super(text("title"));
        this.snapshot = snapshot;
    }

    public UUID viewId() { return snapshot.viewId(); }
    public LobbySnapshotPayload snapshot() { return snapshot; }
    public int selectedTileId() { return selectedTile; }
    public boolean riichiArmed() { return riichiArmed; }

    /** Only this recipient's hand can receive a hover/selection/riichi outline. */
    public int tileOutlineColor(int tile) {
        if (menuOpen || game() == null || finished() || snapshot.ownSeat() < 0 || !game().ownHand().contains(tile)) return 0;
        if (selectedTile == tile) return 0xFFEAC577;
        if (riichiArmed) return !awaitingReply && !game().paused() && rules().riichiDiscards().contains(tile) ? 0xFF7FE5B0 : 0;
        return hoveredTile == tile ? 0xFFF8EED1 : 0;
    }

    void acceptSnapshot(LobbySnapshotPayload updated) {
        boolean sameGame = game() != null && updated.game() != null && game().gameId().equals(updated.game().gameId());
        boolean unchangedTurn = sameGame && game().revision() == updated.game().revision();
        if (!unchangedTurn) {
            discardGate.reset();
            riichiArmed = false;
            selectedTile = -1;
            expandedAction = -1;
        }
        if (!sameGame) { resultPage = 0; detailPage = 0; menuOpen = false; }
        snapshot = updated;
        awaitingReply = false;
        abortArmed = false;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        actionButtons.clear();
        choiceButtons.clear();
        scoreBadges.clear();
        int toolbarHeight = finished() ? 14 : 20;
        Button perspective = button(text("perspective_short"), width - 119, 6, 42, toolbarHeight, () -> {
            discardGate.reset();
            TableCamera.toggle();
        });
        perspective.active = TableInteractionClient.scene() != null && (TableInteractionClient.scene().mounted() || snapshot.ownSeat() < 0);
        perspective.setTooltip(Tooltip.create(text("perspective_key", TableInteractionClient.perspectiveKeyName())));
        button(text("hide"), width - 74, 6, 32, toolbarHeight, this::onClose)
                .setTooltip(Tooltip.create(controlsHint()));
        button(text("menu"), width - 39, 6, 32, toolbarHeight, () -> {
            menuOpen = !menuOpen;
            detail = 0;
            abortArmed = false;
            selectedTile = -1;
            hoveredTile = -1;
            discardGate.reset();
            rebuildWidgets();
        });
        if (menuOpen) { initMenu(); return; }
        if (game() == null) initLobby();
        else if (finished()) initResult();
        else initActions();
    }

    private Button button(Component label, int x, int y, int buttonWidth, int buttonHeight, Runnable action) {
        return addRenderableWidget(new TableButton(label, x, y, buttonWidth, buttonHeight, ignored -> action.run()));
    }

    private void initMenu() {
        resultWidth = Math.min(220, width - 16);
        resultLeft = width - resultWidth - 8;
        resultTop = 36;
        resultHeight = Math.min(156, height - resultTop - 9);
        if (detail != 0) {
            resultLines = detail == 1 ? helpLines() : historyLines();
            detailPage = Math.clamp(detailPage, 0, resultPageCount() - 1);
            button(Component.literal("‹"), resultLeft + 5, resultTop + resultHeight - 22, 20, 17,
                    () -> { detailPage = Math.max(0, detailPage - 1); rebuildWidgets(); }).active = detailPage > 0;
            button(Component.literal("›"), resultLeft + 29, resultTop + resultHeight - 22, 20, 17,
                    () -> { detailPage = Math.min(resultPageCount() - 1, detailPage + 1); rebuildWidgets(); }).active = detailPage + 1 < resultPageCount();
            button(text("back_menu"), resultLeft + 55, resultTop + resultHeight - 22, resultWidth - 60, 17,
                    () -> { detail = 0; rebuildWidgets(); });
            return;
        }
        button(text("help"), resultLeft + 7, resultTop + 9, resultWidth - 14, 20,
                () -> { detail = 1; detailPage = 0; rebuildWidgets(); });
        button(text("history"), resultLeft + 7, resultTop + 33, resultWidth - 14, 20,
                () -> { detail = 2; detailPage = 0; rebuildWidgets(); }).active = game() != null;
        if (game() != null && snapshot.ownSeat() >= 0 && (!finished()
                || game().match() != null && game().match().phase() == GameSnapshot.MATCH_ROUND_RESULT)) {
            button(gameText(abortArmed ? "confirm_abort" : game().match() == null ? "abort" : "abort_match"),
                    resultLeft + 7, resultTop + 65, resultWidth - 14, 20, () -> {
                        if (abortArmed) send(ABORT, -1, -1, -1, List.of());
                        else { abortArmed = true; discardGate.reset(); rebuildWidgets(); }
                    }).active = available();
            if (abortArmed) button(gameText("cancel_abort"), resultLeft + 7, resultTop + 89, resultWidth - 14, 20,
                    () -> { abortArmed = false; rebuildWidgets(); }).active = available();
        }
        button(text("back_table"), resultLeft + 7, resultTop + resultHeight - 27, resultWidth - 14, 20,
                () -> { menuOpen = false; abortArmed = false; rebuildWidgets(); });
    }

    private List<Component> helpLines() {
        List<Component> lines = new ArrayList<>();
        for (Component paragraph : List.of(text("help_select"), text("help_riichi"), text("help_actions"), text("help_menu"), controlsHint()))
            for (var line : font.getSplitter().splitLines(paragraph, resultWidth - 14, net.minecraft.network.chat.Style.EMPTY))
                lines.add(Component.literal(line.getString()));
        return List.copyOf(lines);
    }

    private Component controlsHint() {
        return text("controls_keys", TableInteractionClient.controlsKeyName(), TableInteractionClient.perspectiveKeyName(),
                minecraft.options.keyShift.getTranslatedKeyMessage());
    }

    private List<Component> historyLines() {
        if (game() == null) return List.of();
        List<Component> lines = new ArrayList<>();
        for (int seat = 0; seat < game().seats().size(); seat++) {
            lines.add(text("history_seat", wind(snapshot, seat), snapshot.seats().get(seat).name()));
            var river = game().seats().get(seat).discards();
            if (river.isEmpty()) lines.add(text("history_empty"));
            for (int index = 0; index < river.size(); index++) {
                MutableComponent line = Component.literal((index + 1) + ". ").append(TileFaces.fullName(river.get(index)));
                if (rules() != null) {
                    var mark = rules().seats().get(seat).river().get(index);
                    if (mark.declaration()) line.append(" · ").append(gameText("riichi"));
                    if (mark.claimedBy() >= 0) line.append(" · ").append(text("history_claimed", wind(snapshot, mark.claimedBy())));
                    if (mark.tsumogiri()) line.append(" · ").append(text("history_tsumogiri"));
                }
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private void initLobby() {
        int left = 9, top = height - 62;
        boolean participant = snapshot.ownSeat() >= 0;
        boolean ownReady = participant && snapshot.seats().get(snapshot.ownSeat()).ready();
        Button ready = button(lobbyText(ownReady ? "cancel_ready" : "prepare"), left, top, 77, 20,
                () -> requestLobby(LobbyActionPayload.SET_READY, !ownReady, 0));
        ready.active = available() && participant && (structureValid() || ownReady);
        Button start = button(lobbyText("start"), left + 81, top, 73, 20, () -> requestLobby(LobbyActionPayload.START, false, 0));
        start.active = available() && snapshot.ownSeat() == 0 && snapshot.allReady() && structureValid();
        int nextMode = snapshot.mode() / 3 * 3 + (snapshot.mode() + 1) % 3;
        Button mode = button(lobbyText("mode." + new String[]{"single", "east", "south"}[snapshot.mode() % 3]), left, top + 24, 77, 18,
                () -> requestLobby(LobbyActionPayload.SET_MODE, false, nextMode));
        mode.active = available() && snapshot.ownSeat() == 0 && structureValid();
        Button leave = button(text("leave_lobby"), left + 81, top + 24, 73, 18, () -> requestLobby(LobbyActionPayload.LEAVE, false, 0));
        leave.active = available() && participant;
    }

    private void initActions() {
        if (snapshot.ownSeat() < 0) return;
        if (game().paused()) {
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
        layoutActions();
    }

    private void addAction(Component label, Runnable action, boolean enabled) {
        int buttonWidth = Math.max(36, Math.min(84, font.width(label) + 14));
        Button button = button(label, 0, 0, buttonWidth, 20, action);
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
        resultTop = 50;
        resultHeight = Math.max(88, Math.min(140, height - 67));
        resultLines = resultLines(snapshot);
        int pageCount = resultPageCount();
        resultPage = Math.clamp(resultPage, 0, pageCount - 1);
        Button previous = button(Component.literal("‹"), resultLeft + 5, resultTop + resultHeight - 21, 19, 17,
                () -> { resultPage = Math.max(0, resultPage - 1); rebuildWidgets(); });
        previous.active = resultPage > 0;
        Button next = button(Component.literal("›"), resultLeft + 28, resultTop + resultHeight - 21, 19, 17,
                () -> { resultPage = Math.min(resultPageCount() - 1, resultPage + 1); rebuildWidgets(); });
        next.active = resultPage + 1 < pageCount;
        boolean intermission = game().match() != null && game().match().phase() == GameSnapshot.MATCH_ROUND_RESULT;
        boolean confirmed = intermission && snapshot.ownSeat() >= 0 && game().match().confirmations().get(snapshot.ownSeat());
        Button continueButton = button(gameText(intermission ? confirmed ? "confirmed" : "next_round" : "return_lobby"),
                resultLeft + 53, resultTop + resultHeight - 21, resultWidth - 58, 17, () -> {
            if (intermission) send(NEXT_ROUND, -1, game().match().continueEpoch(), -1, List.of());
            else send(RETURN_LOBBY, -1, -1, -1, List.of());
        });
        continueButton.active = available() && !abortArmed && snapshot.ownSeat() >= 0 && (!intermission
                || !confirmed && structureValid() && game().seats().stream().allMatch(GameSnapshot.Seat::present));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The live world is the board, including the laid-down result hands.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawSituation(graphics, mouseX, mouseY);
        hoveredTile = -1;
        if (game() != null && !finished()) drawScores(graphics, mouseX, mouseY);
        if (menuOpen) drawMenu(graphics, mouseX, mouseY);
        else if (game() == null) drawLobby(graphics);
        else if (finished()) drawResult(graphics, mouseX, mouseY, resultPage);
        else {
            layoutActions();
            int hovered = uiContains(mouseX, mouseY) ? -1 : TableCamera.pickTile(mouseX, mouseY);
            if (hovered >= 0 && game().ownHand().contains(hovered)) {
                hoveredTile = hovered;
                graphics.setTooltipForNextFrame(Component.empty().append(TileFaces.fullName(hovered)).append(" · ")
                        .append(text(canDiscard(hovered) ? riichiArmed ? "riichi_hint" : "discard_hint"
                                : riichiArmed ? "not_riichi_tile" : "view_only")), mouseX, mouseY);
                int previewX = Math.min(width - 37, Math.max(5, mouseX + 13)), previewY = Math.max(30, mouseY - 62);
                TileFaces.drawHand(graphics, font, hovered, previewX, previewY, 32, 47, hovered == selectedTile, true);
            }
            Component note = actionHint();
            if (!note.getString().isEmpty()) {
                int noteWidth = Math.max(1, width - 94);
                graphics.text(font, font.plainSubstrByWidth(note.getString(), noteWidth), 7, height - 13, 0xFFE8D3A5);
                if (mouseY >= height - 15 && mouseX < width - 84) graphics.setTooltipForNextFrame(note, mouseX, mouseY);
            }
        }
        if (!menuOpen && !snapshot.feedbackKey().isEmpty()) {
            Component feedback = Component.translatable(snapshot.feedbackKey());
            graphics.centeredText(font, font.plainSubstrByWidth(feedback.getString(), width - 16), width / 2, 35, 0xFFFFCF8A);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    public Component roundSummary() {
        if (rules() == null) return lobbyText(snapshot.allReady() ? "all_ready" : "waiting");
        int hand = game().match() == null ? 1 : game().match().roundIndex() % game().seats().size() + 1;
        return text("round", lobbyText(WINDS[rules().roundWind() - 27]), hand, rules().honba());
    }

    public Component actionHint() {
        if(menuOpen || game()==null || finished()) return Component.empty();
        return awaitingReply?gameText("processing"):riichiArmed?text("riichi_hint")
                :selectedTile>=0?text("selected",TileFaces.fullName(selectedTile))
                :rules()!=null && rules().opportunity()!=null?text("response_wait"):Component.empty();
    }

    public int displayedPoints(int seat) {
        if (game().match() != null && game().match().result() != null)
            for (var standing : game().match().result().standings()) if (standing.seat() == seat) return standing.points();
        return rules().seats().get(seat).points();
    }

    public int displayedDeposits() {
        return game().match() != null && game().match().result() != null ? 0 : rules().deposits();
    }

    private void drawSituation(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int panelWidth = game() == null ? Math.max(90, width - 131) : Math.min(108, (width - 74) / 2 - 11);
        TableButton.panel(graphics, 6, 6, panelWidth, finished() ? 14 : 27, false);
        Component first = roundSummary();
        Component second = rules() == null ? text("lobby_hint") : text("pool_live", displayedDeposits(), game().remainingTiles());
        graphics.text(font, font.plainSubstrByWidth(first.getString(), panelWidth - 8), 10, 9, 0xFFF1E7CD);
        if (!finished()) graphics.text(font, font.plainSubstrByWidth(second.getString(), panelWidth - 8), 10, 21, 0xFFBBD3C4);
        if (mouseX >= 6 && mouseX < 6 + panelWidth && mouseY >= 6 && mouseY < 33)
            graphics.setComponentTooltipForNextFrame(font, List.of(first, second), mouseX, mouseY);
        if (!menuOpen && snapshot.feedbackKey().isEmpty() && game() != null && (game().paused() || finished())) {
            Component state = finished() ? gameText("end." + ENDS[rules().result().kind()]) : gameText("paused");
            graphics.centeredText(font, font.plainSubstrByWidth(state.getString(), width - 16), width / 2, 36, 0xFFE9D29A);
            if (mouseY >= 35 && mouseY < 46) graphics.setTooltipForNextFrame(state, mouseX, mouseY);
        }
    }

    private void drawScores(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        scoreBadges.clear();
        if (rules() == null) return;
        var scene = TableInteractionClient.scene();
        List<Integer> sides = scene != null && scene.viewId().equals(viewId()) ? scene.sides()
                : java.util.stream.IntStream.range(0, snapshot.seats().size()).boxed().toList();
        int home = sides.get(snapshot.ownSeat() < 0 ? 0 : snapshot.ownSeat());
        var hand = TableCamera.handBounds();
        for (int seat = 0; seat < snapshot.seats().size(); seat++) {
            int side = Math.floorMod(sides.get(seat) - home, 4);
            int cardWidth = side % 2 == 0 ? 74 : 62;
            int x = switch (side) { case 0 -> width - cardWidth - 6; case 1 -> width - cardWidth - 6;
                case 2 -> (width - cardWidth) / 2; default -> 6; };
            int y = switch (side) { case 0 -> height - 33; case 2 -> 6; default -> height / 2 - 13; };
            if (hand != null && x < hand.right() + 3 && x + cardWidth > hand.left() - 3
                    && y < hand.bottom() + 3 && y + 27 > hand.top() - 3) {
                int below = (int) Math.ceil(hand.bottom()) + 6;
                y = below + 27 < height - 6 ? below : Math.max(39, (int) hand.top() - 33);
            }
            if (menuOpen && x < resultLeft + resultWidth && x + cardWidth > resultLeft
                    && y < resultTop + resultHeight && y + 27 > resultTop) continue;
            var badge = new ScoreBadge(seat, x, y, cardWidth, 27);
            scoreBadges.add(badge);
            boolean actor = !finished() && !game().paused() && rules().opportunity()==null && game().turnSeat() == seat;
            TableButton.panel(graphics, x, y, cardWidth, 27, actor);
            int dealer = rules().dealerSeat();
            Component value = Component.empty().append(text("wind." + WINDS[Math.floorMod(seat - dealer, snapshot.seats().size())]))
                    .append(" " + displayedPoints(seat));
            drawFitted(graphics, value, x + 4, y + 4, cardWidth - 8, seat == snapshot.ownSeat() ? 0xFFB6F1C6 : 0xFFF1E7CD);
            int riichi = rules().seats().get(seat).riichiPhase();
            Component marker = riichi > 0 ? gameText(riichi == 2 ? "riichi" : "declaring")
                    : !game().seats().get(seat).present() ? text("absent") : actor ? text(rules().opportunity() != null ? "responding" : "acting") : Component.empty();
            String name = snapshot.seats().get(seat).name();
            String shortName = font.plainSubstrByWidth(name, Math.max(0, cardWidth - 10 - font.width(marker)));
            graphics.text(font, shortName, x + 4, y + 16, 0xFFC0CDBE);
            if (!marker.getString().isEmpty()) graphics.text(font, marker, x + cardWidth - 4 - font.width(marker), y + 16, 0xFFE6C67F);
            if (badge.contains(mouseX, mouseY)) graphics.setComponentTooltipForNextFrame(font,
                    List.of(Component.literal(name), value, marker), mouseX, mouseY);
        }
    }

    private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (detail != 0) { drawResult(graphics, mouseX, mouseY, detailPage); return; }
        TableButton.panel(graphics, resultLeft, resultTop, resultWidth, resultHeight, false);
    }

    private void drawFitted(GuiGraphicsExtractor graphics, Component label, int x, int y, int maxWidth, int color) {
        float scale = Math.min(1F, (float) maxWidth / Math.max(1, font.width(label)));
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y).scale(scale, scale);
        graphics.text(font, label, 0, 0, color);
        graphics.pose().popMatrix();
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

    private void drawResult(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int page) {
        page = Math.clamp(page, 0, resultPageCount() - 1);
        TableButton.panel(graphics, resultLeft, resultTop, resultWidth, resultHeight, false);
        Component title = menuOpen ? text(detail == 1 ? "help" : "history") : gameText("result");
        graphics.text(font, title, resultLeft + 6, resultTop + 5, 0xFFE6C67F);
        String pageLabel = (page + 1) + "/" + resultPageCount();
        graphics.text(font, pageLabel, resultLeft + resultWidth - 6 - font.width(pageLabel), resultTop + 5, 0xFFE6C67F);
        int perPage = resultLinesPerPage(), begin = page * perPage;
        for (int index = begin; index < Math.min(resultLines.size(), begin + perPage); index++) {
            int y = resultTop + 20 + (index - begin) * 12;
            Component line = resultLines.get(index);
            graphics.text(font, font.plainSubstrByWidth(line.getString(), resultWidth - 12), resultLeft + 6, y, 0xFFF1E7CD);
            if (mouseX >= resultLeft && mouseX < resultLeft + resultWidth && mouseY >= y && mouseY < y + 12)
                graphics.setTooltipForNextFrame(line, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) { discardGate.reset(); return true; }
        if (menuOpen) {
            discardGate.reset();
            if (event.button() == 0 && (event.x() < resultLeft || event.x() >= resultLeft + resultWidth
                    || event.y() < resultTop || event.y() >= resultTop + resultHeight)) {
                menuOpen = false; abortArmed = false; rebuildWidgets();
            }
            return true;
        }
        if (uiContains(event.x(), event.y())) { discardGate.reset(); return true; }
        if (event.button() != 0 || game() == null || finished()) { discardGate.reset(); return false; }
        int tile = TableCamera.pickTile(event.x(), event.y());
        boolean legal = canDiscard(tile);
        selectedTile = legal ? tile : -1;
        boolean confirm = discardGate.click(game().gameId(), game().revision(), tile, riichiArmed, System.nanoTime(), legal);
        if (confirm) send(riichiArmed ? RIICHI : DISCARD, tile, -1, -1, List.of());
        return tile >= 0;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (menuOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            menuOpen = false; abortArmed = false; discardGate.reset(); rebuildWidgets(); return true;
        }
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

    private boolean canDiscard(int tile) {
        return !menuOpen && !awaitingReply && snapshot.ownSeat() >= 0 && game() != null && !game().paused() && rules() != null
                && game().ownHand().contains(tile) && (riichiArmed ? rules().riichiDiscards() : rules().legalDiscards()).contains(tile);
    }

    private boolean uiContains(double mouseX, double mouseY) {
        if (menuOpen || mouseY < 34) return true;
        if (scoreBadges.stream().anyMatch(badge -> badge.contains(mouseX, mouseY))) return true;
        for (var child : children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                && widget.visible && mouseX >= widget.getX() && mouseX < widget.getX() + widget.getWidth()
                && mouseY >= widget.getY() && mouseY < widget.getY() + widget.getHeight()) return true;
        return false;
    }

    @Override
    public void onClose() {
        discardGate.reset();
        TableCamera.setOverhead(false);
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
            menuOpen = false;
            rebuildWidgets();
        }
    }

    private void send(int action, int tile, long window, int meld, List<Integer> hand) {
        discardGate.reset();
        if (!awaitingReply && snapshot.ownSeat() >= 0 && LobbyClient.requestGame(snapshot, action, tile, window, meld, hand)) {
            awaitingReply = true;
            menuOpen = false;
            abortArmed = false;
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
        if(!result.uraIndicators().isEmpty()) {
            MutableComponent ura=gameText("ura_indicators");
            for(int tile:result.uraIndicators()) ura.append(" ").append(TileFaces.shortName(tile));
            lines.add(ura);
        }
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

    private int resultLinesPerPage() { return Math.max(1, (resultHeight - 44) / 12); }
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

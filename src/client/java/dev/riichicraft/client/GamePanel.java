package dev.riichicraft.client;

import dev.riichicraft.network.GameSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import static dev.riichicraft.network.GameActionPayload.*;

/** Recipient-only controls. Enabled tiles and actions always come from the server's legal mask. */
final class GamePanel {
    private enum Page { BOARD, ACTIONS, RESULT }
    private static final String[] WINDS={"east","south","west","north"};
    private static final String[] ENDS={"ron","tsumo","exhaustive","nagashi","nine_terminals","four_winds","four_riichi","four_kans"};
    private final LobbyScreen screen;
    private final Font font;
    private final List<GameTileButton> tiles=new ArrayList<>();
    private final List<Button> optionButtons=new ArrayList<>();
    private UUID gameId;
    private long shownWindow=-1;
    private int selectedTileId=-1;
    private boolean awaitingReply, abortArmed;
    private Button discardButton, riichiButton, moreButton, passButton, resumeButton, abortButton, cancelAbortButton, returnButton, continueButton;
    private Button toggleButton, previousButton, nextButton;
    private int width, handTileWidth, drawnX, handY, bottomY;
    private Page page=Page.BOARD;
    private int actionPage, resultPage, riverPage=-1;

    GamePanel(LobbyScreen screen) { this.screen=screen; font=screen.getFont(); }
    int selectedTileId() { return selectedTileId; }

    void acceptSnapshot() {
        var game=game();
        if (!game.gameId().equals(gameId)) {
            gameId=game.gameId(); selectedTileId=-1; shownWindow=-1; page=Page.BOARD;
            actionPage=0; resultPage=0; riverPage=-1;
        }
        if (!game.ownHand().contains(selectedTileId)) selectedTileId=-1;
        if (finished()) {
            if (shownWindow!=-2) { page=Page.RESULT; resultPage=0; shownWindow=-2; }
        } else if (rules()!=null && rules().opportunity()!=null) {
            long serial=rules().opportunity().serial();
            if (shownWindow!=serial) { shownWindow=serial; actionPage=0; if (isResponse()) page=Page.ACTIONS; }
            if (page==Page.ACTIONS && !isResponse()) page=Page.BOARD;
        } else if (page==Page.ACTIONS) { page=Page.BOARD; shownWindow=-1; }
        awaitingReply=false; abortArmed=false;
    }

    void init(int viewWidth) {
        width=viewWidth; tiles.clear(); optionButtons.clear(); handY=screen.height-86; bottomY=screen.height-48;
        handTileWidth=width-16>=366?24:19; int gap=handTileWidth==24?2:1;
        List<Integer> hand=new ArrayList<>(game().ownHand());
        if (game().drawnTile()>=0) hand.remove(Integer.valueOf(game().drawnTile()));
        int closedWidth=13*handTileWidth+12*gap, handLeft=(width-closedWidth-6-handTileWidth)/2;
        drawnX=handLeft+closedWidth+6;
        for (int index=0;index<hand.size();index++) addTile(hand.get(index),handLeft+index*(handTileWidth+gap));
        if (game().drawnTile()>=0) addTile(game().drawnTile(),drawnX);
        int bw=(width-32)/5;
        discardButton=button(text("discard"),0,bw,b->request(DISCARD,selectedTileId));
        riichiButton=button(text("riichi"),1,bw,b->request(RIICHI,selectedTileId));
        moreButton=button(text("actions"),2,bw,b->setPage(page==Page.ACTIONS?Page.BOARD:Page.ACTIONS));
        passButton=button(text("pass"),0,bw,b->requestResponse(new GameSnapshot.ActionOption(PASS,-1,List.of())));
        resumeButton=button(text("resume"),0,bw,b->request(RESUME,-1));
        abortButton=button(text("abort"),3,bw,b->{if (abortArmed) request(ABORT,-1); else {abortArmed=true;updateButtons();}});
        cancelAbortButton=button(text("cancel_abort"),0,bw,b->{abortArmed=false;updateButtons();});
        returnButton=screen.addGameButton(Button.builder(text("return_lobby"),b->request(RETURN_LOBBY,-1)).bounds(8,bottomY,bw*2+4,20).build());
        continueButton=screen.addGameButton(Button.builder(text("next_round"),b->{
            if (game().match()!=null) send(NEXT_ROUND,-1,game().match().continueEpoch(),-1,List.of());
        }).bounds(8,bottomY,bw*2+4,20).build());
        var close=button(text("close"),4,bw,b->screen.onClose());
        close.setTooltip(Tooltip.create(Component.translatable("screen.riichicraft.lobby.close")));
        toggleButton=screen.addGameButton(Button.builder(text("board"),b->setPage(page!=Page.BOARD?Page.BOARD:finished()?Page.RESULT:Page.ACTIONS))
                .bounds(width-70,3,62,18).build());
        previousButton=screen.addGameButton(Button.builder(Component.literal("<"),b->changePage(-1)).bounds(width-70,26,20,15).build());
        nextButton=screen.addGameButton(Button.builder(Component.literal(">"),b->changePage(1)).bounds(width-28,26,20,15).build());
        if (page==Page.ACTIONS) {
            List<GameSnapshot.ActionOption> options=visibleOptions(); actionPage=Math.min(actionPage,Math.max(0,(options.size()-1)/6)); int col=(width-20)/2;
            for (int index=actionPage*6;index<Math.min(options.size(),actionPage*6+6);index++) {
                var option=options.get(index); int local=index%6; Component label=optionLabel(option);
                var widget=screen.addGameButton(Button.builder(label,b->requestOption(option)).bounds(8+(local%2)*(col+4),45+(local/2)*28,col,24).build());
                widget.setTooltip(Tooltip.create(label)); optionButtons.add(widget);
            }
        }
        updateButtons();
    }
    private Button button(Component label,int slot,int bw,Button.OnPress callback) {
        return screen.addGameButton(Button.builder(label,callback).bounds(8+slot*(bw+4),bottomY,bw,20).build());
    }
    private void addTile(int tile,int x) {
        var button=new GameTileButton(tile,x,handY,handTileWidth,b->{selectedTileId=tile;updateButtons();}); tiles.add(button); screen.addGameButton(button);
    }
    private void setPage(Page value) { page=value; screen.rebuildGameWidgets(); }
    private void changePage(int direction) {
        int changed=Math.max(0,Math.min(pageCount()-1,currentPage()+direction));
        if (page==Page.BOARD) riverPage=changed; else if(page==Page.ACTIONS) actionPage=changed; else resultPage=changed;
        screen.rebuildGameWidgets();
    }
    private int currentPage() {
        if (page==Page.ACTIONS) return actionPage; if (page==Page.RESULT) return resultPage;
        return riverPage<0?pageCount()-1:Math.min(riverPage,pageCount()-1);
    }
    private int pageCount() {
        if (page==Page.ACTIONS) return Math.max(1,(visibleOptions().size()+5)/6);
        if (page==Page.RESULT) return Math.max(1,(resultLines().size()+6)/7);
        return Math.max(1,(game().seats().stream().mapToInt(s->s.discards().size()).max().orElse(0)+11)/12);
    }
    private void request(int action,int tile) { send(action,tile,-1,-1,List.of()); }
    private void requestResponse(GameSnapshot.ActionOption option) {
        if (rules()!=null && rules().opportunity()!=null) send(option.action(),-1,rules().opportunity().serial(),option.meldIndex(),option.handTiles());
    }
    private void requestOption(GameSnapshot.ActionOption option) {
        if (isResponse()) requestResponse(option); else if(option.action()==KITA) send(KITA,option.handTiles().getFirst(),-1,-1,List.of()); else send(option.action(),-1,-1,option.meldIndex(),option.handTiles());
    }
    private void send(int action,int tile,long window,int meld,List<Integer> hand) {
        if (!awaitingReply && LobbyClient.requestGame(screen.snapshot(),action,tile,window,meld,hand)) {
            awaitingReply=true; abortArmed=false; updateButtons();
        }
    }
    private boolean finished() { return rules()!=null && rules().result()!=null; }
    private boolean intermission() { return game().match()!=null && game().match().phase()==GameSnapshot.MATCH_ROUND_RESULT; }
    private boolean matchFinished() { return game().match()!=null && game().match().phase()==GameSnapshot.MATCH_RESULT; }
    private boolean isResponse() { return rules()!=null && rules().opportunity()!=null && !rules().responses().isEmpty(); }
    private boolean isTurn() { return rules()!=null && !rules().legalDiscards().isEmpty(); }

    private void updateButtons() {
        boolean available=!awaitingReply && LobbyClient.canSendGame(), seated=screen.snapshot().ownSeat()>=0;
        boolean unlocked=available && seated && !game().paused() && !abortArmed && !finished();
        boolean ownConfirmed=seated && game().seats().get(screen.snapshot().ownSeat()).confirmed();
        for (var tile:tiles) {
            boolean legal=isTurn() && rules().legalDiscards().contains(tile.tileId());
            tile.active=unlocked && legal; tile.setSelected(tile.tileId()==selectedTileId);
            MutableComponent name=Component.empty().append(TileFaces.fullName(tile.tileId()));
            if (isTurn() && !legal) name.append(" · ").append(text("discard_locked"));
            tile.setTooltip(Tooltip.create(name));
        }
        discardButton.visible=!game().paused() && !abortArmed && !finished() && !isResponse();
        discardButton.active=unlocked && isTurn() && rules().legalDiscards().contains(selectedTileId);
        riichiButton.visible=discardButton.visible; riichiButton.active=unlocked && rules()!=null && rules().riichiDiscards().contains(selectedTileId);
        moreButton.visible=!abortArmed && !game().paused() && !finished(); moreButton.active=available && !visibleOptions().isEmpty();
        moreButton.setMessage(text(page==Page.ACTIONS?"board":rules()!=null && rules().canTsumo()?"tsumo_menu":"actions"));
        passButton.visible=isResponse() && !game().paused() && !abortArmed; passButton.active=unlocked && rules().responses().stream().anyMatch(o->o.action()==PASS);
        resumeButton.visible=game().paused() && !abortArmed && !finished();
        resumeButton.active=available && seated && game().seats().stream().allMatch(GameSnapshot.Seat::present) && !ownConfirmed;
        resumeButton.setMessage(text(ownConfirmed?"confirmed":"resume"));
        abortButton.visible=!finished() || intermission(); abortButton.active=available && seated;
        abortButton.setMessage(text(abortArmed?"confirm_abort":game().match()!=null?"abort_match":"abort"));
        cancelAbortButton.visible=abortArmed; cancelAbortButton.active=available;
        returnButton.visible=finished() && !intermission() && !abortArmed; returnButton.active=available && seated;
        boolean ownNextConfirmed=seated && game().match()!=null && game().match().confirmations().get(screen.snapshot().ownSeat());
        continueButton.visible=intermission() && !abortArmed;
        continueButton.active=available && seated && !ownNextConfirmed && game().seats().stream().allMatch(GameSnapshot.Seat::present);
        continueButton.setMessage(text(ownNextConfirmed?"confirmed":"next_round"));
        for (var option:optionButtons) { option.visible=!abortArmed; option.active=unlocked; }
        toggleButton.active=!abortArmed && (finished() || !visibleOptions().isEmpty()); toggleButton.setMessage(text(page!=Page.BOARD?"board":finished()?"result":"actions"));
        previousButton.active=currentPage()>0; nextButton.active=currentPage()+1<pageCount();
    }
    private List<GameSnapshot.ActionOption> visibleOptions() {
        if (rules()==null || finished()) return List.of(); List<GameSnapshot.ActionOption> result=new ArrayList<>();
        if (isResponse()) result.addAll(rules().responses());
        else {
            if (rules().canTsumo()) result.add(new GameSnapshot.ActionOption(TSUMO,-1,List.of()));
            if (rules().canNineTerminals()) result.add(new GameSnapshot.ActionOption(NINE_TERMINALS,-1,List.of()));
            result.addAll(rules().kans());
            for(int tile:rules().kitas()) result.add(new GameSnapshot.ActionOption(KITA,-1,List.of(tile)));
        }
        result.sort(Comparator.comparingInt(o->o.action()==RON?-1:o.action()==PASS?99:o.action()));
        // Normal copies with identical faces need one control; red/non-red alternatives remain distinct.
        var distinct=new LinkedHashMap<String,GameSnapshot.ActionOption>();
        for (var option:result) distinct.putIfAbsent(optionLabel(option).getString(),option); return List.copyOf(distinct.values());
    }
    private Component optionLabel(GameSnapshot.ActionOption option) {
        String key=switch(option.action()) {
            case KITA -> "kita"; case TSUMO -> "tsumo"; case NINE_TERMINALS -> "nine_terminals"; case PASS -> "pass"; case RON -> "ron";
            case CHI -> "chi"; case PON -> "pon"; case OPEN_KAN -> "open_kan"; case CLOSED_KAN -> "closed_kan"; case ADDED_KAN -> "added_kan"; default -> "actions";
        };
        MutableComponent label=text(key); for (int tile:option.handTiles()) label.append(" ").append(TileFaces.shortName(tile)); return label;
    }

    void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY) {
        graphics.fill(0,0,width,screen.height,0xEE12271F); graphics.centeredText(font,title(),width/2,4,0xFFF0E4C9);
        Component headline=matchFinished()?text("match_end."+game().match().result().reason().toLowerCase(Locale.ROOT))
                :finished()?text("end."+ENDS[rules().result().kind()]):text("turn",wind(game().turnSeat()),game().remainingTiles());
        graphics.centeredText(font,headline,width/2,17,0xFFD6E4D9);
        MutableComponent indicators=text("dora");
        if (rules()!=null) { for (int tile:rules().doraIndicators()) indicators.append(" ").append(TileFaces.shortName(tile)); indicators.append("  ").append(text("deposits",matchFinished()?0:rules().deposits())); }
        graphics.text(font,font.plainSubstrByWidth(indicators.getString(),width-88),8,30,0xFFE2C995);
        graphics.centeredText(font,(currentPage()+1)+"/"+pageCount(),width-39,29,0xFFC3D5C8);
        if (page==Page.BOARD) for(int seat=0;seat<game().seats().size();seat++) drawRiver(graphics,seat,mouseX,mouseY);
        else if(page==Page.RESULT) {
            List<Component> lines=resultLines(); resultPage=Math.min(resultPage,Math.max(0,(lines.size()-1)/7));
            for (int line=resultPage*7;line<Math.min(lines.size(),resultPage*7+7);line++) {
                Component value=lines.get(line); int y=46+(line%7)*12;
                graphics.text(font,font.plainSubstrByWidth(value.getString(),width-16),8,y,0xFFF0E4C9);
                if (mouseY>=y && mouseY<y+12) graphics.setTooltipForNextFrame(value,mouseX,mouseY);
            }
        } else if (optionButtons.isEmpty()) graphics.centeredText(font,text("waiting_response"),width/2,78,0xFFC3D5C8);
        Component handTitle=screen.snapshot().ownSeat()<0?text("spectating"):selectedTileId<0?text("hand",wind(screen.snapshot().ownSeat())):text("selected",TileFaces.fullName(selectedTileId));
        graphics.text(font,font.plainSubstrByWidth(handTitle.getString(),Math.max(142,width/2)-16),8,handY-13,0xFFF0E4C9);
        graphics.text(font,text("red_legend"),Math.max(142,width/2),handY-13,0xFFE2BCB2);
        if (game().drawnTile()>=0) graphics.centeredText(font,text("drawn"),drawnX+handTileWidth/2,handY-13,0xFF9BE3AD);
        List<FormattedCharSequence> lines=font.split(note(),width-16);
        for (int line=0;line<Math.min(2,lines.size());line++) graphics.text(font,lines.get(line),8,bottomY+25+line*font.lineHeight,0xFFE8D3A5);
    }
    private Component note() {
        if (abortArmed) return text(game().match()==null?"abort_prompt":"abort_match_prompt"); if (awaitingReply) return text("processing");
        if (!screen.snapshot().feedbackKey().isEmpty()) return Component.translatable(screen.snapshot().feedbackKey());
        if (intermission()) return game().seats().stream().allMatch(GameSnapshot.Seat::present)
                ?text("next_round_hint_count",game().match().confirmations().stream().filter(Boolean::booleanValue).count(),game().seats().size()):text("next_round_absent");
        if (matchFinished()) return text("match_result_hint");
        if (finished()) return text("result_hint"); if (game().paused()) return text("paused");
        if (isResponse()) return text(rules().phase()==GameSnapshot.NORTH_RESPONSES?"north_response_hint":"response_hint",wind(rules().opportunity().actorSeat()),TileFaces.fullName(rules().opportunity().tileId()));
        if (!game().statusKey().isEmpty()) return Component.translatable(game().statusKey()); return text(isTurn()?"select_hint":"waiting_response");
    }

    private void drawRiver(GuiGraphicsExtractor graphics,int seat,int mouseX,int mouseY) {
        int col=(width-24)/2,left=8+(seat%2)*(col+8),top=45+(seat/2)*46; var state=game().seats().get(seat); boolean current=game().turnSeat()==seat;
        graphics.fill(left,top,left+col,top+44,current?0xFF315440:0xFF203F31); MutableComponent header=Component.empty().append(wind(seat));
        if (rules()!=null) { var details=rules().seats().get(seat); header.append(" "+displayPoints(seat)); if (details.riichiPhase()>0) header.append(" ").append(text(details.riichiPhase()==1?"declaring":"riichi")); }
        if (!state.present()) header.append(" ").append(text("absent")); else if (game().paused() && state.confirmed()) header.append(" ").append(text("confirmed"));
        graphics.text(font,font.plainSubstrByWidth(header.getString(),col-6),left+3,top+1,current?0xFFFFD76E:0xFFC3D5C8);
        MutableComponent groups=Component.empty();
        if(rules()!=null && !rules().seats().get(seat).norths().isEmpty()) groups.append(text("kita_count",rules().seats().get(seat).norths().size())).append(" ");
        if (rules()!=null) for (var meld:rules().seats().get(seat).melds()) {
            groups.append(text(meld.kind()==3?"closed_kan_short":meld.kind()==2?"kan_short":meld.kind()==1?"pon_short":"chi_short"));
            for (int tile:meld.tiles()) groups.append(TileFaces.shortName(tile)); groups.append(" ");
        }
        graphics.text(font,font.plainSubstrByWidth(groups.getString(),col-6),left+3,top+10,0xFFA5C9B1);
        if (mouseX>=left && mouseX<left+col && mouseY>=top && mouseY<top+19) graphics.setTooltipForNextFrame(Component.empty().append(header).append(" · ").append(text("river",wind(seat),state.handCount())).append(" ").append(groups),mouseX,mouseY);
        int tileWidth=Math.min(22,(col-11)/6),start=currentPage()*12;
        for(int index=start;index<Math.min(start+12,state.discards().size());index++) {
            int local=index-start,x=left+3+(local%6)*(tileWidth+1),y=top+20+(local/6)*12,tile=state.discards().get(index);
            TileFaces.drawDiscard(graphics,font,tile,x,y,tileWidth,12); MutableComponent tooltip=Component.empty().append(TileFaces.fullName(tile));
            if (rules()!=null) { var mark=rules().seats().get(seat).river().get(index);
                if (mark.declaration()) { graphics.fill(x,y,x+tileWidth,y+1,0xFFFFC647); tooltip.append(" ").append(text("declaration_tile")); }
                if (mark.claimedBy()>=0) { graphics.fill(x,y,x+2,y+12,0xFF6C8A7B); tooltip.append(" ").append(text("claimed",wind(mark.claimedBy()))); }
            }
            if(mouseX>=x && mouseX<x+tileWidth && mouseY>=y && mouseY<y+12) graphics.setTooltipForNextFrame(tooltip,mouseX,mouseY);
        }
    }

    private List<Component> resultLines() {
        if (!finished()) return List.of(); var result=rules().result(); List<Component> lines=new ArrayList<>();
        if (matchFinished()) {
            for (var standing:game().match().result().standings()) lines.add(text("standing",standing.rank(),
                    screen.snapshot().seats().get(standing.seat()).name(),standing.points(),tenths(standing.totalTenths())));
            lines.add(text(game().seats().size()==3?"ranking_formula_three":"ranking_formula"));
            if (game().match().result().depositPoints()>0) lines.add(text("final_deposits",game().match().result().depositPoints(),
                    screen.snapshot().seats().get(game().match().result().depositRecipient()).name()));
            lines.add(text(game().seats().size()==3?"last_round_result_three":"last_round_result"));
        } else if (game().match()!=null) lines.add(text(game().seats().size()==3?"cumulative_points_three":"cumulative_points"));
        if (!result.winners().isEmpty()) {
            MutableComponent winners=text("winners");
            for (int seat:result.winners()) winners.append(" ").append(wind(seat));
            lines.add(winners);
        }
        for(int seat=0;seat<game().seats().size();seat++) { int points=rules().seats().get(seat).points(),delta=points-(game().seats().size()==3?35_000:25_000); lines.add(text("result_points",wind(seat),points,(delta>=0?"+":"")+delta)); }
        if (!result.tenpai().isEmpty()) { MutableComponent tenpai=text("tenpai"); for(int seat:result.tenpai()) tenpai.append(" ").append(wind(seat)); lines.add(tenpai); }
        for(var value:result.values()) {
            lines.add(value.yakumanUnits()>0?text("value_yakuman",wind(value.seat()),value.yakumanUnits(),value.totalPoints())
                    :text("value",wind(value.seat()),value.han(),value.fu(),value.totalPoints()));
            MutableComponent awards=Component.empty();
            for(var award:value.yaku()) { if (!awards.getString().isEmpty()) awards.append(" · "); awards.append(Component.translatable("yaku.riichicraft."+award.yaku().toLowerCase(Locale.ROOT))); }
            lines.add(awards); if(value.yakumanUnits()==0) lines.add(text(value.kita()>0?"bonuses_kita":"bonuses",value.dora(),value.aka(),value.ura(),value.kita()));
        }
        for(var payment:result.transfers()) lines.add(text("payment",payment.fromSeat()<0?text("deposit_pool"):wind(payment.fromSeat()),wind(payment.toSeat()),payment.points()));
        lines.add(text(game().match()==null?"single_round_hint":matchFinished()?"match_result_hint":"next_round_hint_count",game().match()==null?0:game().match().confirmations().stream().filter(Boolean::booleanValue).count(),game().seats().size())); return lines;
    }
    private int displayPoints(int seat) {
        return matchFinished()?game().match().result().standings().stream().filter(s->s.seat()==seat).findFirst().orElseThrow().points():rules().seats().get(seat).points();
    }
    private Component title() {
        if(game().match()==null) return text(game().seats().size()==3?"title_three":"title");
        return text("match_title",Component.translatable("screen.riichicraft.lobby.mode."+(game().match().format()==0?"east":"south")),
                Component.translatable("screen.riichicraft.lobby."+WINDS[rules().roundWind()-27]),game().match().roundIndex()%game().seats().size()+1,rules().honba());
    }
    private static String tenths(int value) { long magnitude=Math.abs((long)value); return (value<0?"-":"+")+magnitude/10+"."+magnitude%10; }
    private GameSnapshot game() { return screen.snapshot().game(); }
    private GameSnapshot.RuleState rules() { return game().rules(); }
    private Component wind(int seat) { return Component.translatable("screen.riichicraft.lobby."+WINDS[(seat-(rules()==null?0:rules().dealerSeat())+game().seats().size())%game().seats().size()]); }
    private static MutableComponent text(String suffix,Object... arguments) { return Component.translatable("screen.riichicraft.game."+suffix,arguments); }
}

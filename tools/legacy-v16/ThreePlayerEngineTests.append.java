    public static Map<String,ThreePlayerRound> legacyRounds() {
        var rounds=new LinkedHashMap<String,ThreePlayerRound>();
        var active=chainRound(new UUID(900,1));
        for(int i=0;i<2;i++){var own=active.view(PLAYERS.getFirst());check(active.kita(PLAYERS.getFirst(),own.command(),own.turnOptions().kitas().stream().min(Integer::compare).orElseThrow())==ThreePlayerRound.Status.ACCEPTED,"legacy kita");}
        rounds.put("three-north-active",active);
        var win=chainRound(new UUID(900,2));
        for(int i=0;i<4;i++){var own=win.view(PLAYERS.getFirst());check(win.kita(PLAYERS.getFirst(),own.command(),own.turnOptions().kitas().stream().min(Integer::compare).orElseThrow())==ThreePlayerRound.Status.ACCEPTED,"legacy kita before win");}
        check(win.tsumo(PLAYERS.getFirst(),win.view(PLAYERS.getFirst()).command())==ThreePlayerRound.Status.ACCEPTED,"legacy scored tsumo");
        rounds.put("three-scored-win",win);
        var partial=ThreePlayerRound.ordered(new UUID(900,3),PLAYERS,0,27,1,List.of(35000,35000,35000),0,
                order(new String[]{"123p456p789s11m4z7s","123p789p123s789s4z","123p789p123s789s4z"},"4z",""));
        var own=partial.view(PLAYERS.getFirst());check(partial.kita(PLAYERS.getFirst(),own.command(),own.turnOptions().kitas().stream().min(Integer::compare).orElseThrow())==ThreePlayerRound.Status.ACCEPTED,"legacy north proposal");
        check(partial.respond(PLAYERS.get(1),partial.view(PLAYERS.get(1)).opportunity().orElseThrow().id(),Simple.RON)==ThreePlayerRound.Status.ACCEPTED,"legacy first of two ron");
        rounds.put("three-partial-north",partial);return rounds;
    }
    public static Map<String,ThreePlayerMatch> legacyMatches() {
        var matches=new LinkedHashMap<String,ThreePlayerMatch>();
        matches.put("three-match-active-south",legacyMatch(ThreePlayerMatchRules.Format.SOUTH,10));
        var intermission=legacyMatch(ThreePlayerMatchRules.Format.EAST,20);
        for(int step=0;intermission.view(null).phase()==ThreePlayerMatch.Phase.PLAYING;step++)legacyStep(intermission,step);
        check(intermission.view(null).phase()==ThreePlayerMatch.Phase.ROUND_RESULT,"legacy first paid intermission");
        var token=intermission.view(null).continueId();
        check(intermission.confirmNext(PLAYERS.get(2),token)==ThreePlayerRound.Status.ACCEPTED,"legacy first vote");
        check(intermission.confirmNext(PLAYERS.get(0),token)==ThreePlayerRound.Status.ACCEPTED,"legacy second vote");
        matches.put("three-match-intermission-east",intermission);
        var finalMatch=legacyMatch(ThreePlayerMatchRules.Format.EAST,30);
        for(int step=0;finalMatch.view(null).phase()!=ThreePlayerMatch.Phase.MATCH_RESULT;step++)legacyStep(finalMatch,step);
        matches.put("three-match-final-east",finalMatch);return matches;
    }
    private static ThreePlayerMatch legacyMatch(ThreePlayerMatchRules.Format format,int series) {
        int[] sequence={0};
        return new ThreePlayerMatch(new UUID(901,series),PLAYERS,format,0,setup->ThreePlayerRound.shuffled(
                new UUID(902,series*1000L+sequence[0]++),setup.players(),setup.dealerSeat(),setup.roundWind(),
                setup.honba(),setup.points(),setup.deposits(),setup.roundIndex()*31L+setup.honba()*101L+7));
    }
    private static void legacyStep(ThreePlayerMatch match,int step) {
        check(step<10000,"bounded complete legacy match");var view=match.view(null);
        if(view.phase()==ThreePlayerMatch.Phase.ROUND_RESULT){var token=view.continueId();for(UUID player:PLAYERS)check(match.confirmNext(player,token)==ThreePlayerRound.Status.ACCEPTED,"legacy match quorum");return;}
        var round=view.round();
        if(round.phase()==ThreePlayerRound.Phase.DRAWN_TURN || round.phase()==ThreePlayerRound.Phase.CALL_DISCARD){
            UUID player=PLAYERS.get(round.actorSeat());var own=match.view(player);var hand=own.round();
            int tile=hand.ownDraw().orElseGet(()->hand.turnOptions().discards().stream().max(Integer::compare).orElseThrow());
            check(match.discard(player,own.command(),tile,false)==ThreePlayerRound.Status.ACCEPTED,"legacy match legal discard");
        }else for(UUID player:PLAYERS){var own=match.view(player).round();if(!own.responses().isEmpty()){
            check(match.respond(player,own.opportunity().orElseThrow().id(),Simple.PASS)==ThreePlayerRound.Status.ACCEPTED,"legacy match pass");break;}}
    }

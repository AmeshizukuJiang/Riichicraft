    public static Map<String,FourPlayerRound> legacyRounds() {
        var rounds=new java.util.LinkedHashMap<String,FourPlayerRound>();
        rounds.put("four-active",closedRobberyRound());
        rounds.put("four-partial-riichi",partialFixture());
        rounds.put("four-scored-win",ambiguousWinFixture());
        return rounds;
    }
    public static Map<String,FourPlayerMatch> legacyMatches() {
        var matches=new java.util.LinkedHashMap<String,FourPlayerMatch>();
        matches.put("four-match-active-east",new FourPlayerMatch(new UUID(164,1),PLAYERS,MatchRules.Format.EAST,0,new ArchiveFactory()));
        matches.put("four-match-intermission-south",intermissionFixture());
        var last=intermissionFixture();matchConfirmAllMissing(last);
        for(int step=0;last.view(null).phase()==FourPlayerMatch.Phase.PLAYING;step++) {
            if(step>200)throw new AssertionError("Final four-player fixture did not finish");
            var view=last.view(null).round();var own=last.view(PLAYERS.get(view.actorSeat())).round();
            matchDiscard(last,own.ownDraw().orElseGet(()->own.turnOptions().discards().stream().min(Integer::compare).orElseThrow()),false);
            matchComplete(last);
        }
        if(last.view(null).phase()!=FourPlayerMatch.Phase.MATCH_RESULT)throw new AssertionError("Final match fixture is not final");
        matches.put("four-match-final-south",last);return matches;
    }
    private static void matchConfirmAllMissing(FourPlayerMatch match) {
        var token=match.view(null).continueId();var votes=match.view(null).confirmations();
        for(int seat=0;seat<4;seat++)if(!votes.get(seat))check(match.confirmNext(PLAYERS.get(seat),token)==Status.ACCEPTED,"missing fixture vote");
    }

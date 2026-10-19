    public static ThreePlayerRound legacyAcceptedBrokenState() {
        var round=ThreePlayerRound.ordered(new UUID(802,0),PLAYERS,0,27,0,List.of(35000,35000,35000),0,order());
        var east=round.view(PLAYERS.getFirst());require(round.discard(PLAYERS.getFirst(),east.command(),east.ownDraw().orElseThrow(),true));pass(round);
        for(int turn=0;turn<2;turn++){var view=round.view(null);UUID actor=PLAYERS.get(view.actorSeat());var own=round.view(actor);require(round.discard(actor,own.command(),own.ownDraw().orElseThrow(),false));pass(round);}
        for(int count=0;count<3;count++){east=round.view(PLAYERS.getFirst());require(round.kita(PLAYERS.getFirst(),east.command(),east.ownDraw().orElseThrow()));if(count<2)pass(round);}
        round.assertConservation();
        try{round.view(PLAYERS.getFirst());throw new AssertionError("Old v16 no longer reproduces the archived own-view failure");}
        catch(IllegalArgumentException expected){/* The accepted archive remains readable through its public view. */}
        return round;
    }

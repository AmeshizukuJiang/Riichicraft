package dev.riichicraft.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;

/** Exercise retained callbacks at both boundaries, including callbacks queued before widget rebuild. */
public final class TablePagingChecks {
    private TablePagingChecks() { }
    public static void exerciseBoundaries(Minecraft client) {
        var screen=(TableScreen)client.gui.screen();
        var arrows=screen.children().stream().filter(child->child instanceof Button).map(child->(Button)child)
                .filter(button->List.of("‹","›").contains(button.getMessage().getString())).toList();
        if(arrows.size()!=2) throw new AssertionError("Expected real pagination controls");
        var previous=arrows.stream().filter(button->button.getMessage().getString().equals("‹")).findFirst().orElseThrow();
        var next=arrows.stream().filter(button->button.getMessage().getString().equals("›")).findFirst().orElseThrow();
        var input=new net.minecraft.client.input.MouseButtonEvent(0,0,new net.minecraft.client.input.MouseButtonInfo(0,0));
        for(int i=0;i<20;i++) previous.onPress(input);
        for(int i=0;i<20;i++) next.onPress(input);
        for(int i=0;i<20;i++) previous.onPress(input);
    }
}

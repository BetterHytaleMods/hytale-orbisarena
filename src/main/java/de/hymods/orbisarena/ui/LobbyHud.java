package de.hymods.orbisarena.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/** In the lobby: the countdown, who is waiting, and the faction one picked. */
public final class LobbyHud extends CustomUIHud {
    public static final String KEY = "OrbisArena_Lobby";

    private String time = "";
    private String players = "";
    private String faction = "";

    public LobbyHud(PlayerRef player) {
        super(player, KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) {
        commands.appendInline(null, """
                Group #OaLobby {
                  Anchor: (Top: 30, Height: 124);
                  LayoutMode: Middle;
                  Group {
                    Anchor: (Width: 420, Height: 120);
                    Background: #000000(0.5);
                    LayoutMode: Top;
                    Label #OaLbTime {
                      Anchor: (Top: 6, Height: 52);
                      Style: (FontSize: 42, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center);
                    }
                    Label #OaLbFaction {
                      Anchor: (Height: 28);
                      Style: (FontSize: 20, TextColor: #ffd84a, RenderBold: true, HorizontalAlignment: Center);
                    }
                    Label #OaLbPlayers {
                      Anchor: (Height: 24);
                      Style: (FontSize: 15, TextColor: #d0d8e8, HorizontalAlignment: Center);
                    }
                  }
                }
                """);
        commands.set("#OaLbTime.Text", this.time);
        commands.set("#OaLbFaction.Text", this.faction);
        commands.set("#OaLbPlayers.Text", this.players);
    }

    public void update(String time, String faction, String players) {
        UICommandBuilder commands = new UICommandBuilder();
        if (!time.equals(this.time)) {
            commands.set("#OaLbTime.Text", time);
        }
        if (!faction.equals(this.faction)) {
            commands.set("#OaLbFaction.Text", faction);
        }
        if (!players.equals(this.players)) {
            commands.set("#OaLbPlayers.Text", players);
        }
        this.time = time;
        this.faction = faction;
        this.players = players;
        if (commands.getCommands().length > 0) {
            this.update(false, commands);
        }
    }
}

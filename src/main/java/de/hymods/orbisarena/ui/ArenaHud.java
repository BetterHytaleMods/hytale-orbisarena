package de.hymods.orbisarena.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.hymods.orbisarena.sim.CardDef;
import de.hymods.orbisarena.sim.Faction;
import de.hymods.orbisarena.sim.Hand;
import de.hymods.orbisarena.sim.Match;
import de.hymods.orbisarena.sim.Side;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A player's HUD during a match. At the top: the crowns of both sides (blue for the south, red for the north)
 * with their keeps' health, the clock, and "x2" or "Overtime". At the bottom: the next card, the four cards in
 * hand (the one on the active hotbar key lit, the ones too dear dimmed) with their cost, the avatar's ability
 * with its cooldown, and the essence bar.
 *
 * <p>The markup holds every card of the player's deck in every slot and only switches them visible, so no
 * picture ever has to change at runtime. Only {@link #update} sends what changed since the last call.
 */
public final class ArenaHud extends CustomUIHud {
    public static final String KEY = "OrbisArena_Match";
    private static final int SEGMENTS = 10;

    private final Faction faction;
    private final List<CardDef> deck;
    private final Map<String, Object> shown = new HashMap<>();

    public ArenaHud(PlayerRef player, Faction faction) {
        super(player, KEY);
        this.faction = faction;
        this.deck = faction.cards();
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) {
        StringBuilder ui = new StringBuilder();
        ui.append("""
                Group #OaTop {
                  Anchor: (Top: 10, Height: 66);
                  LayoutMode: Middle;
                  Group {
                    Anchor: (Width: 380, Height: 62);
                    Background: #000000(0.5);
                    LayoutMode: Left;
                """);
        crowns(ui, 0, "#6aa8ff");
        ui.append("""
                    Group {
                      Anchor: (Width: 200, Height: 62);
                      LayoutMode: Top;
                      Label #OaTime {
                        Anchor: (Top: 4, Height: 34);
                        Style: (FontSize: 28, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center);
                      }
                      Label #OaPhase {
                        Anchor: (Height: 20);
                        Style: (FontSize: 14, TextColor: #ffd84a, RenderBold: true, HorizontalAlignment: Center);
                      }
                    }
                """);
        crowns(ui, 1, "#ff7a6a");
        ui.append("""
                  }
                }
                Group #OaBottom {
                  Anchor: (Bottom: 12, Height: 192);
                  LayoutMode: Middle;
                  Group {
                    Anchor: (Width: 716, Height: 192);
                    LayoutMode: Top;
                    Group #OaHand {
                      Anchor: (Height: 150);
                      LayoutMode: Left;
                      Group #OaNext {
                        Anchor: (Width: 72, Height: 92, Top: 56, Right: 14);
                        Background: #000000(0.45);
                """);
        for (int k = 0; k < this.deck.size(); k++) {
            ui.append("""
                            Group #OaNextK%d {
                              Anchor: (Full: 3);
                              Background: (TexturePath: "OrbisArena/Cards/%s.png");
                              Visible: false;
                            }
                    """.formatted(k, this.deck.get(k).id()));
        }
        ui.append("      }\n");
        for (int s = 0; s < Hand.SIZE; s++) {
            ui.append("""
                          Group #OaS%1$d {
                            Anchor: (Width: 112, Height: 140, Top: 8, Right: 8);
                            Group #OaS%1$dSel {
                              Anchor: (Full: 0);
                              Background: #ffd84a;
                              Visible: false;
                            }
                    """.formatted(s));
            for (int k = 0; k < this.deck.size(); k++) {
                ui.append("""
                                Group #OaS%dK%d {
                                  Anchor: (Full: 4);
                                  Background: (TexturePath: "OrbisArena/Cards/%s.png");
                                  Visible: false;
                                }
                        """.formatted(s, k, this.deck.get(k).id()));
            }
            ui.append("""
                            Group #OaS%1$dDim {
                              Anchor: (Full: 4);
                              Background: #000000(0.55);
                              Visible: false;
                            }
                            Label #OaS%1$dCost {
                              Anchor: (Left: 6, Top: 6, Width: 28, Height: 28);
                              Background: #6a2aa8;
                              Style: (FontSize: 18, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center);
                            }
                            Label #OaS%1$dKey {
                              Anchor: (Right: 6, Top: 6, Width: 20, Height: 20);
                              Style: (FontSize: 13, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center);
                            }
                          }
                    """.formatted(s));
        }
        ui.append("""
                      Group #OaAbility {
                        Anchor: (Width: 96, Height: 96, Top: 50, Left: 14);
                        Background: #000000(0.45);
                        Group {
                          Anchor: (Full: 4);
                          Background: (TexturePath: "OrbisArena/Abilities/%s.png");
                        }
                        Group #OaAbDim {
                          Anchor: (Full: 4);
                          Background: #000000(0.6);
                          Visible: false;
                        }
                        Label #OaAbCd {
                          Anchor: (Top: 30, Height: 36);
                          Style: (FontSize: 28, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center);
                        }
                      }
                    }
                    Group #OaEssence {
                      Anchor: (Top: 8, Width: 580, Height: 28);
                      Background: #000000(0.55);
                      Padding: (Horizontal: 4, Vertical: 4);
                      LayoutMode: Left;
                      Label #OaENum {
                        Anchor: (Width: 36, Height: 20);
                        Style: (FontSize: 16, TextColor: #f0c8ff, RenderBold: true, HorizontalAlignment: Center);
                      }
                """.formatted(this.faction.ability().id()));
        for (int e = 1; e <= SEGMENTS; e++) {
            ui.append("""
                          Group #OaE%d {
                            Anchor: (Width: 51, Height: 20, Right: 2);
                            Background: #b04cff;
                            Visible: false;
                          }
                    """.formatted(e));
        }
        ui.append("""
                    }
                  }
                }
                """);
        commands.appendInline(null, ui.toString());
        for (int s = 0; s < Hand.SIZE; s++) {
            commands.set("#OaS" + s + "Key.Text", Integer.toString(s + 1));
        }
        this.shown.clear();
    }

    private static void crowns(StringBuilder ui, int side, String color) {
        ui.append("""
                    Group {
                      Anchor: (Width: 90, Height: 62);
                      LayoutMode: Top;
                      Label #OaCrowns%1$d {
                        Anchor: (Top: 2, Height: 36);
                        Style: (FontSize: 30, TextColor: %2$s, RenderBold: true, HorizontalAlignment: Center);
                      }
                      Label #OaKeep%1$d {
                        Anchor: (Height: 18);
                        Style: (FontSize: 13, TextColor: %2$s, HorizontalAlignment: Center);
                      }
                    }
                """.formatted(side, color));
    }

    /** Sends what changed: hand, selection, essence, clock, crowns, keeps, ability. */
    public void update(Match match, int sideIndex, int selected) {
        Side side = match.side(sideIndex);
        UICommandBuilder commands = new UICommandBuilder();
        Hand hand = side.hand();
        for (int s = 0; s < Hand.SIZE; s++) {
            CardDef card = hand.slot(s);
            int index = this.deck.indexOf(card);
            Object was = this.shown.put("slot" + s, index);
            if (!Objects.equals(was, index)) {
                for (int k = 0; k < this.deck.size(); k++) {
                    commands.set("#OaS" + s + "K" + k + ".Visible", k == index);
                }
                commands.set("#OaS" + s + "Cost.Text", card == null ? "" : Integer.toString(card.cost()));
            }
            this.set(commands, "#OaS" + s + "Dim.Visible", card != null && card.cost() > side.essence());
            this.set(commands, "#OaS" + s + "Sel.Visible", s == selected);
        }
        int next = this.deck.indexOf(hand.next());
        Object was = this.shown.put("next", next);
        if (!Objects.equals(was, next)) {
            for (int k = 0; k < this.deck.size(); k++) {
                commands.set("#OaNextK" + k + ".Visible", k == next);
            }
        }
        int full = (int) Math.floor(side.essence() + 1e-6);
        for (int e = 1; e <= SEGMENTS; e++) {
            this.set(commands, "#OaE" + e + ".Visible", e <= full);
        }
        this.set(commands, "#OaENum.Text", Integer.toString(full));
        int left = (int) Math.ceil(match.timeLeft());
        this.set(commands, "#OaTime.Text", "%d:%02d".formatted(left / 60, left % 60));
        String phase = match.phase() == Match.Phase.OVERTIME ? "Overtime" : match.doubleEssence() ? "x2" : "";
        this.set(commands, "#OaPhase.Text", phase);
        for (int s = 0; s < 2; s++) {
            this.set(commands, "#OaCrowns" + s + ".Text", Integer.toString(match.side(s).crowns()));
            this.set(commands, "#OaKeep" + s + ".Text", Integer.toString((int) Math.ceil(match.keep(s).hp())));
        }
        int cooldown = (int) Math.ceil(match.abilityCooldown(sideIndex));
        this.set(commands, "#OaAbDim.Visible", cooldown > 0);
        this.set(commands, "#OaAbCd.Text", cooldown > 0 ? Integer.toString(cooldown) : "");
        if (commands.getCommands().length > 0) {
            this.update(false, commands);
        }
    }

    private void set(UICommandBuilder commands, String selector, Object value) {
        Object was = this.shown.put(selector, value);
        if (Objects.equals(was, value)) {
            return;
        }
        if (value instanceof Boolean b) {
            commands.set(selector, b);
        } else {
            commands.set(selector, value.toString());
        }
    }
}

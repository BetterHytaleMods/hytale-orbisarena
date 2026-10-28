# Orbis Arena – Arbeitsregeln

Öffentliches Hytale-Minigame (später CurseForge/hymods.de) im Genre der Lane-Kartenspiele: `/arena` → Lobby
mit Fraktionswahl → Countdown → 1v1 (oder gegen den Bot) in einer eigenen Arena-Welt, Kamera von oben.

## Regeln

- **Commits auf Englisch, ohne Claude-Zeilen.**
- **Öffentliches Repo:** kein dekompilierter Hytale-Code; Spiel-Assets (Modelle, Porträts, Blöcke) dürfen genutzt
  werden. **Keine Namen, Figuren oder Optik von Clash Royale** (Supercell-IP; „Clash“/„Royale“ auch nicht im Namen).
  Mechanik aus dem Genre, alles Sichtbare aus Hytale.
- **Produktentscheidungen** (Karten, Werte, Namen, Fähigkeiten, Optik) trifft Felix: Werte sind Vorschläge.
- `sim/` bleibt frei von Hytale-Klassen (testbar ohne Server, `MatchTest` spielt alle Paarungen Bot gegen Bot).
- UI ohne Erklärtexte. Texte auf Englisch.
- **HUD-Markup nur aus erprobten Bausteinen** (Group, Label, Anchor, Background Farbe/TexturePath, Visible,
  LayoutMode): Markup, das der Client nicht versteht, trennt den Spieler. Texte nie ins Markup, sondern per `set`.
- Kartenbilder nur über `dev/art/Cards.java` (`java -Djava.awt.headless=true -cp ~/.m2/repository/com/hypixel/hytale/Server/0.7.4/Server-0.7.4.jar dev/art/Cards.java`).

## Felix' Entscheidungen (28.10.2026)

- Name **Orbis Arena**, Befehl `/arena`; öffentlich.
- **1v1 + Bot**: wer nach dem Countdown allein ist, spielt gegen den Bot.
- **Fraktion wählen** (je 8 Karten inkl. Bau-Karten): Kweebecs, Trorks, Untote, Goblins; später mehr, irgendwann freie Decks.
- Twists: **Bau-Karten** (echte Blöcke), **Avatar auf der Burg** (schießt automatisch + Fraktions-Fähigkeit per
  Rechtsklick), **Zonen-Arenen** (Emerald Grove, Howling Sands, Borea, Devastated Lands; je Match zufällig).
- Tempo: Lobby 30 s (10 s ab zwei Spielern), 3 Min, letzte Minute doppelte Essenz, 1 Min Sudden Death, dann
  entscheidet der schwächste Turm.

## Aufbau

| Teil | Aufgabe |
|---|---|
| `sim/Arena` | Feld 18 × 32 Kacheln (1 Kachel = 1 Block), Fluss in Reihe 15–16, Brücken, Türme; Seite 1 ist Seite 0 um 180° gedreht |
| `sim/Match` | Das Match, 20 Ticks/s: Essenz, Hand, Einheiten (Ziel, Weg per A* in `Grid`, Angriff), Türme, Bauwerke, Geschosse, Zauber, Uhr, Kronen; Ereignisse für die Ansicht |
| `sim/Bot` | Verteidigt, was über den Fluss kommt, spart und drückt eine Spur, nutzt die Fähigkeit |
| `src/main/resources/orbisarena/factions.json` | Alle Einheiten, Bauwerke, Fraktionen, Karten, Fähigkeiten (Werte = Vorschläge); `role`/`attack` sind Rolle und Angriffsanimation der Puppe |
| `game/Games` | Lobby (Welt `orbis_arena_lobby`), Countdown, Paarung, Hin- und Rückweg; `game/Session` ein Match in eigener Welt `orbis_arena_<n>_<x>` (Vorlage `Server/Instances/OrbisArena/Arena`, Void, Abenteuer, Regeln `OrbisArena`) |
| `game/Camera` | Feste Kamera hinter und über der eigenen Burg, Mauszeiger; `/arena cam <höhe> <neigung> <reihe>` |
| `game/SlotSystem` | Tasten 1–4 (aktiver Hotbar-Platz) wählen die Karte; Linksklick auf einen Block spielt sie, Rechtsklick die Fähigkeit |
| `stash/*` | Eigenes Inventar beim Betreten einer Orbis-Welt weg, beim Verlassen zurück (gespeichert mit dem Spieler) |
| `view/*` | NPCs des Spiels als Puppen (`Frozen`, unverwundbar, nicht gespeichert, Teamfarbe per Effekt), Wachen mit Turm-Leben, Geschosse, Partikel |
| `world/*` | Platz der Arena in der Welt (`Place`), Zonen, Blöcke und Wasser, Arena- und Bauwerk-Blöcke (`ArenaBuilder`) |
| `ui/*` | Match-HUD (Hand, Essenz, Uhr, Kronen, Fähigkeit), Lobby-HUD, Fraktionsseite (`Common/UI/Custom/OrbisArena/Factions.ui`) |

## Entwicklung

`dev/server.sh start|stop|restart|reload|cmd|logs|status`, Server auf `127.0.0.1:5524` (Debug 5009). 5520–5523
und 5531 gehören anderen Mods/Agenten. Operator-Befehle: `/arena status`, `/arena start` (Countdown in 3 s),
`/arena botmatch` (zwei Bots in einer eigenen Arena, prüft serverseitig alles), `/arena cam …`.

## Stand

- 28.10.: Simulation mit Tests, Spielschicht, Kartenbilder. Serverseitig geprüft: Bot gegen Bot läuft in einer echten
  Arena-Welt zu Ende, Figuren, Wachen, Geschosse entstehen, Welt wird danach entfernt. **Im Client noch ungetestet**:
  Kamera, HUD, Fraktionsseite, Klicks/Tasten, Bewegung und Animation der Puppen, Wasser, Teamfarben.

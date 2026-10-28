import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Card art for Orbis Arena, from the game's own portraits (Common/Icons in Assets.zip): one picture per card
 * (104 × 132, the faction's colours, a frame by card type, the portrait, the name), one badge per ability and one
 * portrait per faction for the lobby page. Same output on every run.
 *
 * <pre>java -Djava.awt.headless=true -cp &lt;HytaleServer.jar&gt; dev/art/Cards.java</pre>
 *
 * (the server jar brings Gson). {@code HYTALE_ASSETS} points at another Assets.zip.
 */
public final class Cards {
    private static final Path OUT = Path.of("src/main/resources/Common/UI/Custom/OrbisArena");
    private static final Map<String, Color[]> COLORS = Map.of(
            "kweebec", new Color[]{new Color(0x3f9a4a), new Color(0x163a1c)},
            "trork", new Color[]{new Color(0xa85a2a), new Color(0x3a1c0c)},
            "undead", new Color[]{new Color(0x6a5a92), new Color(0x1e1830)},
            "goblin", new Color[]{new Color(0x7f8f2e), new Color(0x262c0c)});
    private static final Map<String, Color> FRAMES = Map.of(
            "TROOP", new Color(0xe0b85a),
            "SPELL", new Color(0x7ad8ff),
            "BUILDING", new Color(0xc8c8c8),
            "BUILD", new Color(0xc8c8c8));

    public static void main(String[] args) throws IOException {
        Path assets = Path.of(System.getenv().getOrDefault("HYTALE_ASSETS",
                System.getProperty("user.home") + "/Library/Application Support/Hytale/install/release/package/game/latest/Assets.zip"));
        JsonObject data = JsonParser.parseString(Files.readString(Path.of("src/main/resources/orbisarena/factions.json"))).getAsJsonObject();
        try (ZipFile zip = new ZipFile(assets.toFile())) {
            for (JsonElement f : data.getAsJsonArray("factions")) {
                JsonObject faction = f.getAsJsonObject();
                String id = faction.get("id").getAsString();
                Color[] colors = COLORS.get(id);
                write(faction(icon(zip, faction.get("icon").getAsString()), colors), OUT.resolve("Factions/" + id + ".png"));
                JsonObject ability = faction.getAsJsonObject("ability");
                write(badge(icon(zip, ability.get("icon").getAsString()), colors),
                        OUT.resolve("Abilities/" + ability.get("id").getAsString() + ".png"));
                JsonArray cards = faction.getAsJsonArray("cards");
                for (JsonElement c : cards) {
                    JsonObject card = c.getAsJsonObject();
                    write(card(icon(zip, card.get("icon").getAsString()), card.get("name").getAsString(),
                                    colors, FRAMES.get(card.get("type").getAsString())),
                            OUT.resolve("Cards/" + card.get("id").getAsString() + ".png"));
                }
            }
        }
    }

    private static BufferedImage icon(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry("Common/" + path);
        if (entry == null) {
            throw new IOException("no icon " + path);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return ImageIO.read(in);
        }
    }

    private static Graphics2D smooth(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static BufferedImage card(BufferedImage portrait, String name, Color[] colors, Color frame) {
        int w = 104;
        int h = 132;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(image);
        RoundRectangle2D shape = new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 12, 12);
        g.setPaint(new GradientPaint(0, 0, colors[0], 0, h, colors[1]));
        g.fill(shape);
        // A glow behind the figure.
        g.setPaint(new RadialGradientPaint(w / 2f, 50f, 52f, new float[]{0f, 1f},
                new Color[]{new Color(255, 240, 200, 110), new Color(255, 240, 200, 0)}));
        g.fillOval(w / 2 - 52, -2, 104, 104);
        g.drawImage(portrait, 4, 2, 96, 96, null);
        // The name on a dark band.
        g.setPaint(new GradientPaint(0, h - 32, new Color(0, 0, 0, 40), 0, h - 4, new Color(0, 0, 0, 200)));
        g.fillRect(3, h - 34, w - 6, 31);
        g.setColor(Color.WHITE);
        Font font = fit(g, name, w - 10, 13);
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(name, (w - metrics.stringWidth(name)) / 2, h - 12);
        g.setStroke(new BasicStroke(3f));
        g.setColor(frame);
        g.draw(new RoundRectangle2D.Double(2.5, 2.5, w - 5, h - 5, 11, 11));
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(0, 0, 0, 160));
        g.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 13, 13));
        g.dispose();
        return image;
    }

    private static Font fit(Graphics2D g, String text, int width, int size) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
        while (size > 8 && g.getFontMetrics(font).stringWidth(text) > width) {
            size--;
            font = new Font(Font.SANS_SERIF, Font.BOLD, size);
        }
        return font;
    }

    static BufferedImage badge(BufferedImage icon, Color[] colors) {
        int s = 88;
        BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(image);
        Ellipse2D circle = new Ellipse2D.Double(2, 2, s - 4, s - 4);
        g.setPaint(new RadialGradientPaint(s / 2f, s / 2.5f, s / 2f, new float[]{0f, 1f}, new Color[]{colors[0], colors[1]}));
        g.fill(circle);
        g.drawImage(icon, 12, 10, 64, 64, null);
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0xe0b85a));
        g.draw(circle);
        g.dispose();
        return image;
    }

    static BufferedImage faction(BufferedImage portrait, Color[] colors) {
        int s = 152;
        BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(image);
        RoundRectangle2D shape = new RoundRectangle2D.Double(1, 1, s - 2, s - 2, 16, 16);
        g.setPaint(new GradientPaint(0, 0, colors[0], 0, s, colors[1]));
        g.fill(shape);
        g.setPaint(new RadialGradientPaint(s / 2f, s / 2f, s / 2f, new float[]{0f, 1f},
                new Color[]{new Color(255, 240, 200, 90), new Color(255, 240, 200, 0)}));
        g.fillOval(0, 0, s, s);
        g.drawImage(portrait, 8, 8, s - 16, s - 16, null);
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0xe0b85a));
        g.draw(new RoundRectangle2D.Double(2.5, 2.5, s - 5, s - 5, 15, 15));
        g.dispose();
        return image;
    }

    private static void write(BufferedImage image, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        ImageIO.write(image, "png", to.toFile());
    }
}

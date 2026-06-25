import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates the Color Pop Rush launcher icon (a glossy cluster of bubbles on a
 * purple-to-blue rounded tile) at every Android density. Pure AWT so the build
 * ships no pre-made binary assets. Usage: java IconGen <resDir>
 */
public class IconGen {

    // density bucket -> pixel size
    static final String[] DIRS = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi",
            "mipmap-xxhdpi", "mipmap-xxxhdpi"};
    static final int[] SIZES = {48, 72, 96, 144, 192};

    public static void main(String[] args) throws Exception {
        String resDir = args.length > 0 ? args[0] : "app/res";
        for (int i = 0; i < DIRS.length; i++) {
            File dir = new File(resDir, DIRS[i]);
            dir.mkdirs();
            BufferedImage img = render(SIZES[i]);
            ImageIO.write(img, "png", new File(dir, "ic_launcher.png"));
        }
        // A large icon for stores / readme.
        ImageIO.write(render(512), "png", new File(resDir, "../icon-512.png"));
        System.out.println("Icons generated under " + resDir);
    }

    static BufferedImage render(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Rounded tile background with a vertical purple->blue gradient.
        float r = s * 0.23f;
        RoundRectangle2D tile = new RoundRectangle2D.Float(0, 0, s, s, r, r);
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x6B3FE0),
                0, s, new Color(0x3E8EF7)));
        g.fill(tile);
        g.setClip(tile);

        // soft top sheen
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(255, 255, 255, 60),
                0, s * 0.5f, new Color(255, 255, 255, 0)));
        g.fillRect(0, 0, s, (int) (s * 0.5f));

        // Bubble cluster.
        bubble(g, s, 0.36f, 0.42f, 0.27f, new Color(0xFF4D6D));
        bubble(g, s, 0.66f, 0.37f, 0.21f, new Color(0x3DDC84));
        bubble(g, s, 0.55f, 0.67f, 0.25f, new Color(0xFFD23F));
        bubble(g, s, 0.30f, 0.70f, 0.16f, new Color(0x4895EF));
        bubble(g, s, 0.78f, 0.64f, 0.13f, new Color(0x9B5DE5));

        g.dispose();
        return img;
    }

    static void bubble(Graphics2D g, int s, float cx, float cy, float rr, Color base) {
        float x = cx * s, y = cy * s, rad = rr * s;
        Color light = mix(base, Color.WHITE, 0.55f);
        Color dark = mix(base, Color.BLACK, 0.18f);
        RadialGradientPaint paint = new RadialGradientPaint(
                new Point2D.Float(x - rad * 0.35f, y - rad * 0.38f), rad * 1.4f,
                new float[]{0f, 0.55f, 1f},
                new Color[]{light, base, dark},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g.setPaint(paint);
        g.fillOval((int) (x - rad), (int) (y - rad), (int) (rad * 2), (int) (rad * 2));
        // gloss highlight
        g.setColor(new Color(255, 255, 255, 150));
        float gr = rad * 0.32f;
        g.fillOval((int) (x - rad * 0.4f - gr / 2), (int) (y - rad * 0.45f - gr / 2),
                (int) gr, (int) gr);
    }

    static Color mix(Color a, Color b, float t) {
        return new Color(
                clamp(a.getRed() + (b.getRed() - a.getRed()) * t),
                clamp(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                clamp(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static int clamp(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}

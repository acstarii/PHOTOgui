import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Makes small JPEG previews so the grid loads fast. */
final class Thumbs {
    static final int MAX = 480; // longest side in pixels

    /** Returns true if a thumbnail was written to dest. */
    static boolean make(Path src, Path dest, int orientation) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(src.toFile())) {
            if (iis == null) return false;
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return false;
            ImageReader reader = it.next();
            BufferedImage img;
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                // Read a coarsely subsampled version of huge images to save memory and time.
                int sub = Math.max(1, Math.max(w, h) / (MAX * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(sub, sub, 0, 0);
                img = reader.read(0, param);
            } finally {
                reader.dispose();
            }
            if (img == null) return false;

            int iw = img.getWidth(), ih = img.getHeight();
            double scale = Math.min(1.0, (double) MAX / Math.max(iw, ih));
            int tw = Math.max(1, (int) Math.round(iw * scale));
            int th = Math.max(1, (int) Math.round(ih * scale));
            BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, tw, th, null);
            g.dispose();
            img.flush();

            out = orient(out, orientation);

            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(dest.toFile())) {
                ImageWriteParam wp = writer.getDefaultWriteParam();
                wp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                wp.setCompressionQuality(0.82f);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), wp);
            } finally {
                writer.dispose();
            }
            return true;
        } catch (Exception | OutOfMemoryError e) {
            try { Files.deleteIfExists(dest); } catch (IOException ignored) { }
            return false; // the server falls back to serving the original
        }
    }

    /** Applies an EXIF orientation (1..8) by remapping pixels. */
    static BufferedImage orient(BufferedImage s, int o) {
        if (o < 2 || o > 8) return s;
        int w = s.getWidth(), h = s.getHeight();
        boolean swap = o >= 5;
        BufferedImage d = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx, ny;
                switch (o) {
                    case 2: nx = w - 1 - x; ny = y; break;
                    case 3: nx = w - 1 - x; ny = h - 1 - y; break;
                    case 4: nx = x; ny = h - 1 - y; break;
                    case 5: nx = y; ny = x; break;
                    case 6: nx = h - 1 - y; ny = x; break;
                    case 7: nx = h - 1 - y; ny = w - 1 - x; break;
                    default: nx = y; ny = w - 1 - x; break; // 8
                }
                d.setRGB(nx, ny, s.getRGB(x, y));
            }
        }
        return d;
    }
}

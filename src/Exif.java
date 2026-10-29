import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Tiny EXIF reader for JPEG files: pulls out the capture date and the orientation flag.
 * Capture time has no time zone in EXIF, so it is stored as "floating" wall-clock time
 * (the clock reading encoded as if it were UTC). The web UI displays it with UTC getters,
 * which makes the photo show the time that was on the camera's clock.
 */
final class Exif {
    int orientation = 1;     // 1..8, 1 = normal
    Long takenMillis = null; // floating wall-clock time, or null when the file has none

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss");

    static Exif read(Path file) {
        Exif e = new Exif();
        try (InputStream in = Files.newInputStream(file)) {
            e.parse(in.readNBytes(256 * 1024));
        } catch (Exception ignored) {
            // unreadable or odd file: fall back to defaults
        }
        return e;
    }

    private void parse(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return;
        int p = 2;
        while (p + 4 <= b.length) {
            if ((b[p] & 0xFF) != 0xFF) { p++; continue; }
            int marker = b[p + 1] & 0xFF;
            if (marker == 0xFF) { p++; continue; }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { p += 2; continue; }
            if (marker == 0xDA || marker == 0xD9) return; // image data starts, no EXIF found
            int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            if (len < 2) return;
            if (marker == 0xE1 && p + 10 <= b.length
                    && b[p + 4] == 'E' && b[p + 5] == 'x' && b[p + 6] == 'i' && b[p + 7] == 'f'
                    && b[p + 8] == 0 && b[p + 9] == 0) {
                parseTiff(b, p + 10, Math.min(b.length, p + 2 + len));
                return;
            }
            p += 2 + len;
        }
    }

    private void parseTiff(byte[] b, int t, int end) {
        if (t + 8 > end) return;
        boolean le;
        if (b[t] == 'I' && b[t + 1] == 'I') le = true;
        else if (b[t] == 'M' && b[t + 1] == 'M') le = false;
        else return;
        if (u16(b, t + 2, le) != 42) return;

        String dateTime = null, original = null, digitized = null;
        long exifIfd = -1;

        long ifd0 = u32(b, t + 4, le);
        int ifd = t + (int) ifd0;
        if (ifd >= t && ifd + 2 <= end) {
            int n = u16(b, ifd, le);
            for (int i = 0; i < n; i++) {
                int en = ifd + 2 + i * 12;
                if (en + 12 > end) break;
                int tag = u16(b, en, le);
                if (tag == 0x0112) {
                    int v = u16(b, en + 8, le);
                    if (v >= 1 && v <= 8) orientation = v;
                } else if (tag == 0x0132) {
                    dateTime = ascii(b, t, en, le, end);
                } else if (tag == 0x8769) {
                    exifIfd = u32(b, en + 8, le);
                }
            }
        }
        if (exifIfd > 0) {
            int sub = t + (int) exifIfd;
            if (sub >= t && sub + 2 <= end) {
                int n = u16(b, sub, le);
                for (int i = 0; i < n; i++) {
                    int en = sub + 2 + i * 12;
                    if (en + 12 > end) break;
                    int tag = u16(b, en, le);
                    if (tag == 0x9003) original = ascii(b, t, en, le, end);
                    else if (tag == 0x9004) digitized = ascii(b, t, en, le, end);
                }
            }
        }
        String best = original != null ? original : digitized != null ? digitized : dateTime;
        takenMillis = parseDate(best);
    }

    private static Long parseDate(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s, FMT).toEpochSecond(ZoneOffset.UTC) * 1000L;
        } catch (Exception e) {
            return null; // e.g. "0000:00:00 00:00:00"
        }
    }

    private static String ascii(byte[] b, int t, int en, boolean le, int end) {
        long count = u32(b, en + 4, le);
        if (count < 1 || count > 64) return null;
        long start = count <= 4 ? en + 8 : t + u32(b, en + 8, le);
        if (start < 0 || start + count > end) return null;
        return new String(b, (int) start, (int) count, StandardCharsets.US_ASCII).replace("\0", "").trim();
    }

    private static int u16(byte[] b, int o, boolean le) {
        int x = b[o] & 0xFF, y = b[o + 1] & 0xFF;
        return le ? (y << 8) | x : (x << 8) | y;
    }

    private static long u32(byte[] b, int o, boolean le) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int v = b[le ? o + 3 - i : o + i] & 0xFF;
            r = (r << 8) | v;
        }
        return r;
    }
}

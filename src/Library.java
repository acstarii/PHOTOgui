import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The photo library: files on disk, a small index file, and the storage quota.
 *
 * Layout inside dataDir:
 *   originals/<id>.<ext>   the uploaded files, untouched
 *   thumbs/<id>.jpg        previews
 *   index.tsv              one line per photo (name, date, size...)
 *
 * The id is the first 128 bits of the file's SHA-256, so uploading the same photo twice is a no-op.
 */
final class Library {
    record Item(String id, String name, String ext, long taken, long size, long thumbSize, long added) { }

    static final Set<String> EXTS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");

    private final Path originals, thumbs, tmp, index;
    private final long limit;
    private final Map<String, Item> items = new HashMap<>();
    private long used = 0;     // bytes on disk (originals + thumbnails)
    private long reserved = 0; // bytes promised to uploads that are still in flight

    Library(Path root, long limit) throws IOException {
        this.limit = limit;
        originals = root.resolve("originals");
        thumbs = root.resolve("thumbs");
        tmp = root.resolve("tmp");
        index = root.resolve("index.tsv");
        Files.createDirectories(originals);
        Files.createDirectories(thumbs);
        Files.createDirectories(tmp);
        try (var s = Files.list(tmp)) { // leftovers from an interrupted upload
            s.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        }
        load();
    }

    Path tmpDir() { return tmp; }
    Path originalPath(Item it) { return originals.resolve(it.id() + "." + it.ext()); }
    Path thumbPath(Item it) { return thumbs.resolve(it.id() + ".jpg"); }
    long limit() { return limit; }
    synchronized long used() { return used; }
    synchronized Item get(String id) { return items.get(id); }
    synchronized boolean contains(String id) { return items.containsKey(id); }

    /** Claims space for an incoming upload. Returns false if it would not fit. */
    synchronized boolean reserve(long n) {
        if (used + reserved + n > limit) return false;
        reserved += n;
        return true;
    }

    synchronized void release(long n) { reserved = Math.max(0, reserved - n); }

    synchronized List<Item> list() {
        List<Item> l = new ArrayList<>(items.values());
        l.sort(Comparator.comparingLong(Item::taken).reversed()
                .thenComparing(Comparator.comparingLong(Item::added).reversed())
                .thenComparing(Item::id));
        return l;
    }

    /** Moves the finished upload (src) into the library, makes a thumbnail, and records it. */
    Item add(Path src, String id, String name, String ext, long clientTaken, long size) throws IOException {
        synchronized (this) {
            Item existing = items.get(id);
            if (existing != null) { // same photo already stored
                Files.deleteIfExists(src);
                return existing;
            }
        }
        Path orig = originals.resolve(id + "." + ext);
        Files.move(src, orig, StandardCopyOption.REPLACE_EXISTING);

        Exif exif = (ext.equals("jpg") || ext.equals("jpeg")) ? Exif.read(orig) : new Exif();
        long taken = exif.takenMillis != null ? exif.takenMillis
                   : clientTaken > 0 ? clientTaken
                   : System.currentTimeMillis();

        Path th = thumbs.resolve(id + ".jpg");
        long thumbSize = Thumbs.make(orig, th, exif.orientation) ? Files.size(th) : 0;

        Item it = new Item(id, name, ext, taken, size, thumbSize, System.currentTimeMillis());
        synchronized (this) {
            Item raced = items.get(id);
            if (raced != null) return raced;
            items.put(id, it);
            used += size + thumbSize;
            save();
        }
        return it;
    }

    synchronized boolean delete(String id) throws IOException {
        Item it = items.remove(id);
        if (it == null) return false;
        used = Math.max(0, used - it.size() - it.thumbSize());
        Files.deleteIfExists(originalPath(it));
        Files.deleteIfExists(thumbPath(it));
        save();
        return true;
    }

    private void load() throws IOException {
        if (!Files.exists(index)) return;
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            try {
                if (!ID.matcher(f[0]).matches() || !EXTS.contains(f[2])) continue; // guards against a hand-edited index
                Item it = new Item(f[0], f[1], f[2], Long.parseLong(f[3]), Long.parseLong(f[4]),
                        Long.parseLong(f[5]), Long.parseLong(f[6]));
                if (!Files.exists(originalPath(it))) continue;
                items.put(it.id(), it);
                used += it.size() + it.thumbSize();
            } catch (NumberFormatException ignored) { }
        }
    }

    private void save() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Item it : items.values()) {
            lines.add(String.join("\t", it.id(), it.name(), it.ext(), Long.toString(it.taken()),
                    Long.toString(it.size()), Long.toString(it.thumbSize()), Long.toString(it.added())));
        }
        Path t = index.resolveSibling("index.tsv.tmp");
        Files.write(t, lines, StandardCharsets.UTF_8);
        try {
            Files.move(t, index, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(t, index, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String mime(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            default: return "application/octet-stream";
        }
    }
}

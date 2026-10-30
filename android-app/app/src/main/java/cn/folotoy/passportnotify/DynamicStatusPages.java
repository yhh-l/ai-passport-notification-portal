package cn.folotoy.passportnotify;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Strict, bounded v3 pages assembled into the existing v2 status representation. */
final class DynamicStatusPages {
    private final ByteArrayOutputStream records = new ByteArrayOutputStream();
    private final boolean[] seen = new boolean[108];
    private int expected, count;
    void reset() { records.reset(); Arrays.fill(seen, false); expected = 0; count = 0; }
    int nextCursor() { return expected; }
    byte[] accept(byte[] page) {
        if (page.length < 20 || page[0] != 3) throw new IllegalArgumentException("header");
        int cursor = page[19] & 255, next = page[18] & 255, items = page[5] & 255;
        if (cursor == 0) reset();
        if (cursor != expected || items > 2 || count + items > 108 ||
                (next != 255 && (next <= cursor || next >= 108 || items == 0)))
            throw new IllegalArgumentException("cursor");
        int offset = 20, previous = cursor - 1;
        for (int i = 0; i < items; i++) {
            if (offset + 11 > page.length) throw new IllegalArgumentException("record");
            int id = page[offset] & 255, flags = page[offset + 1] & 255;
            int length = page[offset + 10] & 255;
            if (id >= 108 || id <= previous || seen[id] || length > 47 ||
                    (flags & ~3) != 0 || offset + 11 + length > page.length ||
                    (next != 255 && id >= next)) throw new IllegalArgumentException("entry");
            seen[id] = true;
            previous = id;
            offset += 11 + length;
        }
        if (offset != page.length) throw new IllegalArgumentException("trailing bytes");
        records.write(page, 20, page.length - 20);
        count += items;
        if (next != 255) { expected = next; return null; }
        byte[] result = Arrays.copyOf(page, 18 + records.size());
        result[0] = 2;
        result[5] = (byte)count;
        System.arraycopy(records.toByteArray(), 0, result, 18, records.size());
        reset();
        return result;
    }
}

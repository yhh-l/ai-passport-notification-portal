package cn.folotoy.passportnotify;
import org.junit.Test;
import static org.junit.Assert.*;
public class DynamicStatusPagesTest {
    private byte[] page(int cursor, int next, int... ids) {
        byte[] page = new byte[20 + ids.length * 11];
        page[0] = 3; page[5] = (byte)ids.length;
        page[18] = (byte)next; page[19] = (byte)cursor;
        for (int i = 0; i < ids.length; i++) {
            page[20 + i * 11] = (byte)ids[i]; page[21 + i * 11] = 3;
        }
        return page;
    }
    @Test public void collectsMoreThanThreeWithGaps() {
        DynamicStatusPages pages = new DynamicStatusPages();
        assertNull(pages.accept(page(0, 5, 0, 2)));
        assertEquals(5, pages.nextCursor());
        assertNull(pages.accept(page(5, 90, 5, 8)));
        byte[] all = pages.accept(page(90, 255, 90, 107));
        assertEquals(6, all[5]); assertEquals(2, all[0]);
        assertEquals(18 + 6 * 11, all.length);
        assertEquals(107, all[18 + 5 * 11]); assertEquals(0, pages.nextCursor());
    }
    @Test public void reconnectResetsIncompleteSnapshot() {
        DynamicStatusPages pages = new DynamicStatusPages();
        pages.accept(page(0, 2, 0, 1)); pages.reset();
        assertEquals(1, pages.accept(page(0, 255, 0))[5]);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsTruncatedRecord() {
        byte[] page = page(0, 255, 0); page[30] = 47;
        new DynamicStatusPages().accept(page);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsBackwardsCursor() {
        new DynamicStatusPages().accept(page(0, 0, 0));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnexpectedPage() {
        new DynamicStatusPages().accept(page(5, 255, 5));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicateId() {
        new DynamicStatusPages().accept(page(0, 255, 0, 0));
    }
    @Test public void collectsPhysicalMaximum() {
        DynamicStatusPages pages = new DynamicStatusPages();
        for (int i = 0; i < 108; i += 2) {
            byte[] result = pages.accept(page(i, i == 106 ? 255 : i + 2, i, i + 1));
            if (i < 106) assertNull(result); else assertEquals(108, result[5]);
        }
    }
}

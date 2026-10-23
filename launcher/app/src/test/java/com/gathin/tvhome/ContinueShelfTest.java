package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContinueShelfTest {
    private static ContinueShelf.Item item(String id, String title) {
        return new ContinueShelf.Item(id, title, "com.example.app", "https://example.com/watch/" + id, "art", 0.5f);
    }

    private static List<String> ids(List<ContinueShelf.Item> items) {
        List<String> out = new ArrayList<>();
        for (ContinueShelf.Item i : items) out.add(i.id);
        return out;
    }

    @Test public void takesTheMostRecentTitlesInOrder() {
        List<ContinueShelf.Item> recent = Arrays.asList(item("wn:1", "Batman"), item("wn:2", "Coco"),
                item("wn:3", "Bronco"), item("wn:4", "Moana"), item("wn:5", "Carrie"));
        assertEquals(Arrays.asList("wn:1", "wn:2", "wn:3", "wn:4"), ids(ContinueShelf.pick(recent, 4)));
    }

    @Test public void aTitleListedTwiceTakesOneTile() {
        // Two apps with the same film, or an app listing a show twice.
        List<ContinueShelf.Item> recent = Arrays.asList(item("wn:1", "Carrie"), item("wn:2", " carrie "),
                item("wn:3", "Batman"), item("wn:4", "Coco"), item("wn:5", "Moana"));
        assertEquals(Arrays.asList("wn:1", "wn:3", "wn:4", "wn:5"), ids(ContinueShelf.pick(recent, 4)));
    }

    @Test public void lessThanAFullRowShowsNoRow() {
        // A row with gaps would break the grid.
        List<ContinueShelf.Item> recent = Arrays.asList(item("wn:1", "Batman"), item("wn:2", "Coco"));
        assertTrue(ContinueShelf.pick(recent, 4).isEmpty());
        assertTrue(ContinueShelf.pick(Collections.emptyList(), 4).isEmpty());
    }

    @Test public void sameIdsComparesOrderToo() {
        List<ContinueShelf.Item> a = Arrays.asList(item("wn:1", "A"), item("wn:2", "B"));
        assertTrue(ContinueShelf.sameIds(a, Arrays.asList(item("wn:1", "A"), item("wn:2", "B"))));
        assertFalse(ContinueShelf.sameIds(a, Arrays.asList(item("wn:2", "B"), item("wn:1", "A"))));
        assertFalse(ContinueShelf.sameIds(a, Collections.singletonList(item("wn:1", "A"))));
    }

    @Test public void progressIsTheWatchedShareOrNoneWithoutAPosition() {
        assertEquals(0.25f, ContinueShelf.progress(15_000, 60_000), 0f);
        assertEquals(1f, ContinueShelf.progress(70_000, 60_000), 0f);
        assertEquals(-1f, ContinueShelf.progress(0, 60_000), 0f);
        assertEquals(-1f, ContinueShelf.progress(15_000, 0), 0f);
    }
}

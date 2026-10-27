package cn.folotoy.passportnotify;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CatalogSearchTest {
    @Test public void blankQueryMatchesEverything() {
        assertTrue(CatalogSearch.matches("  ", "任意玩法"));
    }

    @Test public void matchesChineseAndEnglishWithoutCaseSensitivity() {
        assertTrue(CatalogSearch.matches("塔罗", "韦特塔罗占卜", "Rider-Waite Tarot"));
        assertTrue(CatalogSearch.matches("TAROT", "韦特塔罗占卜", "Rider-Waite Tarot"));
    }

    @Test public void allQueryTermsMayMatchDifferentFields() {
        assertTrue(CatalogSearch.matches("f1 information", "PDKPASS", "F1 Race Pass",
                "information"));
        assertTrue(CatalogSearch.matches("游戏 塔罗", "韦特塔罗占卜", "games", "游戏"));
    }

    @Test public void rejectsMissingTerm() {
        assertFalse(CatalogSearch.matches("塔罗 联机", "韦特塔罗占卜", "games"));
    }
}

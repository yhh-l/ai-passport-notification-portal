package cn.folotoy.passportnotify;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CatalogRulesTest {
    @Test public void acceptsPublishedOfficialAndCommunitySources() {
        assertTrue(CatalogRules.isPublishedSource("official", "published"));
        assertTrue(CatalogRules.isPublishedSource("community", "published"));
        assertFalse(CatalogRules.isPublishedSource("community", "pending"));
        assertFalse(CatalogRules.isPublishedSource("other", "published"));
    }

    @Test public void downloadPathMustMatchSourceAndSlugExactly() {
        assertTrue(CatalogRules.hasExpectedDownloadPath("official", "clock",
                "/api/download/official/clock"));
        assertTrue(CatalogRules.hasExpectedDownloadPath("community", "stepworld",
                "/api/download/community/stepworld"));
        assertFalse(CatalogRules.hasExpectedDownloadPath("community", "stepworld",
                "/api/download/official/stepworld"));
        assertFalse(CatalogRules.hasExpectedDownloadPath("external", "stepworld",
                "/api/download/external/stepworld"));
    }
}

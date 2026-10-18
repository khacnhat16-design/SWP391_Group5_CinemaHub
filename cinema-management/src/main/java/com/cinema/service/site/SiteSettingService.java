package com.cinema.service.site;

import com.cinema.dao.site.SiteSettingDAO;

/**
 * Service layer for site-wide settings.
 * Currently manages the home page hero banner URL stored in the DB.
 */
public class SiteSettingService {

    public static final String KEY_HOME_BANNER_URL = "home_banner_url";

    private final SiteSettingDAO dao = new SiteSettingDAO();

    /** Returns the current home page banner URL, or null if none is set. */
    public String getHomeBannerUrl() {
        return dao.get(KEY_HOME_BANNER_URL);
    }

    /**
     * Updates the home page banner URL.
     * @param url   the new UploadThing (or any public) URL, or null to clear
     * @param actorId optional user who made the change
     */
    public void setHomeBannerUrl(String url, Long actorId) {
        dao.upsert(KEY_HOME_BANNER_URL, url, actorId);
    }

    /**
     * Convenience overload — no actor tracking.
     */
    public void setHomeBannerUrl(String url) {
        dao.upsert(KEY_HOME_BANNER_URL, url);
    }
}

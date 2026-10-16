package com.cinema.service.site;

import com.cinema.dao.site.SiteSettingDAO;

public class SiteSettingService {

    public static final String KEY_HOME_BANNER_URL = "home_banner_url";

    private final SiteSettingDAO dao = new SiteSettingDAO();

    public String getHomeBannerUrl() {
        return dao.get(KEY_HOME_BANNER_URL);
    }

    public void setHomeBannerUrl(String url, Long actorId) {
        dao.upsert(KEY_HOME_BANNER_URL, url, actorId);
    }

    public void setHomeBannerUrl(String url) {
        dao.upsert(KEY_HOME_BANNER_URL, url);
    }
}

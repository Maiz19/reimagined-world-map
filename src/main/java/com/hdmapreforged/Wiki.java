package com.hdmapreforged;

import okhttp3.*;

/** Links that open an OSRS Wiki page in the browser on a click; the plugin never fetches anything from the wiki. */
final class Wiki {
    static final String WIKI = "https://oldschool.runescape.wiki";

    static String pageUrl(String title) {
        return HttpUrl.get(WIKI).newBuilder().addPathSegment("w").addPathSegment(title.replace(' ', '_')).build().toString();
    }

    /** Opens the page directly when the query is an exact title, otherwise the wiki's search results. */
    static String searchUrl(String query) {
        return HttpUrl.get(WIKI + "/w/Special:Search").newBuilder().addQueryParameter("search", query).build().toString();
    }
}

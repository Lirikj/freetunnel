package org.freetunnel.android;

import android.content.SharedPreferences;
import org.json.JSONArray;

/** Single selected-profile lookup for launcher, tile, and Android Always-on. */
final class SavedProfile {
    static String selected(SharedPreferences prefs) throws Exception {
        JSONArray configs = new JSONArray(ConfigStore.read(prefs));
        if (configs.length() == 0) throw new IllegalStateException("Сначала добавьте конфиг");
        int index = Math.max(0, Math.min(prefs.getInt("active", 0), configs.length() - 1));
        return configs.getJSONObject(index).getString("toml");
    }
}

package com.huangguo.mobile;

import android.app.Activity;
import android.content.Intent;
import androidx.test.runner.AndroidJUnitRunner;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.Map;

/** Supplies local fixtures before onCreate; no testing backdoor exists in the shipping activity. */
public class PlaybackTestRunner extends AndroidJUnitRunner {
    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity activity = super.newActivity(loader, name, intent);
        if (activity instanceof PlayerActivity && intent.hasExtra("qa-source")) {
            try {
                Field field = PlayerActivity.class.getDeclaredField("episodeCache");
                field.setAccessible(true);
                Map<String, JSONObject> cache = (Map<String, JSONObject>) field.get(activity);
                for (int episode = 1; episode <= 4; episode++) {
                    cache.put("998877:" + episode, new JSONObject()
                            .put("source", intent.getStringExtra("qa-source") + "?episode=" + episode));
                }
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        return activity;
    }
}

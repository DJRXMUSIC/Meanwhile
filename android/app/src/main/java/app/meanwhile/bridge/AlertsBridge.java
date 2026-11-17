package app.meanwhile.bridge;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import java.util.UUID;

import org.json.JSONObject;

import app.meanwhile.bridge.core.ActiveAlert;
import app.meanwhile.bridge.core.AlertConfig;
import app.meanwhile.bridge.core.AlertRule;
import app.meanwhile.bridge.core.AlertSettings;
import app.meanwhile.bridge.core.Reading;

/**
 * What the Alerts tab reads and changes, for {@link MainActivity}'s page functions. Changes
 * run on the alert thread (which checks again with them); {@code done} then runs on it too.
 */
final class AlertsBridge {

    private AlertsBridge() {
    }

    /** Everything the tab shows, as JSON. */
    static String state(Context c) {
        final AlertConfig cfg = Alerts.config(c);
        final ActiveAlert a = Alerts.active(c);
        final Reading last = Bridge.get(c).latest();
        final StringBuilder sb = new StringBuilder("{\"config\":").append(cfg.toJson());
        sb.append(",\"active\":");
        if (a == null || cfg.rule(a.uuid) == null) {
            sb.append("null");
        } else {
            sb.append("{\"uuid\":").append(JSONObject.quote(a.uuid)).append(",\"snoozed\":").append(a.snoozed)
                    .append(",\"next_alert_at\":").append(a.nextAlertAt).append('}');
        }
        sb.append(",\"now\":").append(System.currentTimeMillis());
        sb.append(",\"last\":");
        if (last == null) sb.append("null");
        else sb.append("{\"mgdl\":").append(last.mgdl).append(",\"ts\":").append(last.timestamp).append('}');
        sb.append(",\"snooze_choices\":[");
        for (int i = 0; i < Alerts.SNOOZE_CHOICES.length; i++) sb.append(i > 0 ? "," : "").append(Alerts.SNOOZE_CHOICES[i]);
        sb.append("],\"sounds\":{");
        boolean first = true;
        for (AlertRule r : cfg.rules) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JSONObject.quote(r.uuid)).append(':').append(soundInfo(c, r.soundUri));
        }
        sb.append("},\"perms\":").append(perms(c)).append('}');
        return sb.toString();
    }

    /** {"overlay": may show the alarm screen while in use, "full_screen", "dnd", "notifications"} */
    static String perms(Context c) {
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        boolean fullScreen = true;
        boolean dnd = false;
        boolean notifications = true;
        if (nm != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) fullScreen = nm.canUseFullScreenIntent();
                dnd = nm.isNotificationPolicyAccessGranted();
                notifications = nm.areNotificationsEnabled();
            } catch (RuntimeException ignored) {
                // can't tell: leave as is
            }
        }
        final boolean overlay = AlarmOverlay.allowed(c);
        return "{\"overlay\":" + overlay + ",\"full_screen\":" + fullScreen + ",\"dnd\":" + dnd
                + ",\"notifications\":" + notifications + "}";
    }

    static String soundInfo(Context c, String uri) {
        return "{\"name\":" + JSONObject.quote(SoundNames.name(c, uri)) + ",\"playable\":" + SoundNames.playable(c, uri) + "}";
    }

    /** Adds or replaces an alert. Returns null, or what's wrong with it. */
    static String saveRule(Context c, String json, Runnable done) {
        AlertRule r;
        try {
            r = AlertConfig.ruleFromJson(withId(json));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        final AlertRule rule = r;
        Alerts.post(c, x -> {
            Alerts.update(x, Alerts.config(x).withSaved(rule), "saved " + rule + " volume " + rule.volumePercent + "%"
                    + " re-raise " + rule.minutesBetween + " snooze " + rule.snoozeMinutes() + " tone " + rule.soundUri);
            if (done != null) done.run();
        }, null);
        return null;
    }

    /** A new alert from the tab has no id yet. */
    private static String withId(String json) {
        try {
            final JSONObject o = new JSONObject(json);
            if (o.optString("uuid", "").isEmpty()) o.put("uuid", UUID.randomUUID().toString());
            return o.toString();
        } catch (org.json.JSONException e) {
            return json;
        }
    }

    static void removeRule(Context c, String uuid, Runnable done) {
        Alerts.post(c, x -> {
            final AlertConfig cfg = Alerts.config(x);
            final AlertRule r = cfg.rule(uuid);
            if (r != null) Alerts.update(x, cfg.without(uuid), "removed " + r);
            if (done != null) done.run();
        }, null);
    }

    /** Returns null, or what's wrong. */
    static String saveSettings(Context c, String json, Runnable done) {
        final AlertSettings s;
        try {
            s = AlertConfig.settingsFromJson(json, Alerts.config(c).settings);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        Alerts.post(c, x -> {
            final AlertConfig cfg = Alerts.config(x);
            // keep "off until" as it is now (it may have changed since)
            final AlertSettings keep = s.withDisabledUntil(cfg.settings.allDisabledUntil, cfg.settings.lowDisabledUntil,
                    cfg.settings.highDisabledUntil);
            Alerts.update(x, cfg.withSettings(keep), "options: profile " + keep.profile);
            if (done != null) done.run();
        }, null);
        return null;
    }

    /** @param minutes 0 for the alert's own snooze */
    static void snooze(Context c, int minutes, Runnable done) {
        Alerts.post(c, x -> {
            if (minutes > 0) Alerts.snooze(x, minutes, "Alerts tab");
            else Alerts.snoozeDefault(x, "Alerts tab");
            if (done != null) done.run();
        }, null);
    }

    static void unsnooze(Context c, Runnable done) {
        Alerts.post(c, x -> {
            Alerts.unsnooze(x, "Alerts tab");
            if (done != null) done.run();
        }, null);
    }

    /** Turns one alert on or off. */
    static void setActive(Context c, String uuid, boolean on, Runnable done) {
        Alerts.post(c, x -> {
            final AlertConfig cfg = Alerts.config(x);
            final AlertRule r = cfg.rule(uuid);
            if (r != null && r.active != on) Alerts.update(x, cfg.withRule(r.withActive(on)), Alerts.title(r) + (on ? " on" : " off"));
            if (done != null) done.run();
        }, null);
    }

    static void disable(Context c, String which, int minutes, Runnable done) {
        if (!"low".equals(which) && !"high".equals(which) && !"all".equals(which)) return;
        Alerts.post(c, x -> {
            Alerts.disable(x, which, minutes);
            if (done != null) done.run();
        }, null);
    }

    static void preSnooze(Context c, String uuid, int minutes, Runnable done) {
        Alerts.post(c, x -> {
            if (Alerts.config(x).rule(uuid) != null) Alerts.preSnooze(x, uuid, minutes);
            if (done != null) done.run();
        }, null);
    }

    /** Plays an alert's tone once, at its volume (the alert as being edited, saved or not). */
    static String test(Context c, String json) {
        try {
            Alerts.test(c, AlertConfig.ruleFromJson(withId(json)));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}

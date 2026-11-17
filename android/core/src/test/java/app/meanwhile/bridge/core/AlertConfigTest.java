package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AlertConfigTest {

    @Test
    public void builtInSetupIsTheOwnersAlertsAtHalfVolume() {
        final AlertConfig d = AlertConfig.defaults();
        assertEquals(2, d.rules.size());
        final AlertRule low = d.rules.get(0);
        assertFalse(low.above);
        assertEquals(90, low.threshold, 0);
        assertEquals(1, low.minutesBetween);
        assertEquals(60, low.snoozeMinutes());
        assertFalse(low.vibrate);
        assertTrue(low.overrideSilent);
        assertTrue(low.forceSpeaker);
        assertEquals(AlertConfig.SOUND_BEEP, low.soundUri);
        assertEquals(50, low.volumePercent);
        final AlertRule high = d.rules.get(1);
        assertTrue(high.above);
        assertEquals(155, high.threshold, 0);
        assertEquals(3, high.minutesBetween);
        assertEquals(90, high.snoozeMinutes());
        assertTrue(high.vibrate);
        assertEquals(AlertConfig.SOUND_VIBRATION, high.soundUri);
        assertEquals(50, high.volumePercent);
        assertEquals(AlertSettings.HIGH, d.settings.profile);
        assertFalse(d.settings.smartSnoozing);
        assertFalse(d.settings.smartAlerting);
        assertTrue(d.settings.noAlarmsDuringCalls);
        assertEquals("mgdl", d.units);
    }

    @Test
    public void storedConfigRoundTrips() {
        final AlertConfig c = AlertConfig.defaults()
                .withRule(AlertConfig.defaults().rules.get(0).withVolume(35).withSound("content://media/internal/audio/media/9"))
                .withSettings(AlertConfig.defaults().settings.withDisabledUntil(1, 2, 3));
        final AlertConfig back = AlertConfig.fromJson(c.toJson());
        assertEquals(c.toJson(), back.toJson());
        assertEquals(35, back.rules.get(0).volumePercent);
        assertEquals("content://media/internal/audio/media/9", back.rules.get(0).soundUri);
        assertEquals(3, back.settings.highDisabledUntil);

        // nothing stored, or damaged: the built-in setup, never no alerts
        assertEquals(AlertConfig.defaults().toJson(), AlertConfig.fromJson("garbage").toJson());
        assertEquals(AlertConfig.defaults().toJson(), AlertConfig.fromJson(null).toJson());
        // an explicitly empty list stays empty
        assertEquals(0, AlertConfig.fromJson(AlertConfig.empty().toJson()).rules.size());
    }

    @Test
    public void storedBeforePerAlertVolumeTakesTheOldSharedLevel() {
        final String old = "{\"units\":\"mgdl\",\"settings\":{\"bg_alert_profile\":\"High\",\"alert_volume_percent\":30},"
                + "\"alerts\":[{\"uuid\":\"a\",\"threshold\":80,\"above\":false},{\"uuid\":\"b\",\"threshold\":200,\"above\":true}]}";
        final AlertConfig c = AlertConfig.fromJson(old);
        assertEquals(30, c.rule("a").volumePercent);
        assertEquals(30, c.rule("b").volumePercent);
        // no level stored at all: 50%
        assertEquals(50, AlertConfig.fromJson("{\"alerts\":[{\"uuid\":\"a\",\"threshold\":80}],\"settings\":{}}")
                .rule("a").volumePercent);
    }

    @Test
    public void addEditAndRemoveAlerts() {
        final AlertConfig c = AlertConfig.defaults();
        final AlertRule added = new AlertRule("new-1", "Urgent", true, false, 60, true, 0, 0, 5, 30, true, true, true, null, 80);
        final AlertConfig more = c.withSaved(added);
        assertEquals(3, more.rules.size());
        assertEquals(80, more.rule("new-1").volumePercent);
        final AlertConfig edited = more.withSaved(added.withVolume(20));
        assertEquals(3, edited.rules.size());
        assertEquals(20, edited.rule("new-1").volumePercent);
        assertEquals(2, edited.without("new-1").rules.size());
        assertNull(edited.without("new-1").rule("new-1"));
        assertEquals(50, edited.rule(c.rules.get(0).uuid).volumePercent);
    }

    @Test
    public void ruleAndOptionsFromTheAlertsTab() {
        final AlertRule r = AlertConfig.ruleFromJson("{\"uuid\":\"n1\",\"name\":\"Night low\",\"active\":true,\"above\":false,"
                + "\"threshold\":75,\"all_day\":false,\"start_time_minutes\":1320,\"end_time_minutes\":420,"
                + "\"minutes_between\":2,\"default_snooze\":30,\"vibrate\":true,\"override_silent_mode\":true,"
                + "\"force_speaker\":true,\"mp3_file\":\"content://media/internal/audio/media/4\",\"volume_percent\":35}");
        assertEquals("Night low", r.name);
        assertEquals(75, r.threshold, 0);
        assertFalse(r.allDay);
        assertEquals(1320, r.startMinutes);
        assertEquals(35, r.volumePercent);
        assertEquals("content://media/internal/audio/media/4", r.soundUri);
        for (String bad : new String[]{"[]", "{\"uuid\":\"x\"}", "{\"uuid\":\"x\",\"threshold\":20}",
                "{\"uuid\":\"x\",\"threshold\":80,\"minutes_between\":0}", "not json"}) {
            try {
                AlertConfig.ruleFromJson(bad);
                org.junit.Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        final AlertSettings cur = AlertConfig.defaults().settings.withDisabledUntil(7, 8, 9);
        final AlertSettings s = AlertConfig.settingsFromJson("{\"bg_alert_profile\":\"medium\",\"smart_snoozing\":true,"
                + "\"disable_alerts_stale_data_minutes\":3,\"low_alerts_disabled_until\":0}", cur);
        assertEquals(AlertSettings.MEDIUM, s.profile);
        assertTrue(s.smartSnoozing);
        assertEquals(cur.smartAlerting, s.smartAlerting);
        assertEquals(10, s.staleMinutes); // xDrip+'s minimum
        assertEquals(8, s.lowDisabledUntil); // "off until" is changed only through Disable
    }

    @Test
    public void activeAlertRoundTrips() {
        final ActiveAlert a = new ActiveAlert("x", true, 1_790_000_000_123L, 1_790_000_000_000L);
        final ActiveAlert a2 = AlertConfig.activeFromJson(AlertConfig.activeToJson(a));
        assertEquals(a.toString(), a2.toString());
        assertEquals(a.startedAt, a2.startedAt);
        assertNull(AlertConfig.activeFromJson(""));
        assertNull(AlertConfig.activeFromJson("{]"));
        assertEquals("", AlertConfig.activeToJson(null));
    }
}

package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Map;

import org.junit.Test;

public class XdripAlertImportTest {

    /** Shaped like a real "Save settings" export: Gson JSON, HTML-escaped, with \u003d escapes. */
    static final String EXPORT = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            + "    <boolean name=\"miband_feature_switch_display_on_wrist\" value=\"true\" />\n"
            + "    <string name=\"bg_unclear_readings_alert_reraise_sec\">60</string>\n"
            + "    <string name=\"saved_alerts\">[{&quot;above&quot;:true,&quot;active&quot;:true,&quot;all_day&quot;:true,"
            + "&quot;default_snooze&quot;:90,&quot;end_time_minutes&quot;:0,&quot;force_speaker&quot;:true,&quot;light&quot;:false,"
            + "&quot;minutes_between&quot;:3,&quot;mp3_file&quot;:&quot;content://com.android.providers.downloads.documents/document/msf%3A12&quot;,"
            + "&quot;name&quot;:&quot;High &amp; rising&quot;,&quot;override_silent_mode&quot;:true,&quot;predictive&quot;:false,"
            + "&quot;start_time_minutes&quot;:0,&quot;threshold&quot;:180.0,&quot;time_until_threshold_crossed&quot;:0.0,"
            + "&quot;uuid&quot;:&quot;aaaa-high&quot;,&quot;vibrate&quot;:true,&quot;volume&quot;:0},"
            + "{&quot;above&quot;:false,&quot;active&quot;:true,&quot;all_day&quot;:false,&quot;default_snooze&quot;:45,"
            + "&quot;end_time_minutes&quot;:420,&quot;force_speaker&quot;:false,&quot;light&quot;:false,&quot;minutes_between&quot;:2,"
            + "&quot;mp3_file&quot;:&quot;content://media/external_primary/audio/media/7?title\\u003dChime\\u0026canonical\\u003d1&quot;,"
            + "&quot;name&quot;:&quot;Night low&quot;,&quot;override_silent_mode&quot;:false,&quot;predictive&quot;:false,"
            + "&quot;start_time_minutes&quot;:1320,&quot;threshold&quot;:80.0,&quot;time_until_threshold_crossed&quot;:0.0,"
            + "&quot;uuid&quot;:&quot;bbbb-low&quot;,&quot;vibrate&quot;:false,&quot;volume&quot;:0}]</string>\n"
            + "    <string name=\"bg_alert_profile\">High</string>\n"
            + "    <boolean name=\"smart_snoozing\" value=\"false\" />\n"
            + "    <boolean name=\"smart_alerting\" value=\"false\" />\n"
            + "    <boolean name=\"disable_alerts_stale_data\" value=\"true\" />\n"
            + "    <string name=\"disable_alerts_stale_data_minutes\">25</string>\n"
            + "    <boolean name=\"start_snoozed\" value=\"false\" />\n"
            + "    <boolean name=\"delay_ascending_3min\" value=\"false\" />\n"
            + "    <boolean name=\"ascending_volume_to_medium\" value=\"true\" />\n"
            + "    <boolean name=\"no_alarms_during_calls\" value=\"true\" />\n"
            + "    <string name=\"units\">mgdl</string>\n"
            + "    <long name=\"alerts_disabled_until\" value=\"99999999999999\" />\n"
            + "    <long name=\"low_alerts_disabled_until\" value=\"99999999999999\" />\n"
            + "    <string name=\"empty_one\" />\n"
            + "</map>\n";

    @Test
    public void readsEveryAlertAsXdripHasIt() {
        final XdripAlertImport.Result r = XdripAlertImport.parse(EXPORT);
        assertEquals(2, r.rules.size());

        final AlertRule h = r.rules.get(0);
        assertEquals("aaaa-high", h.uuid);
        assertEquals("High & rising", h.name);
        assertTrue(h.above);
        assertTrue(h.active);
        assertEquals(180, h.threshold, 0);
        assertTrue(h.allDay);
        assertEquals(3, h.minutesBetween);
        assertEquals(90, h.defaultSnooze);
        assertTrue(h.vibrate);
        assertTrue(h.overrideSilent);
        assertTrue(h.forceSpeaker);
        assertEquals("content://com.android.providers.downloads.documents/document/msf%3A12", h.soundUri);

        final AlertRule l = r.rules.get(1);
        assertEquals("bbbb-low", l.uuid);
        assertFalse(l.above);
        assertEquals(80, l.threshold, 0);
        assertFalse(l.allDay);
        assertEquals(1320, l.startMinutes);
        assertEquals(420, l.endMinutes);
        assertEquals(2, l.minutesBetween);
        assertEquals(45, l.defaultSnooze);
        assertFalse(l.vibrate);
        assertFalse(l.overrideSilent);
        assertFalse(l.forceSpeaker);
        assertEquals("content://media/external_primary/audio/media/7?title=Chime&canonical=1", l.soundUri);

        // nothing is added: xDrip+ no longer creates the 55 alert either
        assertNull(AlertEngine.find(r.rules, AlertRule.LOW_ALERT_55));
    }

    @Test
    public void readsTheOptionsForAllAlertsButNotXdripsSnoozes() {
        final AlertSettings s = XdripAlertImport.parse(EXPORT).settings;
        assertEquals(AlertSettings.HIGH, s.profile);
        assertFalse(s.smartSnoozing);
        assertFalse(s.smartAlerting);
        assertTrue(s.disableStale);
        assertEquals(25, s.staleMinutes);
        assertFalse(s.startSnoozed);
        assertFalse(s.delayAscending3min);
        assertTrue(s.ascendingToMedium);
        assertTrue(s.noAlarmsDuringCalls);
        assertEquals(0, s.allDisabledUntil);
        assertEquals(0, s.lowDisabledUntil);
        assertEquals(0, s.highDisabledUntil);
        assertEquals("mgdl", XdripAlertImport.parse(EXPORT).units);
    }

    @Test
    public void missingOptionsTakeXdripsDefaults() {
        final String xml = "<map><string name=\"saved_alerts\">[{&quot;uuid&quot;:&quot;u&quot;,&quot;threshold&quot;:70}]</string>"
                + "<string name=\"units\">mmol</string></map>";
        final XdripAlertImport.Result r = XdripAlertImport.parse(xml);
        final AlertSettings d = AlertSettings.defaults();
        assertEquals(d.profile, r.settings.profile);
        assertEquals(d.smartSnoozing, r.settings.smartSnoozing);
        assertEquals(d.smartAlerting, r.settings.smartAlerting);
        assertEquals(d.delayAscending3min, r.settings.delayAscending3min);
        assertEquals(15, r.settings.staleMinutes);
        assertEquals("mmol", r.units);
        final AlertRule a = r.rules.get(0);
        assertTrue(a.active);
        assertFalse(a.above);
        assertTrue(a.allDay);
        assertEquals(1, a.minutesBetween);
        assertNull(a.soundUri);
    }

    @Test
    public void skipsBrokenAndDuplicateEntries() {
        final String xml = "<map><string name=\"saved_alerts\">[{&quot;uuid&quot;:&quot;u&quot;,&quot;threshold&quot;:70},"
                + "{&quot;uuid&quot;:&quot;u&quot;,&quot;threshold&quot;:60},{&quot;threshold&quot;:60},"
                + "{&quot;uuid&quot;:&quot;v&quot;},{&quot;uuid&quot;:&quot;w&quot;,&quot;threshold&quot;:NaN},3,null]</string></map>";
        final List<AlertRule> rules = XdripAlertImport.parse(xml).rules;
        assertEquals(1, rules.size());
        assertEquals(70, rules.get(0).threshold, 0);
    }

    @Test
    public void explainsWhatIsWrongWithABadFile() {
        expectError("{\"not\":\"xml\"}", "isn't an xDrip+ settings file");
        expectError(null, "isn't an xDrip+ settings file");
        expectError("<map><string name=\"units\">mgdl</string></map>", "no alert list");
        expectError("<map><string name=\"saved_alerts\">[{bad</string></map>", "damaged");
        expectError("<map><string name=\"saved_alerts\">{}</string></map>", "damaged");
        expectError("<map><string name=\"saved_alerts\">[]</string></map>", "no low or high alerts");
    }

    private static void expectError(String xml, String text) {
        try {
            XdripAlertImport.parse(xml);
            fail("expected an error for " + xml);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(text));
        }
    }

    @Test
    public void readsTheFileAsBytesOrInsideAZip() throws Exception {
        assertEquals(2, XdripAlertImport.parse(EXPORT.getBytes(StandardCharsets.UTF_8)).rules.size());

        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("xDrip-export/readme.txt"));
            z.write("x".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("xDrip-export/shared_prefs/com.eveningoutpost.dexdrip_preferences.xml"));
            z.write(EXPORT.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("aaaa-high", XdripAlertImport.parse(b.toByteArray()).rules.get(0).uuid);

        final ByteArrayOutputStream empty = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(empty)) {
            z.putNextEntry(new ZipEntry("other.txt"));
            z.write(1);
        }
        try {
            XdripAlertImport.parse(empty.toByteArray());
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("no xDrip+ settings"));
        }
    }

    @Test
    public void unescapesXml() {
        assertEquals("a<b>&\"'", XdripAlertImport.unescape("a&lt;b&gt;&amp;&quot;&apos;"));
        assertEquals("AB", XdripAlertImport.unescape("&#65;&#x42;"));
        assertEquals("& x &bogus; &#zz;", XdripAlertImport.unescape("& x &bogus; &#zz;"));
    }

    @Test
    public void prefsReadsEveryKind() {
        final Map<String, String> p = XdripAlertImport.prefs("<map><int name=\"a\" value=\"3\" /><float name=\"b\" value=\"1.5\"/>"
                + "<string name=\"c\">x\ny</string><string name=\"d\"/></map>");
        assertEquals("3", p.get("a"));
        assertEquals("1.5", p.get("b"));
        assertEquals("x\ny", p.get("c"));
        assertEquals("", p.get("d"));
    }

    @Test
    public void storedConfigRoundTrips() {
        final XdripAlertImport.Result r = XdripAlertImport.parse(EXPORT);
        final AlertConfig c = new AlertConfig(r.rules, r.settings.withDisabledUntil(1, 2, 3), r.units);
        final AlertConfig back = AlertConfig.fromJson(c.toJson());
        assertEquals(c.toJson(), back.toJson());
        assertEquals("High & rising", back.rules.get(0).name);
        assertEquals(r.rules.get(1).soundUri, back.rules.get(1).soundUri);
        assertEquals(3, back.settings.highDisabledUntil);

        // nothing stored, or damaged: the built-in setup, never no alerts
        assertEquals(AlertConfig.defaults().toJson(), AlertConfig.fromJson("garbage").toJson());
        assertEquals(AlertConfig.defaults().toJson(), AlertConfig.fromJson(null).toJson());
        // stored before the volume level existed: the default level, not xDrip+'s maximum
        assertEquals(AlertConfig.DEFAULT_VOLUME, AlertConfig.fromJson("{\"alerts\":[],\"settings\":{}}").settings.volumePercent);
        assertEquals(35, AlertConfig.fromJson(c.withSettings(c.settings.withVolume(35)).toJson()).settings.volumePercent);
        // an explicitly empty list stays empty
        assertEquals(0, AlertConfig.fromJson(AlertConfig.empty().toJson()).rules.size());

        final ActiveAlert a = new ActiveAlert("bbbb-low", true, 1_790_000_000_123L, 1_790_000_000_000L);
        final ActiveAlert a2 = AlertConfig.activeFromJson(AlertConfig.activeToJson(a));
        assertEquals(a.toString(), a2.toString());
        assertEquals(a.startedAt, a2.startedAt);
        assertNull(AlertConfig.activeFromJson(""));
        assertNull(AlertConfig.activeFromJson("{]"));
        assertEquals("", AlertConfig.activeToJson(null));
    }

    @Test
    public void builtInSetupIsTheOwnersXdripAlerts() {
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
        final AlertRule high = d.rules.get(1);
        assertTrue(high.above);
        assertEquals(155, high.threshold, 0);
        assertEquals(3, high.minutesBetween);
        assertEquals(90, high.snoozeMinutes());
        assertTrue(high.vibrate);
        assertTrue(high.overrideSilent);
        assertTrue(high.forceSpeaker);
        assertEquals(AlertConfig.SOUND_VIBRATION, high.soundUri);
        assertEquals(AlertSettings.HIGH, d.settings.profile);
        assertEquals(AlertConfig.DEFAULT_VOLUME, d.settings.volumePercent);
        assertFalse(d.settings.smartSnoozing);
        assertFalse(d.settings.smartAlerting);
        assertTrue(d.settings.noAlarmsDuringCalls);
        assertEquals("mgdl", d.units);
    }

    @Test
    public void importKeepsThisAppsSnoozesAndVolume() {
        final AlertSettings here = AlertSettings.defaults().withVolume(30).withDisabledUntil(5, 6, 7);
        final AlertSettings imported = XdripAlertImport.parse(EXPORT).settings.keepingLocalOf(here);
        assertEquals(30, imported.volumePercent);
        assertEquals(6, imported.lowDisabledUntil);
        assertEquals(AlertSettings.HIGH, imported.profile);
    }

    @Test
    public void addAndRemoveAlerts() {
        final AlertConfig c = AlertConfig.defaults();
        final AlertRule added = new AlertRule("new-1", "Urgent", true, false, 60, true, 0, 0, 5, 30, true, true, true, null);
        final AlertConfig more = c.withSaved(added);
        assertEquals(3, more.rules.size());
        assertEquals("Urgent", more.rule("new-1").name);
        final AlertConfig edited = more.withSaved(new AlertRule("new-1", "Urgent low", true, false, 65, true, 0, 0, 5, 30,
                true, true, true, null));
        assertEquals(3, edited.rules.size());
        assertEquals(65, edited.rule("new-1").threshold, 0);
        assertEquals(2, edited.without("new-1").rules.size());
        assertNull(edited.without("new-1").rule("new-1"));
    }

    @Test
    public void withRuleReplacesByUuid() {
        final AlertConfig c = new AlertConfig(XdripAlertImport.parse(EXPORT).rules, AlertSettings.defaults(), "mgdl");
        final AlertConfig d = c.withRule(c.rule("bbbb-low").withSettings(75, 5, 30, true, true));
        assertEquals(75, d.rule("bbbb-low").threshold, 0);
        assertEquals(5, d.rule("bbbb-low").minutesBetween);
        assertEquals(180, d.rule("aaaa-high").threshold, 0);
        assertEquals("Chime", d.rule("bbbb-low").soundUri.replaceAll(".*title=([^&]*).*", "$1"));
        assertNull(d.rule("bbbb-low").withSound("").soundUri);
        assertEquals("content://x/1", d.rule("bbbb-low").withSound("content://x/1").soundUri);
    }
}

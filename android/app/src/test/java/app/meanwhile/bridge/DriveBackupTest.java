package app.meanwhile.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.net.Uri;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.meanwhile.bridge.core.AlertConfig;

@RunWith(RobolectricTestRunner.class)
public class DriveBackupTest {

    private Application app;
    private File dir;

    @Before
    public void setUp() {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("backup", 0).edit().clear().commit();
        app.getSharedPreferences("alerts", 0).edit().clear().commit();
        dir = new File(app.getFilesDir(), "backups-test");
        final File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    @Test
    public void withoutAFolderItSaysSo() throws Exception {
        final JSONObject st = new JSONObject(DriveBackup.write(app, "meanwhile-2026-11-19.json", "{}"));
        assertEquals("No backup folder chosen", st.getString("error"));
        assertEquals(0, st.getLong("ok_at"));
        assertTrue(st.getLong("attempt_at") > 0);
    }

    @Test
    public void writesTheDaysFileReplacingItAndReportsIt() throws Exception {
        DriveBackup.setFolder(app, Uri.fromFile(dir).toString(), "test folder");
        DriveBackup.write(app, "meanwhile-2026-11-19.json", "{\"bg\":[1]}");
        JSONObject st = new JSONObject(DriveBackup.write(app, "meanwhile-2026-11-19.json", "{\"bg\":[1,2]}"));
        assertEquals("", st.getString("error"));
        assertEquals("meanwhile-2026-11-19.json", st.getString("file"));
        assertEquals("test folder", st.getString("folder_name"));
        assertEquals(12, st.getLong("bytes"));
        assertTrue(st.getLong("ok_at") > 0);
        assertEquals(Arrays.asList("meanwhile-2026-11-19.json"), Arrays.asList(dir.list()));
        assertEquals("{\"bg\":[1,2]}", new String(Files.readAllBytes(new File(dir, "meanwhile-2026-11-19.json").toPath()),
                StandardCharsets.UTF_8));

        // a bad name is refused, and the last good backup is still reported
        st = new JSONObject(DriveBackup.write(app, "../x.json", "{}"));
        assertEquals("Bad backup file name", st.getString("error"));
        assertEquals("meanwhile-2026-11-19.json", st.getString("file"));
    }

    @Test
    public void keepsTheNewestFourteen() throws Exception {
        DriveBackup.setFolder(app, Uri.fromFile(dir).toString(), "test folder");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        for (int d = 1; d <= 20; d++) {
            Files.write(new File(dir, String.format("meanwhile-2026-11-%02d.json", d)).toPath(), new byte[]{'{', '}'});
        }
        Files.write(new File(dir, "notes.txt").toPath(), new byte[]{'x'});
        DriveBackup.write(app, "meanwhile-2026-11-21.json", "{}");
        final List<String> left = new ArrayList<>(Arrays.asList(dir.list()));
        assertEquals(15, left.size()); // 14 backups + notes.txt
        assertTrue(left.contains("notes.txt"));
        assertTrue(left.contains("meanwhile-2026-11-21.json"));
        assertTrue(left.contains("meanwhile-2026-11-08.json"));
        assertFalse(left.contains("meanwhile-2026-11-07.json"));
    }

    @Test
    public void pruneChoosesTheOldestOfOursOnly() {
        assertEquals(Arrays.asList("meanwhile-2026-01-01.json"),
                DriveBackup.toPrune(Arrays.asList("meanwhile-2026-01-02.json", "other.json", "meanwhile-2026-01-01.json",
                        "meanwhile-2026-01-03.json"), 2));
        assertTrue(DriveBackup.toPrune(Arrays.asList("meanwhile-2026-01-02.json"), 14).isEmpty());
    }

    @Test
    public void alertSetupComesBackFromABackupButNotWhatIsOffNow() throws Exception {
        final AlertConfig saved = AlertConfig.defaults();
        // now: one alert left
        Alerts.update(app, saved.without(saved.rules.get(0).uuid), "test");
        assertEquals(1, Alerts.config(app).rules.size());
        Alerts.disable(app, "low", 60);
        final long offUntil = Alerts.config(app).settings.lowDisabledUntil;
        assertEquals("No alerts in the backup", AlertsBridge.restore(app, "{\"alerts\":[]}", null));
        assertEquals("The backup's alerts couldn't be read", AlertsBridge.restore(app, "not json", null));
        assertEquals("", AlertsBridge.restore(app, saved.toJson(), null));
        shadowOf(Alerts.handler().getLooper()).idle();
        assertEquals(2, Alerts.config(app).rules.size());
        assertEquals(offUntil, Alerts.config(app).settings.lowDisabledUntil);
    }
}

package app.meanwhile.bridge;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

import app.meanwhile.bridge.core.AlertEngine;
import app.meanwhile.bridge.core.AlertRule;
import app.meanwhile.bridge.core.AlertSettings;

/**
 * The noise of an alert, as xDrip+'s {@code AlertPlayer.VibrateNotifyMakeNoise} and
 * {@code playFile} make it: the alert's sound played once, on the alarm stream when "force
 * speaker" is on (else the media stream), with the stream's volume set for the alert and put
 * back afterwards; xDrip+'s vibration pattern; nothing while on a call if so set; and only an
 * alert that overrides silent mode sounds while the phone is on silent or vibrate.
 * All calls come on the alert thread.
 */
final class AlertSound implements Alerts.Noise {

    /** xDrip+ {@code Notifications.vibratePattern}. */
    static final long[] VIBRATE_PATTERN = {0, 1000, 300, 1000, 300, 1000};

    private final Handler handler;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private int stream = AudioManager.STREAM_ALARM;
    private int volumeBefore = -1;
    private int volumeForAlert = -1;

    AlertSound(Handler handler) {
        this.handler = handler;
    }

    @Override
    public void alert(Context c, AlertRule rule, AlertSettings settings, AlertEngine.Sound sound) {
        final AudioManager am = c.getSystemService(AudioManager.class);
        final boolean inCall = settings.noAlarmsDuringCalls && am != null && am.getMode() == AudioManager.MODE_IN_CALL;
        final int ringer = am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
        if (rule.overrideSilent && !inCall && (sound.volume > 0 || sound.vibrate)) throughDoNotDisturb(c);
        if (sound.volume > 0) {
            if (inCall) {
                EventLog.log(c, "SOUND", "no sound: on a call");
            } else if (rule.overrideSilent || ringer == AudioManager.RINGER_MODE_NORMAL) {
                play(c, rule.soundUri, sound.volume, rule.forceSpeaker, rule.overrideSilent);
            } else {
                EventLog.log(c, "SOUND", "no sound: the phone is on silent or vibrate and the alert doesn't override that");
            }
        }
        if (sound.vibrate && !inCall && (rule.overrideSilent || ringer != AudioManager.RINGER_MODE_SILENT)) {
            vibrate(c);
        }
    }

    /**
     * A critical alert (one that sounds on silent) plays on the alarm volume, which Do Not
     * Disturb lets through unless it is set to total silence. Then, if allowed to manage Do
     * Not Disturb, EverBridge switches it to "alarms only" so the alert is heard.
     */
    static void throughDoNotDisturb(Context c) {
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            if (nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_NONE) return;
            if (!nm.isNotificationPolicyAccessGranted()) {
                EventLog.log(c, "SOUND", "Do Not Disturb is on total silence and EverBridge may not change it: "
                        + "allow it on the alerts screen");
                return;
            }
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
            EventLog.log(c, "SOUND", "Do Not Disturb was on total silence: switched to alarms only for a critical alert");
        } catch (RuntimeException e) {
            EventLog.log(c, "SOUND", "could not change Do Not Disturb: " + e);
        }
    }

    @Override
    public void test(Context c, AlertRule rule) {
        play(c, rule.soundUri, 1f, rule.forceSpeaker, true);
    }

    @Override
    public void stop(Context c) {
        release(c);
        final Vibrator v = vibrator(c);
        if (v != null) v.cancel();
    }

    // ------------------------------------------------------------------ sound

    private void play(Context c, String uri, float volume, boolean forceSpeaker, boolean overrideSilent) {
        release(c);
        final AudioManager am = c.getSystemService(AudioManager.class);
        stream = forceSpeaker ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC;
        final AudioAttributes attrs = attributes(forceSpeaker);
        final MediaPlayer mp = new MediaPlayer();
        final String source;
        try {
            mp.setAudioAttributes(attrs);
            source = setSource(c, mp, uri);
        } catch (RuntimeException e) {
            mp.release();
            EventLog.log(c, "SOUND", "media player failed (" + e + "): beeping instead");
            tone(c, forceSpeaker);
            return;
        }
        if (source == null) {
            mp.release();
            EventLog.log(c, "SOUND", "no sound could be opened: beeping instead");
            tone(c, forceSpeaker);
            return;
        }
        player = mp;
        mp.setLooping(false);
        mp.setOnCompletionListener(m -> {
            if (m == player) release(c);
        });
        mp.setOnErrorListener((m, what, extra) -> {
            EventLog.log(c, "SOUND", "could not play " + source + " (" + what + "/" + extra + "): beeping instead");
            if (m == player) release(c);
            tone(c, forceSpeaker);
            return true;
        });
        mp.setOnPreparedListener(m -> {
            if (m != player) return;
            if (am != null) {
                adjustVolume(am, volume, overrideSilent);
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs).build();
                am.requestAudioFocus(focus);
            }
            m.start();
            EventLog.log(c, "SOUND", "playing " + source + " at " + Math.round(volume * 100) + "% on the "
                    + (stream == AudioManager.STREAM_ALARM ? "alarm" : "media") + " volume");
        });
        try {
            mp.prepareAsync();
        } catch (RuntimeException e) {
            release(c);
            EventLog.log(c, "SOUND", "media player failed (" + e + "): beeping instead");
            tone(c, forceSpeaker);
        }
    }

    /** The alert's own sound, else the phone's alarm, notification or ringtone sound. Returns which, or null. */
    private static String setSource(Context c, MediaPlayer mp, String uri) {
        if (uri != null && !uri.isEmpty() && !"default".equals(uri)) {
            try {
                mp.setDataSource(c, Uri.parse(uri));
                return "the alert's sound";
            } catch (Exception e) {
                EventLog.log(c, "SOUND", "can't open the alert's sound " + uri + " (" + e.getClass().getSimpleName()
                        + "); using the phone's alarm sound. Choose the sound again on the alerts screen.");
                mp.reset();
            }
        }
        final Uri[] fallbacks = {Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_NOTIFICATION_URI,
                Settings.System.DEFAULT_RINGTONE_URI};
        final String[] names = {"the phone's alarm sound", "the phone's notification sound", "the phone's ringtone"};
        for (int i = 0; i < fallbacks.length; i++) {
            try {
                mp.setDataSource(c, fallbacks[i]);
                return names[i];
            } catch (Exception e) {
                mp.reset();
            }
        }
        return null;
    }

    /** Last resort when no sound file plays: a few seconds of beeping. */
    private void tone(Context c, boolean forceSpeaker) {
        try {
            final ToneGenerator tg = new ToneGenerator(forceSpeaker ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC,
                    ToneGenerator.MAX_VOLUME);
            tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 4000);
            handler.postDelayed(tg::release, 4500);
        } catch (RuntimeException e) {
            EventLog.log(c, "SOUND", "beep failed too: " + e);
        }
    }

    private void release(Context c) {
        final MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (RuntimeException ignored) {
                // not started yet
            }
            mp.release();
        }
        final AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            if (focus != null) am.abandonAudioFocusRequest(focus);
            revertVolume(am);
        }
        focus = null;
    }

    /** xDrip+ {@code adjustCurrentVolumeForAlert}. */
    private void adjustVolume(AudioManager am, float volume, boolean overrideSilent) {
        final int max = am.getStreamMaxVolume(stream);
        if (volumeBefore == -1) volumeBefore = am.getStreamVolume(stream);
        volumeForAlert = (int) (max * volume);
        if ((volumeForAlert != 0 && volumeBefore <= 0 && overrideSilent)
                || (volumeBefore > 0 && volumeBefore != volumeForAlert)) {
            setVolume(am, volumeForAlert);
        }
    }

    /** xDrip+ {@code revertCurrentVolume}: only if the user hasn't changed it meanwhile. */
    private void revertVolume(AudioManager am) {
        if (volumeBefore != -1 && volumeForAlert != -1 && am.getStreamVolume(stream) == volumeForAlert) {
            setVolume(am, volumeBefore);
        }
        volumeBefore = -1;
        volumeForAlert = -1;
    }

    private void setVolume(AudioManager am, int v) {
        try {
            am.setStreamVolume(stream, v, 0);
        } catch (SecurityException ignored) {
            // e.g. Do Not Disturb rules: play at the volume it is
        }
    }

    private static AudioAttributes attributes(boolean alarmStream) {
        return new AudioAttributes.Builder()
                .setUsage(alarmStream ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    // ------------------------------------------------------------------ vibration

    private static void vibrate(Context c) {
        final Vibrator v = vibrator(c);
        if (v == null || !v.hasVibrator()) return;
        final VibrationEffect effect = VibrationEffect.createWaveform(VIBRATE_PATTERN, -1);
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            v.vibrate(effect, attributes(true));
        }
    }

    private static Vibrator vibrator(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            final VibratorManager vm = c.getSystemService(VibratorManager.class);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return c.getSystemService(Vibrator.class);
    }
}

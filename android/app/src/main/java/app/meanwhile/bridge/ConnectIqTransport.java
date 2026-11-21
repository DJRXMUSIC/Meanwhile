package app.meanwhile.bridge;

import android.content.Context;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@link GarminPush.Transport} over Garmin's Connect IQ Mobile SDK (through Garmin Connect). */
final class ConnectIqTransport implements GarminPush.Transport {

    private ConnectIQ ciq;
    private Context context;
    private final IQApp watchFace = new IQApp(GarminPush.WATCH_FACE_ID);

    @Override
    public void start(Context context, Events events) {
        this.context = context;
        ciq = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS);
        ciq.initialize(context, false, new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                watchDevices(events);
                events.onReady();
            }

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                events.onUnavailable(String.valueOf(status));
            }

            @Override
            public void onSdkShutDown() {
                events.onUnavailable("Garmin Connect shut down");
            }
        });
    }

    /** Hears when a watch (re)connects, so it is sent the current reading straight away. */
    private void watchDevices(Events events) {
        try {
            for (IQDevice d : ciq.getKnownDevices()) {
                ciq.registerForDeviceEvents(d, (device, status) -> {
                    if (status == IQDevice.IQDeviceStatus.CONNECTED) events.onDeviceConnected(name(device));
                });
            }
        } catch (InvalidStateException | ServiceUnavailableException | RuntimeException e) {
            EventLog.log(context, "GARMIN", "can't watch for watches connecting: " + e);
        }
    }

    @Override
    public List<Object> connectedDevices() {
        try {
            return new ArrayList<>(ciq.getConnectedDevices());
        } catch (InvalidStateException | ServiceUnavailableException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String name(Object device) {
        final IQDevice d = (IQDevice) device;
        final String friendly = d.getFriendlyName();
        return friendly != null ? friendly : Long.toString(d.getDeviceIdentifier());
    }

    @Override
    public void send(Object device, Map<String, Object> message, Result result) {
        try {
            ciq.sendMessage((IQDevice) device, watchFace, message,
                    (d, app, status) -> result.done(status == ConnectIQ.IQMessageStatus.SUCCESS, String.valueOf(status)));
        } catch (InvalidStateException | ServiceUnavailableException e) {
            result.done(false, e.getClass().getSimpleName());
        }
    }

    @Override
    public void stop(Context context) {
        if (ciq == null) return;
        try {
            ciq.shutdown(context);
        } catch (InvalidStateException ignored) {
            // not running
        }
    }
}

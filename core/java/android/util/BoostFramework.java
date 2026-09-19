package android.util;

import android.content.Context;
import android.hardware.power.Boost;
import android.hardware.power.Mode;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;

import com.android.server.LocalServices;

/**
 * @hide
 */
public class BoostFramework {
    public static final int VENDOR_HINT_SCROLL_BOOST = 1;
    public static final int VENDOR_HINT_FIRST_LAUNCH_BOOST = 2;
    public static final int VENDOR_HINT_TAP_EVENT = 3;
    public static final int VENDOR_HINT_DRAG_BOOST = 4;
    public static final int VENDOR_HINT_ANIMATION_BOOST = 5;
    public static final int VENDOR_HINT_FRAME_RECOVERY_BOOST = 6;
    public static final int VENDOR_FEEDBACK_WORKLOAD_TYPE = 7;

    public static final int DURATION_TAP = 150;
    public static final int DURATION_SCROLL = 350;
    public static final int DURATION_LAUNCH = 1200;
    public static final int DURATION_ANIMATION = 400;

    private static final long THROTTLE_INTERVAL_MS = 50;

    private static final Object sLock = new Object();
    private static volatile BoostFramework sInstance;
    private static PowerManagerInternal sLocalPowerManager;
    private static IPowerManager sRemotePowerManager;
    private static boolean sLocalChecked = false;

    private long mLastScrollBoostTime = 0;
    private long mLastTapBoostTime = 0;

    public BoostFramework() {
    }

    public BoostFramework(Context context) {
    }

    public static BoostFramework getInstance() {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new BoostFramework();
                }
            }
        }
        return sInstance;
    }

    private static PowerManagerInternal getLocalPowerManager() {
        if (!sLocalChecked) {
            try {
                sLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
            } catch (Throwable ignored) {
            }
            sLocalChecked = true;
        }
        return sLocalPowerManager;
    }

    private static IPowerManager getRemotePowerManager() {
        if (sRemotePowerManager == null) {
            try {
                IBinder binder = ServiceManager.getService(Context.POWER_SERVICE);
                if (binder != null) {
                    sRemotePowerManager = IPowerManager.Stub.asInterface(binder);
                }
            } catch (Throwable ignored) {
            }
        }
        return sRemotePowerManager;
    }

    public int perfHint(int hintId, String pkg, int duration, int type) {
        try {
            switch (hintId) {
                case VENDOR_HINT_TAP_EVENT: {
                    long now = SystemClock.uptimeMillis();
                    if (now - mLastTapBoostTime < THROTTLE_INTERVAL_MS) {
                        return 0;
                    }
                    mLastTapBoostTime = now;
                    sendPowerBoost(Boost.INTERACTION, duration > 0 ? duration : DURATION_TAP);
                    break;
                }
                case VENDOR_HINT_SCROLL_BOOST:
                case VENDOR_HINT_DRAG_BOOST: {
                    long now = SystemClock.uptimeMillis();
                    if (now - mLastScrollBoostTime < THROTTLE_INTERVAL_MS) {
                        return 0;
                    }
                    mLastScrollBoostTime = now;
                    sendPowerBoost(Boost.INTERACTION, duration > 0 ? duration : DURATION_SCROLL);
                    break;
                }
                case VENDOR_HINT_FIRST_LAUNCH_BOOST: {
                    sendPowerMode(Mode.LAUNCH, true);
                    break;
                }
                case VENDOR_HINT_ANIMATION_BOOST: {
                    sendPowerBoost(Boost.DISPLAY_UPDATE_IMMINENT, duration > 0 ? duration : DURATION_ANIMATION);
                    break;
                }
                case VENDOR_HINT_FRAME_RECOVERY_BOOST: {
                    sendPowerBoost(Boost.INTERACTION, duration > 0 ? duration : 50);
                    break;
                }
            }
            return 0;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public int perfHint(int hintId, String pkg) {
        return perfHint(hintId, pkg, -1, -1);
    }

    public int perfLockAcquire(int duration, int[] list) {
        return perfHint(VENDOR_HINT_SCROLL_BOOST, null, duration, 0);
    }

    public int perfLockRelease() {
        try {
            sendPowerMode(Mode.LAUNCH, false);
            return 0;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public int perfLockReleaseHandler(int handle) {
        return perfLockRelease();
    }

    private static void sendPowerBoost(int boost, int durationMs) {
        PowerManagerInternal pmi = getLocalPowerManager();
        if (pmi != null) {
            pmi.setPowerBoost(boost, durationMs);
            return;
        }
        IPowerManager ipm = getRemotePowerManager();
        if (ipm != null) {
            try {
                ipm.setPowerBoost(boost, durationMs);
            } catch (RemoteException ignored) {
                sRemotePowerManager = null;
            }
        }
    }

    private static void sendPowerMode(int mode, boolean enabled) {
        PowerManagerInternal pmi = getLocalPowerManager();
        if (pmi != null) {
            pmi.setPowerMode(mode, enabled);
            return;
        }
        IPowerManager ipm = getRemotePowerManager();
        if (ipm != null) {
            try {
                ipm.setPowerMode(mode, enabled);
            } catch (RemoteException ignored) {
                sRemotePowerManager = null;
            }
        }
    }

    public static void boostTap() {
        getInstance().perfHint(VENDOR_HINT_TAP_EVENT, null);
    }

    public static void boostScroll(int duration) {
        getInstance().perfHint(VENDOR_HINT_SCROLL_BOOST, null, duration, 0);
    }

    public static void boostLaunch(boolean start) {
        if (start) {
            getInstance().perfHint(VENDOR_HINT_FIRST_LAUNCH_BOOST, null);
        } else {
            getInstance().perfLockRelease();
        }
    }

    public static void boostAnimation(int duration) {
        getInstance().perfHint(VENDOR_HINT_ANIMATION_BOOST, null, duration, 0);
    }
}

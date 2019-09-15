package acidhax.cordova.chromecast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;

import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaRouter.RouteInfo;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.CastState;
import com.google.android.gms.cast.framework.CastStateListener;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;

import org.apache.cordova.CallbackContext;

import java.util.ArrayList;
import java.util.List;

public class ChromecastConnection {

    /** Lifetime variable. */
    private Activity activity;
    /** settings object. */
    private SharedPreferences settings;

    /** Lifetime variable. */
    private SessionListener newConnectionListener;
    /** The Listener callback. */
    private Listener listener;

    /** Initialize lifetime variable. */
    private String appId;

    /**
     * Constructor.
     * @param act the current context
     * @param connectionListener client callbacks for specific events
     */
    public ChromecastConnection(Activity act, Listener connectionListener) {
        this.activity = act;
        this.settings = activity.getSharedPreferences("CORDOVA-PLUGIN-CHROMECAST_ChromecastConnection", 0);
        this.appId = settings.getString("appId", CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID);
        this.listener = connectionListener;
        // Set the initial appId
        CastOptionsProvider.setAppId(appId);

        // This is the first call to getContext which will start up the
        // CastContext and prep it for searching for a session to rejoin
        // Also adds the receiver update callback
        getContext().addCastStateListener(listener);
    }

    /**
     * Must be called each time the appId changes and at least once before any other method is called.
     * @param applicationId the app id to use
     * @param callback called when initialization is complete
     */
    public void initialize(String applicationId, CallbackContext callback) {
        activity.runOnUiThread(new Runnable() {
            public void run() {

                // If the app Id changed, set it again
                if (!applicationId.equals(appId)) {
                    setAppId(applicationId);
                }

                // Tell the client that initialization was a success
                callback.success();

                // Check if there is any available receivers for 5 seconds
                scanForRoutes(5000L, new ScanCallback() {
                    @Override
                    void onRouteUpdate(List<RouteInfo> routes) {
                        // if the routes have changed, we may have an available device
                        // If there is at least one device available
                        if (getContext().getCastState() != CastState.NO_DEVICES_AVAILABLE) {
                            // Stop the scan
                            stopScan(this);
                            // Let the client know a receiver is available
                            listener.onReceiverAvailableUpdate(true);
                            // Since we have a receiver we may also have an active session
                            CastSession session = getSessionManager().getCurrentCastSession();
                            // If we do have a session
                            if (session != null) {
                                // Let the client know
                                listener.onSessionRejoined(session);
                            }
                        }
                    }
                }, null);
            }
        });
    }

    private MediaRouter getMediaRouter() {
        return MediaRouter.getInstance(activity);
    }

    private CastContext getContext() {
        return CastContext.getSharedInstance(activity);
    }

    private SessionManager getSessionManager() {
        return getContext().getSessionManager();
    }

    private CastSession getSession() {
        return getSessionManager().getCurrentCastSession();
    }

    private void setAppId(String applicationId) {
        this.appId = applicationId;
        this.settings.edit().putString("appId", appId).apply();
        getContext().setReceiverApplicationId(appId);
        // Invalidate any old session
        listener.onInvalidateSession();
    }

    /**
     * This will create a new session or seamlessly join an existing one if we created it.
     * @param routeId the id of the route to join
     * @param routeName the name of the route
     * @param callback calls callback.onJoin when we have joined a session,
     *                 or callback.onError if an error occurred
     */
    public void join(final String routeId, final String routeName, JoinCallback callback) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (getSession() != null) {
                    // We are are already connected to a route
                    callback.onJoin(getSession());
                    return;
                }

                // We need this hack so that we can access the foundRoute value
                // Without having to store it as a global variable.
                // Just always access first element
                final boolean[] foundRoute = {false};

                listenForConnection(callback);

                // We need to start an active scan because getMediaRouter().getRoutes() may be out
                // of date.  Also, maintaining a list of known routes doesn't work.  It is possible
                // to have a route in your "known" routes list, but is not in
                // getMediaRouter().getRoutes() which will result in "Ignoring attempt to select
                // removed route: ", even if that route *should* be available.  This state could
                // happen because routes are periodically "removed" and "added", and if the last
                // time media router was scanning ended when the route was temporarily removed the
                // getRoutes() fn will have no record of the route.  We need the active scan to
                // avoid this situation as well.  PS. Just running the scan non-stop is a poor idea
                // since it will drain battery power quickly.
                ScanCallback scan = new ScanCallback() {
                    @Override
                    void onRouteUpdate(List<RouteInfo> routes) {
                        // Look for the matching route
                        for (RouteInfo route : routes) {
                            if (!foundRoute[0] && route.getId().equals(routeId)) {
                                // Found the route!
                                foundRoute[0] = true;
                                // So stop the scan
                                stopScan(this);
                                // And select it!
                                getMediaRouter().selectRoute(route);
                            }
                        }
                    }
                };
                scanForRoutes(5000L, scan, new Runnable() {
                    @Override
                    public void run() {
                        // If we were not able to find the route
                        if (!foundRoute[0]) {
                            stopScan(scan);
                            callback.onError("TIMEOUT Could not find active route with id: "
                                    + routeId + " after 5s.");
                        }
                    }
                });
            }
        });
    }

    /**
     * Will do one of two things:
     *
     * If no current connection will:
     * 1)
     * Displays the built in native prompt to the user.
     * It will actively scan for routes and display them to the user.
     * Upon selection it will immediately attempt to join the route.
     * Will call onJoin or onError of callback.
     *
     * Else we have a connection, so:
     * 2)
     * Displays the active connection dialog which includes the option
     * to disconnect.
     * Will only call onError of callback if the user cancels the dialog.
     *
     * @param callback calls callback.success when we have joined a session,
     *                 or callback.error if an error occurred or if the dialog was dismissed
     */
    public void showConnectionDialog(JoinCallback callback) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                CastSession session = getSession();
                if (session == null) {
                    // show the "choose a connection" dialog

                    // Add the connection listener callback
                    listenForConnection(callback);

                    // Create the dialog
                    // TODO accept theme as a config.xml option
                    MediaRouteChooserDialog builder = new MediaRouteChooserDialog(activity, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar);
                    builder.setRouteSelector(new MediaRouteSelector.Builder()
                            .addControlCategory(CastMediaControlIntent.categoryForCast(appId))
                            .build());
                    builder.setCanceledOnTouchOutside(true);
                    builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            getSessionManager().removeSessionManagerListener(newConnectionListener, CastSession.class);
                            callback.onError("CANCEL");
                        }
                    });
                    builder.show();
                } else {
                    // We are are already connected, so show the "connection options" Dialog
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    if (session.getCastDevice() != null) {
                        builder.setTitle(session.getCastDevice().getFriendlyName());
                    }
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            callback.onError("CANCEL");
                        }
                    });
                    builder.setPositiveButton("Stop Casting", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            endSession(true, null);
                        }
                    });
                    builder.show();
                }
            }
        });
    }

    /**
     * Must be called from the main thread.
     * @param callback calls callback.success when we have joined, or callback.error if an error occurred
     */
    private void listenForConnection(JoinCallback callback) {
        // We should only ever have one of these listeners active at a time, so remove previous
        getSessionManager().removeSessionManagerListener(newConnectionListener, CastSession.class);
        newConnectionListener = new SessionListener() {
            @Override
            public void onSessionStarted(CastSession castSession, String sessionId) {
                getSessionManager().removeSessionManagerListener(this, CastSession.class);
                listener.onSessionStarted(castSession);
                callback.onJoin(castSession);
            }
            @Override
            public void onSessionStartFailed(CastSession castSession, int errCode) {
                getSessionManager().removeSessionManagerListener(this, CastSession.class);
                callback.onError(Integer.toString(errCode));
            }
        };
        getSessionManager().addSessionManagerListener(newConnectionListener, CastSession.class);
    }

    /**
     * Starts listening for receiver updates.
     * Must call stopScan(callback) or the battery will drain with non-stop active scanning.
     * @param timeout ms until the scan automatically stops,
     *                if 0 only calls callback.onRouteUpdate once with the currently known routes
     *                if null, will scan until stopScan is called
     * @param callback the callback to receive route updates on
     * @param onTimeout called when the timeout hits
     */
    public void scanForRoutes(Long timeout, ScanCallback callback, Runnable onTimeout) {
        // Add the callback in active scan mode
        activity.runOnUiThread(new Runnable() {
            public void run() {
                callback.setMediaRouter(getMediaRouter());

                if (timeout != null && timeout == 0) {
                    // Send out the one time routes
                    callback.onFilteredRouteUpdate();
                    return;
                }

                // Add the callback in active scan mode
                getMediaRouter().addCallback(new MediaRouteSelector.Builder()
                        .addControlCategory(CastMediaControlIntent.categoryForCast(appId))
                        .build(),
                        callback,
                        MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);

                // Send out the initial routes after the callback has been added.
                // This is important because if the callback calls stopScan only once, and it
                // happens during this call of "onFilterRouteUpdate", there must actually be an
                // added callback to remove to stop the scan.
                callback.onFilteredRouteUpdate();

                if (timeout != null) {
                    // remove the callback after timeout ms, and notify caller
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // And stop the scan for routes
                            getMediaRouter().removeCallback(callback);
                            // Notify
                            if (onTimeout != null) {
                                onTimeout.run();
                            }
                        }
                    }, timeout);
                }
            }
        });
    }

    /**
     * Call to stop the active scan if any exist.
     * @param callback the callback to stop and remove
     */
    public void stopScan(ScanCallback callback) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                callback.stop();
                getMediaRouter().removeCallback(callback);
            }
        });
    }

    /**
     * Exits the current session.
     * @param stopCasting should the receiver application  be stopped as well?
     * @param callback called with .success or .error depending on the initial result
     */
    public void endSession(boolean stopCasting, CallbackContext callback) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                getSessionManager().addSessionManagerListener(new SessionListener() {
                    @Override
                    public void onSessionEnded(CastSession castSession, int error) {
                        getSessionManager().removeSessionManagerListener(this, CastSession.class);
                        listener.onSessionEnd(castSession, stopCasting ? "stopped" : "disconnected");
                    }
                }, CastSession.class);

                getSessionManager().endCurrentSession(stopCasting);
                if (callback != null) {
                    callback.success();
                }
            }
        });
    }

    /**
     * Create this empty class so that we don't have to override every function
     * each time we need a SessionManagerListener.
     */
    private class SessionListener implements SessionManagerListener<CastSession> {
        @Override
        public void onSessionStarting(CastSession castSession) { }
        @Override
        public void onSessionStarted(CastSession castSession, String sessionId) { }
        @Override
        public void onSessionStartFailed(CastSession castSession, int error) { }
        @Override
        public void onSessionEnding(CastSession castSession) { }
        @Override
        public void onSessionEnded(CastSession castSession, int error) { }
        @Override
        public void onSessionResuming(CastSession castSession, String sessionId) { }
        @Override
        public void onSessionResumed(CastSession castSession, boolean wasSuspended) { }
        @Override
        public void onSessionResumeFailed(CastSession castSession, int error) { }
        @Override
        public void onSessionSuspended(CastSession castSession, int reason) { }
    }

    public interface JoinCallback {
        /**
         * Successfully joined a session on a route.
         * @param session the session we joined
         */
        void onJoin(CastSession session);

        /**
         * Called if we received an error.
         * @param errorCode "CANCEL" means the user cancelled
         *                  If the errorCode is an integer, you can find the meaning here:
         *                 https://developers.google.com/android/reference/com/google/android/gms/cast/CastStatusCodes
         */
        void onError(String errorCode);
    }

    public abstract static class ScanCallback extends MediaRouter.Callback {
        /**
         * Called whenever a route is updated.
         * @param routes the currently available routes
         */
        abstract void onRouteUpdate(List<RouteInfo> routes);

        /** records whether we have been stopped or not. */
        private boolean stopped = false;
        /** Global mediaRouter object. */
        private MediaRouter mediaRouter;

        /**
         * Sets the mediaRouter object.
         * @param router mediaRouter object
         */
        void setMediaRouter(MediaRouter router) {
            this.mediaRouter = router;
        }

        /**
         * Call this method when you wish to stop scanning.
         * It is important that it is called, otherwise battery
         * life will drain more quickly.
         */
        void stop() {
            stopped = true;
        }
        private void onFilteredRouteUpdate() {
            if (stopped || mediaRouter == null) {
                return;
            }
            List<RouteInfo> outRoutes = new ArrayList<>();
            // Filter the routes
            for (RouteInfo route : mediaRouter.getRoutes()) {
                // We don't want default routes, or duplicate active routes
                // or multizone duplicates https://github.com/jellyfin/cordova-plugin-chromecast/issues/32
                Bundle extras = route.getExtras();
                if (extras != null) {
                    CastDevice.getFromBundle(extras);
                    if (extras.getString("com.google.android.gms.cast.EXTRA_SESSION_ID") != null) {
                        continue;
                    }
                }
                if (!route.isDefault()
                        && !route.getDescription().equals("Google Cast Multizone Member")
                        && route.getPlaybackType() == RouteInfo.PLAYBACK_TYPE_REMOTE
                ) {
                    outRoutes.add(route);
                }
            }
            onRouteUpdate(outRoutes);
        }
        @Override
        public final void onRouteAdded(MediaRouter router, RouteInfo route) {
            onFilteredRouteUpdate();
        }
        @Override
        public final void onRouteChanged(MediaRouter router, RouteInfo route) {
            onFilteredRouteUpdate();
        }
        @Override
        public final void onRouteRemoved(MediaRouter router, RouteInfo route) {
            onFilteredRouteUpdate();
        }
    }

    abstract static class Listener implements CastStateListener {
        abstract void onReceiverAvailableUpdate(boolean available);
        abstract void onSessionStarted(CastSession castSession);
        abstract void onSessionRejoined(CastSession castSession);
        abstract void onSessionEnd(CastSession castSession, String state);
        abstract void onInvalidateSession();

        /** CastStateListener functions. */
        @Override
        public void onCastStateChanged(int state) {
            onReceiverAvailableUpdate(state != CastState.NO_DEVICES_AVAILABLE);
        }
    }

}

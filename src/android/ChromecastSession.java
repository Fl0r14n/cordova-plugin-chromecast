package acidhax.cordova.chromecast;

import java.io.IOException;
import java.util.GregorianCalendar;
import java.util.Iterator;

import org.apache.cordova.CallbackContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.TextTrackStyle;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.framework.media.RemoteMediaClient.MediaChannelResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.images.WebImage;

import android.app.Activity;
import android.net.Uri;
import androidx.annotation.NonNull;

/*
 * All of the Chromecast session specific functions should start here.
 */
public class ChromecastSession {

    /** The current context. */
    private Activity activity;
    /** A registered callback that we will un-register and re-register each time the session changes. */
    private Listener clientListener;
    /** The current session. */
    private CastSession session;
    /** The current session's client for controlling playback. */
    private RemoteMediaClient client;

    /**
     * ChromecastSession constructor.
     * @param act the current activity
     * @param listener callback that will notify of certain events
     */
    public ChromecastSession(Activity act, @NonNull Listener listener) {
        this.activity = act;
        this.clientListener = listener;
    }

    /**
     * Sets the session object the will be used for other commands in this class.
     * @param castSession the session to use
     */
    public void setSession(CastSession castSession) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (castSession == null) {
                    client = null;
                    return;
                }
                if (castSession.equals(session)) {
                    // Don't client and listeners if session did not change
                    return;
                }
                session = castSession;
                client = session.getRemoteMediaClient();
                if (client == null) {
                    return;
                }
                client.registerCallback(new RemoteMediaClient.Callback() {
                    private String currentState = "idle";
                    @Override
                    public void onStatusUpdated() {
                        MediaStatus status = client.getMediaStatus();
                        if (status != null) {
                            switch (status.getPlayerState()) {
                                case MediaStatus.PLAYER_STATE_LOADING:
                                case MediaStatus.PLAYER_STATE_IDLE:
                                    if (!currentState.equals("requesting")) {
                                        currentState = "loading";
                                    }
                                    break;
                                default:
                                    if (currentState.equals("loading")) {
                                        clientListener.onMediaLoaded(createMediaObject());
                                    }
                                    currentState = "loaded";
                                    break;
                            }
                        }
                        clientListener.onMediaUpdate(createMediaObject());
                    }
                    @Override
                    public void onMetadataUpdated() {
                        clientListener.onMediaUpdate(createMediaObject());
                    }
                    @Override
                    public void onQueueStatusUpdated() {
                        clientListener.onMediaUpdate(createMediaObject());
                    }
                    @Override
                    public void onPreloadStatusUpdated() {
                        clientListener.onMediaUpdate(createMediaObject());
                    }
                    @Override
                    public void onSendingRemoteMediaRequest() {
                        currentState = "requesting";
                        clientListener.onMediaUpdate(createMediaObject());
                    }
                    @Override
                    public void onAdBreakStatusUpdated() {
                        clientListener.onMediaUpdate(createMediaObject());
                    }
                });
                session.addCastListener(new Cast.Listener() {
                    @Override
                    public void onApplicationStatusChanged() {
                        clientListener.onSessionUpdate(createSessionObject());
                    }
                    @Override
                    public void onApplicationMetadataChanged(ApplicationMetadata appMetadata) {
                        clientListener.onSessionUpdate(createSessionObject());
                    }
                    @Override
                    public void onApplicationDisconnected(int i) {
                        clientListener.onSessionEnd(
                                ChromecastUtilities.createSessionObject(session, "stopped"));
                    }
                    @Override
                    public void onActiveInputStateChanged(int i) {
                        clientListener.onSessionUpdate(createSessionObject());
                    }
                    @Override
                    public void onStandbyStateChanged(int i) {
                        clientListener.onSessionUpdate(createSessionObject());
                    }
                    @Override
                    public void onVolumeChanged() {
                        clientListener.onSessionUpdate(createSessionObject());
                    }
                });
            }
        });
    }

    /**
     * Adds a message listener if one does not already exist.
     * @param namespace namespace
     */
    public void addMessageListener(String namespace) {
        if (client == null || session == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    session.setMessageReceivedCallbacks(namespace, clientListener);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Sends a message to a specified namespace.
     * @param namespace namespace
     * @param message the message to send
     * @param callback called with success or error
     */
    public void sendMessage(String namespace, String message, CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                session.sendMessage(namespace, message).setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status result) {
                        if (!result.isSuccess()) {
                            callback.success();
                        } else {
                            callback.error(result.toString());
                        }
                    }
                });

            }
        });
    }

    /**
     * Loads media over the media API.
     * @param contentId      - The URL of the content
     * @param customData     - CustomData
     * @param contentType    - The MIME type of the content
     * @param duration       - The length of the video (if known)
     * @param streamType     - The stream type
     * @param autoPlay       - Whether or not to start the video playing or not
     * @param currentTime    - Where in the video to begin playing from
     * @param metadata       - Metadata
     * @param textTrackStyle - The text track style
     * @param callback called with success or error
     */
    public void loadMedia(String contentId, JSONObject customData, String contentType, long duration, String streamType, boolean autoPlay, double currentTime, JSONObject metadata, JSONObject textTrackStyle, CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                MediaInfo mediaInfo = createMediaInfo(contentId, customData, contentType, duration, streamType, metadata, textTrackStyle);
                MediaLoadRequestData loadRequest = new MediaLoadRequestData.Builder()
                        .setMediaInfo(mediaInfo)
                        .setAutoplay(autoPlay)
                        .setCurrentTime((long) currentTime * 1000)
                        .build();

                client.load(loadRequest).setResultCallback(new ResultCallback<MediaChannelResult>() {
                    @Override
                    public void onResult(@NonNull MediaChannelResult result) {
                        if (result.getStatus().isSuccess()) {
                            JSONObject out = createMediaObject();
                            if (out == null) {
                                callback.success();
                            } else {
                                callback.success(out);
                            }
                        } else {
                            callback.error("session_error");
                        }
                    }
                });
            }
        });
    }

    private MediaInfo createMediaInfo(String contentId, JSONObject customData, String contentType, long duration, String streamType, JSONObject metadata, JSONObject textTrackStyle) {
        MediaInfo.Builder mediaInfoBuilder = new MediaInfo.Builder(contentId);

        MediaMetadata mediaMetadata;
        try {
            mediaMetadata = new MediaMetadata(metadata.getInt("metadataType"));
        } catch (JSONException e) {
            mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC);
        }
        // Add any images
        try {
            JSONArray images = metadata.getJSONArray("images");
            for (int i = 0; i < images.length(); i++) {
                JSONObject imageObj = images.getJSONObject(i);
                try {
                    Uri imageURI = Uri.parse(imageObj.getString("url"));
                    mediaMetadata.addImage(new WebImage(imageURI));
                } catch (Exception e) {
                }
            }
        } catch (JSONException e) {
        }

        // Dynamically add other parameters
        Iterator<String> keys = metadata.keys();
        String key;
        String convertedKey;
        Object value;
        while (keys.hasNext()) {
            key = keys.next();
            if (key.equals("metadataType")
                    || key.equals("images")
                    || key.equals("type")) {
                continue;
            }
            try {
                value = metadata.get(key);
                convertedKey = ChromecastUtilities.getAndroidMetadataName(key);
                // Try to add the translated version of the key
                switch (ChromecastUtilities.getMetadataType(convertedKey)) {
                    case "string":
                        mediaMetadata.putString(convertedKey, metadata.getString(key));
                        break;
                    case "int":
                        mediaMetadata.putInt(convertedKey, metadata.getInt(key));
                        break;
                    case "double":
                        mediaMetadata.putDouble(convertedKey, metadata.getDouble(key));
                        break;
                    case "date":
                        GregorianCalendar c = new GregorianCalendar();
                        if (value instanceof java.lang.Integer
                                || value instanceof java.lang.Long
                                || value instanceof java.lang.Float
                                || value instanceof java.lang.Double) {
                            c.setTimeInMillis(metadata.getLong(key));
                            mediaMetadata.putDate(convertedKey, c);
                        } else {
                            String stringValue;
                            try {
                                stringValue = " value: " + metadata.getString(key);
                            } catch (JSONException e) {
                                stringValue = "";
                            }
                            new Error("Cannot date from metadata key: " + key + stringValue
                                    + "\n Dates must be in milliseconds from epoch UTC")
                                    .printStackTrace();
                        }
                        break;
                    case "ms":
                        mediaMetadata.putTimeMillis(convertedKey, metadata.getLong(key));
                        break;
                    default:
                }
                // Also always add the client's version of the key because sometimes the
                // MediaMetadata object removes some parameters.
                // eg. If you pass metadataType == 2 == MEDIA_TYPE_TV_SHOW you will lose any
                // subtitle added for "com.google.android.gms.cast.metadata.SUBTITLE", but this
                // is not in-line with chrome desktop which preserves the value.
                if (!key.equals(convertedKey)) {
                    // It is is really stubborn and if you try to add the key "subtitle" that is
                    // also stripped.  (Hence the "cordova-plugin-chromecast_metadata_key=" prefix
                    convertedKey = "cordova-plugin-chromecast_metadata_key=" + key;
                }
                mediaMetadata.putString(convertedKey, metadata.getString(key));
            } catch (JSONException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        mediaInfoBuilder.setMetadata(mediaMetadata);

        int intStreamType;
        switch (streamType) {
            case "buffered":
                intStreamType = MediaInfo.STREAM_TYPE_BUFFERED;
                break;
            case "live":
                intStreamType = MediaInfo.STREAM_TYPE_LIVE;
                break;
            default:
                intStreamType = MediaInfo.STREAM_TYPE_NONE;
        }

        TextTrackStyle trackStyle = ChromecastUtilities.parseTextTrackStyle(textTrackStyle);

        mediaInfoBuilder
                .setContentType(contentType)
                .setCustomData(customData)
                .setStreamType(intStreamType)
                .setStreamDuration(duration)
                .setTextTrackStyle(trackStyle);

        return mediaInfoBuilder.build();
    }

    /**
     * Media API - Calls play on the current media.
     * @param callback called with success or error
     */
    public void mediaPlay(CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                client.play()
                        .setResultCallback(getResultCallback(callback, "Failed to play."));
            }
        });
    }

    /**
     * Media API - Calls pause on the current media.
     * @param callback called with success or error
     */
    public void mediaPause(CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                client.pause()
                        .setResultCallback(getResultCallback(callback, "Failed to pause."));
            }
        });
    }

    /**
     * Media API - Seeks the current playing media.
     * @param seekPosition - Seconds to seek to
     * @param resumeState  - Resume state once seeking is complete: PLAYBACK_PAUSE or PLAYBACK_START
     * @param callback called with success or error
     */
    public void mediaSeek(long seekPosition, String resumeState, CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                int resState;
                switch (resumeState) {
                    case "PLAYBACK_START":
                        resState = MediaSeekOptions.RESUME_STATE_PLAY;
                        break;
                    case "PLAYBACK_PAUSE":
                        resState = MediaSeekOptions.RESUME_STATE_PAUSE;
                        break;
                    default:
                        resState = MediaSeekOptions.RESUME_STATE_UNCHANGED;
                }

                client.seek(new MediaSeekOptions.Builder()
                        .setPosition(seekPosition)
                        .setResumeState(resState)
                        .build()
                ).setResultCallback(getResultCallback(callback, "Failed to seek."));
            }
        });
    }

    /**
     * Media API - Sets the volume on the current playing media object, NOT ON THE CHROMECAST DIRECTLY.
     * @param level the level to set the volume to
     * @param muted if true set the media to muted, else, unmute
     * @param callback called with success or error
     */
    public void mediaSetVolume(Double level, Boolean muted, CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                // Figure out the number of callbacks we expect to receive
                int calls = 0;
                if (level != null) {
                    calls++;
                }
                if (muted != null) {
                    calls++;
                }
                if (calls == 0) {
                    // No change
                    callback.success();
                    return;
                }

                // We need this callback so that we can wait for a variable number of calls to come back
                final int expectedCalls = calls;
                ResultCallback<MediaChannelResult> cb = new ResultCallback<MediaChannelResult>() {
                    private int callsCompleted = 0;
                    private String finalErr = null;
                    private void completionCall() {
                        callsCompleted++;
                        if (callsCompleted >= expectedCalls) {
                            // Both the setvolume an setMute have returned
                            if (finalErr != null) {
                                callback.error(finalErr);
                            } else {
                                callback.success();
                            }
                        }
                    }
                    @Override
                    public void onResult(@NonNull MediaChannelResult result) {
                        if (!result.getStatus().isSuccess()) {
                            if (finalErr == null) {
                                finalErr = "Failed to set media volume/mute state:\n";
                            }
                            JSONObject errorResult = result.getCustomData();
                            if (errorResult != null) {
                                finalErr += "\n" + errorResult;
                            }
                        }
                        completionCall();
                    }
                };

                if (level != null) {
                    client.setStreamVolume(level)
                            .setResultCallback(cb);
                }
                if (muted != null) {
                    client.setStreamMute(muted)
                            .setResultCallback(cb);
                }
            }
        });
    }

    /**
     * Media API - Stops and unloads the current playing media.
     * @param callback called with success or error
     */
    public void mediaStop(CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                client.stop()
                        .setResultCallback(getResultCallback(callback, "Failed to stop."));
            }
        });
    }

    /**
     * Handle track changed.
     * @param activeTracksIds active track ids
     * @param textTrackStyle track style
     * @param callback called with success or error
     */
    public void mediaEditTracksInfo(long[] activeTracksIds, JSONObject textTrackStyle, CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                client.setActiveMediaTracks(activeTracksIds)
                        .setResultCallback(getResultCallback(callback, "Failed to set active media tracks."));
                client.setTextTrackStyle(ChromecastUtilities.parseTextTrackStyle(textTrackStyle))
                        .setResultCallback(getResultCallback(callback, "Failed to set text track style."));
            }
        });
    }

    /**
     * Sets the receiver volume level.
     * @param volume volume to set the receiver to
     * @param callback called with success or error
     */
    public void setVolume(double volume, CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    session.setVolume(volume);
                    callback.success();
                } catch (IOException e) {
                    callback.error("CHANNEL_ERROR");
                }
            }
        });
    }

    /**
     * Mutes the receiver.
     * @param muted if true mute, else, unmute
     * @param callback called with success or error
     */
    public void setMute(boolean muted, CallbackContext callback) {
        if (client == null || session == null) {
            callback.error("session_error");
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    session.setMute(muted);
                    callback.success();
                } catch (IOException e) {
                    callback.error("CHANNEL_ERROR");
                }
            }
        });
    }

    /**
     * Returns a resultCallback that wraps the callback and calls the onMediaUpdate listener.
     * @param callback client callback
     * @param errorMsg error message if failure
     * @return a callback for use in PendingResult.setResultCallback()
     */
    private ResultCallback<MediaChannelResult> getResultCallback(CallbackContext callback, String errorMsg) {
        return new ResultCallback<MediaChannelResult>() {
            @Override
            public void onResult(@NonNull MediaChannelResult result) {
                if (result.getStatus().isSuccess()) {
                    callback.success();
                } else {
                    JSONObject errorResult = result.getCustomData();
                    String error = errorMsg;
                    if (errorResult != null) {
                        error += "\nError details: " + errorMsg;
                    }
                    callback.error(error);
                }
            }
        };
    }

    private JSONObject createSessionObject() {
        return ChromecastUtilities.createSessionObject(session);
    }

    private JSONObject createMediaObject() {
        return ChromecastUtilities.createMediaObject(session);
    }

    interface Listener extends Cast.MessageReceivedCallback {
        void onMediaLoaded(JSONObject jsonMedia);
        void onMediaUpdate(JSONObject jsonMedia);
        void onSessionUpdate(JSONObject jsonSession);
        void onSessionEnd(JSONObject jsonSession);
    }
}

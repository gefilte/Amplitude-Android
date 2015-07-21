package com.amplitude.api;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.squareup.okhttp.FormEncodingBuilder;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

public class AmplitudeClient {

    public static final String TAG = "com.amplitude.api.AmplitudeClient";

    public static final String START_SESSION_EVENT = "session_start";
    public static final String END_SESSION_EVENT = "session_end";
    public static final String REVENUE_EVENT = "revenue_amount";

    protected static AmplitudeClient instance = new AmplitudeClient();

    public static AmplitudeClient getInstance() {
        return instance;
    }

    protected Context context;
    protected String apiKey;
    protected String userId;
    protected String deviceId;
    private boolean newDeviceIdPerInstall = false;
    private boolean useAdvertisingIdForDeviceId = false;
    private boolean initialized = false;
    private boolean optOut = false;
    private boolean offline = false;

    private DeviceInfo deviceInfo;

    /* VisibleForTesting */
    JSONObject userProperties;

    private long sessionId = -1;
    private int eventUploadThreshold = Constants.EVENT_UPLOAD_THRESHOLD;
    private int eventUploadMaxBatchSize = Constants.EVENT_UPLOAD_MAX_BATCH_SIZE;
    private int eventMaxCount = Constants.EVENT_MAX_COUNT;
    private long eventUploadPeriodMillis = Constants.EVENT_UPLOAD_PERIOD_MILLIS;
    private long minTimeBetweenSessionsMillis = Constants.MIN_TIME_BETWEEN_SESSIONS_MILLIS;
    private long sessionTimeoutMillis = Constants.SESSION_TIMEOUT_MILLIS;
    private boolean backoffUpload = false;
    private int backoffUploadBatchSize = eventUploadMaxBatchSize;
    private boolean usingAccurateTracking = false;
    private boolean trackingSessionEvents = false;
    private boolean inPauseState = false;

    private Runnable endSessionRunnable;

    private AtomicBoolean updateScheduled = new AtomicBoolean(false);
    private AtomicBoolean uploadingCurrently = new AtomicBoolean(false);

    // Let test classes have access to these properties.
    Throwable lastError;
    String url = Constants.EVENT_LOG_URL;
    WorkerThread logThread = new WorkerThread("logThread");
    WorkerThread httpThread = new WorkerThread("httpThread");

    public AmplitudeClient() {
        logThread.start();
        httpThread.start();
    }

    public void initialize(Context context, String apiKey) {
        initialize(context, apiKey, null);
    }

    public synchronized void initialize(Context context, String apiKey, String userId) {
        if (context == null) {
            Log.e(TAG, "Argument context cannot be null in initialize()");
            return;
        }

        AmplitudeClient.upgradePrefs(context);

        if (TextUtils.isEmpty(apiKey)) {
            Log.e(TAG, "Argument apiKey cannot be null or blank in initialize()");
            return;
        }
        if (!initialized) {
            this.context = context.getApplicationContext();
            this.apiKey = apiKey;
            initializeDeviceInfo();
            SharedPreferences preferences = context.getSharedPreferences(
                    getSharedPreferencesName(), Context.MODE_PRIVATE);
            if (userId != null) {
                this.userId = userId;
                preferences.edit().putString(Constants.PREFKEY_USER_ID, userId).commit();
            } else {
                this.userId = preferences.getString(Constants.PREFKEY_USER_ID, null);
            }
            this.optOut = preferences.getBoolean(Constants.PREFKEY_OPT_OUT, false);
            initialized = true;
        }
    }

    private void initializeDeviceInfo() {
        deviceInfo = new DeviceInfo(context);
        runOnLogThread(new Runnable() {

            @Override
            public void run() {
                deviceId = initializeDeviceId();
                deviceInfo.prefetch();
            }
        });
    }

    public void enableNewDeviceIdPerInstall(boolean newDeviceIdPerInstall) {
        this.newDeviceIdPerInstall = newDeviceIdPerInstall;
    }

    public void useAdvertisingIdForDeviceId() {
        this.useAdvertisingIdForDeviceId = true;
    }

    public void enableLocationListening() {
        if (deviceInfo == null) {
            throw new IllegalStateException(
                    "Must initialize before acting on location listening.");
        }
        deviceInfo.setLocationListening(true);
    }

    public void disableLocationListening() {
        if (deviceInfo == null) {
            throw new IllegalStateException(
                    "Must initialize before acting on location listening.");
        }
        deviceInfo.setLocationListening(false);
    }

    public void setEventUploadThreshold(int eventUploadThreshold) {
        this.eventUploadThreshold = eventUploadThreshold;
    }

    public void setEventUploadMaxBatchSize(int eventUploadMaxBatchSize) {
        this.eventUploadMaxBatchSize = eventUploadMaxBatchSize;
        this.backoffUploadBatchSize = eventUploadMaxBatchSize;
    }

    public void setEventMaxCount(int eventMaxCount) {
        this.eventMaxCount = eventMaxCount;
    }

    public void setEventUploadPeriodMillis(int eventUploadPeriodMillis) {
        this.eventUploadPeriodMillis = eventUploadPeriodMillis;
    }

    public void setMinTimeBetweenSessionsMillis(int minTimeBetweenSessionsMillis) {
        this.minTimeBetweenSessionsMillis = minTimeBetweenSessionsMillis;
    }

    public void setSessionTimeoutMillis(long sessionTimeoutMillis) {
        this.sessionTimeoutMillis = sessionTimeoutMillis;
    }

    public void setOptOut(boolean optOut) {
        this.optOut = optOut;

        SharedPreferences preferences = context.getSharedPreferences(
                getSharedPreferencesName(), Context.MODE_PRIVATE);
        preferences.edit().putBoolean(Constants.PREFKEY_OPT_OUT, optOut).commit();
    }

    public void setOffline(boolean offline) {
        this.offline = offline;

        // Try to update to the server once offline mode is disabled.
        if (!offline) {
            uploadEvents();
        }
    }

    public void trackSessionEvents(boolean trackingSessionEvents) {
        this.trackingSessionEvents = trackingSessionEvents;
    }

    public void logEvent(String eventType) {
        logEvent(eventType, null);
    }

    public void logEvent(String eventType, JSONObject eventProperties) {
        if (validateLogEvent(eventType)) {
            boolean checkSession = !usingAccurateTracking;
            logEventAsync(eventType, eventProperties, null, System.currentTimeMillis(), checkSession);
        }
    }

    public void logEventSync(String eventType, JSONObject eventProperties) {
        if (validateLogEvent(eventType)) {
            boolean checkSession = !usingAccurateTracking;
            logEvent(eventType, eventProperties, null, System.currentTimeMillis(), checkSession);
        }
    }

    protected boolean validateLogEvent(String eventType) {
        if (TextUtils.isEmpty(eventType)) {
            Log.e(TAG, "Argument eventType cannot be null or blank in logEvent()");
            return false;
        }

        if (!contextAndApiKeySet("logEvent()")) {
            return false;
        }

        return true;
    }

    protected void logEventAsync(final String eventType, JSONObject eventProperties,
            final JSONObject apiProperties, final long timestamp, final boolean checkSession) {
        // Clone the incoming eventProperties object before sending over
        // to the log thread. Helps avoid ConcurrentModificationException
        // if the caller starts mutating the object they passed in.
        // Only does a shallow copy, so it's still possible, though unlikely,
        // to hit concurrent access if the caller mutates deep in the object.
        if (eventProperties != null) {
            eventProperties = cloneJSONObject(eventProperties);
        }

        final JSONObject copyEventProperties = eventProperties;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                logEvent(eventType, copyEventProperties, apiProperties, timestamp, checkSession);
            }
        });
    }

    protected long logEvent(String eventType, JSONObject eventProperties,
            JSONObject apiProperties, long timestamp, boolean checkSession) {
        Log.d(TAG, "Logged event to Amplitude: " + eventType);

        if (optOut) {
            return -1;
        }

        // corner case: using accurate tracking and log event in between onPause/onResume
        // inPauseState will indicate if in between onPause/onResume --> need to check session
        if (checkSession || inPauseState) {
            startNewSessionIfNeeded(timestamp);
        }
        refreshSessionTime(timestamp);

        JSONObject event = new JSONObject();
        try {
            event.put("event_type", replaceWithJSONNull(eventType));

            event.put("timestamp", timestamp);
            event.put("user_id", (userId == null) ? replaceWithJSONNull(deviceId)
                    : replaceWithJSONNull(userId));
            event.put("device_id", replaceWithJSONNull(deviceId));
            event.put("session_id", sessionId);
            event.put("version_name", replaceWithJSONNull(deviceInfo.getVersionName()));
            event.put("os_name", replaceWithJSONNull(deviceInfo.getOsName()));
            event.put("os_version", replaceWithJSONNull(deviceInfo.getOsVersion()));
            event.put("device_brand", replaceWithJSONNull(deviceInfo.getBrand()));
            event.put("device_manufacturer", replaceWithJSONNull(deviceInfo.getManufacturer()));
            event.put("device_model", replaceWithJSONNull(deviceInfo.getModel()));
            event.put("carrier", replaceWithJSONNull(deviceInfo.getCarrier()));
            event.put("country", replaceWithJSONNull(deviceInfo.getCountry()));
            event.put("language", replaceWithJSONNull(deviceInfo.getLanguage()));
            event.put("platform", Constants.PLATFORM);

            JSONObject library = new JSONObject();
            library.put("name", Constants.LIBRARY);
            library.put("version", Constants.VERSION);
            event.put("library", library);

            apiProperties = (apiProperties == null) ? new JSONObject() : apiProperties;
            Location location = deviceInfo.getMostRecentLocation();
            if (location != null) {
                JSONObject locationJSON = new JSONObject();
                locationJSON.put("lat", location.getLatitude());
                locationJSON.put("lng", location.getLongitude());
                apiProperties.put("location", locationJSON);
            }
            if (deviceInfo.getAdvertisingId() != null) {
                apiProperties.put("androidADID", deviceInfo.getAdvertisingId());
            }

            event.put("api_properties", apiProperties);
            event.put("event_properties", (eventProperties == null) ? new JSONObject()
                    : eventProperties);
            event.put("user_properties", (userProperties == null) ? new JSONObject()
                    : userProperties);
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
        }

        return saveEvent(event);
    }

    protected long saveEvent(JSONObject event) {
        DatabaseHelper dbHelper = DatabaseHelper.getDatabaseHelper(context);
        long eventId = dbHelper.addEvent(event.toString());
        long eventCount = dbHelper.getEventCount();

        if (eventCount >= eventMaxCount) {
            dbHelper.removeEvents(dbHelper.getNthEventId(Constants.EVENT_REMOVE_BATCH_SIZE));
        }

        if ((eventCount % eventUploadThreshold) == 0 && eventCount >= eventUploadThreshold) {
            updateServer();
        } else {
            updateServerLater(eventUploadPeriodMillis);
        }

        return eventId;
    }

    private long getPreviousEventTime() {
        SharedPreferences preferences = context.getSharedPreferences(
                getSharedPreferencesName(), Context.MODE_PRIVATE);
        return preferences.getLong(Constants.PREFKEY_PREVIOUS_SESSION_TIME, -1);
    }

    void setPreviousEventTime(long timestamp) {
        SharedPreferences preferences = context.getSharedPreferences(
                getSharedPreferencesName(), Context.MODE_PRIVATE);
        preferences.edit().putLong(Constants.PREFKEY_PREVIOUS_SESSION_TIME, timestamp).commit();
    }

//    private long getPreviousSessionId() {
//        SharedPreferences preferences = context.getSharedPreferences(
//                getSharedPreferencesName(), Context.MODE_PRIVATE);
//        return preferences.getLong(Constants.PREFKEY_PREVIOUS_SESSION_ID, -1);
//    }

    void setPreviousSessionId(long timestamp) {
        SharedPreferences preferences = context.getSharedPreferences(
                getSharedPreferencesName(), Context.MODE_PRIVATE);
        preferences.edit().putLong(Constants.PREFKEY_PREVIOUS_SESSION_ID, timestamp).commit();
    }

//    private void startSession() {
//        long timestamp = System.currentTimeMillis();
//        createOrContinueSession(timestamp);
//    }

//    private void createOrContinueSession(long timestamp) {
//        if (!startNewSessionIfNeeded(timestamp)) {
//            // not creating a session means we should continue the session
//            refreshSessionTime(timestamp);
//        }
//    }

    private boolean startNewSessionIfNeeded(long timestamp) {
        if (!inSession() || sessionExpired(timestamp)) {

            // end previous session
            if (trackingSessionEvents) {
                sendSessionEvent(END_SESSION_EVENT);
            }

            // start new session
            setSessionId(timestamp);
            refreshSessionTime(timestamp);
            if (trackingSessionEvents) {
                sendSessionEvent(START_SESSION_EVENT);
            }

            return true;
        }

        return false;
    }

    private boolean inSession() {
        return sessionId >= 0;
    }

    private boolean sessionExpired(long timestamp) {
        if (!inSession()) {
            return false;
        }

        long lastEventTime = getPreviousEventTime();
        long sessionLimit = usingAccurateTracking ? minTimeBetweenSessionsMillis : sessionTimeoutMillis;
        return (timestamp - lastEventTime) > sessionLimit;
    }

    private void setSessionId(long timestamp) {
        sessionId = timestamp;
        setPreviousSessionId(timestamp);
    }

    private void refreshSessionTime(long timestamp) {
        if (!inSession()) {
            return;
        }

        setPreviousEventTime(timestamp);
    }

    private void sendSessionEvent(final String session_event) {
        if (!contextAndApiKeySet(String.format("sendSessionEvent('%s')", session_event))) {
            return;
        }

        final long timestamp = getPreviousEventTime();

        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                JSONObject apiProperties = new JSONObject();
                try {
                    apiProperties.put("special", session_event);
                } catch (JSONException e) {
                    return;
                }

                logEvent(session_event, null, apiProperties, timestamp, false);
            }
        });
    }

    public void logRevenue(double amount) {
        // Amount is in dollars
        // ex. $3.99 would be pass as logRevenue(3.99)
        logRevenue(null, 1, amount);
    }

    public void logRevenue(String productId, int quantity, double price) {
        logRevenue(productId, quantity, price, null, null);
    }

    public void logRevenue(String productId, int quantity, double price, String receipt,
            String receiptSignature) {
        if (!contextAndApiKeySet("logRevenue()")) {
            return;
        }

        // Log revenue in events
        JSONObject apiProperties = new JSONObject();
        try {
            apiProperties.put("special", REVENUE_EVENT);
            apiProperties.put("productId", productId);
            apiProperties.put("quantity", quantity);
            apiProperties.put("price", price);
            apiProperties.put("receipt", receipt);
            apiProperties.put("receiptSig", receiptSignature);
        } catch (JSONException e) {
        }

        logEvent(REVENUE_EVENT, null, apiProperties, System.currentTimeMillis(), true);
    }

    public void setUserProperties(JSONObject userProperties) {
        setUserProperties(userProperties, false);
    }

    public void setUserProperties(final JSONObject userProperties, final boolean replace) {
        if (replace || this.userProperties == null) {
            this.userProperties = userProperties;
            return;
        }

        if (userProperties == null) {
            return;
        }

        // If merging is needed, do it on the log thread. Avoids an issue
        // where user properties is being mutated here at the same time
        // it's being iterated on for stringify in the event sending logic.
        final JSONObject currentUserProperties = this.userProperties;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                Iterator<?> keys = userProperties.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    try {
                        currentUserProperties.put(key, userProperties.get(key));
                    } catch (JSONException e) {
                        Log.e(TAG, e.toString());
                    }
                }
            }
        });
    }


    /**
     * @return The developer specified identifier for tracking within the analytics system.
     *         Can be null.
     */
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        if (!contextAndApiKeySet("setUserId()")) {
            return;
        }

        this.userId = userId;
        SharedPreferences preferences = context.getSharedPreferences(
                getSharedPreferencesName(), Context.MODE_PRIVATE);
        preferences.edit().putString(Constants.PREFKEY_USER_ID, userId).commit();
    }

    public void uploadEvents() {
        if (!contextAndApiKeySet("uploadEvents()")) {
            return;
        }

        logThread.post(new Runnable() {
            @Override
            public void run() {
                updateServer();
            }
        });
    }

    private void updateServerLater(long delayMillis) {
        if (updateScheduled.getAndSet(true)) {
            return;
        }

        logThread.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateScheduled.set(false);
                updateServer();
            }
        }, delayMillis);
    }

    protected void updateServer() {
        updateServer(true);
    }

    // Always call this from logThread
    protected void updateServer(boolean limit) {
        if (optOut || offline) {
            return;
        }

        if (!uploadingCurrently.getAndSet(true)) {
            DatabaseHelper dbHelper = DatabaseHelper.getDatabaseHelper(context);
            try {
                long endSessionId = getEndSessionId();
                int batchLimit = limit ? (backoffUpload ? backoffUploadBatchSize : eventUploadMaxBatchSize) : -1;
                Pair<Long, JSONArray> pair = dbHelper.getEvents(endSessionId, batchLimit);
                final long maxId = pair.first;
                final JSONArray events = pair.second;
                httpThread.post(new Runnable() {
                    @Override
                    public void run() {
                        makeEventUploadPostRequest(new OkHttpClient(), events.toString(), maxId);
                    }
                });
            } catch (JSONException e) {
                uploadingCurrently.set(false);
                Log.e(TAG, e.toString());
            }
        }
    }

    protected void makeEventUploadPostRequest(OkHttpClient client, String events, final long maxId) {
        String apiVersionString = "" + Constants.API_VERSION;
        String timestampString = "" + System.currentTimeMillis();

        String checksumString = "";
        try {
            String preimage = apiVersionString + apiKey + events + timestampString;
            checksumString = bytesToHexString(MessageDigest.getInstance("MD5").digest(
                    preimage.getBytes("UTF-8")));
        } catch (NoSuchAlgorithmException e) {
            // According to
            // http://stackoverflow.com/questions/5049524/is-java-utf-8-charset-exception-possible,
            // this will never be thrown
            Log.e(TAG, e.toString());
        } catch (UnsupportedEncodingException e) {
            // According to
            // http://stackoverflow.com/questions/5049524/is-java-utf-8-charset-exception-possible,
            // this will never be thrown
            Log.e(TAG, e.toString());
        }

        RequestBody body = new FormEncodingBuilder()
            .add("v", apiVersionString)
            .add("client", apiKey)
            .add("e", events)
            .add("upload_time", timestampString)
            .add("checksum", checksumString)
            .build();

        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();

        boolean uploadSuccess = false;

        try {
            Response response = client.newCall(request).execute();
            String stringResponse = response.body().string();
            if (stringResponse.equals("success")) {
                uploadSuccess = true;
                logThread.post(new Runnable() {
                    @Override
                    public void run() {
                        DatabaseHelper dbHelper = DatabaseHelper.getDatabaseHelper(context);
                        dbHelper.removeEvents(maxId);
                        uploadingCurrently.set(false);
                        if (dbHelper.getEventCount() > eventUploadThreshold) {
                            logThread.post(new Runnable() {
                                @Override
                                public void run() {
                                    updateServer(backoffUpload);
                                }
                            });
                        }
                        else {
                            backoffUpload = false;
                            backoffUploadBatchSize = eventUploadMaxBatchSize;
                        }
                    }
                });
            } else if (stringResponse.equals("invalid_api_key")) {
                Log.e(TAG, "Invalid API key, make sure your API key is correct in initialize()");
            } else if (stringResponse.equals("bad_checksum")) {
                Log.w(TAG,
                        "Bad checksum, post request was mangled in transit, will attempt to reupload later");
            } else if (stringResponse.equals("request_db_write_failed")) {
                Log.w(TAG,
                        "Couldn't write to request database on server, will attempt to reupload later");
            } else if (response.code() == 413) {

                // If blocked by one massive event, drop it
                DatabaseHelper dbHelper = DatabaseHelper.getDatabaseHelper(context);
                if (backoffUpload && backoffUploadBatchSize == 1) {
                    dbHelper.removeEvent(maxId);
                    // maybe we want to reset backoffUploadBatchSize after dropping massive event
                }

                // Server complained about length of request, backoff and try again
                backoffUpload = true;
                int numEvents = Math.min((int)dbHelper.getEventCount(), backoffUploadBatchSize);
                backoffUploadBatchSize = (int)Math.ceil(numEvents / 2.0);
                Log.w(TAG, "Request too large, will decrease size and attempt to reupload");
                logThread.post(new Runnable() {
                   @Override
                    public void run() {
                       uploadingCurrently.set(false);
                       updateServer(true);
                   }
                });
            } else {
                Log.w(TAG, "Upload failed, " + stringResponse
                        + ", will attempt to reupload later");
            }
        } catch (org.apache.http.conn.HttpHostConnectException e) {
            // Log.w(TAG,
            // "No internet connection found, unable to upload events");
            lastError = e;
        } catch (java.net.UnknownHostException e) {
            // Log.w(TAG,
            // "No internet connection found, unable to upload events");
            lastError = e;
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            lastError = e;
        } catch (AssertionError e) {
            // This can be caused by a NoSuchAlgorithmException thrown by DefaultHttpClient
            Log.e(TAG, "Exception:", e);
            lastError = e;
        } catch (Exception e) {
            // Just log any other exception so things don't crash on upload
            Log.e(TAG, "Exception:", e);
            lastError = e;
        }

        if (!uploadSuccess) {
            uploadingCurrently.set(false);
        }

    }

    /**
     * @return A unique identifier for tracking within the analytics system. Can be null if
     *         deviceId hasn't been initialized yet;
     */
    public String getDeviceId() {
        return deviceId;
    }

    private String initializeDeviceId() {
        Set<String> invalidIds = new HashSet<String>();
        invalidIds.add("");
        invalidIds.add("9774d56d682e549c");
        invalidIds.add("unknown");
        invalidIds.add("000000000000000"); // Common Serial Number
        invalidIds.add("Android");
        invalidIds.add("DEFACE");

        SharedPreferences preferences = context.getSharedPreferences(
                getSharedPreferencesName(), Context.MODE_PRIVATE);
        String deviceId = preferences.getString(Constants.PREFKEY_DEVICE_ID, null);
        if (!(TextUtils.isEmpty(deviceId) || invalidIds.contains(deviceId))) {
            return deviceId;
        }

        if (!newDeviceIdPerInstall && useAdvertisingIdForDeviceId) {
            // Android ID is deprecated by Google.
            // We are required to use Advertising ID, and respect the advertising ID preference

            String advertisingId = deviceInfo.getAdvertisingId();
            if (!(TextUtils.isEmpty(advertisingId) || invalidIds.contains(advertisingId))) {
                preferences.edit().putString(Constants.PREFKEY_DEVICE_ID, advertisingId)
                        .commit();
                return advertisingId;
            }
        }

        // If this still fails, generate random identifier that does not persist
        // across installations. Append R to distinguish as randomly generated
        String randomId = deviceInfo.generateUUID() + "R";
        preferences.edit().putString(Constants.PREFKEY_DEVICE_ID, randomId).commit();
        return randomId;

    }

    private void runOnLogThread(Runnable r) {
        if (Thread.currentThread() != logThread) {
            logThread.post(r);
        } else {
            r.run();
        }
    }

    protected Object replaceWithJSONNull(Object obj) {
        return obj == null ? JSONObject.NULL : obj;
    }

    protected synchronized boolean contextAndApiKeySet(String methodName) {
        if (context == null) {
            Log.e(TAG, "context cannot be null, set context with initialize() before calling "
                    + methodName);
            return false;
        }
        if (TextUtils.isEmpty(apiKey)) {
            Log.e(TAG,
                    "apiKey cannot be null or empty, set apiKey with initialize() before calling "
                            + methodName);
            return false;
        }
        return true;
    }

    protected String getSharedPreferencesName() {
        return Constants.SHARED_PREFERENCES_NAME_PREFIX + "." + context.getPackageName();
    }

    protected String bytesToHexString(byte[] bytes) {
        final char[] hexArray = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b',
                'c', 'd', 'e', 'f' };
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Do a shallow copy of a JSONObject. Takes a bit of code to avoid
     * stringify and reparse given the API.
     */
    private JSONObject cloneJSONObject(final JSONObject obj) {
        if (obj == null) {
            return null;
        }

        // obj.names returns null if the json obj is empty.
        JSONArray nameArray = obj.names();
        int len = (nameArray != null ? nameArray.length() : 0);

        String[] names = new String[len];
        for (int i = 0; i < len; i++) {
            names[i] = nameArray.optString(i);
        }

        try {
            return new JSONObject(obj, names);
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            return null;
        }
    }

    /**
     * Move all preference data from the legacy name to the new, static name if needed.
     *
     * Constants.PACKAGE_NAME used to be set using "Constants.class.getPackage().getName()"
     * Some aggressive proguard optimizations broke the reflection and caused apps
     * to crash on startup.
     *
     * Now that Constants.PACKAGE_NAME is changed, old data on devices needs to be
     * moved over to the new location so that device ids remain consistent.
     *
     * This should only happen once -- the first time a user loads the app after updating.
     * This logic needs to remain in place for quite a long time. It was first introduced in
     * April 2015 in version 1.6.0.
     */
    static boolean upgradePrefs(Context context) {
        return upgradePrefs(context, null, null);
    }

    static boolean upgradePrefs(Context context, String sourcePkgName, String targetPkgName) {
        try {
            if (sourcePkgName == null) {
                // Try to load the package name using the old reflection strategy.
                sourcePkgName = Constants.PACKAGE_NAME;
                try {
                    sourcePkgName = Constants.class.getPackage().getName();
                } catch (Exception e) { }
            }

            if (targetPkgName == null) {
                targetPkgName = Constants.PACKAGE_NAME;
            }

            // No need to copy if the source and target are the same.
            if (targetPkgName.equals(sourcePkgName)) {
                return false;
            }

            // Copy over any preferences that may exist in a source preference store.
            String sourcePrefsName = sourcePkgName + "." + context.getPackageName();
            SharedPreferences source =
                    context.getSharedPreferences(sourcePrefsName, Context.MODE_PRIVATE);

            // Nothing left in the source store to copy
            if (source.getAll().size() == 0) {
                return false;
            }

            String prefsName = targetPkgName + "." + context.getPackageName();
            SharedPreferences targetPrefs =
                    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            SharedPreferences.Editor target = targetPrefs.edit();

            // Copy over all existing data.
            if (source.contains(sourcePkgName + ".previousSessionTime")) {
                target.putLong(Constants.PREFKEY_PREVIOUS_SESSION_TIME,
                        source.getLong(sourcePkgName + ".previousSessionTime", -1));
            }
            if (source.contains(sourcePkgName + ".previousSessionId")) {
                target.putLong(Constants.PREFKEY_PREVIOUS_SESSION_ID,
                        source.getLong(sourcePkgName + ".previousSessionId", -1));
            }
            if (source.contains(sourcePkgName + ".deviceId")) {
                target.putString(Constants.PREFKEY_DEVICE_ID,
                        source.getString(sourcePkgName + ".deviceId", null));
            }
            if (source.contains(sourcePkgName + ".userId")) {
                target.putString(Constants.PREFKEY_USER_ID,
                        source.getString(sourcePkgName + ".userId", null));
            }
            if (source.contains(sourcePkgName + ".optOut")) {
                target.putBoolean(Constants.PREFKEY_OPT_OUT,
                        source.getBoolean(sourcePkgName + ".optOut", false));
            }

            // Commit the changes and clear the source store so we don't recopy.
            target.apply();
            source.edit().clear().apply();

            Log.i(TAG, "Upgraded shared preferences from " + sourcePrefsName + " to " + prefsName);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error upgrading shared preferences", e);
            return false;
        }
    }}

package com.herdmate.tagreapersentinel;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.rscja.deviceapi.RFIDWithUHFA4;
import com.rscja.deviceapi.entity.AntennaState;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.enums.AntennaEnum;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "tag_reaper_sentinel";
    private static final String PREF_ACTIVE_SESSION = "active_session";
    private static final long SAVE_THROTTLE_MS = 1000L;

    private RFIDWithUHFA4 reader;
    private boolean readerConnected = false;

    private Button btnConnect;
    private Button btnNewSession;
    private Button btnStart;
    private Button btnPause;
    private Button btnEndSession;
    private Button btnClear;

    private CheckBox chkAnt1;
    private CheckBox chkAnt2;
    private CheckBox chkAnt3;
    private CheckBox chkAnt4;

    private TextView txtStatus;
    private TextView txtSession;
    private TextView txtTotalReads;
    private TextView txtUniqueTags;

    private ListView listTags;
    private ArrayAdapter<String> adapter;

    private final List<String> logLines = new ArrayList<>();
    private final LinkedHashMap<String, TagRecord> uniqueTags =
            new LinkedHashMap<>();

    private SharedPreferences preferences;

    private SessionState sessionState = SessionState.NO_SESSION;

    private String sessionId;
    private long sessionStartedAt;
    private long sessionEndedAt;
    private long pauseStartedAt;
    private long totalPausedMillis;
    private long totalActiveMillis;
    private long activeStartedAt;
    private long lastSaveAt;

    private final Handler sessionClockHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable sessionClockRunnable =
            new Runnable() {
                @Override
                public void run() {
                     updateSessionText();
                     sessionClockHandler.postDelayed(this, 1000L);
            }
        };

    private int totalReads = 0;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private enum SessionState {
        NO_SESSION,
        READY,
        SCANNING,
        PAUSED,
        ENDED
    }

    private static class AntennaRecord {
        int count;
        double strongestRssi = -9999.0;
        double latestRssi = -9999.0;
        long firstSeenAt;
        long lastSeenAt;
    }

    private static class TagRecord {
        String epc;
        int count;
        long firstSeenAt;
        long lastSeenAt;
        double strongestRssi = -9999.0;

        final LinkedHashMap<String, AntennaRecord> antennas =
                new LinkedHashMap<>();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        btnConnect = findViewById(R.id.btnConnect);
        btnNewSession = findViewById(R.id.btnNewSession);
        btnStart = findViewById(R.id.btnStart);
        btnPause = findViewById(R.id.btnPause);
        btnEndSession = findViewById(R.id.btnEndSession);
        btnClear = findViewById(R.id.btnClear);

        chkAnt1 = findViewById(R.id.chkAnt1);
        chkAnt2 = findViewById(R.id.chkAnt2);
        chkAnt3 = findViewById(R.id.chkAnt3);
        chkAnt4 = findViewById(R.id.chkAnt4);

        txtStatus = findViewById(R.id.txtStatus);
        txtSession = findViewById(R.id.txtSession);
        txtTotalReads = findViewById(R.id.txtTotalReads);
        txtUniqueTags = findViewById(R.id.txtUniqueTags);

        listTags = findViewById(R.id.listTags);

        adapter = new ArrayAdapter<>(
                this,
                R.layout.list_item_tag,
                logLines
        );

        listTags.setAdapter(adapter);

        btnConnect.setOnClickListener(v -> connectReader());
        btnNewSession.setOnClickListener(v -> createNewSession());
        btnStart.setOnClickListener(v -> startOrResumeScanning());
        btnPause.setOnClickListener(v -> pauseScanning());
        btnEndSession.setOnClickListener(v -> endSession());
        btnClear.setOnClickListener(v -> clearSession());

        restoreSession();
        refreshDisplay();
        updateControls();
        sessionClockHandler.post(sessionClockRunnable);
    }

    private void connectReader() {
        setStatus("Connecting...");
        btnConnect.setEnabled(false);

        new Thread(() -> {
            boolean connected;
            String error = null;

            try {
                reader = RFIDWithUHFA4.getInstance();
                connected = reader.init(getApplicationContext());
            } catch (Throwable throwable) {
                connected = false;
                error = throwable.getClass().getSimpleName()
                        + ": "
                        + throwable.getMessage();
            }

            final boolean finalConnected = connected;
            final String finalError = error;

            runOnUiThread(() -> {
                readerConnected = finalConnected;

                if (finalConnected) {
                    reader.setInventoryCallback(inventoryCallback);
                    setStatus("Reader connected");
                } else if (finalError != null) {
                    setStatus("Connection failed — " + finalError);
                } else {
                    setStatus("Connection failed — init returned false");
                }

                updateControls();
            });
        }).start();
    }

    private void createNewSession() {
        if (sessionState == SessionState.SCANNING) {
            setStatus("Pause or end the current session first");
            return;
        }

        uniqueTags.clear();
        totalReads = 0;

        sessionId = UUID.randomUUID().toString();
        sessionStartedAt = System.currentTimeMillis();
        sessionEndedAt = 0L;
        pauseStartedAt = 0L;
        totalPausedMillis = 0L;
        totalActiveMillis = 0L;
        activeStartedAt = 0L;

        sessionState = SessionState.READY;

        saveSessionNow();
        refreshDisplay();
        setStatus("New session ready");
        updateControls();
    }

    private void startOrResumeScanning() {
        if (!readerConnected || reader == null) {
            setStatus("Connect the reader first");
            return;
        }

        if (sessionState != SessionState.READY
                && sessionState != SessionState.PAUSED) {
            setStatus("Start a new session first");
            return;
        }

        if (!hasSelectedAntenna()) {
            setStatus("Select at least one antenna");
            return;
        }

        applyAntennaSelection();

        new Thread(() -> {
            boolean started;
            String error = null;

            try {
                started = reader.startInventoryTag();
            } catch (Throwable throwable) {
                started = false;
                error = throwable.getClass().getSimpleName()
                        + ": "
                        + throwable.getMessage();
            }

            final boolean finalStarted = started;
            final String finalError = error;

            runOnUiThread(() -> {
                if (finalStarted) {
                    if (pauseStartedAt > 0L) {
                        totalPausedMillis +=
                                System.currentTimeMillis() - pauseStartedAt;

                        pauseStartedAt = 0L;
                    }

                    sessionState = SessionState.SCANNING;
                    activeStartedAt = System.currentTimeMillis();

                    saveSessionNow();
                    setStatus("Scanning");
                } else if (finalError != null) {
                    setStatus("Failed to start scan — " + finalError);
                } else {
                    setStatus("Failed to start scan");
                }

                updateControls();
            });
        }).start();
    }

    private void pauseScanning() {
        if (sessionState != SessionState.SCANNING) {
            return;
        }

        new Thread(() -> {
            String error = null;

            try {
                if (reader != null) {
                    reader.stopInventory();
                }
            } catch (Throwable throwable) {
                error = throwable.getClass().getSimpleName()
                        + ": "
                        + throwable.getMessage();
            }

            final String finalError = error;

            runOnUiThread(() -> {
                if (activeStartedAt > 0L) {
                    totalActiveMillis +=
                System.currentTimeMillis() - activeStartedAt;

                    activeStartedAt = 0L;
                }

                sessionState = SessionState.PAUSED;
                pauseStartedAt = System.currentTimeMillis();

                saveSessionNow();

                if (finalError == null) {
                    setStatus("Session paused");
                } else {
                    setStatus("Paused — reader warning: " + finalError);
                }

                updateControls();
            });
        }).start();
    }

    private void endSession() {
        if (sessionState == SessionState.NO_SESSION
                || sessionState == SessionState.ENDED) {
            return;
        }

        new Thread(() -> {
            if (reader != null
                    && sessionState == SessionState.SCANNING) {
                try {
                    reader.stopInventory();
                } catch (Throwable ignored) {
                }
            }

            runOnUiThread(() -> {
                long now = System.currentTimeMillis();

            if (activeStartedAt > 0L) {
            totalActiveMillis += now - activeStartedAt;
            activeStartedAt = 0L;
         }

            if (pauseStartedAt > 0L) {
              totalPausedMillis += now - pauseStartedAt;
           pauseStartedAt = 0L;
          }

             sessionEndedAt = now;
             sessionState = SessionState.ENDED;

                saveSessionNow();

                setStatus("Session ended and saved");
                updateControls();
            });
        }).start();
    }

    private void clearSession() {
        if (sessionState == SessionState.SCANNING) {
            setStatus("Pause before clearing session data");
            return;
        }

        uniqueTags.clear();
        totalReads = 0;

        sessionId = null;
        sessionStartedAt = 0L;
        sessionEndedAt = 0L;
        pauseStartedAt = 0L;
        totalPausedMillis = 0L;
        totalActiveMillis = 0L;
        activeStartedAt = 0L;

        sessionState = SessionState.NO_SESSION;

        preferences.edit()
                .remove(PREF_ACTIVE_SESSION)
                .apply();

        refreshDisplay();

        if (readerConnected) {
            setStatus("Reader connected — no session");
        } else {
            setStatus("Not connected");
        }

        updateControls();
    }

    private boolean hasSelectedAntenna() {
        return chkAnt1.isChecked()
                || chkAnt2.isChecked()
                || chkAnt3.isChecked()
                || chkAnt4.isChecked();
    }

    private void applyAntennaSelection() {
        List<AntennaState> antennaStates = new ArrayList<>();

        antennaStates.add(
                new AntennaState(
                        AntennaEnum.ANT1,
                        chkAnt1.isChecked()
                )
        );

        antennaStates.add(
                new AntennaState(
                        AntennaEnum.ANT2,
                        chkAnt2.isChecked()
                )
        );

        antennaStates.add(
                new AntennaState(
                        AntennaEnum.ANT3,
                        chkAnt3.isChecked()
                )
        );

        antennaStates.add(
                new AntennaState(
                        AntennaEnum.ANT4,
                        chkAnt4.isChecked()
                )
        );

        reader.setANT(antennaStates);
    }

    private final IUHFInventoryCallback inventoryCallback =
            new IUHFInventoryCallback() {

                @Override
                public void callback(UHFTAGInfo tagInfo) {
                    if (tagInfo == null
                            || tagInfo.getEPC() == null) {
                        return;
                    }

                    runOnUiThread(() -> {
                        if (sessionState != SessionState.SCANNING) {
                            return;
                        }

                        long now = System.currentTimeMillis();

                        String epc = tagInfo.getEPC();
                        String antenna =
                                normalizeAntenna(tagInfo.getAnt());

                        double rssi = safeRssi(tagInfo);

                        TagRecord record = uniqueTags.get(epc);

                        if (record == null) {
                            record = new TagRecord();
                            record.epc = epc;
                            record.firstSeenAt = now;

                            uniqueTags.put(epc, record);
                        }

                        record.count++;
                        record.lastSeenAt = now;

                        if (rssi > record.strongestRssi) {
                            record.strongestRssi = rssi;
                        }

                        AntennaRecord antennaRecord =
                                record.antennas.get(antenna);

                        if (antennaRecord == null) {
                            antennaRecord = new AntennaRecord();
                            antennaRecord.firstSeenAt = now;

                            record.antennas.put(
                                    antenna,
                                    antennaRecord
                            );
                        }

                        antennaRecord.count++;
                        antennaRecord.lastSeenAt = now;
                        antennaRecord.latestRssi = rssi;

                        if (rssi > antennaRecord.strongestRssi) {
                            antennaRecord.strongestRssi = rssi;
                        }

                        totalReads++;

                        refreshDisplay();
                        saveSessionThrottled();
                    });
                }
            };

    private String normalizeAntenna(String rawAntenna) {
        if (rawAntenna == null
                || rawAntenna.trim().isEmpty()) {
            return "?";
        }

        String digits =
                rawAntenna.replaceAll("[^0-9]", "");

        if (digits.isEmpty()) {
            return rawAntenna.trim();
        }

        return digits;
    }

    private double safeRssi(UHFTAGInfo tagInfo) {
        try {
            String value = tagInfo.getRssi();

            if (value == null) {
                return -9999.0;
            }

            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return -9999.0;
        }
    }

    private void refreshDisplay() {
        logLines.clear();

        for (Map.Entry<String, TagRecord> entry
                : uniqueTags.entrySet()) {

            TagRecord record = entry.getValue();

            StringBuilder antennaSummary =
                    new StringBuilder();

            for (int antennaNumber = 1;
                 antennaNumber <= 4;
                 antennaNumber++) {

                String antennaId =
                        String.valueOf(antennaNumber);

                AntennaRecord antennaRecord =
                        record.antennas.get(antennaId);

                if (antennaRecord != null
                        && antennaRecord.count > 0) {

                    if (antennaSummary.length() > 0) {
                        antennaSummary.append("  ");
                    }

                    antennaSummary
                            .append("A")
                            .append(antennaNumber)
                            .append(":")
                            .append(antennaRecord.count);
                }
            }

            if (antennaSummary.length() == 0) {
                antennaSummary
                        .append("A?:")
                        .append(record.count);
            }

            String line = String.format(
                    Locale.US,
                    "%s | x%d | best %s | %s-%s | %s",
                    record.epc,
                    record.count,
                    formatRssi(record.strongestRssi),
                    formatTime(record.firstSeenAt),
                    formatTime(record.lastSeenAt),
                    antennaSummary
            );

            logLines.add(line);
        }

        adapter.notifyDataSetChanged();

        txtTotalReads.setText(
                "Total Reads: " + totalReads
        );

        txtUniqueTags.setText(
                "Unique Tags: " + uniqueTags.size()
        );

        updateSessionText();
    }

    private String formatRssi(double rssi) {
        if (rssi <= -9998.0) {
            return "?";
        }

        return String.format(
                Locale.US,
                "%.2f",
                rssi
        );
    }

    private String formatTime(long timeMillis) {
        if (timeMillis <= 0L) {
            return "--:--:--";
        }

        return timeFormat.format(
                new Date(timeMillis)
        );
    }

    private void updateSessionText() {
    if (sessionId == null) {
        txtSession.setText("Session: none");
        return;
    }

    String shortId;

    if (sessionId.length() > 8) {
        shortId = sessionId.substring(0, 8);
    } else {
        shortId = sessionId;
    }

    txtSession.setText(
            "Session "
                    + shortId
                    + " • "
                    + sessionState.name()
                    + "\nElapsed "
                    + formatDuration(currentElapsedMillis())
                    + " • Active "
                    + formatDuration(currentActiveMillis())
                    + " • Paused "
                    + formatDuration(currentPausedMillis())
    );
}

    private long currentActiveMillis() {
    long currentActive = 0L;

    if (activeStartedAt > 0L) {
        currentActive =
                System.currentTimeMillis()
                        - activeStartedAt;
    }

    return totalActiveMillis + currentActive;
}

    private long currentElapsedMillis() {
    if (sessionStartedAt <= 0L) {
        return 0L;
    }

    long endTime;

    if (sessionEndedAt > 0L) {
        endTime = sessionEndedAt;
    } else {
        endTime = System.currentTimeMillis();
    }

    return Math.max(0L, endTime - sessionStartedAt);
}

    private String formatDuration(long timeMillis) {
        long totalSeconds = timeMillis / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        return String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds
        );
    }

    private void updateControls() {
        boolean openSession =
                sessionState == SessionState.READY
                        || sessionState == SessionState.SCANNING
                        || sessionState == SessionState.PAUSED;

        btnConnect.setEnabled(!readerConnected);

        btnNewSession.setEnabled(
                sessionState != SessionState.SCANNING
        );

        btnStart.setEnabled(
                readerConnected
                        && (
                        sessionState == SessionState.READY
                                || sessionState
                                == SessionState.PAUSED
                )
        );

        if (sessionState == SessionState.PAUSED) {
            btnStart.setText("Resume Scan");
        } else {
            btnStart.setText("Start Scan");
        }

        btnPause.setEnabled(
                sessionState == SessionState.SCANNING
        );

        btnEndSession.setEnabled(openSession);

        btnClear.setEnabled(
                sessionState != SessionState.SCANNING
        );

        boolean antennaControlsEnabled =
                sessionState != SessionState.SCANNING;

        chkAnt1.setEnabled(antennaControlsEnabled);
        chkAnt2.setEnabled(antennaControlsEnabled);
        chkAnt3.setEnabled(antennaControlsEnabled);
        chkAnt4.setEnabled(antennaControlsEnabled);

        updateSessionText();
    }

    private void setStatus(String message) {
        txtStatus.setText(message);
    }

    private void saveSessionThrottled() {
        long now = System.currentTimeMillis();

        if (now - lastSaveAt >= SAVE_THROTTLE_MS) {
            saveSessionNow();
        }
    }

    private void saveSessionNow() {
        lastSaveAt = System.currentTimeMillis();

        if (sessionId == null) {
            return;
        }

        try {
            JSONObject root = new JSONObject();

            root.put("sessionId", sessionId);
            root.put("state", sessionState.name());
            root.put("startedAt", sessionStartedAt);
            root.put("endedAt", sessionEndedAt);
            root.put("pauseStartedAt", pauseStartedAt);
            root.put(
                     "totalPausedMillis",
                      totalPausedMillis
            );

            root.put(
                     "totalActiveMillis",
                      totalActiveMillis
            );

            root.put(
                     "activeStartedAt",
                      activeStartedAt
            );

            root.put("totalReads", totalReads);

            JSONArray tags = new JSONArray();

            for (TagRecord record : uniqueTags.values()) {
                JSONObject tag = new JSONObject();

                tag.put("epc", record.epc);
                tag.put("count", record.count);
                tag.put(
                        "firstSeenAt",
                        record.firstSeenAt
                );
                tag.put(
                        "lastSeenAt",
                        record.lastSeenAt
                );
                tag.put(
                        "strongestRssi",
                        record.strongestRssi
                );

                JSONArray antennas = new JSONArray();

                for (Map.Entry<String, AntennaRecord>
                        antennaEntry
                        : record.antennas.entrySet()) {

                    AntennaRecord antennaRecord =
                            antennaEntry.getValue();

                    JSONObject antenna =
                            new JSONObject();

                    antenna.put(
                            "id",
                            antennaEntry.getKey()
                    );

                    antenna.put(
                            "count",
                            antennaRecord.count
                    );

                    antenna.put(
                            "strongestRssi",
                            antennaRecord.strongestRssi
                    );

                    antenna.put(
                            "latestRssi",
                            antennaRecord.latestRssi
                    );

                    antenna.put(
                            "firstSeenAt",
                            antennaRecord.firstSeenAt
                    );

                    antenna.put(
                            "lastSeenAt",
                            antennaRecord.lastSeenAt
                    );

                    antennas.put(antenna);
                }

                tag.put("antennas", antennas);
                tags.put(tag);
            }

            root.put("tags", tags);

            preferences.edit()
                    .putString(
                            PREF_ACTIVE_SESSION,
                            root.toString()
                    )
                    .apply();

        } catch (Exception exception) {
            setStatus(
                    "Local save failed — "
                            + exception.getMessage()
            );
        }
    }

    private void restoreSession() {
        String storedSession =
                preferences.getString(
                        PREF_ACTIVE_SESSION,
                        null
                );

        if (storedSession == null) {
            return;
        }

        try {
            JSONObject root =
                    new JSONObject(storedSession);

            sessionId =
                    root.optString(
                            "sessionId",
                            null
                    );

            sessionState =
                    SessionState.valueOf(
                            root.optString(
                                    "state",
                                    SessionState.PAUSED.name()
                            )
                    );

            sessionStartedAt =
                    root.optLong(
                            "startedAt",
                            0L
                    );

            sessionEndedAt =
                    root.optLong(
                            "endedAt",
                            0L
                    );

            pauseStartedAt =
                    root.optLong(
                            "pauseStartedAt",
                            0L
                    );

            totalPausedMillis =
            root.optLong(
                "totalPausedMillis",
                0L
        );

            totalActiveMillis =
            root.optLong(
                "totalActiveMillis",
                0L
        );

            activeStartedAt =
        root.optLong(
                "activeStartedAt",
                0L
        );

totalReads =
        root.optInt(
                "totalReads",
                0
        );

            if (sessionState == SessionState.SCANNING) {
    long now = System.currentTimeMillis();

    if (activeStartedAt > 0L) {
        totalActiveMillis += now - activeStartedAt;
        activeStartedAt = 0L;
    }

    sessionState = SessionState.PAUSED;
    pauseStartedAt = now;

    setStatus(
            "Recovered session — paused for safety"
    );
} else {
    activeStartedAt = 0L;
    setStatus("Recovered saved session");
}

            uniqueTags.clear();

            JSONArray tags =
                    root.optJSONArray("tags");

            if (tags != null) {
                for (int index = 0;
                     index < tags.length();
                     index++) {

                    JSONObject tag =
                            tags.getJSONObject(index);

                    TagRecord record =
                            new TagRecord();

                    record.epc =
                            tag.optString(
                                    "epc",
                                    ""
                            );

                    record.count =
                            tag.optInt(
                                    "count",
                                    0
                            );

                    record.firstSeenAt =
                            tag.optLong(
                                    "firstSeenAt",
                                    0L
                            );

                    record.lastSeenAt =
                            tag.optLong(
                                    "lastSeenAt",
                                    0L
                            );

                    record.strongestRssi =
                            tag.optDouble(
                                    "strongestRssi",
                                    -9999.0
                            );

                    JSONArray antennas =
                            tag.optJSONArray("antennas");

                    if (antennas != null) {
                        for (int antennaIndex = 0;
                             antennaIndex
                                     < antennas.length();
                             antennaIndex++) {

                            JSONObject antenna =
                                    antennas.getJSONObject(
                                            antennaIndex
                                    );

                            AntennaRecord antennaRecord =
                                    new AntennaRecord();

                            antennaRecord.count =
                                    antenna.optInt(
                                            "count",
                                            0
                                    );

                            antennaRecord.strongestRssi =
                                    antenna.optDouble(
                                            "strongestRssi",
                                            -9999.0
                                    );

                            antennaRecord.latestRssi =
                                    antenna.optDouble(
                                            "latestRssi",
                                            -9999.0
                                    );

                            antennaRecord.firstSeenAt =
                                    antenna.optLong(
                                            "firstSeenAt",
                                            0L
                                    );

                            antennaRecord.lastSeenAt =
                                    antenna.optLong(
                                            "lastSeenAt",
                                            0L
                                    );

                            String antennaId =
                                    antenna.optString(
                                            "id",
                                            "?"
                                    );

                            record.antennas.put(
                                    antennaId,
                                    antennaRecord
                            );
                        }
                    }

                    if (!record.epc.isEmpty()) {
                        uniqueTags.put(
                                record.epc,
                                record
                        );
                    }
                }
            }

            saveSessionNow();

        } catch (Exception exception) {
            preferences.edit()
                    .remove(PREF_ACTIVE_SESSION)
                    .apply();

            sessionState =
                    SessionState.NO_SESSION;

            setStatus(
                    "Saved session could not be restored"
            );
        }
    }

    @Override
    protected void onDestroy() {
        sessionClockHandler.removeCallbacks(sessionClockRunnable);
        saveSessionNow();

        if (reader != null) {
            try {
                reader.stopInventory();
                reader.free();
            } catch (Throwable ignored) {
            }
        }

        super.onDestroy();
    }
}

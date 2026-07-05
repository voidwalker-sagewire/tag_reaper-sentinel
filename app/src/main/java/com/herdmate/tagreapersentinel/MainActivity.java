package com.herdmate.tagreapersentinel;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.rscja.deviceapi.RFIDWithUHFA4Host;
import com.rscja.deviceapi.entity.AntennaState;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.enums.AntennaEnum;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private RFIDWithUHFA4Host reader;

    private Button btnConnect, btnStart, btnStop;
    private CheckBox chkAnt1, chkAnt2, chkAnt3, chkAnt4;
    private TextView txtStatus, txtTotalReads, txtUniqueTags;
    private ListView listTags;

    private ArrayAdapter<String> adapter;
    private final List<String> logLines = new ArrayList<>();

    private final LinkedHashMap<String, TagRecord> uniqueTags = new LinkedHashMap<>();
    private int totalReads = 0;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static class TagRecord {
        String epc;
        String ant;
        String rssi;
        int count;
        String firstSeen;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        chkAnt1 = findViewById(R.id.chkAnt1);
        chkAnt2 = findViewById(R.id.chkAnt2);
        chkAnt3 = findViewById(R.id.chkAnt3);
        chkAnt4 = findViewById(R.id.chkAnt4);
        txtStatus = findViewById(R.id.txtStatus);
        txtTotalReads = findViewById(R.id.txtTotalReads);
        txtUniqueTags = findViewById(R.id.txtUniqueTags);
        listTags = findViewById(R.id.listTags);

        adapter = new ArrayAdapter<>(this, R.layout.list_item_tag, logLines);
        listTags.setAdapter(adapter);

        btnConnect.setOnClickListener(v -> connectReader());
        btnStart.setOnClickListener(v -> startScanning());
        btnStop.setOnClickListener(v -> stopScanning());
    }

    private void connectReader() {
        setStatus("Connecting...");
        btnConnect.setEnabled(false);

        new Thread(() -> {
            reader = RFIDWithUHFA4Host.getInstance();
            boolean connected = reader.init(getApplicationContext());

            runOnUiThread(() -> {
                if (connected) {
                    setStatus("Connected — " + safeVersion());
                    btnStart.setEnabled(true);
                    reader.setInventoryCallback(inventoryCallback);
                } else {
                    setStatus("Connection failed — check hardware and try again");
                    btnConnect.setEnabled(true);
                }
            });
        }).start();
    }

    private String safeVersion() {
        try {
            String v = reader.getVersion();
            return v != null ? v : "unknown version";
        } catch (Exception e) {
            return "unknown version";
        }
    }

    private void startScanning() {
        if (reader == null) return;

        applyAntennaSelection();

        new Thread(() -> {
            boolean started = reader.startInventoryTag();
            runOnUiThread(() -> {
                if (started) {
                    setStatus("Scanning...");
                    btnStart.setEnabled(false);
                    btnStop.setEnabled(true);
                } else {
                    setStatus("Failed to start scan");
                }
            });
        }).start();
    }

    private void stopScanning() {
        if (reader == null) return;

        new Thread(() -> {
            reader.stopInventory();
            runOnUiThread(() -> {
                setStatus("Stopped");
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
            });
        }).start();
    }

    private void applyAntennaSelection() {
        List<AntennaState> states = new ArrayList<>();
        states.add(makeAntennaState(AntennaEnum.ANT1, chkAnt1.isChecked()));
        states.add(makeAntennaState(AntennaEnum.ANT2, chkAnt2.isChecked()));
        states.add(makeAntennaState(AntennaEnum.ANT3, chkAnt3.isChecked()));
        states.add(makeAntennaState(AntennaEnum.ANT4, chkAnt4.isChecked()));
        reader.setANT(states);
    }

    private AntennaState makeAntennaState(AntennaEnum ant, boolean enabled) {
        return new AntennaState(ant, enabled);
    }

    private final IUHFInventoryCallback inventoryCallback = new IUHFInventoryCallback() {
        @Override
        public void callback(UHFTAGInfo tagInfo) {
            if (tagInfo == null || tagInfo.getEPC() == null) return;

            runOnUiThread(() -> {
                totalReads++;
                String epc = tagInfo.getEPC();
                TagRecord record = uniqueTags.get(epc);
                if (record == null) {
                    record = new TagRecord();
                    record.epc = epc;
                    record.firstSeen = timeFormat.format(new Date());
                    record.count = 0;
                    uniqueTags.put(epc, record);
                }
                record.ant = tagInfo.getAnt();
                record.rssi = safeRssi(tagInfo);
                record.count++;

                refreshLog();
                txtTotalReads.setText("Total Reads: " + totalReads);
                txtUniqueTags.setText("Unique Tags: " + uniqueTags.size());
            });
        }
    };

    private String safeRssi(UHFTAGInfo tagInfo) {
        try {
            String r = tagInfo.getRssi();
            return r != null ? r : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    private void refreshLog() {
        logLines.clear();
        for (Map.Entry<String, TagRecord> entry : uniqueTags.entrySet()) {
            TagRecord r = entry.getValue();
            logLines.add(String.format(Locale.US,
                    "%s  |  ANT %s  |  RSSI %s  |  x%d  |  first seen %s",
                    r.epc, r.ant, r.rssi, r.count, r.firstSeen));
        }
        adapter.notifyDataSetChanged();
    }

    private void setStatus(String msg) {
        txtStatus.setText(msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (reader != null) {
            reader.stopInventory();
            reader.free();
        }
    }
}

package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.text.method.ScrollingMovementMethod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;

public class DetailActivity extends AppCompatActivity {

    private TextView smsDisplay, statusLabel;
    private TextView testConsole;
    private final ArrayList<HashMap<String, String>> smsList = new ArrayList<>();
    private final int PERMISSION_REQUEST_CODE = 1;
    private String ownerName = "";
    private android.content.SharedPreferences prefs;
    private final String API_ENDPOINT = "https://debtless-creation-goal.ngrok-free.dev/webhook/api/sms/ingest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        // Initialize SharedPreferences
        prefs = getSharedPreferences("SevaiPrefs", MODE_PRIVATE);
        // Load cached username (used as owner name)
        ownerName = prefs.getString("username", "");

        smsDisplay = findViewById(R.id.smsDisplay);
        statusLabel = findViewById(R.id.statusLabel);
        Button readButton = findViewById(R.id.btnRead);
        Button exportJsonButton = findViewById(R.id.btnExportJson);
        Button shareButton = findViewById(R.id.btnShare);
        Button testSendButton = findViewById(R.id.btnTestSendLatest);
        Button backButton = findViewById(R.id.btnBack);
        testConsole = findViewById(R.id.testConsole);
        if (testConsole != null) {
            testConsole.setMovementMethod(new ScrollingMovementMethod());
        }

        readButton.setOnClickListener(v -> readTodaySms());
        exportJsonButton.setOnClickListener(v -> exportToJson());
        shareButton.setOnClickListener(v -> shareFiles());
        if (testSendButton != null) {
            testSendButton.setOnClickListener(v -> manualTestSendLatest());
        }
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        // Add a long-press listener to the read button to filter only financial SMS
        readButton.setOnLongClickListener(v -> {
            filterFinancialOnly();
            return true;
        });

        // Update status to show live processing capability
        statusLabel.setText("Sevai.app - SMS Processor\nLive processing: ✅ Enabled\nAPI forwarding: Score ≥7 only");
    }

    private void consoleClear() {
        if (testConsole == null) {
            return;
        }
        runOnUiThread(() -> testConsole.setText(""));
    }

    private void consoleLog(String line) {
        if (testConsole == null) {
            return;
        }
        runOnUiThread(() -> {
            testConsole.append(line + "\n");
            // Auto-scroll to bottom
            if (testConsole.getLayout() != null) {
                int scrollAmount = testConsole.getLayout().getLineTop(testConsole.getLineCount()) - testConsole.getHeight();
                if (scrollAmount > 0) {
                    testConsole.scrollTo(0, scrollAmount);
                } else {
                    testConsole.scrollTo(0, 0);
                }
            }
        });
    }

    private void manualTestSendLatest() {
        consoleClear();
        consoleLog("[Test] Starting manual send of latest financial SMS...");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            consoleLog("[Perm] READ_SMS not granted. Requesting...");
            Toast.makeText(this, "Grant READ_SMS permission and try again", Toast.LENGTH_LONG).show();
            return;
        }

        // Check API key
        String apiKey = prefs.getString("api_key", null);
        if (apiKey == null || apiKey.trim().isEmpty() || "your-api-key-here".equals(apiKey)) {
            consoleLog("[Config] Missing API key. Open app setup to configure.");
            Toast.makeText(this, "Configure your API key first (Setup)", Toast.LENGTH_LONG).show();
            return;
        }

        new Thread(() -> {
            try {
                consoleLog("[Query] Searching inbox for latest financial SMS...");
                Uri smsUri = Uri.parse("content://sms/inbox");
                Cursor cursor = getContentResolver().query(
                        smsUri,
                        null,
                        null,
                        null,
                        "date DESC"
                );
                if (cursor == null) {
                    consoleLog("[Error] Could not access SMS inbox.");
                    return;
                }

                String sender = null;
                String body = null;
                long timestamp = 0L;
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                while (cursor.moveToNext()) {
                    long dateMillis = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                    String addr = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String msg = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    SmsClassifier.ClassificationResult r = SmsClassifier.classify(msg);
                    if (r.isFinancial()) {
                        sender = addr;
                        body = msg;
                        timestamp = dateMillis;
                        consoleLog("[Match] Found financial SMS (score=" + r.getScore() + ") from " + sender);
                        break;
                    }
                }
                cursor.close();

                if (sender == null || body == null) {
                    consoleLog("[Result] No financial SMS found to send.");
                    return;
                }

                // Prepare payload
                String receiptNumber = null;
                try {
                    android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                    if (tm != null) {
                        String n = tm.getLine1Number();
                        if (n != null && !n.trim().isEmpty()) {
                            receiptNumber = n;
                        }
                    }
                } catch (Exception ignored) {
                }

                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("time", df.format(timestamp));
                payload.put("senderName", sender);
                payload.put("receipientNumber", receiptNumber != null ? receiptNumber : "");
                payload.put("message", body);

                consoleLog("[HTTP] POST " + API_ENDPOINT);
                consoleLog("[HTTP] Payload prepared (apiKey=****" + (apiKey.length() >= 4 ? apiKey.substring(apiKey.length() - 4) : apiKey) + ")");

                java.net.URL url = new java.net.URL(API_ENDPOINT);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Sevai.app Android Client");
                // New API expects API key via header X-SMS-API-Key
                conn.setRequestProperty("X-SMS-API-Key", apiKey);
                conn.setDoOutput(true);

                String json = payload.toString();
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                consoleLog("[HTTP] Response code: " + responseCode);
                if (responseCode == 200) {
                    consoleLog("[Success] SMS sent to API successfully.");
                } else {
                    consoleLog("[Warning] API returned code: " + responseCode);
                }
                conn.disconnect();
                consoleLog("[Done] Manual test finished.");
            } catch (Exception e) {
                consoleLog("[Error] " + e.getMessage());
            }
        }).start();
    }

    // Copy all other methods from MainActivity.java here
    // (readTodaySms, exportToJson, performJsonExport, filterFinancialOnly, shareFiles, getStoragePath)
    private void readTodaySms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant READ_SMS permission and try again", Toast.LENGTH_LONG).show();
            return;
        }

        Uri smsUri = Uri.parse("content://sms/inbox");

        // Get timestamp for one month ago at the start of that day
        Calendar oneMonthAgo = Calendar.getInstance();
        oneMonthAgo.add(Calendar.MONTH, -1);
        oneMonthAgo.set(Calendar.HOUR_OF_DAY, 0);
        oneMonthAgo.set(Calendar.MINUTE, 0);
        oneMonthAgo.set(Calendar.SECOND, 0);
        oneMonthAgo.set(Calendar.MILLISECOND, 0);
        long oneMonthAgoMillis = oneMonthAgo.getTimeInMillis();

        // Query SMS inbox for messages from the past month
        Cursor cursor = getContentResolver().query(
                smsUri,
                null,
                "date >= ?",
                new String[]{String.valueOf(oneMonthAgoMillis)},
                "date DESC"
        );

        if (cursor == null) {
            statusLabel.setText("Error accessing SMS content");
            return;
        }

        smsList.clear();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        SimpleDateFormat fullFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        while (cursor.moveToNext()) {
            long dateMillis = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
            String sender = cursor.getString(cursor.getColumnIndexOrThrow("address"));
            String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));

            // Classify the SMS using heuristic scoring
            SmsClassifier.ClassificationResult classification = SmsClassifier.classify(body);

            if (classification.isFinancial()) {
                HashMap<String, String> smsData = new HashMap<>();
                smsData.put("sender", sender);
                smsData.put("message", body);
                smsData.put("timestamp", String.valueOf(dateMillis));
                smsData.put("date", dateFormat.format(dateMillis));
                smsData.put("time", timeFormat.format(dateMillis));
                smsData.put("datetime_readable", fullFormat.format(dateMillis));
                smsData.put("category", classification.getCategory());
                smsData.put("score", String.valueOf(classification.getScore()));
                smsData.put("is_financial", String.valueOf(classification.isFinancial()));
                // Extracted fields (use empty string if null)
                smsData.put("amount", classification.getAmount() != null ? String.valueOf(classification.getAmount()) : "");
                smsData.put("currency", classification.getCurrency() != null ? classification.getCurrency() : "");
                smsData.put("type", classification.getType() != null ? classification.getType() : "");
                smsData.put("medium", classification.getMedium() != null ? classification.getMedium() : "");
                smsData.put("targetParty", classification.getTargetParty() != null ? classification.getTargetParty() : "");
                smsData.put("description", classification.getDescription() != null ? classification.getDescription() : "");
                smsData.put("extractedDate", classification.getExtractedDate() != null ? classification.getExtractedDate() : "");

                smsList.add(smsData);
            }
        }

        cursor.close();

        if (smsList.isEmpty()) {
            smsDisplay.setText("No financial SMS found in the last 1 month.");
            statusLabel.setText("No messages found.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("💰 Found ").append(smsList.size()).append(" financial SMS from the last 1 month\n\n");

            int count = 1;
            for (HashMap<String, String> sms : smsList) {
                sb.append(count++).append(". 💰 [").append(sms.get("time")).append("] ");
                sb.append("From: ").append(sms.get("sender")).append("\n");
                sb.append("Category: ").append(sms.get("category")).append(" (Score: ").append(sms.get("score")).append(")\n");
                sb.append(sms.get("message")).append("\n");
                sb.append("==================================================\n\n");
            }
            smsDisplay.setText(sb.toString());
            statusLabel.setText("✅ Loaded " + smsList.size() + " financial SMS (1 month)");
        }
    }

    private File getStoragePath() {
        // Use app-specific external storage (works on Android 10+ without storage permission)
        File storageDir = getExternalFilesDir(null);
        if (storageDir == null) {
            storageDir = getFilesDir(); // Fallback to internal storage
        }
        return storageDir;
    }

    private void exportToJson() {
        if (smsList.isEmpty()) {
            Toast.makeText(this, "No SMS to export. Read SMS first!", Toast.LENGTH_SHORT).show();
            return;
        }
        // Try to autodetect device phone number (best-effort, no extra permissions requested)
        String detectedPhone = "";
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                String n = tm.getLine1Number();
                if (n != null) {
                    detectedPhone = n;
                }
            }
        } catch (Exception ignored) {
        }

        if (detectedPhone == null || detectedPhone.trim().isEmpty()) {
            // Ask the user for their phone number (receiver) since autodetect failed
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("Enter your phone number");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setHint("+1234567890");
            builder.setView(input);
            builder.setPositiveButton("OK", (dialog, which) -> {
                String provided = input.getText() != null ? input.getText().toString().trim() : "";
                performJsonExport(provided);
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
            builder.show();
        } else {
            performJsonExport(detectedPhone);
        }
    }

    private void performJsonExport(String ownerPhone) {
        try {
            // Filter only financial transactions
            ArrayList<HashMap<String, String>> financialSms = new ArrayList<>();
            for (HashMap<String, String> sms : smsList) {
                if ("true".equals(sms.get("is_financial"))) {
                    financialSms.add(sms);
                }
            }

            if (financialSms.isEmpty()) {
                Toast.makeText(this, "No financial transactions found to export!", Toast.LENGTH_LONG).show();
                return;
            }

            // Build JSON content
            JSONObject root = new JSONObject();
            JSONObject owner = new JSONObject();
            owner.put("name", ownerName != null ? ownerName : "");
            owner.put("phone", ownerPhone != null ? ownerPhone : "");
            root.put("owner", owner);

            JSONArray messages = new JSONArray();
            for (HashMap<String, String> sms : financialSms) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("sender", sms.get("sender"));
                jsonObject.put("message", sms.get("message"));
                jsonObject.put("timestamp", sms.get("timestamp"));
                jsonObject.put("date", sms.get("date"));
                jsonObject.put("time", sms.get("time"));
                jsonObject.put("datetime_readable", sms.get("datetime_readable"));
                jsonObject.put("category", sms.get("category"));
                jsonObject.put("score", sms.get("score"));
                jsonObject.put("is_financial", sms.get("is_financial"));
                // Include extracted fields
                jsonObject.put("amount", sms.get("amount"));
                jsonObject.put("currency", sms.get("currency"));
                jsonObject.put("type", sms.get("type"));
                jsonObject.put("medium", sms.get("medium"));
                jsonObject.put("targetParty", sms.get("targetParty"));
                jsonObject.put("description", sms.get("description"));
                jsonObject.put("extractedDate", sms.get("extractedDate"));
                messages.put(jsonObject);
            }
            root.put("messages", messages);

            String filename = "sms_export_" + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(System.currentTimeMillis()) + ".json";

            File storageDir = getStoragePath();
            File candidate = new File(storageDir, filename);
            FileWriter w = new FileWriter(candidate);
            w.write(root.toString(2));
            w.close();

            Toast.makeText(this, "✅ Exported " + financialSms.size() + " financial transactions to:\n" + candidate.getAbsolutePath(), Toast.LENGTH_LONG).show();
            statusLabel.setText("✅ JSON export complete! (" + financialSms.size() + " transactions)");
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void filterFinancialOnly() {
        if (smsList.isEmpty()) {
            Toast.makeText(this, "No SMS loaded. Read SMS first!", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<HashMap<String, String>> financialSms = new ArrayList<>();
        for (HashMap<String, String> sms : smsList) {
            if ("true".equals(sms.get("is_financial"))) {
                financialSms.add(sms);
            }
        }

        if (financialSms.isEmpty()) {
            smsDisplay.setText("No financial SMS found in the loaded messages.");
            statusLabel.setText("No financial messages found.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("💰 FINANCIAL SMS ONLY\n");
        sb.append("Found ").append(financialSms.size()).append(" financial messages:\n\n");

        int count = 1;
        for (HashMap<String, String> sms : financialSms) {
            sb.append(count++).append(". 💰 [").append(sms.get("time")).append("] ");
            sb.append("From: ").append(sms.get("sender")).append("\n");
            sb.append("Category: ").append(sms.get("category")).append(" (Score: ").append(sms.get("score")).append(")\n");
            sb.append(sms.get("message")).append("\n");
            sb.append("==================================================\n\n");
        }

        smsDisplay.setText(sb.toString());
        statusLabel.setText("💰 Showing " + financialSms.size() + " financial SMS");
        Toast.makeText(this, "Filtered to show only financial SMS", Toast.LENGTH_SHORT).show();
    }

    private void shareFiles() {
        try {
            File storageDir = getStoragePath();
            String todayStr = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(System.currentTimeMillis());

            ArrayList<Uri> fileUris = new ArrayList<>();
            // Only look for JSON export
            File jsonFile = new File(storageDir, "sms_export_" + todayStr + ".json");
            if (jsonFile.exists()) {
                Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", jsonFile);
                fileUris.add(fileUri);
            }

            if (fileUris.isEmpty()) {
                Toast.makeText(this, "No exported JSON file found. Export first!", Toast.LENGTH_LONG).show();
                return;
            }

            Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUris.get(0));
            shareIntent.setType("application/json");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share SMS Export"));

            statusLabel.setText("✅ Sharing JSON file");
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

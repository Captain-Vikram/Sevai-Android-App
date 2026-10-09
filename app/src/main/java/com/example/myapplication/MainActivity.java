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
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.DecelerateInterpolator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

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
        setContentView(R.layout.activity_main);

        // Initialize SharedPreferences
        prefs = getSharedPreferences("SevaiPrefs", MODE_PRIVATE);
        // Load cached username (used as owner name)
        ownerName = prefs.getString("username", "");

        // Find the new buttons from the modern layout
        Button zeroTrustButton = findViewById(R.id.zeroTrustButton);
        Button testSMSButton = findViewById(R.id.testSMSButton);

        // Keep references for hidden views (compatibility)
        smsDisplay = findViewById(R.id.smsDisplay);
        statusLabel = findViewById(R.id.statusLabel);
        testConsole = findViewById(R.id.testConsole);

        // Wire new buttons
        if (zeroTrustButton != null) {
            applyPressAnimation(zeroTrustButton);
            updateZeroTrustButtonState(zeroTrustButton);
            zeroTrustButton.setOnClickListener(v -> toggleZeroTrustMode(zeroTrustButton));
        }

        if (testSMSButton != null) {
            applyPressAnimation(testSMSButton);
            testSMSButton.setOnClickListener(v -> openDetailActivityForTest());
        }

        // 1-Month History button: send all transaction-related SMS (score >= 7) to API in one JSON
        Button historyButton = findViewById(R.id.historyButton);
        if (historyButton != null) {
            applyPressAnimation(historyButton);
            historyButton.setOnClickListener(v -> sendOneMonthHistory());
        }

        // Check if API key and username are configured
        checkInitialSetup();

        requestSmsPermission();
        requestNotificationPermission();
    }

    private void applyPressAnimation(View view) {
        if (view == null) {
            return;
        }

        view.setScaleX(1f);
        view.setScaleY(1f);

        final DecelerateInterpolator pressInterpolator = new DecelerateInterpolator();
        final OvershootInterpolator releaseInterpolator = new OvershootInterpolator(2f);

        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(0.95f)
                            .scaleY(0.95f)
                            .setDuration(90)
                            .setInterpolator(pressInterpolator)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(170)
                            .setInterpolator(releaseInterpolator)
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void toggleZeroTrustMode(Button button) {
        boolean currentlyEnabled = prefs.getBoolean("zero_trust_mode", false);
        boolean newState = !currentlyEnabled;

        prefs.edit().putBoolean("zero_trust_mode", newState).apply();

        updateZeroTrustButtonState(button);

        String message = newState
                ? "Zero Trust Mode ENABLED - Background SMS processing stopped"
                : "Zero Trust Mode DISABLED - Background SMS processing active";

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();

        // Show notification for mode change
        NotificationUtils.notify(this, 999,
                newState ? "🔒 Zero Trust Mode ON" : "🔓 Zero Trust Mode OFF",
                message);
    }

    private void updateZeroTrustButtonState(Button button) {
        boolean zeroTrustEnabled = prefs.getBoolean("zero_trust_mode", false);

        if (zeroTrustEnabled) {
            button.setText("Zero Trust: ON");
            // Optional: Change button appearance when enabled
        } else {
            button.setText("Zero Trust Mode");
        }
    }

    private void openDetailActivity() {
        Intent intent = new Intent(this, DetailActivity.class);
        startActivity(intent);
    }

    private void openDetailActivityForTest() {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("auto_test", true);
        startActivity(intent);
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
            requestSmsPermission();
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
                conn.setRequestProperty("ngrok-skip-browser-warning", "true");
                // Pass API key in header as X-SMS-API-Key per new API contract
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

    /**
     * Query SMS from the last 1 month, filter financial messages with score >=
     * 7 and POST them in a single JSON payload to the API endpoint.
     */
    private void sendOneMonthHistory() {
        consoleClear();
        consoleLog("[History] Preparing 1-month history export...");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            consoleLog("[Perm] READ_SMS not granted. Requesting...");
            requestSmsPermission();
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
                consoleLog("[Query] Searching inbox for financial SMS in last 1 month (score >=7)...");
                Uri smsUri = Uri.parse("content://sms/inbox");

                // One month ago timestamp
                Calendar oneMonthAgo = Calendar.getInstance();
                oneMonthAgo.add(Calendar.MONTH, -1);
                oneMonthAgo.set(Calendar.HOUR_OF_DAY, 0);
                oneMonthAgo.set(Calendar.MINUTE, 0);
                oneMonthAgo.set(Calendar.SECOND, 0);
                oneMonthAgo.set(Calendar.MILLISECOND, 0);
                long oneMonthAgoMillis = oneMonthAgo.getTimeInMillis();

                Cursor cursor = getContentResolver().query(
                        smsUri,
                        null,
                        "date >= ?",
                        new String[]{String.valueOf(oneMonthAgoMillis)},
                        "date DESC"
                );

                if (cursor == null) {
                    consoleLog("[Error] Could not access SMS inbox.");
                    return;
                }

                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                JSONArray messages = new JSONArray();
                int matched = 0;

                while (cursor.moveToNext()) {
                    long dateMillis = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                    String addr = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String msg = cursor.getString(cursor.getColumnIndexOrThrow("body"));

                    SmsClassifier.ClassificationResult r = SmsClassifier.classify(msg);
                    if (r.isFinancial() && r.getScore() >= 7) {
                        matched++;
                        JSONObject jsonObject = new JSONObject();
                        try {
                            jsonObject.put("sender", addr);
                            jsonObject.put("message", msg);
                            jsonObject.put("timestamp", String.valueOf(dateMillis));
                            jsonObject.put("datetime_readable", df.format(dateMillis));
                            jsonObject.put("category", r.getCategory());
                            jsonObject.put("score", r.getScore());
                            jsonObject.put("amount", r.getAmount() != null ? r.getAmount() : JSONObject.NULL);
                            jsonObject.put("currency", r.getCurrency() != null ? r.getCurrency() : JSONObject.NULL);
                            jsonObject.put("type", r.getType() != null ? r.getType() : JSONObject.NULL);
                            jsonObject.put("medium", r.getMedium() != null ? r.getMedium() : JSONObject.NULL);
                            jsonObject.put("targetParty", r.getTargetParty() != null ? r.getTargetParty() : JSONObject.NULL);
                            jsonObject.put("description", r.getDescription() != null ? r.getDescription() : JSONObject.NULL);
                            jsonObject.put("extractedDate", r.getExtractedDate() != null ? r.getExtractedDate() : JSONObject.NULL);
                        } catch (Exception ignored) {
                        }
                        messages.put(jsonObject);
                    }
                }
                cursor.close();

                if (matched == 0) {
                    consoleLog("[Result] No qualifying financial SMS (score>=7) found in last 1 month.");
                    runOnUiThread(() -> Toast.makeText(this, "No qualifying messages found.", Toast.LENGTH_LONG).show());
                    return;
                }

                // Make a final copy for use inside lambdas (required by Java)
                final int matchedCount = matched;

                // Build payload
                JSONObject root = new JSONObject();
                JSONObject owner = new JSONObject();
                owner.put("name", ownerName != null ? ownerName : "");

                // Try to detect phone number (optional)
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
                owner.put("phone", detectedPhone != null ? detectedPhone : "");
                root.put("owner", owner);
                root.put("messages", messages);

                consoleLog("[HTTP] POST " + API_ENDPOINT + " (" + matchedCount + " messages)");

                java.net.URL url = new java.net.URL(API_ENDPOINT);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Sevai.app Android Client");
                conn.setRequestProperty("ngrok-skip-browser-warning", "true");
                conn.setRequestProperty("X-SMS-API-Key", apiKey);
                conn.setDoOutput(true);

                // API key is sent in header (X-SMS-API-Key) per updated API spec; do not include in body
                String json = root.toString();
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                consoleLog("[HTTP] Response code: " + responseCode);
                if (responseCode == 200) {
                    consoleLog("[Success] History sent to API successfully.");
                    runOnUiThread(() -> Toast.makeText(this, "Sent " + matchedCount + " messages to server.", Toast.LENGTH_LONG).show());
                } else {
                    consoleLog("[Warning] API returned code: " + responseCode);
                    runOnUiThread(() -> Toast.makeText(this, "Server returned code: " + responseCode, Toast.LENGTH_LONG).show());
                }
                conn.disconnect();
            } catch (Exception e) {
                consoleLog("[Error] " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * Check if API key and username are configured, prompt if missing
     */
    private void checkInitialSetup() {
        String apiKey = prefs.getString("api_key", null);
        String username = prefs.getString("username", null);

        if (apiKey == null || apiKey.trim().isEmpty() || username == null || username.trim().isEmpty()) {
            showInitialSetupDialog();
        }
    }

    /**
     * Show dialog to input API key and username
     */
    private void showInitialSetupDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Sevai.app Setup");
        builder.setMessage("Please enter your Sevai.app credentials to enable SMS forwarding.");
        builder.setCancelable(false);

        // Create layout for two input fields
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        // Username input
        android.widget.TextView usernameLabel = new android.widget.TextView(this);
        usernameLabel.setText("Username:");
        usernameLabel.setTextSize(16);
        usernameLabel.setPadding(0, 0, 0, 8);
        layout.addView(usernameLabel);

        android.widget.EditText usernameInput = new android.widget.EditText(this);
        usernameInput.setHint("Enter your Sevai.app username");
        usernameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        layout.addView(usernameInput);

        // API Key input
        android.widget.TextView apiKeyLabel = new android.widget.TextView(this);
        apiKeyLabel.setText("API Key:");
        apiKeyLabel.setTextSize(16);
        apiKeyLabel.setPadding(0, 16, 0, 8);
        layout.addView(apiKeyLabel);

        android.widget.EditText apiKeyInput = new android.widget.EditText(this);
        apiKeyInput.setHint("Enter your Sevai.app API key");
        apiKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(apiKeyInput);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String enteredUsername = usernameInput.getText() != null ? usernameInput.getText().toString().trim() : "";
            String enteredApiKey = apiKeyInput.getText() != null ? apiKeyInput.getText().toString().trim() : "";

            if (enteredUsername.isEmpty() || enteredApiKey.isEmpty()) {
                Toast.makeText(this, "Both username and API key are required!", Toast.LENGTH_LONG).show();
                showInitialSetupDialog(); // Show again if empty
                return;
            }

            // Save to SharedPreferences
            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putString("username", enteredUsername);
            editor.putString("api_key", enteredApiKey);
            editor.apply();

            Toast.makeText(this, "✅ Setup complete! SMS forwarding enabled.", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Skip", (dialog, which) -> {
            Toast.makeText(this, "⚠️ Setup skipped. SMS forwarding disabled until configured.", Toast.LENGTH_LONG).show();
        });

        builder.show();
    }

    private void askOwnerNameIfMissing() {
        // Load username from SharedPreferences (set during initial setup)
        ownerName = prefs.getString("username", "");
        if (ownerName == null || ownerName.trim().isEmpty()) {
            // Fallback: ask for name if not set during setup
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("Enter your name");

            final android.widget.EditText input = new android.widget.EditText(this);
            input.setHint("Your name (for exports)");
            builder.setView(input);

            builder.setPositiveButton("OK", (dialog, which) -> {
                ownerName = input.getText() != null ? input.getText().toString().trim() : "";
                // Save to prefs for future use
                android.content.SharedPreferences.Editor editor = prefs.edit();
                editor.putString("username", ownerName);
                editor.apply();
            });
            builder.setNegativeButton("Skip", (dialog, which) -> dialog.cancel());
            builder.setCancelable(false);
            builder.show();
        }
    }

    private void requestSmsPermission() {
        // Request both READ_SMS and RECEIVE_SMS permissions at runtime
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS},
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean readSmsGranted = false;
            boolean receiveSmsGranted = false;

            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.READ_SMS.equals(permissions[i])) {
                    readSmsGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                } else if (Manifest.permission.RECEIVE_SMS.equals(permissions[i])) {
                    receiveSmsGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                }
            }

            if (readSmsGranted && receiveSmsGranted) {
                Toast.makeText(this, "✅ SMS permissions granted! Live SMS processing enabled.", Toast.LENGTH_SHORT).show();
            } else {
                String deniedPerms = "";
                if (!readSmsGranted) {
                    deniedPerms += "READ_SMS ";
                }
                if (!receiveSmsGranted) {
                    deniedPerms += "RECEIVE_SMS";
                }
                Toast.makeText(this, "❌ SMS permissions denied: " + deniedPerms.trim()
                        + ". Some features may not work.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void readTodaySms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant READ_SMS permission and try again", Toast.LENGTH_LONG).show();
            requestSmsPermission();
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
            sb.append("� Found ").append(smsList.size()).append(" financial SMS from the last 1 month\n\n");

            int count = 1;
            for (HashMap<String, String> sms : smsList) {
                sb.append(count++).append(". 💰 [").append(sms.get("time")).append("] ");
                sb.append("From: ").append(sms.get("sender")).append("\n");
                sb.append("Category: ").append(sms.get("category")).append(" (Score: ").append(sms.get("score")).append(")\n");
                sb.append(sms.get("message")).append("\n");
                sb.append("==================================================\n\n");
            }
            smsDisplay.setText(sb.toString());
            statusLabel.setText("✅ Loaded " + smsList.size() + " financial SMS (1 month)\nReady to export!");
        }
    }

    private void requestNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            PERMISSION_REQUEST_CODE);
                }
            }
        } catch (Exception ignored) {
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

            // 1) Try writing to public Downloads folder
            File exportFile = null;
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (downloads != null) {
                    if (!downloads.exists()) {
                        downloads.mkdirs();
                    }
                    File candidate = new File(downloads, filename);
                    FileWriter w = new FileWriter(candidate);
                    w.write(root.toString(2));
                    w.close();
                    exportFile = candidate;
                }
            } catch (Exception ignored) {
                exportFile = null;
            }

            // 2) If Downloads failed, try creating a custom folder at external storage root: /storage/emulated/0/json
            if (exportFile == null) {
                try {
                    File extRoot = Environment.getExternalStorageDirectory();
                    File jsonDir = new File(extRoot, "json");
                    if (!jsonDir.exists()) {
                        jsonDir.mkdirs();
                    }
                    File candidate = new File(jsonDir, filename);
                    FileWriter w = new FileWriter(candidate);
                    w.write(root.toString(2));
                    w.close();
                    exportFile = candidate;
                } catch (Exception ignored) {
                    exportFile = null;
                }
            }

            // 3) Fallback to app-specific storage
            if (exportFile == null) {
                File storageDir = getStoragePath();
                File candidate = new File(storageDir, filename);
                FileWriter w = new FileWriter(candidate);
                w.write(root.toString(2));
                w.close();
                exportFile = candidate;
            }

            Toast.makeText(this, "✅ Exported " + financialSms.size() + " financial transactions to:\n" + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            statusLabel.setText("✅ JSON export complete! (" + financialSms.size() + " transactions)");
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Filter and display only financial SMS messages
     */
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
        statusLabel.setText("💰 Showing " + financialSms.size() + " financial SMS (Long-press Read again to show all)");
        Toast.makeText(this, "Filtered to show only financial SMS", Toast.LENGTH_SHORT).show();
    }

    // Owner info is gathered via user prompt; no Contacts/phone permissions are required
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

package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * BroadcastReceiver for processing incoming SMS messages in real-time Filters
 * financial transactions and forwards them to the API endpoint
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String API_ENDPOINT = "https://debtless-creation-goal.ngrok-free.dev/api/sms/ingest";
    // API key will be loaded from SharedPreferences
    private static String API_KEY = null;
    // Higher threshold for API forwarding (only high-confidence financial messages)
    private static final int API_FORWARDING_THRESHOLD = 7;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            return;
        }

        // Check if Zero Trust Mode is enabled
        android.content.SharedPreferences prefs = context.getSharedPreferences("SevaiPrefs", Context.MODE_PRIVATE);
        boolean zeroTrustMode = prefs.getBoolean("zero_trust_mode", false);

        if (zeroTrustMode) {
            Log.d(TAG, "Zero Trust Mode is ENABLED - Skipping SMS processing");
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }

        try {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null || pdus.length == 0) {
                return;
            }

            // Process each PDU (usually one for single SMS)
            for (Object pdu : pdus) {
                SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                if (sms == null) {
                    continue;
                }

                String sender = sms.getOriginatingAddress();
                String messageBody = sms.getMessageBody();
                long timestamp = sms.getTimestampMillis();

                if (sender == null || messageBody == null) {
                    continue;
                }

                // Classify the message
                SmsClassifier.ClassificationResult result = SmsClassifier.classify(messageBody);

                Log.d(TAG, "Received SMS from " + sender + ": " + messageBody);
                Log.d(TAG, "Classification: " + result.getCategory() + " (Score: " + result.getScore() + ")");

                // Only forward to API if score is 7 or above (high-confidence financial messages)
                if (result.getScore() >= API_FORWARDING_THRESHOLD) {
                    Log.d(TAG, "High-confidence financial SMS detected (Score: " + result.getScore() + ") - forwarding to API");
                    // Notify detection with clear details
                    String shortMsg = messageBody.length() > 60 ? messageBody.substring(0, 60) + "..." : messageBody;
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE),
                            "💰 Financial SMS Detected",
                            "From: " + sender + " | Score: " + result.getScore() + "\n" + shortMsg);
                    sendToApi(context, sender, messageBody, timestamp);
                } else if (result.isFinancial()) {
                    Log.d(TAG, "Medium-confidence financial SMS detected (Score: " + result.getScore() + ") - not forwarding to API");
                    // Show lighter notification for medium confidence
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE),
                            "📊 Financial SMS (Medium)",
                            "From: " + sender + " | Score: " + result.getScore() + " (not sent to API)");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing SMS", e);
        }
    }

    /**
     * Send financial SMS to the API endpoint
     */
    private void sendToApi(Context context, String sender, String message, long timestamp) {
        new Thread(() -> {
            try {
                // Ensure API key is available
                String apiKey = getApiKey(context);
                if (apiKey == null || apiKey.trim().isEmpty() || "your-api-key-here".equals(apiKey)) {
                    Log.w(TAG, "API key missing or placeholder; skipping API send.");
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE),
                            "Sevai.app: API skipped",
                            "Missing API key. Configure in app to enable forwarding.");
                    return;
                }
                // Get device phone number
                String receiptNumber = getDevicePhoneNumber(context);

                // Prepare JSON payload (API key sent in header)
                JSONObject payload = new JSONObject();
                payload.put("time", dateFormat.format(timestamp));
                payload.put("senderName", sender);
                payload.put("receipientNumber", receiptNumber != null ? receiptNumber : "");
                payload.put("message", message);

                String jsonString = payload.toString();

                // Create HTTP connection
                URL url = new URL(API_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Sevai.app Android Client");
                // New API expects API key via header X-SMS-API-Key
                conn.setRequestProperty("X-SMS-API-Key", apiKey);
                conn.setDoOutput(true);

                // Write JSON payload
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonString.getBytes("UTF-8"));
                    os.flush();
                }

                // Get response
                int responseCode = conn.getResponseCode();
                Log.d(TAG, "API Response Code: " + responseCode);

                if (responseCode == 200 || responseCode == 201) {
                    Log.d(TAG, "✅ SMS successfully sent to API");
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 1,
                            "✅ Sent Successfully",
                            "Financial SMS from " + sender + " delivered to API (HTTP " + responseCode + ")");
                } else if (responseCode >= 400 && responseCode < 500) {
                    Log.w(TAG, "⚠️ API returned client error: " + responseCode);
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 1,
                            "⚠️ API Error " + responseCode,
                            "Client error sending SMS from " + sender + ". Check API key or payload.");
                } else {
                    Log.w(TAG, "⚠️ API returned error code: " + responseCode);
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 1,
                            "❌ API Error " + responseCode,
                            "Server error sending SMS from " + sender + ". Will retry.");
                }

                conn.disconnect();

            } catch (java.net.UnknownHostException e) {
                Log.e(TAG, "Network error: Unable to reach API endpoint", e);
                NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 2,
                        "🌐 Network Error",
                        "Cannot reach API. Check internet connection. Will retry.");
                retrySendToApi(context, sender, message, timestamp, 1);
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "Timeout sending SMS to API", e);
                NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 2,
                        "⏱️ API Timeout",
                        "API did not respond in time. Will retry.");
                retrySendToApi(context, sender, message, timestamp, 1);
            } catch (Exception e) {
                Log.e(TAG, "Error sending SMS to API", e);
                NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 2,
                        "❌ Send Failed",
                        "Error: " + e.getMessage() + ". Will retry.");
                retrySendToApi(context, sender, message, timestamp, 1);
            }
        }).start();
    }

    /**
     * Retry sending to API with exponential backoff
     */
    private void retrySendToApi(Context context, String sender, String message, long timestamp, int attempt) {
        if (attempt > 2) {
            return; // Max 2 retries
        }
        new Thread(() -> {
            try {
                // Wait with exponential backoff (1s, 2s)
                Thread.sleep(attempt * 1000L);

                // Ensure API key is available
                String apiKey = getApiKey(context);
                if (apiKey == null || apiKey.trim().isEmpty() || "your-api-key-here".equals(apiKey)) {
                    Log.w(TAG, "API key missing or placeholder; skipping API retry.");
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + attempt,
                            "Sevai.app: Retry skipped",
                            "Missing API key. Configure in app.");
                    return;
                }
                // Get device phone number
                String receiptNumber = getDevicePhoneNumber(context);

                // Prepare JSON payload (API key sent via header)
                JSONObject payload = new JSONObject();
                payload.put("time", dateFormat.format(timestamp));
                payload.put("senderName", sender);
                payload.put("receipientNumber", receiptNumber != null ? receiptNumber : "");
                payload.put("message", message);

                String jsonString = payload.toString();

                // Create HTTP connection
                URL url = new URL(API_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Sevai.app Android Client");
                // New API expects API key via header X-SMS-API-Key
                conn.setRequestProperty("X-SMS-API-Key", apiKey);
                conn.setDoOutput(true);

                // Write JSON payload
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonString.getBytes("UTF-8"));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "API Retry " + attempt + " Response Code: " + responseCode);
                if (responseCode == 200 || responseCode == 201) {
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 10 + attempt,
                            "✅ Retry Successful",
                            "SMS from " + sender + " delivered on retry #" + attempt + " (HTTP " + responseCode + ")");
                } else if (responseCode >= 400) {
                    NotificationUtils.notify(context, (int) (timestamp % Integer.MAX_VALUE) + 10 + attempt,
                            "❌ Retry Failed " + responseCode,
                            "SMS from " + sender + " failed on retry #" + attempt);
                }

                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error in API retry attempt " + attempt, e);
            }
        }).start();
    }

    /**
     * Get API key from SharedPreferences
     */
    private String getApiKey(Context context) {
        if (API_KEY == null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences("SevaiPrefs", Context.MODE_PRIVATE);
            API_KEY = prefs.getString("api_key", "your-api-key-here");
        }
        return API_KEY;
    }

    private String getDevicePhoneNumber(Context context) {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String number = tm.getLine1Number();
                if (number != null && !number.trim().isEmpty()) {
                    return number;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get device phone number", e);
        }
        return null;
    }
}

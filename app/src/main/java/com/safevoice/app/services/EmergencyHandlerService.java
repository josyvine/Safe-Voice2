package com.safevoice.app.services;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.safevoice.app.utils.LocationHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * This service is responsible for handling the emergency alert logic.
 * It is started by VoiceRecognitionService upon detecting the trigger phrase.
 * It fetches the location, checks network connectivity, and dispatches alerts.
 */
public class EmergencyHandlerService extends Service {

    private static final String TAG = "EmergencyHandlerService";

    private LocationHelper locationHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        locationHelper = new LocationHelper(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Emergency sequence initiated.");
        Toast.makeText(this, "Emergency Triggered! Sending alerts...", Toast.LENGTH_LONG).show();

        // Check for necessary permissions before proceeding.
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot proceed with emergency alerts. Missing permissions.");
            stopSelf(); // Stop the service if permissions are missing.
            return START_NOT_STICKY;
        }

        // Use the LocationHelper to get the current location.
        // The callback will handle the rest of the emergency logic.
        locationHelper.getCurrentLocation(new LocationHelper.LocationCallback() {
            @Override
            public void onLocationResult(Location location) {
                if (location != null) {
                    Log.d(TAG, "Location acquired: " + location.getLatitude() + ", " + location.getLongitude());
                    executeEmergencyActions(location);
                } else {
                    Log.e(TAG, "Failed to acquire location. Sending alerts without it.");
                    // Even if location fails, we should still send alerts.
                    executeEmergencyActions(null);
                }
                // The service has completed its task.
                stopSelf();
            }
        });

        // We return START_NOT_STICKY because this is a one-off task.
        // We don't want it to restart if it's killed.
        return START_NOT_STICKY;
    }

    /**
     * Executes the main emergency logic (calling, sending SMS) based on network state.
     *
     * @param location The user's current location. Can be null if fetching failed.
     */
    private void executeEmergencyActions(Location location) {
        // TODO: Replace these hardcoded contacts with a real ContactsManager that reads from SharedPreferences or a database.
        String primaryContactPhone = "911"; // Placeholder
        Map<String, String> priorityContacts = new HashMap<>(); // Placeholder
        priorityContacts.put("Mom", "5551234567");
        priorityContacts.put("Dad", "5557654321");

        // Make the primary phone call regardless of network state.
        makePhoneCall(primaryContactPhone);

        // Check for internet connectivity.
        if (isOnline()) {
            Log.d(TAG, "Device is online. Sending SMS alerts.");
            // If online, send SMS alerts to all priority contacts.
            for (String phone : priorityContacts.values()) {
                sendSmsAlert(phone, location);
            }
        } else {
            Log.d(TAG, "Device is offline. Only primary phone call was made.");
        }
    }

    /**
     * Initiates a direct phone call to the specified number.
     *
     * @param phoneNumber The number to call.
     */
    private void makePhoneCall(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            Log.e(TAG, "Primary contact phone number is not set. Cannot make call.");
            return;
        }

        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + phoneNumber));
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            Log.i(TAG, "Attempting to call " + phoneNumber);
            startActivity(callIntent);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: CALL_PHONE permission might be missing or denied.", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate phone call.", e);
        }
    }

    /**
     * Sends an SMS alert to the specified number.
     *
     * @param phoneNumber The number to send the SMS to.
     * @param location    The user's location, used to generate a map link.
     */
    private void sendSmsAlert(String phoneNumber, Location location) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            Log.e(TAG, "Priority contact phone number is invalid. Cannot send SMS.");
            return;
        }

        // TODO: Replace "User" with the verified name from Firebase.
        String message = "EMERGENCY: This is an automated alert from Safe Voice for User. They may be in trouble.";

        if (location != null) {
            String mapLink = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
            message += "\n\nTheir last known location is:\n" + mapLink;
        }

        try {
            // Use the default SmsManager to send the text message.
            SmsManager smsManager = SmsManager.getDefault();
            // For longer messages, use divideMessage to split it into parts.
            ArrayList<String> messageParts = smsManager.divideMessage(message);
            smsManager.sendMultipartTextMessage(phoneNumber, null, messageParts, null, null);
            Log.i(TAG, "SMS alert sent to " + phoneNumber);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS to " + phoneNumber, e);
        }
    }

    /**
     * Checks if the device has an active internet connection.
     *
     * @return true if connected, false otherwise.
     */
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }

    /**
     * Checks if the service has the permissions it needs to function.
     *
     * @return true if all required permissions are granted.
     */
    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // This is a started service, not a bound one.
        return null;
    }
          }

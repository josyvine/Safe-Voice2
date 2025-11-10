package com.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class SenderService extends Service {

    private static final String TAG = "SenderService";
    private static final String NOTIFICATION_CHANNEL_ID = "SenderServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START_SEND = "com.hfm.app.action.START_SEND";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_RECEIVER_USERNAME = "receiver_username";
    public static final String EXTRA_SECRET_NUMBER = "secret_number";

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private File cloakedFile;
    private String dropRequestId;
    private ListenerRegistration requestListener;

    private static final String[] ADJECTIVES = {"Red", "Blue", "Green", "Silent", "Fast", "Brave", "Ancient", "Wandering", "Golden", "Iron"};
    private static final String[] NOUNS = {"Tiger", "Lion", "Eagle", "Fox", "Wolf", "River", "Mountain", "Star", "Comet", "Shadow"};

    @Override
    public void onCreate() {
        super.onCreate();
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUser = mAuth.getCurrentUser();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_SEND.equals(intent.getAction())) {
            final String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            final String receiverUsername = intent.getStringExtra(EXTRA_RECEIVER_USERNAME);
            final String secretNumber = intent.getStringExtra(EXTRA_SECRET_NUMBER);

            Notification notification = buildNotification("Starting Drop Send Service...", true);
            startForeground(NOTIFICATION_ID, notification);

            Intent progressIntent = new Intent(this, DropProgressActivity.class);
            progressIntent.putExtra("is_sender", true);
            progressIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(progressIntent);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    startSenderProcess(filePath, receiverUsername, secretNumber);
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    private void startSenderProcess(final String filePath, final String receiverUsername, final String secretNumber) {
        final File inputFile = new File(filePath);
        if (!inputFile.exists()) {
            Log.e(TAG, "File to send does not exist: " + filePath);
            broadcastError("File not found at path: " + filePath);
            stopServiceAndCleanup(null);
            return;
        }

        updateNotification("Cloaking data...", true);
        broadcastStatus("Cloaking data...", "Please wait, this may take a moment...", -1, -1, -1);
        cloakedFile = CloakingManager.cloakFile(this, inputFile, secretNumber);
        if (cloakedFile == null) {
            Log.e(TAG, "Cloaking file failed.");
            broadcastError("Failed to cloak file for secure transfer.");
            stopServiceAndCleanup(null);
            return;
        }

        updateNotification("Creating drop request...", true);
        broadcastStatus("Creating Request...", "Contacting server...", -1, -1, -1);
        String senderUsername = generateUsernameFromUid(currentUser.getUid());

        Map<String, Object> dropRequest = new HashMap<>();
        dropRequest.put("senderId", currentUser.getUid());
        dropRequest.put("senderUsername", senderUsername);
        dropRequest.put("receiverUsername", receiverUsername);
        dropRequest.put("filename", inputFile.getName());
        dropRequest.put("cloakedFilename", cloakedFile.getName());
        dropRequest.put("filesize", cloakedFile.length());
        dropRequest.put("status", "pending");
        dropRequest.put("secretNumber", secretNumber);
        dropRequest.put("timestamp", System.currentTimeMillis());
        dropRequest.put("magnetLink", null); // Placeholder

        db.collection("drop_requests")
                .add(dropRequest)
                .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                    @Override
                    public void onSuccess(final DocumentReference documentReference) {
                        dropRequestId = documentReference.getId();
                        Log.d(TAG, "Drop request created with ID: " + dropRequestId);

                        // Now that we have the ID, we can start seeding and get the magnet link
                        updateNotification("Generating transfer link...", true);
                        broadcastStatus("Generating Link...", "Preparing secure P2P session...", -1, -1, -1);

                        String magnetLink = TorrentManager.getInstance(SenderService.this).startSeeding(cloakedFile, dropRequestId);

                        if (magnetLink != null) {
                            // Update the document with the magnet link
                            documentReference.update("magnetLink", magnetLink)
                                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                                        @Override
                                        public void onSuccess(Void aVoid) {
                                            Log.d(TAG, "Successfully added magnet link to drop request.");
                                            updateNotification("Waiting for receiver...", true);
                                            broadcastStatus("Waiting for Receiver...", "Request sent. Waiting for acceptance.", -1, -1, -1);
                                            listenForStatusChange(dropRequestId);
                                        }
                                    })
                                    .addOnFailureListener(new OnFailureListener() {
                                        @Override
                                        public void onFailure(@NonNull Exception e) {
                                            Log.e(TAG, "Failed to update drop request with magnet link.", e);
                                            broadcastError("Failed to create secure link for the transfer.\n\n" + getStackTraceAsString(e));
                                            stopServiceAndCleanup(null);
                                        }
                                    });
                        } else {
                            Log.e(TAG, "Failed to generate magnet link.");
                            broadcastError("Failed to generate a secure link for the transfer.");
                            stopServiceAndCleanup(null);
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "Failed to create drop request.", e);
                        broadcastError("Failed to create drop request on server.\n\n" + getStackTraceAsString(e));
                        stopServiceAndCleanup(null);
                    }
                });
    }

    private void broadcastStatus(String major, String minor, int progress, int max, long bytes) {
        Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
        intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, major);
        intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, minor);
        intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, progress);
        intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, max);
        intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, bytes);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastComplete() {
        Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastError(String message) {
        Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
        errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(errorIntent);

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
    }

    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    private void listenForStatusChange(String docId) {
        final DocumentReference docRef = db.collection("drop_requests").document(docId);
        requestListener = docRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot snapshot, FirebaseFirestoreException e) {
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e);
                    return;
                }

                if (snapshot != null && snapshot.exists()) {
                    String status = snapshot.getString("status");
                    Log.d(TAG, "Drop request status changed to: " + status);

                    if ("accepted".equals(status)) {
                        updateNotification("Receiver connected. Transferring...", true);
                        broadcastStatus("Transferring...", "Sending file data...", -1, -1, -1);
                        // libtorrent handles the connection automatically, no more hole punching needed.
                    } else if ("declined".equals(status)) {
                        stopServiceAndCleanup("Receiver declined the transfer.");
                    } else if ("complete".equals(status)) {
                        broadcastComplete();
                        stopServiceAndCleanup(null);
                        if (currentUser != null) {
                            currentUser.delete();
                        }
                    } else if ("error".equals(status)) {
                        stopServiceAndCleanup("An error occurred on the receiver's end.");
                    }
                } else {
                    Log.d(TAG, "Drop request document deleted by receiver.");
                    stopServiceAndCleanup(null);
                }
            }
        });
    }

    private String generateUsernameFromUid(String uid) {
        long hash = uid.hashCode();
        int adjIndex = (int) (Math.abs(hash % ADJECTIVES.length));
        int nounIndex = (int) (Math.abs((hash / ADJECTIVES.length) % NOUNS.length));
        int number = (int) (Math.abs((hash / (ADJECTIVES.length * NOUNS.length)) % 100));
        return ADJECTIVES[adjIndex] + "-" + NOUNS[nounIndex] + "-" + number;
    }


    private void stopServiceAndCleanup(final String toastMessage) {
        if (toastMessage != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(SenderService.this, toastMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SenderService onDestroy.");
        if (requestListener != null) {
            requestListener.remove();
        }
        if (cloakedFile != null && cloakedFile.exists()) {
            cloakedFile.delete();
        }

        if (dropRequestId != null) {
            db.collection("drop_requests").document(dropRequestId).get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                @Override
                public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document.exists()) {
                            String status = document.getString("status");
                            if (!"complete".equals(status)) {
                                document.getReference().delete()
                                        .addOnSuccessListener(new OnSuccessListener<Void>() {
                                            @Override
                                            public void onSuccess(Void aVoid) {
                                                Log.d(TAG, "Drop request document successfully deleted.");
                                            }
                                        });
                            }
                        }
                    }
                }
            });
        }
        stopForeground(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "HFM Drop Sender Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, boolean ongoing) {
        Notification notification = buildNotification(text, ongoing);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text, boolean ongoing) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Drop Sender")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setOngoing(ongoing)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
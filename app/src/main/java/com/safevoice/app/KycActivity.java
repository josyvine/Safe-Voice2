package com.safevoice.app;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.safevoice.app.databinding.ActivityKycBinding;
import com.safevoice.app.utils.FaceVerifier;
import com.safevoice.app.utils.ImageUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity for performing on-device KYC (Know Your Customer) verification.
 * It uses CameraX for the camera feed, ML Kit for text and face detection,
 * and a custom TFLite model (via FaceVerifier) for face matching.
 */
public class KycActivity extends AppCompatActivity {

    private static final String TAG = "KycActivity";
    private static final double FACE_MATCH_THRESHOLD = 0.8; // Similarity threshold for a match

    // Enum for managing the state of the KYC process
    private enum KycState {
        SCANNING_ID,
        SCANNING_FACE,
        VERIFYING,
        COMPLETE
    }

    private ActivityKycBinding binding;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService analysisExecutor;
    private FaceVerifier faceVerifier;

    // State management variables
    private KycState currentState = KycState.SCANNING_ID;
    private float[] idCardEmbedding = null;
    private String verifiedName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityKycBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize the background executor for image analysis
        analysisExecutor = Executors.newSingleThreadExecutor();

        // Load the FaceVerifier with the TFLite model from assets.
        try {
            faceVerifier = new FaceVerifier(this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load FaceVerifier model.", e);
            Toast.makeText(this, "Error: Verification model could not be loaded.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Start the camera setup process.
        startCamera();
        updateUIForState();
    }

    /**
     * Initializes and starts the camera using the CameraX library.
     */
    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindCameraUseCases(cameraProvider);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start camera.", e);
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Binds the camera preview and image analysis use cases to the activity's lifecycle.
     */
    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        // Set up the camera preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());

        // Use the back camera for scanning the ID, and front for the face
        CameraSelector cameraSelector = (currentState == KycState.SCANNING_ID) ?
                CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;

        // Set up the image analyzer
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, new KycImageAnalyzer());

        // Unbind any previous use cases and bind the new ones.
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    /**
     * Updates the on-screen instruction text based on the current KYC state.
     */
    private void updateUIForState() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (currentState) {
                    case SCANNING_ID:
                        binding.textInstructions.setText(R.string.kyc_instructions_id);
                        break;
                    case SCANNING_FACE:
                        binding.textInstructions.setText(R.string.kyc_instructions_face);
                        // Re-bind the camera to switch to the front lens
                        startCamera();
                        break;
                    case VERIFYING:
                        binding.textInstructions.setText(R.string.kyc_status_verifying);
                        binding.progressBar.setVisibility(View.VISIBLE);
                        break;
                    case COMPLETE:
                        binding.progressBar.setVisibility(View.GONE);
                        break;
                }
            }
        });
    }

    /**
     * The core class that analyzes each camera frame.
     */
    private class KycImageAnalyzer implements ImageAnalysis.Analyzer {
        private final TextRecognizer textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        private final FaceDetector faceDetector;

        KycImageAnalyzer() {
            // Configure the face detector to find all faces (for the ID card)
            FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build();
            faceDetector = FaceDetection.getClient(options);
        }

        @Override
        @SuppressLint("UnsafeOptInUsageError")
        public void analyze(@NonNull ImageProxy imageProxy) {
            Image mediaImage = imageProxy.getImage();
            if (mediaImage == null || currentState == KycState.VERIFYING || currentState == KycState.COMPLETE) {
                imageProxy.close();
                return;
            }

            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());

            Task<?> processingTask;

            if (currentState == KycState.SCANNING_ID) {
                processingTask = processIdCardImage(image, imageProxy);
            } else if (currentState == KycState.SCANNING_FACE) {
                processingTask = processLiveFaceImage(image, imageProxy);
            } else {
                imageProxy.close();
                return;
            }

            // Add a listener to close the proxy once the processing is done.
            processingTask.addOnCompleteListener(task -> imageProxy.close());
        }

        /**
         * Processes an image frame to find text and a face from an ID card.
         * Returns a Task that completes when both detection processes are finished.
         */
        private Task<Void> processIdCardImage(InputImage image, ImageProxy imageProxy) {
            // 1. Create two separate tasks
            Task<Text> textRecognitionTask = textRecognizer.process(image);
            Task<List<Face>> faceDetectionTask = faceDetector.process(image);

            // 2. Combine them into a single task that completes when both are done
            return Tasks.whenAll(textRecognitionTask, faceDetectionTask)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        // Both tasks succeeded, now we can safely get their results
                        List<Face> faces = faceDetectionTask.getResult();
                        Text visionText = textRecognitionTask.getResult();

                        // Process Text Result
                        String name = extractNameFromText(visionText);
                        if (name != null) {
                            verifiedName = name;
                        }

                        // Process Face Result
                        if (faces != null && !faces.isEmpty()) {
                            // Assume the largest face is the one on the ID.
                            Face idFace = faces.get(0);
                            Bitmap fullBitmap = ImageUtils.getBitmap(imageProxy);
                            if (fullBitmap != null) {
                                Bitmap croppedFace = cropBitmapToFace(fullBitmap, idFace.getBoundingBox());
                                idCardEmbedding = faceVerifier.getFaceEmbedding(croppedFace);
                                Log.d(TAG, "ID Card embedding generated.");
                            }
                        }

                        // Check if we have everything we need to move to the next step
                        if (verifiedName != null && idCardEmbedding != null) {
                            currentState = KycState.SCANNING_FACE;
                            updateUIForState();
                        }
                    }
                });
        }

        /**
         * Processes an image frame to find the user's live face.
         * Returns a Task that completes when the detection process is finished.
         */
        private Task<List<Face>> processLiveFaceImage(InputImage image, ImageProxy imageProxy) {
            return faceDetector.process(image)
                .addOnSuccessListener(new OnSuccessListener<List<Face>>() {
                    @Override
                    public void onSuccess(List<Face> faces) {
                        if (!faces.isEmpty()) {
                            currentState = KycState.VERIFYING;
                            updateUIForState();

                            Face liveFace = faces.get(0);
                            Bitmap fullBitmap = ImageUtils.getBitmap(imageProxy);
                            if (fullBitmap != null) {
                                Bitmap croppedFace = cropBitmapToFace(fullBitmap, liveFace.getBoundingBox());
                                float[] liveEmbedding = faceVerifier.getFaceEmbedding(croppedFace);
                                Log.d(TAG, "Live face embedding generated.");

                                // Compare the two embeddings
                                double similarity = faceVerifier.calculateSimilarity(idCardEmbedding, liveEmbedding);
                                Log.i(TAG, "Face similarity score: " + similarity);

                                if (similarity > FACE_MATCH_THRESHOLD) {
                                    handleVerificationSuccess();
                                } else {
                                    handleVerificationFailure("Face does not match ID.");
                                }
                            }
                        }
                    }
                });
        }
    }


    /**
     * A simple heuristic to extract a name from recognized text blocks.
     * Looks for multi-word lines with capitalized first letters.
     *
     * @param visionText The Text object from ML Kit.
     * @return A plausible name, or null.
     */
    private String extractNameFromText(Text visionText) {
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                // A simple check for a name: 2 or 3 words, starts with capitals.
                if (lineText.matches("([A-Z][a-z]+ ){1,2}[A-Z][a-z]+")) {
                    Log.d(TAG, "Potential name found: " + lineText);
                    return lineText;
                }
            }
        }
        return null;
    }

    /**
     * Crops a larger bitmap to the bounding box of a detected face.
     */
    private Bitmap cropBitmapToFace(Bitmap source, Rect boundingBox) {
        // Ensure the bounding box is within the bitmap dimensions
        int x = Math.max(0, boundingBox.left);
        int y = Math.max(0, boundingBox.top);
        int width = Math.min(source.getWidth() - x, boundingBox.width());
        int height = Math.min(source.getHeight() - y, boundingBox.height());
        return Bitmap.createBitmap(source, x, y, width, height);
    }

    /**
     * Handles the successful verification case.
     */
    private void handleVerificationSuccess() {
        Log.i(TAG, "Verification SUCCESSFUL. Name: " + verifiedName);
        currentState = KycState.COMPLETE;
        updateUIForState();

        // Save the verified name to Firestore
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            Map<String, Object> userData = new HashMap<>();
            userData.put("verifiedName", verifiedName);
            FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                    .set(userData)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override
                        public void onSuccess(Void aVoid) {
                            Toast.makeText(KycActivity.this, "Verification successful!", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                         @Override
                         public void onFailure(@NonNull Exception e) {
                             Toast.makeText(KycActivity.this, "Verification successful, but failed to save name.", Toast.LENGTH_LONG).show();
                             finish();
                         }
                     });
        } else {
             Toast.makeText(this, "Verification successful, but no signed-in user found.", Toast.LENGTH_LONG).show();
             finish();
        }
    }

    /**
     * Handles the verification failure case.
     */
    private void handleVerificationFailure(String reason) {
        Log.e(TAG, "Verification FAILED. Reason: " + reason);
        currentState = KycState.COMPLETE;
        updateUIForState();
        Toast.makeText(this, "Verification Failed: " + reason, Toast.LENGTH_LONG).show();
        // After a delay, finish the activity
        new android.os.Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 3000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Shut down the background executor to prevent memory leaks.
        analysisExecutor.shutdown();
    }
}
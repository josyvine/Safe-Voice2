package com.safevoice.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.os.Bundle;
import android.os.Looper;
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

        analysisExecutor = Executors.newSingleThreadExecutor();

        try {
            faceVerifier = new FaceVerifier(this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load FaceVerifier model.", e);
            Toast.makeText(this, "Error: Verification model could not be loaded.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        startCamera();
        updateUIForState();
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera.", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());

        CameraSelector cameraSelector = (currentState == KycState.SCANNING_ID) ?
                CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, new KycImageAnalyzer());

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    private void updateUIForState() {
        runOnUiThread(() -> {
            switch (currentState) {
                case SCANNING_ID:
                    binding.textInstructions.setText(R.string.kyc_instructions_id);
                    break;
                case SCANNING_FACE:
                    binding.textInstructions.setText(R.string.kyc_instructions_face);
                    startCamera(); // Re-bind the camera to switch to the front lens
                    break;
                case VERIFYING:
                    binding.textInstructions.setText(R.string.kyc_status_verifying);
                    binding.progressBar.setVisibility(View.VISIBLE);
                    break;
                case COMPLETE:
                    binding.progressBar.setVisibility(View.GONE);
                    break;
            }
        });
    }

    private class KycImageAnalyzer implements ImageAnalysis.Analyzer {
        private final TextRecognizer textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        private final FaceDetector faceDetector;

        KycImageAnalyzer() {
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

            processingTask.addOnCompleteListener(task -> imageProxy.close());
        }

        private Task<Void> processIdCardImage(InputImage image, ImageProxy imageProxy) {
            // Only run the tasks if we still need the data to avoid redundant work
            Task<Text> textRecognitionTask = (verifiedName == null) ? textRecognizer.process(image) : Tasks.forResult(null);
            Task<List<Face>> faceDetectionTask = (idCardEmbedding == null) ? faceDetector.process(image) : Tasks.forResult(null);

            return Tasks.whenAll(textRecognitionTask, faceDetectionTask)
                .addOnSuccessListener(aVoid -> {
                    // Process Text Result only if we don't have a name yet
                    if (verifiedName == null) {
                        Text visionText = textRecognitionTask.getResult();
                        if (visionText != null) {
                            String name = extractNameFromText(visionText);
                            if (name != null) {
                                Log.i(TAG, "Successfully extracted name: " + name);
                                verifiedName = name;
                            }
                        }
                    }

                    // Process Face Result only if we don't have an embedding yet
                    if (idCardEmbedding == null) {
                        List<Face> faces = faceDetectionTask.getResult();
                        if (faces != null && !faces.isEmpty()) {
                            Face idFace = faces.get(0);
                            Bitmap fullBitmap = ImageUtils.getBitmap(imageProxy);
                            if (fullBitmap != null) {
                                Bitmap croppedFace = cropBitmapToFace(fullBitmap, idFace.getBoundingBox());
                                idCardEmbedding = faceVerifier.getFaceEmbedding(croppedFace);
                                Log.i(TAG, "Successfully generated ID card embedding.");
                            }
                        }
                    }

                    // If we have now collected both pieces of information, move to the next state
                    if (verifiedName != null && idCardEmbedding != null) {
                        Log.i(TAG, "ID Scan Complete! Proceeding to face scan.");
                        currentState = KycState.SCANNING_FACE;
                        updateUIForState();
                    }
                });
        }

        private Task<List<Face>> processLiveFaceImage(InputImage image, ImageProxy imageProxy) {
            return faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty()) {
                        currentState = KycState.VERIFYING;
                        updateUIForState();

                        Face liveFace = faces.get(0);
                        Bitmap fullBitmap = ImageUtils.getBitmap(imageProxy);
                        if (fullBitmap != null) {
                            Bitmap croppedFace = cropBitmapToFace(fullBitmap, liveFace.getBoundingBox());
                            float[] liveEmbedding = faceVerifier.getFaceEmbedding(croppedFace);
                            Log.d(TAG, "Live face embedding generated.");

                            double similarity = faceVerifier.calculateSimilarity(idCardEmbedding, liveEmbedding);
                            Log.i(TAG, "Face similarity score: " + similarity);

                            if (similarity > FACE_MATCH_THRESHOLD) {
                                handleVerificationSuccess();
                            } else {
                                handleVerificationFailure("Face does not match ID.");
                            }
                        }
                    }
                });
        }
    }

    private String extractNameFromText(Text visionText) {
        if (visionText == null) return null;
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                // A more robust check for a name: Two or three words, starting with capitals.
                // Allows for single-letter middle names or initials.
                if (lineText.matches("([A-Z][a-zA-Z]*[.]?[ ]?){2,3}")) {
                     // Additional filter to avoid picking up address lines etc.
                    if (!lineText.matches(".*[0-9].*") && lineText.length() < 30) {
                        Log.d(TAG, "Potential name found: " + lineText);
                        return lineText;
                    }
                }
            }
        }
        return null;
    }

    private Bitmap cropBitmapToFace(Bitmap source, Rect boundingBox) {
        int x = Math.max(0, boundingBox.left);
        int y = Math.max(0, boundingBox.top);
        int width = Math.min(source.getWidth() - x, boundingBox.width());
        int height = Math.min(source.getHeight() - y, boundingBox.height());
        return Bitmap.createBitmap(source, x, y, width, height);
    }

    private void handleVerificationSuccess() {
        Log.i(TAG, "Verification SUCCESSFUL. Name: " + verifiedName);
        currentState = KycState.COMPLETE;
        updateUIForState();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            Map<String, Object> userData = new HashMap<>();
            userData.put("verifiedName", verifiedName);
            FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                    .set(userData)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(KycActivity.this, "Verification successful!", Toast.LENGTH_LONG).show();
                        finish();
                    })
                    .addOnFailureListener(e -> {
                         Toast.makeText(KycActivity.this, "Verification successful, but failed to save name.", Toast.LENGTH_LONG).show();
                         finish();
                     });
        } else {
             Toast.makeText(this, "Verification successful, but no signed-in user found.", Toast.LENGTH_LONG).show();
             finish();
        }
    }

    private void handleVerificationFailure(String reason) {
        Log.e(TAG, "Verification FAILED. Reason: " + reason);
        currentState = KycState.COMPLETE;
        updateUIForState();
        Toast.makeText(this, "Verification Failed: " + reason, Toast.LENGTH_LONG).show();
        
        new android.os.Handler(Looper.getMainLooper()).postDelayed(this::finish, 3000);
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Prevent camera provider future from leaking if activity is destroyed quickly
        if (cameraProviderFuture != null) {
            cameraProviderFuture.cancel(true);
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
    }
}
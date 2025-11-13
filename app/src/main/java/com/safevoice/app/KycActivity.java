package com.safevoice.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class KycActivity extends AppCompatActivity {

    private static final String TAG = "KycActivity";
    private static final double FACE_MATCH_THRESHOLD = 0.8;

    private enum KycState { SCANNING_ID, SCANNING_FACE, VERIFYING, COMPLETE }

    private ActivityKycBinding binding;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService analysisExecutor;
    private FaceVerifier faceVerifier;

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
            showErrorDialog("Model Loading Failed", e);
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
                showErrorDialog("Camera Provider Failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        try {
            cameraProvider.unbindAll();
            Preview preview = new Preview.Builder().build();
            CameraSelector cameraSelector = (currentState == KycState.SCANNING_ID) ?
                    CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            imageAnalysis.setAnalyzer(analysisExecutor, new KycImageAnalyzer());
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());
        } catch (Exception e) {
            showErrorDialog("Camera Binding Failed", e);
        }
    }

    private void updateUIForState() {
        runOnUiThread(() -> {
            switch (currentState) {
                case SCANNING_ID:
                    binding.textInstructions.setText(R.string.kyc_instructions_id);
                    break;
                case SCANNING_FACE:
                    binding.textInstructions.setText(R.string.kyc_instructions_face);
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
        });
    }

    private class KycImageAnalyzer implements ImageAnalysis.Analyzer {
        private final TextRecognizer textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        private final FaceDetector faceDetector;
        private final AtomicBoolean isProcessing = new AtomicBoolean(false);

        KycImageAnalyzer() {
            faceDetector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build());
        }

        @Override
        @SuppressLint("UnsafeOptInUsageError")
        public void analyze(@NonNull ImageProxy imageProxy) {
            if (!isProcessing.compareAndSet(false, true)) {
                imageProxy.close();
                return;
            }

            Image mediaImage = imageProxy.getImage();
            if (mediaImage == null || currentState == KycState.COMPLETE || currentState == KycState.VERIFYING) {
                isProcessing.set(false);
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
                isProcessing.set(false);
                imageProxy.close();
                return;
            }

            processingTask.addOnCompleteListener(task -> {
                isProcessing.set(false);
                imageProxy.close();
            });
        }

        private Task<Void> processIdCardImage(InputImage image, ImageProxy imageProxy) {
            Task<Text> textTask = (verifiedName == null) ? textRecognizer.process(image) : Tasks.forResult(null);
            Task<List<Face>> faceTask = (idCardEmbedding == null) ? faceDetector.process(image) : Tasks.forResult(null);

            return Tasks.whenAll(textTask, faceTask).addOnSuccessListener(aVoid -> {
                if (verifiedName == null) {
                    Text visionText = textTask.getResult();
                    if (visionText != null) verifiedName = extractNameFromText(visionText);
                }
                if (idCardEmbedding == null) {
                    List<Face> faces = faceTask.getResult();
                    if (faces != null && !faces.isEmpty()) {
                        // --- THIS IS THE CRITICAL FIX ---
                        // Replaced the dangerous getBitmap() with the safe cropAndConvert()
                        Bitmap croppedFace = ImageUtils.cropAndConvert(imageProxy, faces.get(0).getBoundingBox());
                        if (croppedFace != null) {
                            idCardEmbedding = faceVerifier.getFaceEmbedding(croppedFace);
                        }
                    }
                }
                if (verifiedName != null && idCardEmbedding != null) {
                    currentState = KycState.SCANNING_FACE;
                    updateUIForState();
                }
            });
        }

        private Task<List<Face>> processLiveFaceImage(InputImage image, ImageProxy imageProxy) {
            return faceDetector.process(image).addOnSuccessListener(faces -> {
                if (!faces.isEmpty()) {
                    currentState = KycState.VERIFYING;
                    updateUIForState();
                    // --- THIS IS THE CRITICAL FIX ---
                    // Replaced the dangerous getBitmap() with the safe cropAndConvert()
                    Bitmap croppedFace = ImageUtils.cropAndConvert(imageProxy, faces.get(0).getBoundingBox());
                    if (croppedFace != null) {
                        float[] liveEmbedding = faceVerifier.getFaceEmbedding(croppedFace);
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
                if (lineText.matches("([A-Z][a-zA-Z]*[.]?[ ]?){2,3}")) {
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
        if (isFinishing() || isDestroyed()) return;
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
        if (isFinishing() || isDestroyed()) return;
        currentState = KycState.COMPLETE;
        updateUIForState();
        Toast.makeText(this, "Verification Failed: " + reason, Toast.LENGTH_LONG).show();
        new android.os.Handler(Looper.getMainLooper()).postDelayed(this::finish, 3000);
    }

    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void showErrorDialog(String title, Exception e) {
        if (isFinishing() || isDestroyed()) return;
        final String errorReport = getStackTraceAsString(e);
        runOnUiThread(() -> new AlertDialog.Builder(KycActivity.this)
                .setTitle(title)
                .setMessage(errorReport)
                .setPositiveButton("Close", (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                })
                .setCancelable(false)
                .show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProviderFuture != null) {
            cameraProviderFuture.cancel(true);
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
    }
}
package com.arcsoft.arcface.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import com.arcsoft.arcface.R;
import com.arcsoft.arcface.model.FaceFeatureTask;
import com.arcsoft.arcface.util.DrawHelper;
import com.arcsoft.arcface.util.camera.CameraHelper;
import com.arcsoft.arcface.util.camera.CameraListener;
import com.arcsoft.arcface.widget.FaceRectView;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.FaceSimilar;
import com.arcsoft.face.LivenessInfo;
import com.arcsoft.face.VersionInfo;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Nick
 */
public class DetectActivity extends BaseActivity implements ViewTreeObserver.OnGlobalLayoutListener {

    private static final String TAG = "DetectActivity";

    private static final int ACTION_REQUEST_PERMISSIONS = 0x001;

    /**
     * 所需的所有权限信息
     */
    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE
    };

    private static ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("arcface_pool_%d")
            .build();

    public static final String EXTRA_ACTION = "action";

    public static final String ACTION_COMPARE_FACE = "compare_face";

    public static final String ACTION_EXTRACT_FEATURE = "extract_feature";

    public static final String EXTRA_SRC_FEATURE = "src_feature";

    public static final String EXTRA_SIMILAR_THRESHOLD = "similar_threshold";

    private static final int EXTRACT_FEATURE = 0;

    private static final int COMPARE_FACE = 1;

    private static final int SHOW_RETRY = 2;

    private String action;

    private int afCode = -1;

    private Button btnRetry;

    private CameraHelper cameraHelper;

    private DrawHelper drawHelper;

    private FaceEngine faceEngine;

    private FaceRectView faceRectView;

    private Handler handler;

    private HandlerThread handlerThread;

    private LinkedBlockingQueue<FaceFeatureTask> mBlockingQueue
            = new LinkedBlockingQueue<>(1);

    private Camera.Size previewSize;

    /**
     * 相机预览显示的控件，可为SurfaceView或TextureView
     */
    private View previewView;

    private float similarThreshold;

    private String srcFeatureData;

    private ExecutorService threadPool = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), threadFactory);

    private ListeningExecutorService service = MoreExecutors.listeningDecorator(threadPool);

    /**
     * 文本提示控件
     */
    private TextView tipView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);
        try {
            initExtraParams(savedInstanceState);
        } catch (VerifyException e) {
            showMessage(e.getMessage());
            Intent errorResult = new Intent();
            errorResult.putExtra("result_code", -1);
            setResult(RESULT_OK, errorResult);
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            getWindow().setAttributes(attributes);
        }
        // Activity启动后就锁定为启动时的方向
        switch (getResources().getConfiguration().orientation) {
            case Configuration.ORIENTATION_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case Configuration.ORIENTATION_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                break;
        }
        handlerThread = new HandlerThread("handler-thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(final Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case EXTRACT_FEATURE:
                        extractFaceFeature();
                        break;
                    case COMPARE_FACE:
                        FaceFeature dest = (FaceFeature) msg.obj;
                        byte[] srcData = Base64.decode(srcFeatureData, Base64.NO_PADDING);
                        FaceFeature src = new FaceFeature(srcData);
                        compareFace(src, dest);
                        break;
                    case SHOW_RETRY:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnRetry.setVisibility(View.VISIBLE);
                            }
                        });
                        break;
                    default:
                        break;
                }
            }
        };
        tipView = findViewById(R.id.tv_tip);
        previewView = findViewById(R.id.texture_preview);
        faceRectView = findViewById(R.id.face_rect_view);
        btnRetry = findViewById(R.id.btn_retry);
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(this);
        btnRetry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                btnRetry.setVisibility(View.INVISIBLE);
                handler.sendEmptyMessage(EXTRACT_FEATURE);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        outState.putString(EXTRA_ACTION, action);
        outState.putString(EXTRA_SRC_FEATURE, srcFeatureData);
        outState.putFloat(EXTRA_SIMILAR_THRESHOLD, similarThreshold);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        handlerThread.quit();
        if (cameraHelper != null) {
            cameraHelper.release();
            cameraHelper = null;
        }
        unInitEngine();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ACTION_REQUEST_PERMISSIONS) {
            boolean isAllGranted = true;
            for (int grantResult : grantResults) {
                isAllGranted &= (grantResult == PackageManager.PERMISSION_GRANTED);
            }
            if (isAllGranted) {
                initEngine();
                initCamera();
                if (cameraHelper != null) {
                    cameraHelper.start();
                }
            } else {
                showMessage(getString(R.string.permission_denied));
            }
        }
    }

    /**
     * 在{@link #previewView}第一次布局完成后，去除该监听，并且进行引擎和相机的初始化
     */
    @Override
    public void onGlobalLayout() {
        previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initEngine();
            initCamera();
        }
    }


    private void compareFace(final FaceFeature src, final FaceFeature dest) {
        ListenableFutureTask<FaceSimilar> futureTask = ListenableFutureTask.create(new Callable<FaceSimilar>() {
            @Override
            public FaceSimilar call() throws Exception {
                Preconditions.checkNotNull(src);
                Preconditions.checkNotNull(dest);
                Preconditions.checkNotNull(faceEngine);
                FaceSimilar faceSimilar = new FaceSimilar();
                int code = faceEngine.compareFaceFeature(src, dest, faceSimilar);
                Verify.verify(code == ErrorInfo.MOK, "人脸比对失败，错误码：%s", code);
                return faceSimilar;
            }
        });
        service.submit(futureTask);
        Futures.addCallback(futureTask, new FutureCallback<FaceSimilar>() {
            @Override
            public void onFailure(@NonNull final Throwable t) {
                Log.e(TAG, String.format("人脸比对--失败，线程: %s", Thread.currentThread().getName()), t);
                handler.sendEmptyMessage(SHOW_RETRY);
            }

            @Override
            public void onSuccess(@NonNull final FaceSimilar similar) {
                Log.i(TAG, String.format("人脸比对--成功，得分： %f", similar.getScore()));
                if (similar.getScore() < similarThreshold) {
                    handler.sendEmptyMessage(SHOW_RETRY);
                } else {
                    Intent data = new Intent();
                    data.putExtra("data", similar.getScore());
                    setResult(RESULT_OK, data);
                    finish();
                }
            }
        }, service);
    }

    private void extractFaceFeature() {
        try {
            mBlockingQueue.clear();
            FaceFeatureTask featureTask = mBlockingQueue.take();
            ListenableFutureTask<FaceFeature> futureTask = ListenableFutureTask.create(featureTask);
            service.submit(futureTask);
            Futures.addCallback(futureTask, new FutureCallback<FaceFeature>() {
                @Override
                public void onFailure(@NonNull final Throwable t) {
                    Log.e(TAG, String.format("人脸特征提取--失败，线程: %s", Thread.currentThread().getName()), t);
                    handler.sendEmptyMessage(EXTRACT_FEATURE);
                }

                @Override
                public void onSuccess(@NonNull final FaceFeature feature) {
                    String featureData = Base64.encodeToString(feature.getFeatureData(), Base64.NO_PADDING);
                    Log.i(TAG, String.format("人脸特征提取成功: %s", featureData));
                    if (srcFeatureData == null) {
                        Intent data = new Intent();
                        data.putExtra("data", featureData);
                        setResult(RESULT_OK, data);
                        finish();
                    } else {
                        Message message = new Message();
                        message.what = COMPARE_FACE;
                        message.obj = feature;
                        handler.sendMessage(message);
                    }
                }
            }, service);
        } catch (InterruptedException e) {
            Log.e(TAG, "extractFaceFeature: 特征提取异常", e);
            handler.sendEmptyMessage(0);
        }
    }

    private void initCamera() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        CameraListener cameraListener = new CameraListener() {
            @Override
            public void onCameraClosed() {
                Log.i(TAG, "onCameraClosed: ");
            }

            @Override
            public void onCameraConfigurationChanged(int cameraID, int displayOrientation) {
                if (drawHelper != null) {
                    drawHelper.setCameraDisplayOrientation(displayOrientation);
                }
                Log.i(TAG, "onCameraConfigurationChanged: " + cameraID + "  " + displayOrientation);
            }

            @Override
            public void onCameraError(Exception e) {
                Log.i(TAG, "onCameraError: " + e.getMessage());
            }

            @Override
            public void onCameraOpened(Camera camera, int cameraId, int displayOrientation, boolean isMirror) {
                Log.i(TAG, "onCameraOpened: " + cameraId + "  " + displayOrientation + " " + isMirror);
                previewSize = camera.getParameters().getPreviewSize();
                drawHelper = new DrawHelper(previewSize.width, previewSize.height, previewView.getWidth(),
                        previewView.getHeight(), displayOrientation
                        , cameraId, isMirror);
                handler.sendEmptyMessage(0);
            }

            @Override
            public void onPreview(byte[] nv21, Camera camera) {
                if (faceRectView != null) {
                    faceRectView.clearFaceInfo();
                }
                List<FaceInfo> faceInfoList = new ArrayList<>();
                int code = faceEngine.detectFaces(nv21, previewSize.width, previewSize.height,
                        FaceEngine.CP_PAF_NV21, faceInfoList);
                if (code != ErrorInfo.MOK) {
                    return;
                }
                if (faceInfoList.size() == 0) {
                    tipView.setText(R.string.detect_no_face_tips);
                } else if (faceInfoList.size() > 1) {
                    tipView.setText(R.string.detect_many_face_tips);
                } else {
                    tipView.setText(R.string.detect_eyes_tips);
                    code = faceEngine.process(nv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21,
                            faceInfoList, FaceEngine.ASF_LIVENESS);
                    if (code != ErrorInfo.MOK) {
                        return;
                    }
                    List<LivenessInfo> livenessInfoList = new ArrayList<>();
                    int livenessCode = faceEngine.getLiveness(livenessInfoList);
                    if (livenessCode != ErrorInfo.MOK) {
                        return;
                    }
                    if (livenessInfoList.get(0).getLiveness() != LivenessInfo.ALIVE) {
                        tipView.setText(R.string.detect_eyes_tips);
                        return;
                    } else {
                        tipView.setText("");
                    }
                    if (faceRectView != null && drawHelper != null) {
                        drawHelper.draw(faceRectView, faceInfoList.get(0).getRect());
                    }
                    FaceFeatureTask task = new FaceFeatureTask(faceEngine,
                            nv21, previewSize.width, previewSize.height,
                            FaceEngine.CP_PAF_NV21, faceInfoList.get(0));
                    mBlockingQueue.offer(task);
                }
            }
        };
        cameraHelper = new CameraHelper.Builder()
                .previewViewSize(new Point(previewView.getMeasuredWidth(), previewView.getMeasuredHeight()))
                .rotation(getWindowManager().getDefaultDisplay().getRotation())
                .specificCameraId(Camera.CameraInfo.CAMERA_FACING_FRONT)
                .isMirror(false)
                .previewOn(previewView)
                .cameraListener(cameraListener)
                .build();
        cameraHelper.init();
    }

    private void initEngine() {
        faceEngine = new FaceEngine();
        afCode = faceEngine.init(this.getApplicationContext(),
                FaceEngine.ASF_DETECT_MODE_VIDEO, FaceEngine.ASF_OP_0_HIGHER_EXT, 16, 20,
                FaceEngine.ASF_FACE_DETECT | FaceEngine.ASF_LIVENESS | FaceEngine.ASF_FACE_RECOGNITION);
        VersionInfo versionInfo = new VersionInfo();
        faceEngine.getVersion(versionInfo);
        Log.i(TAG, "initEngine:  init: " + afCode + "  version:" + versionInfo);
        if (afCode != ErrorInfo.MOK) {
            showMessage(getString(R.string.init_failed, afCode));
            finish();
        }
    }

    private void initExtraParams(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            savedInstanceState = getIntent().getExtras();
        }
        Verify.verifyNotNull(savedInstanceState, "参数传递有误.");
        action = savedInstanceState.getString(EXTRA_ACTION, ACTION_EXTRACT_FEATURE);
        Verify.verify(ACTION_EXTRACT_FEATURE.equals(action)
                || ACTION_COMPARE_FACE.equals(action), "参数传递有误.");
        if (ACTION_COMPARE_FACE.equals(action)) {
            srcFeatureData = savedInstanceState.getString(EXTRA_SRC_FEATURE);
            similarThreshold = savedInstanceState.getFloat(EXTRA_SIMILAR_THRESHOLD, 0.8F);
            Verify.verify(similarThreshold > 0.5F, "参数传递有误.");
            Verify.verify(!Strings.isNullOrEmpty(srcFeatureData), "参数传递有误.");
        }
    }

    private void unInitEngine() {
        if (afCode == 0) {
            afCode = faceEngine.unInit();
            Log.i(TAG, "unInitEngine: " + afCode);
        }
    }
}

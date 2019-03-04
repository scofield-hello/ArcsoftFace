package com.arcsoft.arcface.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import com.arcsoft.arcface.R;
import com.arcsoft.arcface.common.Constants;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Nick
 */
public class ActiveActivity extends BaseActivity {

    private static final String TAG = "ActiveActivity";

    private static final int ACTION_REQUEST_PERMISSIONS = 0x001;

    private static final int ACTION_REQUEST_FEATURE = 0x011;

    private static final int ACTION_REQUEST_COMPARE = 0x010;

    public static final String EXTRA_ACTION = "action";

    public static final String ACTION_COMPARE_FACE = "compare_face";

    public static final String ACTION_EXTRACT_FEATURE = "extract_feature";

    public static final String EXTRA_SRC_FEATURE = "src_feature";

    public static final String EXTRA_SIMILAR_THRESHOLD = "similar_threshold";

    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE
    };

    private static ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("arcface_pool_%d")
            .build();

    private String action;

    private float similarThreshold;

    private String srcFeatureData;

    private ExecutorService threadPool = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), threadFactory);

    private ListeningExecutorService service = MoreExecutors.listeningDecorator(threadPool);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active);
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
        Button mActiveButton = findViewById(R.id.btn_active);
        if (ACTION_EXTRACT_FEATURE.equals(action)) {
            mActiveButton.setText(R.string.active_button_register);
        } else if (ACTION_COMPARE_FACE.equals(action)) {
            mActiveButton.setText(R.string.active_button_compare);
        }
        mActiveButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                activeEngine();
            }
        });
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, @Nullable final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Intent intent = data != null ? data : new Intent();
        intent = resultCode == RESULT_OK ? intent.putExtra("result_code", 0)
                : intent.putExtra("result_code", -1);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        outState.putString(EXTRA_ACTION, action);
        outState.putString(EXTRA_SRC_FEATURE, srcFeatureData);
        outState.putFloat(EXTRA_SIMILAR_THRESHOLD, similarThreshold);
        super.onSaveInstanceState(outState);
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
                activeEngine();
            } else {
                showMessage(getString(R.string.permission_denied));
            }
        }
    }

    public void activeEngine() {
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
            return;
        }
        ListenableFutureTask<Integer> activeEngine = ListenableFutureTask.create(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                FaceEngine faceEngine = new FaceEngine();
                int activeCode = faceEngine.active(ActiveActivity.this, Constants.APP_ID, Constants.SDK_KEY);
                boolean isEngineActive = activeCode == ErrorInfo.MOK
                        || activeCode == ErrorInfo.MERR_ASF_ALREADY_ACTIVATED;
                Verify.verify(isEngineActive, getString(R.string.active_failed, activeCode));
                return activeCode;
            }
        });
        service.execute(activeEngine);
        Futures.addCallback(activeEngine, new FutureCallback<Integer>() {
            @Override
            public void onFailure(@NonNull Throwable throwable) {
                Log.e(TAG, String.format("激活人脸识别引擎--失败，线程: %s", Thread.currentThread().getName()), throwable);
                showMessage(throwable.getMessage());
            }

            @Override
            public void onSuccess(Integer result) {
                Log.i(TAG, String.format("激活人脸识别引擎--成功，线程: %s", Thread.currentThread().getName()));
                showMessage(getString(R.string.active_success));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(ActiveActivity.this, DetectActivity.class);
                        if (ACTION_COMPARE_FACE.equals(action)) {
                            intent.putExtra(DetectActivity.EXTRA_ACTION, DetectActivity.ACTION_COMPARE_FACE);
                            intent.putExtra(DetectActivity.EXTRA_SIMILAR_THRESHOLD, similarThreshold);
                            intent.putExtra(DetectActivity.EXTRA_SRC_FEATURE, srcFeatureData);
                            startActivityForResult(intent, ACTION_REQUEST_COMPARE);
                        } else if (ACTION_EXTRACT_FEATURE.equals(action)) {
                            intent.putExtra(DetectActivity.EXTRA_ACTION, DetectActivity.ACTION_EXTRACT_FEATURE);
                            startActivityForResult(intent, ACTION_REQUEST_FEATURE);
                        }
                    }
                });
            }
        }, service);
    }


    private void initExtraParams(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            savedInstanceState = getIntent().getExtras();
        }
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
}

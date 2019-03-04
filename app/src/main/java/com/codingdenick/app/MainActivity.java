package com.codingdenick.app;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import com.arcsoft.arcface.activity.ActiveActivity;

/**
 * @author Nick
 */
public class MainActivity extends AppCompatActivity {

    private static final int ACTION_REQUEST_FEATURE = 0x011;

    private static final int ACTION_REQUEST_COMPARE = 0x010;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.button1).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                Intent intent = new Intent(MainActivity.this, ActiveActivity.class);
                intent.putExtra(ActiveActivity.EXTRA_ACTION, ActiveActivity.ACTION_EXTRACT_FEATURE);
                startActivityForResult(intent, ACTION_REQUEST_FEATURE);
            }
        });

        findViewById(R.id.button2).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                Intent intent = new Intent(MainActivity.this, ActiveActivity.class);
                intent.putExtra(ActiveActivity.EXTRA_ACTION, ActiveActivity.ACTION_COMPARE_FACE);
                intent.putExtra(ActiveActivity.EXTRA_SIMILAR_THRESHOLD, 0.85F);
                intent.putExtra(ActiveActivity.EXTRA_SRC_FEATURE, Constants.NICK_FEATURE);
                startActivityForResult(intent, ACTION_REQUEST_COMPARE);
            }
        });
    }


    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, @Nullable final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            //失败
            Log.w("MainActivity", "用户取消操作:" + resultCode);
            return;
        }
        switch (requestCode) {
            case ACTION_REQUEST_FEATURE:
                int featureResultCode = data.getIntExtra("result_code", -1);
                if (featureResultCode == 0) {
                    String featureData = data.getStringExtra("data");
                    Log.i("MainActivity", "获取特征值成功：" + featureData);
                } else {
                    Log.w("MainActivity", "获取特征值失败：" + featureResultCode);
                }
                break;
            case ACTION_REQUEST_COMPARE:
                int compareResultCode = data.getIntExtra("result_code", -1);
                if (compareResultCode == 0) {
                    float scoreData = data.getFloatExtra("data", 0.0F);
                    Log.i("MainActivity", "人脸比对成功, 相似度：" + scoreData);
                } else {
                    Log.w("MainActivity", "人脸比对失败：" + compareResultCode);
                }
                break;
            default:
                break;
        }
    }
}

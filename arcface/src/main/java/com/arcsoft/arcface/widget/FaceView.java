package com.arcsoft.arcface.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Region;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

public class FaceView extends View {

    private static final String TAG = "FaceView";

    private Paint mPaint;

    public FaceView(Context context) {
        this(context, null);
    }

    public FaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setColor(Color.parseColor("#009688"));
        mPaint.setStyle(Style.STROKE);
        mPaint.setStrokeWidth(10.0f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.parseColor("#009688"));
        int width = getWidth() > getHeight() ? getHeight() : getWidth();
        Path path = new Path();
        //设置裁剪的圆心，半径
        path.addCircle(width / 2, width / 2, width / 2, Direction.CW);
        //裁剪画布，并设置其填充方式
        canvas.clipPath(path, Region.Op.REPLACE);
    }
}
package com.arcsoft.arcface.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;
import com.arcsoft.arcface.util.DrawHelper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Nick
 */
public class FaceRectView extends View {

    private CopyOnWriteArrayList<Rect> faceRectList = new CopyOnWriteArrayList<>();

    public FaceRectView(Context context) {
        this(context, null);
    }

    public FaceRectView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void addFaceInfo(Rect faceInfo) {
        faceRectList.add(faceInfo);
        postInvalidate();
    }

    public void addFaceInfo(List<Rect> faceInfoList) {
        faceRectList.addAll(faceInfoList);
        postInvalidate();
    }

    public void clearFaceInfo() {
        faceRectList.clear();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (faceRectList != null && faceRectList.size() > 0) {
            for (int i = 0; i < faceRectList.size(); i++) {
                DrawHelper.drawFaceRect(canvas, faceRectList.get(i), Color.YELLOW, 5);
            }
        }
    }
}
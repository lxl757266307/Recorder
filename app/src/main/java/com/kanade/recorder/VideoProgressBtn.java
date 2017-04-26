package com.kanade.recorder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

public class VideoProgressBtn extends View {
    private static final String TAG = "VideoProgressBar";
    private static final int MAX_PROGRESS = 100;
    private static final int CIRCLE_LINE_WIDTH = 10;
    // 动画持续时间
    private static final int ANI_DURATION = 350;
    // 放大倍数
    private static final float ZOOM_IN = 1.3f;

    private Paint btnPaint;
    private Paint bgPaint;
    private Paint paint;
    private RectF rectF;

    private int initSize = 0;
    private int maxSize = 0;
    private int circleCenter = 0;

    private boolean isAni = false;
    private float btnScale = 1;
    private float progressScale = 1;
    private int progress = 0;

    private AniEndListener listener;

    public VideoProgressBtn(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setListener(AniEndListener listener) {
        this.listener = listener;
    }

    private void init() {
        btnPaint = new Paint();
        btnPaint.setAntiAlias(true);
        btnPaint.setColor(0xFFFFFFFF);
        btnPaint.setStyle(Paint.Style.FILL);

        bgPaint = new Paint();
        bgPaint.setAntiAlias(true);
        bgPaint.setColor(0xFFCDC9C9);
        bgPaint.setStyle(Paint.Style.FILL);

        paint = new Paint();
        paint.setColor(0xFFA4C739);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(CIRCLE_LINE_WIDTH);
        paint.setStyle(Paint.Style.STROKE);

        rectF = new RectF();
        rectF.left = CIRCLE_LINE_WIDTH / 2 + .8f;
        rectF.top = CIRCLE_LINE_WIDTH / 2 + .8f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float btnSize = initSize * btnScale;
        float progressSize = initSize * progressScale;

        rectF.right = progressSize - CIRCLE_LINE_WIDTH / 2 - 1.5f;
        rectF.bottom = progressSize - CIRCLE_LINE_WIDTH / 2 - 1.5f;

        float p = (float) progress / MAX_PROGRESS * 360;
        canvas.drawCircle(circleCenter, circleCenter, progressSize / 2 - 1f, bgPaint);
        canvas.drawCircle(circleCenter, circleCenter, btnSize / 3 - 1f, btnPaint);
        if (!isAni) canvas.drawArc(rectF, -90, p, false, paint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (initSize == 0 || maxSize == 0) {
            initSize = MeasureSpec.getSize(widthMeasureSpec);
            maxSize = (int) Math.ceil(initSize * ZOOM_IN);
            circleCenter = maxSize / 2;
        }
        setMeasuredDimension(maxSize, maxSize);
    }

    public void recording() {
        ValueAnimator progressAni = ValueAnimator.ofFloat(progressScale, ZOOM_IN);
        ValueAnimator btnAni = ValueAnimator.ofFloat(btnScale, 0.5f);
        progressAni.addUpdateListener(progressListener);
        btnAni.addUpdateListener(btnListener);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(progressAni, btnAni);
        set.addListener(zoomInAdapter);
        set.setDuration(ANI_DURATION);
        isAni = true;
        set.start();
    }

    public void recordComplete() {
        ValueAnimator progressAni = ValueAnimator.ofFloat(progressScale, 1f);
        ValueAnimator btnAni = ValueAnimator.ofFloat(btnScale, 1f);
        progressAni.addUpdateListener(progressListener);
        btnAni.addUpdateListener(btnListener);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(progressAni, btnAni);
        set.addListener(zoomOutAdapter);
        set.setDuration(ANI_DURATION);
        isAni = true;
        set.start();
    }

    private AnimatorListenerAdapter zoomInAdapter = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            isAni = false;
            if (listener != null) {
                listener.touched();
            }
        }
    };

    private AnimatorListenerAdapter zoomOutAdapter = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            isAni = false;
            if (listener != null) {
                listener.untouched();
            }
        }
    };

    private ValueAnimator.AnimatorUpdateListener progressListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
//            Log.d(TAG, "progressScale: " + progressScale);
            progressScale = (float) animation.getAnimatedValue();
            invalidate();
        }
    };

    private ValueAnimator.AnimatorUpdateListener btnListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
//            Log.d(TAG, "btnScale: " + btnScale);
            btnScale = (float) animation.getAnimatedValue();
            invalidate();
        }
    };

    public void setProgress(int progress) {
        this.progress = progress;
        invalidate();
    }

    public interface AniEndListener {
        void touched();

        void untouched();
    }
}

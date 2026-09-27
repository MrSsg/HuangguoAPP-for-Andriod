package com.huangguo.mobile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

final class ShimmerView extends View {
    private final Paint paint = new Paint();
    private final ValueAnimator animator;
    private float phase;

    ShimmerView(Context context) {
        super(context);
        animator = ValueAnimator.ofFloat(-1f, 1.5f);
        animator.setDuration(1700);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float start = width * phase;
        paint.setShader(new LinearGradient(start, 0, start + width, 0,
                new int[]{0xFF171A1E, 0xFF30343A, 0xFF171A1E},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, getHeight(), paint);
        paint.setShader(null);
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) animator.start();
        else animator.cancel();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }
}

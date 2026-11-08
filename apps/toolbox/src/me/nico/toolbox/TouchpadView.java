package me.nico.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Laptop-style touchpad.
 * 1 finger move = pointer, tap = left click, long press then move = drag (left held),
 * 2 finger tap = right click, 2 finger move = scroll.
 */
final class TouchpadView extends View {
    private static final long TAP_MS = 220;
    private static final long LONG_PRESS_MS = 450;
    private static final float BASE_GAIN = 0.6f;
    private static final float ACCEL = 0.035f;
    private static final float MAX_GAIN = 2.2f;

    private final HidMouseProvider provider;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float touchSlop;
    private final float scrollStepPx;

    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private long downTime;
    private boolean moved;
    private boolean dragging;
    private boolean twoFinger;
    private boolean twoFingerMoved;
    private float scrollAccum;
    private float remX;
    private float remY;

    interface HidMouseProvider {
        HidMouse mouse();
    }

    private final Runnable longPress = () -> {
        HidMouse m = mouse();
        if (m != null && !moved && !twoFinger) {
            dragging = true;
            m.press(HidMouse.LEFT);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

    TouchpadView(Context context, HidMouseProvider provider) {
        super(context);
        this.provider = provider;
        float density = getResources().getDisplayMetrics().density;
        touchSlop = 8 * density;
        scrollStepPx = 22 * density;
        fill.setColor(0xFF1B2125);
        hint.setColor(0xFF5F6B72);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
    }

    private HidMouse mouse() {
        return provider.mouse();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 18 * getResources().getDisplayMetrics().density;
        bounds.set(0, 0, getWidth(), getHeight());
        fill.setColor(dragging ? 0xFF33302A : 0xFF1B2125);
        canvas.drawRoundRect(bounds, r, r, fill);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.drawText(dragging ? "Clic maintenu · relâcher pour lâcher" : "Pavé tactile", cx, cy - hint.getTextSize(), hint);
        canvas.drawText("1 doigt : bouger · toucher : clic", cx, cy + hint.getTextSize() * 0.6f, hint);
        canvas.drawText("2 doigts : clic droit · glisser : défiler", cx, cy + hint.getTextSize() * 2.0f, hint);
        canvas.drawText("appui long + glisser : sélectionner", cx, cy + hint.getTextSize() * 3.4f, hint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        HidMouse m = mouse();
        if (m == null) {
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downTime = e.getEventTime();
                moved = false;
                twoFinger = false;
                twoFingerMoved = false;
                scrollAccum = 0;
                handler.postDelayed(longPress, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2 && !dragging) {
                    twoFinger = true;
                    handler.removeCallbacks(longPress);
                    lastY = averageY(e);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                onMove(e, m);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_UP:
                onUp(e, m);
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    m.release(HidMouse.LEFT);
                    dragging = false;
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    private void onMove(MotionEvent e, HidMouse m) {
        if (twoFinger) {
            float y = averageY(e);
            scrollAccum += y - lastY;
            lastY = y;
            int steps = (int) (scrollAccum / scrollStepPx);
            if (steps != 0) {
                twoFingerMoved = true;
                m.scroll(steps); // natural scrolling: finger down = content down = wheel up
                scrollAccum -= steps * scrollStepPx;
            }
            return;
        }
        float x = e.getX();
        float y = e.getY();
        if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
            moved = true;
            if (!dragging) {
                handler.removeCallbacks(longPress);
            }
        }
        float dx = x - lastX;
        float dy = y - lastY;
        lastX = x;
        lastY = y;
        float speed = (float) Math.hypot(dx, dy);
        float gain = Math.min(MAX_GAIN, BASE_GAIN + speed * ACCEL);
        remX += dx * gain;
        remY += dy * gain;
        int ix = (int) remX;
        int iy = (int) remY;
        if (ix != 0 || iy != 0) {
            m.move(ix, iy);
            remX -= ix;
            remY -= iy;
        }
    }

    private void onUp(MotionEvent e, HidMouse m) {
        handler.removeCallbacks(longPress);
        long duration = e.getEventTime() - downTime;
        if (dragging) {
            m.release(HidMouse.LEFT);
            dragging = false;
            invalidate();
        } else if (twoFinger) {
            if (!twoFingerMoved && duration < TAP_MS * 2) {
                m.click(HidMouse.RIGHT);
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            }
        } else if (!moved && duration < TAP_MS) {
            m.click(HidMouse.LEFT);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        twoFinger = false;
    }

    private static float averageY(MotionEvent e) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            sum += e.getY(i);
        }
        return sum / e.getPointerCount();
    }
}

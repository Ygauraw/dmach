/*
* Copyright (C) 2014 Simon Norberg
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package net.simno.dmach.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import net.simno.dmach.DMachActivity;
import net.simno.dmach.R;

import java.util.ArrayList;
import java.util.Iterator;

public final class SequencerView extends View {

    public interface OnStepChangedListener {
        public void onStepChanged(int group, int step, int mask, int index);
    }

    private static final int CHANNELS_PER_GROUP = DMachActivity.CHANNELS / DMachActivity.GROUPS;

    private final ArrayList<Step> mSequence = new ArrayList<>();
    private OnStepChangedListener mListener;
    private Paint mUncheckedLight;
    private Paint mUncheckedDark;
    private Paint mChecked;
    private int mBackground;
    private boolean mIsChecked;
    private int mWidth;
    private int mHeight;
    private float mMargin;
    private float mStepWidth;
    private float mStepHeight;
    private float mStepWidthMargin;
    private float mStepHeightMargin;

    private class Step {
        private RectF rect;
        private boolean checked;

        void toggle() {
            checked = !checked;
        }
    }

    public SequencerView(Context context) {
        super(context);
        init();
    }

    public SequencerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SequencerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mBackground = getResources().getColor(R.color.colonial);

        mUncheckedLight = new Paint();
        mUncheckedLight.setColor(getResources().getColor(R.color.khaki));
        mUncheckedLight.setStyle(Paint.Style.FILL);

        mUncheckedDark = new Paint();
        mUncheckedDark.setColor(getResources().getColor(R.color.gurkha));
        mUncheckedDark.setStyle(Paint.Style.FILL);

        mChecked = new Paint();
        mChecked.setColor(getResources().getColor(R.color.poppy));
        mChecked.setStyle(Paint.Style.FILL);

        mMargin = getResources().getDimension(R.dimen.margin_small);

        for (int channel = 0; channel < DMachActivity.CHANNELS; ++channel) {
            for (int step = 0; step < DMachActivity.STEPS; ++step) {
                mSequence.add(new Step());
            }
        }
    }

    private void initSteps() {
        for (int channel = 0; channel < DMachActivity.CHANNELS; ++channel) {
            for (int step = 0; step < DMachActivity.STEPS; ++step) {
                float left = step * mStepWidthMargin;
                float right = left + mStepWidth;
                float top = channel * mStepHeightMargin;
                float bottom = top + mStepHeight;
                int index = (channel * DMachActivity.STEPS) + step;
                mSequence.get(index).rect = new RectF(left, top, right, bottom);
            }
        }
    }

    public void setOnStepChangedListener(OnStepChangedListener listener) {
        mListener = listener;
    }

    private void notifyOnStepChanged(int channel, int step) {
        if (mListener != null) {
            mListener.onStepChanged(
                    getGroup(channel),
                    step,
                    getMask(channel),
                    getIndex(channel, step));
        }
    }

    public void setChecked(int[] sequence) {
        Iterator<Step> it = mSequence.iterator();
        for (int channel = 0; channel < (DMachActivity.CHANNELS); ++channel) {
            for (int step = 0; step < DMachActivity.STEPS; ++step) {
                if (it.hasNext()) {
                    int mask = getMask(channel);
                    int index = getIndex(channel, step);
                    int value = sequence[index] & mask;
                    it.next().checked = value != 0;
                }
            }
        }
        invalidate();
    }

    public int[] getPdSequence() {
        int[] sequence = new int[DMachActivity.GROUPS * DMachActivity.STEPS];
        Iterator<Step> it = mSequence.iterator();
        for (int channel = 0; channel < (DMachActivity.CHANNELS); ++channel) {
            for (int step = 0; step < DMachActivity.STEPS; ++step) {
                if (it.hasNext()) {
                    int mask = getMask(channel);
                    int index = getIndex(channel, step);
                    if (it.next().checked) {
                        sequence[index] |= mask;
                    }
                }
            }
        }
        return sequence;
    }

    public static int getMask(int channel) {
        return DMachActivity.MASKS[channel % CHANNELS_PER_GROUP];
    }

    public static int getGroup(int channel) {
        return channel / CHANNELS_PER_GROUP;
    }

    public static int getIndex(int channel, int step) {
        int offset = getGroup(channel) * DMachActivity.STEPS;
        return step + offset;
    }

    public static int getListIndex(int channel, int step) {
        return channel * DMachActivity.STEPS + step;
    }

    private int pxToChannel(float px) {
        return (int) (px / mStepHeightMargin);
    }

    private int pxToStep(float px) {
        return (int) (px / mStepWidthMargin);
    }

    private boolean isOutsideView(float x, float y) {
        return x < 0 || y < 0 || x > mWidth || y > mHeight;
    }

    private void onActionDown(float x, float y) {
        if (isOutsideView(x, y)) {
            return;
        }

        int channel = pxToChannel(y);
        int step = pxToStep(x);
        int index = getListIndex(channel, step);

        mIsChecked = mSequence.get(index).checked;
        mSequence.get(index).toggle();
        notifyOnStepChanged(channel, step);
        invalidate();
    }

    private void onActionMove(float x, float y) {
        if (isOutsideView(x, y)) {
            return;
        }

        int channel = pxToChannel(y);
        int step = pxToStep(x);
        int index = getListIndex(channel, step);

        if (mSequence.get(index).checked == mIsChecked) {
            mSequence.get(index).toggle();
            notifyOnStepChanged(channel, step);
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                onActionDown(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                onActionMove(event.getX(), event.getY());
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(mBackground);
        for (int i = 0; i < mSequence.size(); ++i) {
            Step step = mSequence.get(i);
            if (step.checked) {
                canvas.drawRect(step.rect, mChecked);
            } else {
                if ((i % 8) < 4) {
                    canvas.drawRect(step.rect, mUncheckedLight);
                } else {
                    canvas.drawRect(step.rect, mUncheckedDark);
                }
            }
        }
    }

    @Override
    protected void onSizeChanged (int w, int h, int oldw, int oldh) {
        if (w != 0 && h != 0) {
            mWidth = w;
            mHeight = h;
            mStepWidth = (w - ((DMachActivity.STEPS - 1f) * mMargin)) / DMachActivity.STEPS;
            mStepHeight = (h - ((DMachActivity.CHANNELS - 1f) * mMargin)) / DMachActivity.CHANNELS;
            mStepWidthMargin = mStepWidth + mMargin;
            mStepHeightMargin = mStepHeight + mMargin;
            initSteps();
            invalidate();
        }
    }
}

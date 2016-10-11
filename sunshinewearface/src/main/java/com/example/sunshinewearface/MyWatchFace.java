/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sunshinewearface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.ColorRes;
import android.support.annotation.DimenRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    public static int getIconResourceForWeatherCondition(int weatherId) {
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_clouds;
        }
        return -1;
    }

    /*
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener {

        final Handler updateTimeHandler = new EngineHandler(this);
        Paint backgroundPaint, blackPaint, textPaintClock, textPaintDate, textPaintTempHigh, textPaintTempLow;
        boolean ambient;
        Time time;

        final BroadcastReceiver timeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                time.clear(intent.getStringExtra("time-zone"));
                time.setToNow();
            }
        };

        boolean lowBitAmbient;
        private int width;
        private String weatherHigh;
        private String weatherLow;
        private Bitmap weatherIcon;
        private Bitmap grayWeatherIcon;
        private Rect cardRect = new Rect();
        private float XCenter;
        private float YOffset;

        private SharedPreferences preferences;
        private String TAG = "ENGINE";
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                        Wearable.DataApi.addListener(googleApiClient, Engine.this);

                        getWeather();
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        Log.d(TAG, "onConnectionSuspended: " + i);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                        Log.d(TAG, "onConnectionFailed: " + connectionResult);
                    }
                })
                .addApi(Wearable.API)
                .build();

        private void getWeather() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/weather-req");
            putDataMapRequest.getDataMap().putString("id", UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(googleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "onResult: " + "Data request failed.");
                            } else {
                                Log.d(TAG, "onResult: " + "Data request success.");
                            }
                        }
                    });
        }

        private boolean mRegisteredTimeZoneReceiver = false;
        private Bitmap backgroundBitmap;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = MyWatchFace.this.getResources();
            YOffset = resources.getDimension(R.dimen.watch_y_offset);

            backgroundPaint = new Paint();
            backgroundPaint.setColor(Color.BLACK);
            backgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg);

            textPaintClock = createTextPaint(R.color.digital_text_white, R.dimen.watch_text_size_digital_clock);
            textPaintDate = createTextPaint(R.color.digital_text_semi_white, R.dimen.watch_text_size_digital_date);
            textPaintTempLow = createTextPaint(R.color.digital_text_semi_white, R.dimen.watch_text_size_digital_temp);
            textPaintTempHigh = createTextPaint(R.color.digital_text_white, R.dimen.watch_text_size_digital_temp);

            time = new Time();

            preferences = PreferenceManager.getDefaultSharedPreferences(MyWatchFace.this);
            weatherLow = preferences.getString("low", null);
            weatherHigh = preferences.getString("high", null);
            weatherIcon = getWeatherIcon(preferences.getInt("weatherId", 0));
        }

        private Bitmap getWeatherIcon(int weatherId) {
            if (weatherId > 0){
                Drawable drawable = getResources().getDrawable(getIconResourceForWeatherCondition(weatherId));
                Bitmap icon = ((BitmapDrawable) drawable).getBitmap();
                Bitmap scaledIcon = Bitmap.createScaledBitmap(icon, 60, 60, true);

                grayWeatherIcon = Bitmap.createBitmap(
                        scaledIcon.getWidth(),
                        scaledIcon.getHeight(),
                        Bitmap.Config.ARGB_8888
                );
                Canvas canvas = new Canvas(grayWeatherIcon);
                Paint paint = new Paint();
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
                paint.setColorFilter(filter);
                canvas.drawBitmap(scaledIcon, 0, 0, paint);

                return scaledIcon;
            } else {
                return null;
            }
        }

        private Paint createTextPaint(@ColorRes int color, @DimenRes int dimenTextSize) {
            Paint paint = new Paint();
            setTextPaintColor(paint, color);
            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(NORMAL_TYPEFACE);
            float textSize = MyWatchFace.this.getResources().getDimension(dimenTextSize);
            paint.setTextSize(textSize);
            return paint;
        }

        private void setTextPaintColor(Paint paintColor, @ColorRes int textColor) {
            if (!ambient) {
                paintColor.setColor(MyWatchFace.this.getResources().getColor(textColor));
            } else {
                paintColor.setColor(Color.WHITE);
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            cardRect.set(rect);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                googleApiClient.connect();

                registerReceiver();

                time.clear(TimeZone.getDefault().getID());
                time.setToNow();
            } else {
                unregisterReceiver();

                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    googleApiClient.disconnect();
                }
            }
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }

            mRegisteredTimeZoneReceiver = true;
            IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(timeZoneReceiver, intentFilter);
        }

        private void unRegisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(timeZoneReceiver);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            this.width = width;

            XCenter = width / 2f;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (ambient != inAmbientMode) {
                ambient = inAmbientMode;
                if (lowBitAmbient) {
                    textPaintDate.setAntiAlias(!inAmbientMode);
                    textPaintClock.setAntiAlias(!inAmbientMode);
                    textPaintTempHigh.setAntiAlias(!inAmbientMode);
                    textPaintTempLow.setAntiAlias(!inAmbientMode);
                }

                setTextPaintColor(textPaintDate, R.color.digital_text_semi_white);
                setTextPaintColor(textPaintTempLow, R.color.digital_text_semi_white);

                invalidate();
            }

            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }

            time.setToNow();

            canvas.drawText(String.format("%02d:%02d", time.hour, time.minute), XCenter, YOffset, textPaintClock);

            float y = YOffset + 30;
            canvas.drawText(time.format("%a, %b, %d %Y").toUpperCase(), XCenter, y, textPaintDate);

            y = y + 30;
            canvas.drawLine(XCenter - 30, y, XCenter + 30, y, textPaintDate);

            y = y + 50;
            if (weatherHigh != null && weatherLow != null) {
                canvas.drawText(weatherHigh, XCenter, y, textPaintTempHigh);
                float highLength = textPaintTempHigh.measureText(weatherHigh);
                canvas.drawText(weatherLow, XCenter + highLength + 10, y, textPaintTempLow);

                if (!lowBitAmbient) {
                    Bitmap icon = (!ambient) ? weatherIcon : grayWeatherIcon;
                    if (icon != null) {
                        canvas.drawBitmap(icon, XCenter - (highLength+icon.getHeight()),
                                y - icon.getHeight() + 15, null);
                    }
                }

                if (ambient) {
                    canvas.drawRect(cardRect, blackPaint);
                }
            }
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(timeZoneReceiver);
        }

        private void updateTimer() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !ambient;
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/weather-info") == 0) {

                        SharedPreferences.Editor editor = preferences.edit();

                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        if (dataMap.containsKey("high")) {
                            weatherHigh = dataMap.getString("high");
                            editor.putString("high", weatherHigh);
                        }

                        if (dataMap.containsKey("low")) {
                            weatherLow = dataMap.getString("low");
                            editor.putString("low", weatherLow);
                        }

                        if (dataMap.containsKey("weatherId")) {
                            int weatherId = dataMap.getInt("weatherId");
                            editor.putInt("weatherId", weatherId);

                            weatherIcon = getWeatherIconBitmap(weatherId);
                        }

                        editor.commit();

                        invalidate();
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                    preferences.edit().clear().commit();
                }
            }
        }

        private Bitmap getWeatherIconBitmap(int weatherId) {
            if (weatherId > 0) {
                Drawable b = getResources().getDrawable(getIconResourceForWeatherCondition(weatherId));
                Bitmap icon = ((BitmapDrawable) b).getBitmap();

                Bitmap resBitmap = Bitmap.createScaledBitmap(icon, 60, 60, true);

                //clone for Ambient mode
                grayWeatherIcon = Bitmap.createBitmap(
                        resBitmap.getWidth(),
                        resBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(grayWeatherIcon);
                Paint grayPaint = new Paint();
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new
                        ColorMatrixColorFilter(colorMatrix);
                grayPaint.setColorFilter(filter);
                canvas.drawBitmap(resBitmap, 0, 0, grayPaint);

                return resBitmap;
            } else {
                return null;
            }
        }
    }
}

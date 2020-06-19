package me.liaoheng.wallpaper.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import com.github.liaoheng.common.util.Callback;
import com.github.liaoheng.common.util.L;
import com.github.liaoheng.common.util.Utils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.ObservableTransformer;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import me.liaoheng.wallpaper.model.BingWallpaperImage;
import me.liaoheng.wallpaper.model.BingWallpaperState;
import me.liaoheng.wallpaper.util.BingWallpaperUtils;
import me.liaoheng.wallpaper.util.LogDebugFileUtils;
import me.liaoheng.wallpaper.util.SetWallpaperStateBroadcastReceiverHelper;

/**
 * @author liaoheng
 * @version 2020-06-17 16:59
 */
public class LiveWallpaperService extends WallpaperService {
    private String TAG = LiveWallpaperService.class.getSimpleName();
    public static final String UPDATE_LIVE_WALLPAPER = "me.liaoheng.wallpaper.UPDATE_LIVE_WALLPAPER";
    private LiveWallpaperEngine mEngine;
    private LiveWallpaperBroadcastReceiver mReceiver;

    @Override
    public Engine onCreateEngine() {
        mEngine = new LiveWallpaperEngine(this);
        return mEngine;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mReceiver = new LiveWallpaperBroadcastReceiver();
        registerReceiver(mReceiver, new IntentFilter(UPDATE_LIVE_WALLPAPER));
    }

    @Override
    public void onDestroy() {
        mEngine = null;
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        super.onDestroy();
    }

    class LiveWallpaperBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (UPDATE_LIVE_WALLPAPER.equals(intent.getAction())) {
                BingWallpaperImage image = intent.getParcelableExtra("image");
                if (mEngine != null) {
                    mEngine.loadBingWallpaper(image);
                }
            }
        }
    }

    static class DownloadBitmap {
        public DownloadBitmap(BingWallpaperImage image) {
            this.image = image;
        }

        BingWallpaperImage image;
        Bitmap bitmap;
    }

    private class LiveWallpaperEngine extends LiveWallpaperService.Engine {
        private Context mContext;
        private Paint paint;
        private Handler handler;
        private Runnable drawRunner;
        private Disposable mDisposable;

        public LiveWallpaperEngine(Context context) {
            mContext = context;
            handler = new Handler();
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setFilterBitmap(true);
            paint.setDither(true);
            setOffsetNotificationsEnabled(true);
            drawRunner = this::timing;
        }

        private void postDelayed() {
            //handler.postDelayed(drawRunner, BingWallpaperUtils.getAutomaticUpdateInterval(mContext));
            handler.postDelayed(drawRunner, TimeUnit.MINUTES.toMillis(10));
        }

        public void loadBingWallpaper(BingWallpaperImage image) {
            handler.removeCallbacks(drawRunner);
            postDelayed();
            mDisposable = Utils.addSubscribe(
                    Observable.just(new DownloadBitmap(image)).subscribeOn(Schedulers.io()).compose(download()),
                    new Callback.EmptyCallback<DownloadBitmap>() {

                        @Override
                        public void onSuccess(DownloadBitmap d) {
                            draw(d.bitmap);
                            SetWallpaperStateBroadcastReceiverHelper.sendSetWallpaperBroadcast(mContext,
                                    BingWallpaperState.SUCCESS);
                            BingWallpaperUtils.setLastWallpaperImageUrl(getApplicationContext(), d.image.getImageUrl());
                        }

                        @Override
                        public void onError(Throwable e) {
                            SetWallpaperStateBroadcastReceiverHelper.sendSetWallpaperBroadcast(mContext,
                                    BingWallpaperState.FAIL);
                        }
                    });
        }

        public void init() {
            L.alog().d(TAG,"init");
            handler.removeCallbacks(drawRunner);
            setBingWallpaper(true);
            postDelayed();
        }

        private void timing() {
            postDelayed();
            L.alog().d(TAG,"timing check...");
            if (BingWallpaperUtils.isEnableLogProvider(getApplicationContext())) {
                LogDebugFileUtils.get().i(TAG, "Timing check...");
            }
            if (!BingWallpaperUtils.isTaskUndone(mContext)) {
                return;
            }
            setBingWallpaper(false);
        }

        private void setBingWallpaper(boolean init) {
            mDisposable = Utils.addSubscribe(
                    Observable.just(init).subscribeOn(Schedulers.io()).compose(load()).compose(download()),
                    new Callback.EmptyCallback<DownloadBitmap>() {

                        @Override
                        public void onSuccess(DownloadBitmap d) {
                            draw(d.bitmap);
                            BingWallpaperUtils.setLastWallpaperImageUrl(getApplicationContext(), d.image.getImageUrl());
                            BingWallpaperUtils.taskComplete(mContext, TAG);
                        }
                    });
        }

        private ObservableTransformer<Boolean, DownloadBitmap> load() {
            return upstream -> upstream.flatMap((Function<Boolean, ObservableSource<DownloadBitmap>>) force -> {
                BingWallpaperImage image;
                if (!force) {
                    Intent intent = BingWallpaperUtils.checkRunningServiceIntent(mContext,
                            TAG, false);
                    if (intent == null) {
                        return null;
                    }
                }
                try {
                    image = BingWallpaperUtils.getImage(getApplicationContext(), true);
                    return Observable.just(new DownloadBitmap(image));
                } catch (IOException e) {
                    return Observable.error(e);
                }
            });
        }

        private ObservableTransformer<DownloadBitmap, DownloadBitmap> download() {
            return upstream -> upstream.flatMap((Function<DownloadBitmap, ObservableSource<DownloadBitmap>>) image -> {
                image.bitmap = BingWallpaperUtils.getGlideBitmap(mContext, image.image.getImageUrl());
                return Observable.just(image);
            });
        }

        private void draw(Bitmap bitmap) {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK);
                    canvas.drawBitmap(bitmap, 0, 0, paint);
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (mEngine != null) {
                mEngine.init();
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            Utils.dispose(mDisposable);
            handler.removeCallbacks(drawRunner);
            super.onSurfaceDestroyed(holder);
        }
    }

}

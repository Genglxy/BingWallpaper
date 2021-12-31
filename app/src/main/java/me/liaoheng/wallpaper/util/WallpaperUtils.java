package me.liaoheng.wallpaper.util;

import android.app.ProgressDialog;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ShareCompat;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.commit451.nativestackblur.NativeStackBlur;
import com.github.liaoheng.common.util.BitmapUtils;
import com.github.liaoheng.common.util.Callback;
import com.github.liaoheng.common.util.DisplayUtils;
import com.github.liaoheng.common.util.FileUtils;
import com.github.liaoheng.common.util.L;
import com.github.liaoheng.common.util.UIUtils;
import com.github.liaoheng.common.util.Utils;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;

import io.reactivex.Observable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import me.liaoheng.wallpaper.R;
import me.liaoheng.wallpaper.model.Config;
import me.liaoheng.wallpaper.model.Wallpaper;

/**
 * @author liaoheng
 * @version 2020-07-01 13:40
 */
public class WallpaperUtils {

    public static boolean isNotSupportedWallpaper(Context context) {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(context);
            if (manager == null) {
                Toast.makeText(context, "This device not support wallpaper", Toast.LENGTH_LONG).show();
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!manager.isWallpaperSupported()) {
                    Toast.makeText(context, "This device not support wallpaper", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!manager.isSetWallpaperAllowed()) {
                    Toast.makeText(context, "This device not support set wallpaper", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void autoSaveWallpaper(Context context, String tag, Wallpaper image) {
        if (!Settings.isAutoSave(context)) {
            return;
        }
        String imageUrl = BingWallpaperUtils.getImageUrl(context, Settings.getSaveResolution(context),
                image.getBaseUrl());
        Utils.addSubscribe(Observable.just(imageUrl)
                        .subscribeOn(Schedulers.io())
                        .retryWhen(new RetryWithDelay(3, 10))
                        .map(url -> WallpaperUtils.getImageFile(context, url)),
                new Callback.EmptyCallback<File>() {
                    @Override
                    public void onSuccess(File file) {
                        saveWallpaper(context, tag, imageUrl, file);
                    }

                    @Override
                    public void onError(Throwable e) {
                        L.alog().e(tag, e, "auto download wallpaper failure");
                        if (Settings.isEnableLogProvider(context)) {
                            LogDebugFileUtils.get().e(tag, e, "Auto download wallpaper failure");
                        }
                    }
                });
    }

    public static void autoSaveWallpaper(Context context, String tag, Wallpaper image, File wallpaper) {
        if (!Settings.isAutoSave(context)) {
            return;
        }
        File saveFile = new File(wallpaper.toURI());
        try {
            String saveResolution = Settings.getSaveResolution(context);
            String resolution = Settings.getResolution(context);
            String saveImageUrl = image.getImageUrl();
            if (!saveResolution.equals(resolution)) {
                saveImageUrl = BingWallpaperUtils.getImageUrl(context, saveResolution,
                        image.getBaseUrl());
                saveFile = getImageFile(context, saveImageUrl);
            }
            saveWallpaper(context, tag, saveImageUrl, saveFile);
            if (Settings.isEnableLogProvider(context)) {
                LogDebugFileUtils.get().d(tag, "auto save wallpaper url: %s", saveImageUrl);
            }
        } catch (Throwable e) {
            L.alog().e(tag, e, "auto download wallpaper failure");
            if (Settings.isEnableLogProvider(context)) {
                LogDebugFileUtils.get().e(tag, e, "Auto download wallpaper failure");
            }
        }
    }

    private static void saveWallpaper(Context context, String tag, String imageUrl, File file) {
        try {
            saveToFile(context, imageUrl, file);
            L.alog().i(tag, "auto download wallpaper url: %s", imageUrl);
            if (Settings.isEnableLogProvider(context)) {
                LogDebugFileUtils.get().d(tag, "Auto download wallpaper url: %s", imageUrl);
            }
        } catch (IOException e) {
            if (Settings.isEnableLogProvider(context)) {
                LogDebugFileUtils.get().e(tag, e, "Auto download wallpaper save failure");
            }
        }
    }

    public static Uri saveToFile(Context context, String url, File from) throws IOException {
        return FileUtils.saveFileToPictureCompat(context, BingWallpaperUtils.getWallpaperName(url), from);
    }

    @SuppressWarnings("UnstableApiUsage")
    public static File getLocalWallpaperFile(Context context, File file) {
        try {
            File wallpaper = FileUtils.createFile(FileUtils.getProjectSpaceCacheDirectory(context, "wallpaper"),
                    "wallpaper.w");
            Files.copy(file, wallpaper);
            return wallpaper;
        } catch (Exception e) {
            return file;
        }
    }

    public static File getImageFile(Context context, String url) throws Exception {
        return getLocalWallpaperFile(context, GlideApp.with(context).downloadOnly().load(url).submit().get());
    }

    public static File getImageFile(Context context, @NonNull Config config, @NonNull String url) throws Exception {
        return getImageStackBlurFile(config.getStackBlur(), getImageFile(context, url));
    }

    public static File getImageStackBlurFile(int stackBlur, File wallpaper) {
        if (stackBlur > 0) {
            String key = BingWallpaperUtils.createKey(wallpaper.getAbsolutePath() + "_blur_" + stackBlur);
            File stackBlurFile = CacheUtils.get().get(key);
            if (stackBlurFile == null) {
                Bitmap bitmap = transformStackBlur(BitmapFactory.decodeFile(wallpaper.getAbsolutePath()), stackBlur);
                stackBlurFile = CacheUtils.get().put(key, BitmapUtils.bitmapToStream(bitmap,
                        Bitmap.CompressFormat.JPEG));
                bitmap.recycle();
            }
            if (stackBlurFile != null && stackBlurFile.exists()) {
                return stackBlurFile;
            }
        }
        return wallpaper;
    }

    public static File getImageWaterMarkFile(@NonNull Context context, File wallpaper, String str) {
        String key = BingWallpaperUtils.createKey(wallpaper.getAbsolutePath() + "_mark_" + str);
        File mark = CacheUtils.get().get(key);
        if (mark == null) {
            Bitmap bitmap = waterMark(context, BitmapFactory.decodeFile(wallpaper.getAbsolutePath()), str);
            mark = CacheUtils.get().put(key, BitmapUtils.bitmapToStream(bitmap,
                    Bitmap.CompressFormat.JPEG));
            bitmap.recycle();
        }
        return mark;
    }

    public static Bitmap transformStackBlur(Bitmap bitmap, int stackBlur) {
        if (stackBlur <= 0) {
            return bitmap;
        }
        return toStackBlur2(bitmap, stackBlur);
    }

    @SuppressWarnings("UnstableApiUsage")
    public static File getShareFile(Context context, File file) {
        try {
            File share = FileUtils.createFile(FileUtils.getProjectSpaceCacheDirectory(context, "share"),
                    "share.jpg");
            Files.copy(file, share);
            return share;
        } catch (Exception e) {
            return file;
        }
    }

    public static void shareImage(@NonNull Context context, @NonNull Config config, @NonNull String url,
            @NonNull final String title) {
        ProgressDialog dialog = UIUtils.showProgressDialog(context, context.getString(R.string.share) + "...");
        Observable<File> fileObservable = Observable.just("")
                .subscribeOn(Schedulers.io())
                .map(s -> getImageFile(context, config, url))
                .flatMap(file -> Observable.just(getImageWaterMarkFile(context, file, title)))
                .map(file -> getShareFile(context, file));
        Utils.addSubscribe(fileObservable, new Callback.EmptyCallback<File>() {
            @Override
            public void onSuccess(File file) {
                UIUtils.dismissDialog(dialog);
                Intent share = new ShareCompat.IntentBuilder(context)
                        .setType("image/jpeg")
                        .setText(title)
                        .setStream(BingWallpaperUtils.getUriForFile(context, file))
                        .getIntent();
                context.startActivity(share);
            }

            @Override
            public void onError(Throwable e) {
                UIUtils.dismissDialog(dialog);
                L.alog().e("Share", e);
                UIUtils.showToast(context, "Share error");
            }
        });
    }

    public static <T extends View> void loadImage(GlideRequest<File> request, T imageView,
            Callback<File> callback) {
        request.addListener(new RequestListener<File>() {

            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<File> target,
                    boolean isFirstResource) {
                if (callback != null) {
                    callback.onPostExecute();
                    callback.onError(e);
                }
                return false;
            }

            @Override
            public boolean onResourceReady(File resource, Object model, Target<File> target,
                    DataSource dataSource,
                    boolean isFirstResource) {
                return false;
            }
        }).into(new CustomViewTarget<T, File>(imageView) {
            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
            }

            @Override
            public void onResourceReady(@NonNull File resource,
                    @Nullable Transition<? super File> transition) {
                if (callback != null) {
                    callback.onPostExecute();
                    callback.onSuccess(resource);
                }
            }

            @Override
            protected void onResourceCleared(@Nullable Drawable placeholder) {
            }

            @Override
            public void onResourceLoading(Drawable placeholder) {
                super.onResourceLoading(placeholder);
                if (callback != null) {
                    callback.onPreExecute();
                }
            }
        });
    }

    //https://github.com/halibobo/WaterMark
    public static Bitmap waterMark(Context context, Bitmap bitmap, String str) {
        int destWidth = bitmap.getWidth();
        int destHeight = bitmap.getHeight();
        Bitmap icon = Bitmap.createBitmap(destWidth, destHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(icon);

        Paint photoPaint = new Paint();
        photoPaint.setDither(true);
        photoPaint.setFilterBitmap(true);

        Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        Rect dst = new Rect(0, 0, destWidth, destHeight);
        canvas.drawBitmap(bitmap, src, dst, photoPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
        textPaint.setTextSize(DisplayUtils.dp2px(context, 9));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setAntiAlias(true);
        textPaint.setStrokeWidth(1);
        textPaint.setAlpha(120);
        textPaint.setColor(Color.WHITE);

        Rect bounds = new Rect();
        textPaint.getTextBounds(str, 0, str.length(), bounds);

        canvas.drawText(str, destWidth - bounds.width() - 20, destHeight - bounds.height() / 2F - 5, textPaint);
        canvas.save();
        canvas.restore();
        return icon;
    }

    @NonNull
    static Bitmap toStackBlur2(Bitmap original, int radius) {
        return NativeStackBlur.process(original, radius);
    }

    public static void drawSurfaceHolder(SurfaceHolder holder, Consumer<Canvas> callback) {
        if (!holder.getSurface().isValid()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas != null) {
                callback.accept(canvas);
            }
        } catch (Throwable ignored) {
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void drawText(Canvas canvas, String text, int textSize, int width, int height) {
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
        textPaint.setTextSize(textSize);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setAntiAlias(true);
        textPaint.setStrokeWidth(1);
        textPaint.setAlpha(120);
        textPaint.setColor(Color.WHITE);

        canvas.drawText(text, width / 2F - textPaint.measureText(text) / 2, height / 2F, textPaint);
    }

}

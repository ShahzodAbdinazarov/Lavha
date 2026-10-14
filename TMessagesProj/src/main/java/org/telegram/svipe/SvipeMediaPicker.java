package org.telegram.svipe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Pair;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * The Android photo picker in place of the in-app gallery, for the build that ships without the
 * broad media permissions.
 *
 * Play's Photo and Video Permissions policy allows READ_MEDIA_IMAGES / READ_MEDIA_VIDEO only to apps
 * whose core function is managing the user's whole library (gallery apps). A messenger has to pick
 * through the system picker, so the Play bundle's manifest drops both permissions. Telegram's own
 * screens then only ever see "no access" and would ask for a permission that is not declared, which
 * Android denies without showing anything. Every such place asks {@link #systemOnly()} first and
 * opens the picker instead.
 *
 * The answer comes from the merged manifest, not from a build flag, so the .web build (which still
 * declares both permissions and keeps the in-app gallery) is untouched, and a Play build can never
 * end up asking for a permission it does not declare.
 */
public final class SvipeMediaPicker {

    public static final int REQUEST = 7301;

    private static Boolean declared;
    private static Utilities.Callback<ArrayList<Uri>> pending;

    private SvipeMediaPicker() {
    }

    /** True when the gallery is reachable only through the system picker. */
    public static boolean systemOnly() {
        return Build.VERSION.SDK_INT >= 33 && !declaresMediaPermissions();
    }

    private static boolean declaresMediaPermissions() {
        if (declared == null) {
            boolean found = false;
            try {
                Context context = ApplicationLoader.applicationContext;
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
                if (info.requestedPermissions != null) {
                    for (String permission : info.requestedPermissions) {
                        if (Manifest.permission.READ_MEDIA_IMAGES.equals(permission)) {
                            found = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            declared = found;
        }
        return declared;
    }

    /**
     * Opens the system picker. {@code max} is the number of items the caller can take (0 = no
     * limit); {@code done} gets the picked items, never an empty list.
     */
    public static boolean open(Activity activity, boolean allowVideo, int max, Utilities.Callback<ArrayList<Uri>> done) {
        if (activity == null || Build.VERSION.SDK_INT < 33) {
            return false;
        }
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        if (!allowVideo) {
            intent.setType("image/*");
        }
        int limit = MediaStore.getPickImagesMaxLimit();
        if (max <= 0 || max > limit) {
            max = limit;
        }
        if (max > 1) {
            intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
        }
        try {
            pending = done;
            activity.startActivityForResult(intent, REQUEST);
            return true;
        } catch (Exception e) {
            pending = null;
            FileLog.e(e);
            return false;
        }
    }

    /**
     * A one-image picker intent, for screens that already handle an ACTION_PICK result themselves
     * (wallpaper, QR from gallery): they keep their request code and their own result handling.
     */
    public static Intent imageIntent() {
        return new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
    }

    /** Called from LaunchActivity.onActivityResult; true when the result was ours. */
    public static boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST) {
            return false;
        }
        Utilities.Callback<ArrayList<Uri>> done = pending;
        pending = null;
        if (done == null || resultCode != Activity.RESULT_OK || data == null) {
            return true;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) {
            done.run(uris);
        }
        return true;
    }

    /**
     * Copies the picked items into the cache and describes them the way the attach screen describes
     * a photo taken with the camera, so they go through the same editor and send path. Runs the copy
     * off the UI thread; {@code done} is called on the UI thread with whatever could be read.
     */
    public static void toPhotoEntries(ArrayList<Uri> uris, Utilities.Callback<ArrayList<MediaController.PhotoEntry>> done) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<MediaController.PhotoEntry> entries = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    MediaController.PhotoEntry entry = toPhotoEntry(uri);
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            AndroidUtilities.runOnUIThread(() -> done.run(entries));
        });
    }

    private static MediaController.PhotoEntry toPhotoEntry(Uri uri) {
        String mime = ApplicationLoader.applicationContext.getContentResolver().getType(uri);
        boolean video = mime != null && mime.startsWith("video/");
        String ext = mime != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
        if (ext == null) {
            ext = video ? "mp4" : "jpg";
        }
        String path = MediaController.copyFileToCache(uri, ext);
        if (path == null) {
            return null;
        }
        MediaController.PhotoEntry entry;
        if (video) {
            long duration = 0;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(path);
                String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    duration = (long) Math.ceil(Long.parseLong(d) / 1000.0f);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignore) {
                }
            }
            Bitmap bitmap = SendMessagesHelper.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);
            int width = 0, height = 0;
            String thumbPath = null;
            if (bitmap != null) {
                width = bitmap.getWidth();
                height = bitmap.getHeight();
                File thumb = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), Integer.MIN_VALUE + "_" + SharedConfig.getLastLocalId() + ".jpg");
                try (FileOutputStream stream = new FileOutputStream(thumb)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 55, stream);
                    thumbPath = thumb.getAbsolutePath();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                SharedConfig.saveConfig();
            }
            entry = new MediaController.PhotoEntry(0, 0, 0, path, 0, true, width, height, 0);
            entry.duration = (int) duration;
            entry.thumbPath = thumbPath;
        } else {
            Pair<Integer, Integer> orientation = AndroidUtilities.getImageOrientation(path);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            entry = new MediaController.PhotoEntry(0, 0, 0, path, orientation.first, false, options.outWidth, options.outHeight, 0).setOrientation(orientation);
        }
        entry.canDeleteAfter = true;
        return entry;
    }
}

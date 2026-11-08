package com.example.onwin.data.audio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

/**
 * {@code content://} провайдер, который ведёт себя как чужие приложения. Файлы — из {@code assets/audio} тестового APK.
 * <ul>
 *   <li>{@code content://AUTHORITY/file/<имя>} — дескриптор настоящего файла, как у androidx FileProvider
 *       (через него делится файлами Telegram);</li>
 *   <li>{@code content://AUTHORITY/pipe/<имя>} — pipe без перемотки, как у стриминговых провайдеров
 *       (облачные хранилища могут так отдавать ещё не скачанные файлы);</li>
 *   <li>другие пути и несуществующие имена — {@link FileNotFoundException}.</li>
 * </ul>
 * <p>
 * Написан на Java намеренно: провайдер работает в процессе тестового APK, а kotlin-stdlib туда не попадает
 * (она уже есть в APK приложения), и Kotlin-код здесь падает с {@code NoClassDefFoundError}.
 */
public class TestAudioProvider extends ContentProvider {

    /** Authority из androidTest/AndroidManifest.xml. */
    public static final String AUTHORITY = "io.github.god4claw.onwin.test.audio";

    /** URI файла {@code name} из {@code assets/audio} в режиме {@code mode} ({@code file} или {@code pipe}). */
    @NonNull
    public static Uri uri(@NonNull String mode, @NonNull String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + mode + "/" + name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) throw new FileNotFoundException(uri.toString());
        String name = segments.get(1);
        try {
            switch (segments.get(0)) {
                case "file":
                    return ParcelFileDescriptor.open(assetFile(name), ParcelFileDescriptor.MODE_READ_ONLY);
                case "pipe":
                    return pipe(name);
                default:
                    throw new FileNotFoundException(uri.toString());
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            // Через Binder провайдер может сообщить только FileNotFoundException.
            FileNotFoundException error = new FileNotFoundException(uri.toString());
            error.initCause(e);
            throw error;
        }
    }

    private synchronized File assetFile(String name) throws IOException {
        Context context = Objects.requireNonNull(getContext());
        File file = new File(context.getCacheDir(), name);
        if (!file.exists()) {
            File temp = new File(context.getCacheDir(), name + ".tmp");
            try (InputStream input = context.getAssets().open("audio/" + name);
                 OutputStream output = new FileOutputStream(temp)) {
                copy(input, output);
            }
            // Если переименовать не вышло, ParcelFileDescriptor.open бросит FileNotFoundException.
            temp.renameTo(file);
        }
        return file;
    }

    private ParcelFileDescriptor pipe(String name) throws IOException {
        InputStream input = Objects.requireNonNull(getContext()).getAssets().open("audio/" + name);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        new Thread(() -> {
            try (InputStream source = input;
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                copy(source, output);
            } catch (IOException e) {
                // Читатель закрыл pipe, не дочитав, — для теста это ожидаемо.
            }
        }, "TestAudioProvider-pipe").start();
        return pipe[0];
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues values,
            @Nullable String selection,
            @Nullable String[] selectionArgs) {
        return 0;
    }
}

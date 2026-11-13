package com.desertkun.brainout.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands log files to other apps (a messenger, mail) for the share sheet.
 * Android does not let an app pass a plain file path to another app, so the
 * file is served through this provider; it only serves files from the
 * cache/logs folder and only to the app the player picked (a one-off grant).
 */
public class LogFileProvider extends ContentProvider
{
    static String authority(Context context)
    {
        return context.getPackageName() + ".logs";
    }

    static File dir(Context context)
    {
        File dir = new File(context.getCacheDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    static Uri uriFor(Context context, File file)
    {
        return new Uri.Builder().scheme("content").authority(authority(context))
            .appendPath(file.getName()).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException
    {
        Context context = getContext();
        String name = uri.getLastPathSegment();

        if (context == null || name == null)
            throw new FileNotFoundException(String.valueOf(uri));

        try
        {
            File dir = dir(context).getCanonicalFile();
            File file = new File(dir, name).getCanonicalFile();

            if (!dir.equals(file.getParentFile()) || !file.isFile())
                throw new FileNotFoundException(String.valueOf(uri));

            return file;
        }
        catch (IOException e)
        {
            throw new FileNotFoundException(String.valueOf(uri));
        }
    }

    @Override
    public boolean onCreate()
    {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException
    {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
    {
        // messengers ask for the name and size to show the attachment
        File file;
        try
        {
            file = fileFor(uri);
        }
        catch (FileNotFoundException e)
        {
            return null;
        }

        String[] columns = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri)
    {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values)
    {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs)
    {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs)
    {
        return 0;
    }
}

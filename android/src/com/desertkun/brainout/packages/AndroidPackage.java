package com.desertkun.brainout.packages;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.desertkun.brainout.BrainOut;
import com.desertkun.brainout.utils.FileCopy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class AndroidPackage extends ClientContentPackage
{
    /** Folder in the app cache that holds sounds extracted from packages. */
    public static final String AUDIO_CACHE = "audio";

    public AndroidPackage(String name) throws ContentPackage.ValidationException
    {
        super(name);
    }

    public class AndroidFileHandle extends PackageFileHandle
    {
        private final String entryName;

        public AndroidFileHandle(String path)
        {
            super(path);

            this.entryName = path;
        }

        @Override
        public FileHandle handle()
        {
            // The extracted copy is named after the package, its checksum and
            // the entry, so the next load (every lobby/match switch) reuses it
            // instead of writing yet another temp file. An APK update clears
            // the folder (AndroidEnvironment).
            File dir = new File(BrainOut.Env.getCacheDir(), AUDIO_CACHE);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

            File f = new File(dir, AndroidPackage.this.getName() + "-" + Long.toHexString(AndroidPackage.this.crc32) + "-"
                + entryName.replaceAll("[^A-Za-z0-9._-]", "_"));

            if (!f.exists())
            {
                File part = new File(dir, f.getName() + "." + Thread.currentThread().getId() + ".part");

                // The abstract PackageFileHandle has no read(); pull the entry's
                // bytes through the normal zip handle and copy them out so Android
                // can play the extracted file.
                try (InputStream in = AndroidPackage.super.getFile(entryName).read())
                {
                    FileCopy.copyFile(in, part);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    return null;
                }

                if (!part.renameTo(f))
                {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();

                    if (!f.exists())
                        return null;
                }
            }

            return Gdx.files.absolute(f.getAbsolutePath());
        }
    }

    @Override
    public PackageFileHandle getFile(String fileName)
    {
        if (isExtractedAudio(fileName))
        {
            // The Android audio backend (SoundPool/MediaPlayer) needs a real file
            // — it can't decode a sound straight from the in-zip stream — so
            // extract audio assets to a temp file first.
            return new AndroidFileHandle(fileName);
        }

        return super.getFile(fileName);
    }

    private static boolean isExtractedAudio(String fileName)
    {
        return fileName.endsWith(".mp3")
            || fileName.endsWith(".wav")
            || fileName.endsWith(".ogg");
    }
}

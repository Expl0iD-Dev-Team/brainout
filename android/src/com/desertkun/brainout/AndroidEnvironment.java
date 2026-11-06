package com.desertkun.brainout;

import android.content.Context;
import android.provider.Settings;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.desertkun.brainout.controllers.GameController;
import com.desertkun.brainout.online.KryoNetworkClient;
import com.desertkun.brainout.online.NetworkClient;
import com.desertkun.brainout.online.NetworkConnectionListener;
import com.desertkun.brainout.packages.ZipContentPackage;
import com.desertkun.brainout.reflection.Reflection;
import com.desertkun.brainout.utils.FileCopy;
import com.esotericsoftware.kryo.Kryo;

import java.io.File;
import java.io.IOException;

public class AndroidEnvironment extends ClientEnvironment
{
    private final Context context;
    private final AndroidGameController androidGameController;

    public AndroidEnvironment(Context context)
    {
        this.context = context;
        this.androidGameController = new AndroidGameController();
    }

    @Override
    public String getUniqueId()
    {
        // Stable per-install device identifier; replaces the old reflection into
        // the hidden android.os.SystemProperties API (which no longer works).
        String id = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ANDROID_ID);

        return id != null ? id : "";
    }

    @Override
    public GameUser newUser()
    {
        return new GameUser();
    }

    @Override
    public String getStoreName()
    {
        // Same catalog the Steam build uses. Without a name the client asks the
        // store service for nothing and the store menu stays empty.
        return "main";
    }

    @Override
    public float getUIScale()
    {
        // The interface was laid out for desktop screens around 720 points tall.
        // A phone in landscape is ~1080 px tall at ~3x density, which made every
        // menu, label and the character itself tiny; scale so the short side is
        // about 720 UI units again.
        if (Gdx.graphics == null)
            return 1.0f;

        int shortSide = Math.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        return Math.max(1.0f, shortSide / AndroidConstants.UI_HEIGHT);
    }

    @Override
    public void prepareTextField(com.badlogic.gdx.scenes.scene2d.ui.TextField field)
    {
        AndroidTextInput.attach(field);
    }

    @Override
    public boolean greenlightEnabled()
    {
        // The lobby otherwise nags with "Get this game on Steam!" and a link to
        // the original game's store page, which means nothing for this build.
        return false;
    }

    @Override
    public String getStoreComponent()
    {
        // No real payment provider is wired up on Android yet, so IAP tiers can't
        // be charged. Items priced with "offline-price" (in-game currency) are
        // bought through purchaseOffline(), which doesn't need a component.
        return null;
    }

    @Override
    public Reflection getReflection()
    {
        // Android can't scan a JVM class path; AndroidReflection scans the bundled
        // class jar instead (see bundleReflectClasses + AndroidReflection).
        try
        {
            return new AndroidReflection(context);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to prepare reflection class jar", e);
        }
    }

    @Override
    public String getExternalPath(String from)
    {
        // App-private internal storage: writable without any runtime permission
        // and not broken by scoped storage on Android 10+. Must share the base
        // that Gdx.files.local uses on Android (the app files dir) so that
        // package unpacking and PackageManager.searchPackages() — which scans
        // Gdx.files.local("packages") — resolve to the same location.
        return new File(context.getFilesDir(), from).getAbsolutePath();
    }

    @Override
    public File getFile(String path)
    {
        return new File(path);
    }

    @Override
    public File getCacheDir()
    {
        return context.getCacheDir();
    }

    @Override
    public void init()
    {
        super.init();

        unpackBundledPackages();
    }

    /**
     * Data packages ship read-only inside the APK assets. {@code searchPackages()}
     * scans {@code Gdx.files.local("packages")} and {@link ZipContentPackage}
     * opens packages as real {@link File}s, so the bundled packages are copied
     * into writable app storage.
     *
     * They are copied again whenever this APK was installed or updated after the
     * last copy: an update brings new data, and keeping the old copies made new
     * text and content silently not show up. Each package is written to a
     * temporary file and renamed, so an interrupted copy never leaves a broken
     * package behind.
     */
    private void unpackBundledPackages()
    {
        File localPackages = new File(context.getFilesDir(), "packages");
        localPackages.mkdirs();

        File stamp = new File(localPackages, ".bundled-from");
        String install = installStamp();
        boolean fresh = !install.equals(readStamp(stamp));

        FileHandle assetPackages = Gdx.files.internal("packages");

        if (assetPackages.exists())
        {
            for (FileHandle pkg : assetPackages.list())
            {
                if (!pkg.name().endsWith(".zip"))
                    continue;

                File dst = new File(localPackages, pkg.name());

                if (dst.exists() && !fresh)
                    continue;

                File part = new File(localPackages, pkg.name() + ".part");

                try
                {
                    FileCopy.copyFile(pkg.read(), part);

                    if (dst.exists() && !dst.delete())
                        throw new IOException("Cannot replace " + dst);
                    if (!part.renameTo(dst))
                        throw new IOException("Cannot rename " + part + " to " + dst);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    part.delete();
                    // leave the stamp alone so the next launch tries again
                    install = null;
                }
            }
        }

        if (install != null)
        {
            writeStamp(stamp, install);
        }

        File mainMenu = ZipContentPackage.packageFile(ClientConstants.Client.MAINMENU_PACKAGE);

        if (!mainMenu.exists())
        {
            throw new RuntimeException("Mainmenu package is missing from the APK assets.");
        }
    }

    /** Identifies this installation of the APK: changes on every install and update. */
    private String installStamp()
    {
        try
        {
            android.content.pm.PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime + "/" + info.versionName;
        }
        catch (android.content.pm.PackageManager.NameNotFoundException e)
        {
            return "unknown";
        }
    }

    private static String readStamp(File file)
    {
        try
        {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8").trim();
        }
        catch (IOException e)
        {
            return "";
        }
    }

    private static void writeStamp(File file, String value)
    {
        try
        {
            java.nio.file.Files.write(file.toPath(), value.getBytes("UTF-8"));
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public GameController getGameController()
    {
        return androidGameController;
    }

    @Override
    public NetworkClient newNetworkClient(Kryo kryo, NetworkConnectionListener listener)
    {
        return new KryoNetworkClient(kryo, listener);
    }
}

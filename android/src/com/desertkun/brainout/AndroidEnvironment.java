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
     * opens packages as real {@link File}s, so any bundled package that is not yet
     * present in writable app storage is copied there once (on first run).
     */
    private void unpackBundledPackages()
    {
        File localPackages = new File(context.getFilesDir(), "packages");
        localPackages.mkdirs();

        FileHandle assetPackages = Gdx.files.internal("packages");

        if (assetPackages.exists())
        {
            for (FileHandle pkg : assetPackages.list())
            {
                if (!pkg.name().endsWith(".zip"))
                    continue;

                File dst = new File(localPackages, pkg.name());

                if (dst.exists())
                    continue;

                try
                {
                    FileCopy.copyFile(pkg.read(), dst);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        File mainMenu = ZipContentPackage.packageFile(ClientConstants.Client.MAINMENU_PACKAGE);

        if (!mainMenu.exists())
        {
            throw new RuntimeException("Mainmenu package is missing from the APK assets.");
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

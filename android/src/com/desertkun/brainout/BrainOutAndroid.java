package com.desertkun.brainout;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.desertkun.brainout.client.settings.ClientSettings;
import com.desertkun.brainout.packages.AndroidPackageManager;
import com.desertkun.brainout.packages.PackageManager;

public class BrainOutAndroid extends BrainOutClient
{
    public BrainOutAndroid(AndroidEnvironment env, ClientSettings clientSettings)
    {
        super(env, clientSettings);
    }

    public static BrainOutAndroid initAndroidInstance(AndroidEnvironment env,
          ClientSettings clientSettings)
    {
        instance = new BrainOutAndroid(env, clientSettings);
        return (BrainOutAndroid)instance;
    }

    @Override
    public void create()
    {
        super.create();

        // Back must reach the game (it closes menus, opens the pause menu in a
        // match) instead of finishing the activity and quitting the game.
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
    }

    @Override
    public PackageManager getPackageManager(int threads)
    {
        return new AndroidPackageManager(threads);
    }
}

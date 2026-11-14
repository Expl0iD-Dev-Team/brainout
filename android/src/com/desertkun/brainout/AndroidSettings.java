package com.desertkun.brainout;

import android.content.Context;
import android.util.DisplayMetrics;

import com.badlogic.gdx.Graphics;
import com.desertkun.brainout.client.settings.ClientSettings;

public class AndroidSettings extends ClientSettings
{
    private final Context context;

    public AndroidSettings(ClientEnvironment environment, Context context)
    {
        super(environment);

        this.context = context;
    }

    @Override
    public Graphics.DisplayMode getDefaultDisplayMode()
    {
        // Built before the GL surface exists, so query the device metrics
        // directly instead of going through Gdx.graphics (which is still null).
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();

        return new AndroidDisplayMode(metrics.widthPixels, metrics.heightPixels, 60, 16);
    }

    @Override
    public Graphics.DisplayMode[] getDisplayModes()
    {
        return new Graphics.DisplayMode[]{ getDefaultDisplayMode() };
    }
}

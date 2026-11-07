package com.desertkun.brainout;

import com.badlogic.gdx.Graphics;

/**
 * Concrete {@link com.badlogic.gdx.Graphics.DisplayMode} for Android.
 *
 * libGDX makes the {@code DisplayMode} constructor {@code protected} and only
 * exposes real instances through a running {@code Graphics} backend. On Android
 * the settings are built before the GL surface exists, so we need to fabricate a
 * display mode from the device metrics — a subclass can legally call the
 * protected super constructor.
 */
public class AndroidDisplayMode extends Graphics.DisplayMode
{
    public AndroidDisplayMode(int width, int height, int refreshRate, int bitsPerPixel)
    {
        super(width, height, refreshRate, bitsPerPixel);
    }
}

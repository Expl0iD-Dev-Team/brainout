package com.desertkun.brainout;

import android.content.Context;

import com.desertkun.brainout.reflection.Reflection;
import com.desertkun.brainout.utils.FileCopy;
import eu.infomas.annotation.AnnotationDetector;
import eu.infomas.annotation.Builder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Android reflection registry.
 *
 * The annotation scanner walks {@code .class} files on the JVM class path, but an
 * APK ships its code as a DEX, not as scannable class files — so on Android the
 * {@code com/desertkun} classes are bundled as an asset jar (produced by the
 * {@code bundleReflectClasses} Gradle task) and scanned from there. Only the
 * annotation metadata is read from the jar; the actual classes are resolved by
 * the normal DEX class loader at {@code Class.forName} time.
 *
 * This runs from the {@link BrainOut} constructor, before the GL surface and
 * {@code Gdx.files} exist, so the asset is read straight through the Android
 * {@code AssetManager}.
 */
public class AndroidReflection extends Reflection
{
    private static final String CLASSES_ASSET = "reflect/desertkun-classes.jar";

    private final File classesJar;

    public AndroidReflection(Context context) throws IOException
    {
        this.classesJar = new File(context.getCacheDir(), "desertkun-classes.jar");

        try (InputStream in = context.getAssets().open(CLASSES_ASSET))
        {
            FileCopy.copyFile(in, classesJar);
        }
    }

    @Override
    protected Builder scan() throws IOException
    {
        return AnnotationDetector.scanFiles(classesJar);
    }
}

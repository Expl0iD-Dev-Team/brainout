package com.desertkun.brainout.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Keeps a report of the last crash so a player can send it to whoever runs
 * the server. The report is written when an uncaught exception kills the game
 * and offered through the Android share sheet on the next launch.
 */
public final class CrashLog
{
    private static final String FILE = "last-crash.txt";
    private static final int LOG_LINES = 200;

    private static Context appContext;

    private CrashLog() {}

    /** Installs the handler; call first thing in onCreate. */
    public static void install(Context context)
    {
        appContext = context.getApplicationContext();
        reinstall();
    }

    /**
     * The game installs its own default handler while it starts
     * (BrainOut.init), so the report handler is put on top of it again once
     * the game has been created.
     */
    public static void reinstall()
    {
        final Context app = appContext;
        if (app == null)
            return;

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
        {
            try
            {
                write(app, thread, error);
            }
            catch (Throwable ignored)
            {
                // never let the report hide the original crash
            }

            if (previous != null)
            {
                previous.uncaughtException(thread, error);
            }
            else
            {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    private static File file(Context context)
    {
        return new File(context.getFilesDir(), FILE);
    }

    private static void write(Context context, Thread thread, Throwable error) throws IOException
    {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));

        File part = new File(context.getFilesDir(), FILE + ".part");

        try (Writer out = new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8))
        {
            out.write("BrainOut crash report\n");
            out.write("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()) + "\n");
            out.write("app: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + (BuildConfig.ONLINE_ENABLED ? " online " + BuildConfig.ENV_SERVICE : " offline") + "\n");
            out.write("device: " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            out.write("thread: " + thread.getName() + "\n\n");
            out.write(trace.toString());
            out.write("\n--- game log (last " + LOG_LINES + " lines) ---\n");
            out.write(recentLog());
        }

        File dst = file(context);
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(dst);
    }

    /** This process's own logcat lines; an app may read its own log. */
    private static String recentLog()
    {
        Deque<String> lines = new ArrayDeque<>();

        try
        {
            java.lang.Process logcat = Runtime.getRuntime().exec(new String[]{
                "logcat", "-d", "-v", "time", "--pid=" + Process.myPid(), "*:I"});

            try (BufferedReader in = new BufferedReader(
                new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = in.readLine()) != null)
                {
                    lines.addLast(line);
                    if (lines.size() > LOG_LINES)
                        lines.removeFirst();
                }
            }
        }
        catch (IOException e)
        {
            return "(log unavailable: " + e + ")\n";
        }

        StringBuilder sb = new StringBuilder();
        for (String line : lines)
            sb.append(line).append('\n');
        return sb.toString();
    }

    /**
     * If the previous run crashed, asks whether to send the report and opens
     * the share sheet. The report is removed either way, so it is offered once.
     */
    public static void offerLastReport(final Activity activity)
    {
        final File report = file(activity);

        if (!report.exists())
            return;

        final String text;

        try
        {
            text = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            //noinspection ResultOfMethodCallIgnored
            report.delete();
            return;
        }

        //noinspection ResultOfMethodCallIgnored
        report.delete();

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_message)
            .setPositiveButton(R.string.crash_send, (dialog, which) ->
            {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "BrainOut crash report");
                send.putExtra(Intent.EXTRA_TEXT, text);

                try
                {
                    activity.startActivity(Intent.createChooser(send,
                        activity.getString(R.string.crash_send)));
                }
                catch (android.content.ActivityNotFoundException ignored)
                {
                    // nothing on the phone can share text; drop the report
                }
            })
            .setNegativeButton(R.string.crash_skip, null)
            .show();
    }
}

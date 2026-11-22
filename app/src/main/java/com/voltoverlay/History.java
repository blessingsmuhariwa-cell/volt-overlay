package com.voltoverlay;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;

/** One battery sample per minute for the last 24 hours, kept in memory and saved to a small file. */
final class History {
    private History() { }

    static final int CAP = 1440;
    static final long[] time = new long[CAP];        // wall clock, ms
    static final float[] pct = new float[CAP];
    static final float[] volt = new float[CAP];
    static final boolean[] plug = new boolean[CAP];
    static int count = 0;
    static int head = 0;                             // next slot to write
    private static boolean loaded = false;

    static void add(long t, float p, float v, boolean pl) {
        time[head] = t;
        pct[head] = p;
        volt[head] = v;
        plug[head] = pl;
        head = (head + 1) % CAP;
        if (count < CAP) count++;
    }

    /** i = 0 is the oldest stored sample. */
    static int index(int i) {
        return ((head - count + i) % CAP + CAP) % CAP;
    }

    static void load(File f) {
        if (loaded) return;
        loaded = true;
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length != 4) continue;
                add(Long.parseLong(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), p[3].equals("1"));
            }
        } catch (Exception ignored) {
            // a damaged history file only costs the graph
        }
    }

    static void save(File f) {
        File tmp = new File(f.getPath() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
            for (int i = 0; i < count; i++) {
                int k = index(i);
                w.write(time[k] + "," + String.format(Locale.US, "%.1f,%.3f", pct[k], volt[k])
                        + "," + (plug[k] ? "1" : "0"));
                w.newLine();
            }
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }
}

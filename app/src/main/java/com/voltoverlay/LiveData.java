package com.voltoverlay;

/** Numbers the overlay service publishes once per second and the dashboard reads. Both run on the main thread. */
final class LiveData {
    private LiveData() { }

    static boolean running = false;
    static long updatedMs = 0;              // SystemClock.elapsedRealtime() of the last update

    static double volts = Double.NaN;
    static double currentMa = Double.NaN;   // + into the battery, - out of it
    static double tempC = Double.NaN;
    static int pct = -1;
    static boolean plugged = false;
    static boolean charging = false;
    static boolean full = false;
    static int plugType = 0;                // BatteryManager.BATTERY_PLUGGED_*

    static long sessionStartWall = 0;       // when this on-battery or charging stretch began
    static long screenOnMs = 0;             // screen-on time during this stretch
    static int sessionStartPct = -1;
    static double avgDrainMa = Double.NaN;  // blended average drain used for the time estimates

    static double hoursTo0 = Double.NaN;
    static double hoursTo15 = Double.NaN;
    static double hoursToFull = Double.NaN;

    static double capacityMah = 0;
    static int learnedCycles = 0;
    static double equivCycles = 0;
    static double remainingMah = 0;
    static double emptyV = 0;
}

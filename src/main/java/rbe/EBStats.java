/*-------------------------------------------------------------------------
 * rbe.EBStats.java
 * Timothy Heil
 * 10/19/99
 *
 * ECE902 Fall '99
 *
 * Collects statistics for TPC-W.
 *------------------------------------------------------------------------
 *
 * This is part of the the Java TPC-W distribution,
 * written by Harold Cain, Tim Heil, Milo Martin, Eric Weglarz, and Todd
 * Bezenek.  University of Wisconsin - Madison, Computer Sciences
 * Dept. and Dept. of Electrical and Computer Engineering, as a part of
 * Prof. Mikko Lipasti's Fall 1999 ECE 902 course.
 *
 * Copyright (C) 1999, 2000 by Harold Cain, Timothy Heil, Milo Martin, 
 *                             Eric Weglarz, Todd Bezenek.
 *
 * This source code is distributed "as is" in the hope that it will be
 * useful.  It comes with no warranty, and no author or distributor
 * accepts any responsibility for the consequences of its use.
 *
 * Everyone is granted permission to copy, modify and redistribute
 * this code under the following conditions:
 *
 * This code is distributed for non-commercial use only.
 * Please contact the maintainer for restrictions applying to 
 * commercial use of these tools.
 *
 * Permission is granted to anyone to make or distribute copies
 * of this code, either as received or modified, in any
 * medium, provided that all copyright notices, permission and
 * nonwarranty notices are preserved, and that the distributor
 * grants the recipient permission for further redistribution as
 * permitted by this document.
 *
 * Permission is granted to distribute this code in compiled
 * or executable form under the same conditions that apply for
 * source code, provided that either:
 *
 * A. it is accompanied by the corresponding machine-readable
 *    source code,
 * B. it is accompanied by a written offer, with no time limit,
 *    to give anyone a machine-readable copy of the corresponding
 *    source code in return for reimbursement of the cost of
 *    distribution.  This written offer must permit verbatim
 *    duplication by anyone, or
 * C. it is distributed by someone who received only the
 *    executable form, and is accompanied by a copy of the
 *    written offer of source code that they received concurrently.
 *
 * In other words, you are welcome to use, share and improve this codes.
 * You are forbidden to forbid anyone else to use, share and improve what
 * you give them.
 *
 ************************************************************************/

package rbe;

import java.io.PrintStream;
import java.util.Vector;

import com.codahale.metrics.Meter;
import rbe.util.Histogram;
import rbe.util.Pad;

public class EBStats {

    public  static int maxError = 0;
    public  static int errorCnt = 0;
    private static boolean DEBUG = false;

    private final Histogram [] wirt;     // WIRT (See TPC-W Spec.)
    private final Histogram tt;          // Think Time.
    private final int [][] trans;
    private final long [] start_times;
    private final long [] end_times;
    private int num_interactions = 0;
    private RBE rbe;
    private final int NUM_INTERACTIONS = 10000000;

    // List of retries/errors encount.
    public final Vector errors = new Vector(0);

    // web-interaction throughput over time.
    //  Sampled continuously at 1 second intervals for upto two hours and 30 minutes.
    public final int [] through = new int[9000];

    //PEDRO -- stats about memory and cpu usage (see comment above)
    public final float[] memoryHeap = new float[9000];
    public final float[] memoryNonHeap = new float[9000];
    public final float[] cpu = new float[9000];


    // The following four times are absolute times (UTC), millis.
    //  They are corrected for the slow-down factor (rbe.slowDown).

    long start;    // When to start test

    // WIRT and think times are only measured over the measurement interval
    //  (MI)
    long startMI;  // When measurement interval starts.
    long startRD;  // When measurement interval ends.
    long term;     // When to terminate test

    private Meter errorMeter;

    public void setErrorMeter(Meter errorMeter) {
        this.errorMeter = errorMeter;
    }

    // Times are all expressed in milliseconds.
    public EBStats(RBE rbe,
                   int wirt_max, int wirt_binSize,
                   int tt_max, int tt_binSize,
                   int states,
                   long start,    // When to start test.
                   long miWait,   // Length of ramp-up
                   long miDur,    // Duration of measurement interval
                   long rampDown) // Length of ramp-down
    {
        int h;

        this.rbe = rbe;
        this.start =  start;
        startMI = start + rbe.slow(miWait);
        startRD   = startMI + rbe.slow(miDur);
        term    = startRD + rbe.slow(rampDown);

        if (DEBUG) {
            System.out.println("start " + start + " startMI " + startMI +
                    " startRD " + startRD);
        }

        // Force loading of EBError class ???
        new EBError("temp", "dud");

        wirt = new Histogram[15];
        trans = new int[states][states];
        start_times = new long[NUM_INTERACTIONS];
        end_times = new long[NUM_INTERACTIONS];
        for (h=0;h<wirt.length;h++) {
            wirt[h] = new Histogram(wirt_max/wirt_binSize, wirt_binSize);
        }

        tt   = new Histogram(tt_max/tt_binSize, tt_binSize);
    }

    public final synchronized void transition(int cur, int next) {
        if (System.currentTimeMillis() < start) return;
        trans[cur][next]++;
    }

    public synchronized void
    interaction(int state, long wirt_t1, long wirt_t2, long itt, float percentOfUsedHeapMemory,
                float percentOfUsedNonHeapMemory, float cpuUsagePercent)
    {
        int b;  // Throughput bin.
        // Discard interactions that completed before the start
        //  of the ramp-up period.
        if (wirt_t2 < start) return;
        //HWC
        start_times[num_interactions] = wirt_t1;
        end_times[num_interactions] = wirt_t2;
        num_interactions++;
        //end HWC

        // update recorder values only if this interaction occurred within the measuring interval
        if (wirt_t2 < rbe.stats.startRD && wirt_t2 > rbe.stats.startMI){
            rbe.responseTimesHistogram.recordValue(wirt_t2 - wirt_t1);
        }


        b= ((int) (rbe.speed(wirt_t2-start)/1000L));
        if (b<through.length) {
            through[b]++;
            memoryHeap[b] = (memoryHeap[b] + percentOfUsedHeapMemory) / 2;
            memoryNonHeap[b] = (memoryNonHeap[b] + percentOfUsedNonHeapMemory) / 2;
            cpu[b] = (cpu[b] + cpuUsagePercent) / 2;
        }

        if (DEBUG) {
            System.out.println("t2 " + wirt_t2 + " startMI " + startMI +
                    " startRD " + startRD);
            System.out.println("Interact " + ((wirt_t2-start)/1000L) + " b " + b);
        }

        if ((wirt_t2 >= startMI) && (wirt_t2 <= startRD)) {
            if (DEBUG) {
                System.out.println("adding...");
            }

            wirt[state].add((int) rbe.speed((wirt_t2-wirt_t1)));
            tt.add((int) rbe.speed(itt));
        }

    }

    public void error(String message, String url)
    {
        errorMeter.mark();
        EBError error = new EBError(message, url);
        errors.addElement(error);
        System.out.println(error);
        errorCnt++;
        if ((errorCnt >= maxError) && (maxError>0)) {
            System.exit(-1);
        }
    }


    public void print(PrintStream out) {
        int h;

        out.println("function [dat] = tpcw()");

        for (h=0;h<wirt.length;h++) {
            wirt[h].printMFile(out, "dat.wirt{" + (h+1) + "}");
        }

        tt.printMFile(out, "dat.tt");

        int i,j,tot;

        out.println("dat.trans = [");
        for (i=0;i<trans.length;i++) {
            for (j=0;j<trans.length;j++) {
                out.print(" " + Pad.l(6, "" + trans[i][j]));
            }
            out.println();
        }
        out.println("];\n");

        out.print("dat.interact = [");
        for (j=0;j<trans.length;j++) {
            for (i=0,tot=0;i<trans.length;tot=tot + trans[i][j],i++);
            out.print(" " + Pad.l(6, "" + tot));
        }
        out.println("];\n");

        out.println("dat.wips = [");
        for (j=0;j<through.length;j++) {
            out.println(""+through[j]);
        }
        out.println("];\n");

        out.println("dat.starttimes = [");
        for(j=0; j < num_interactions; j++){
            out.println(""+(start_times[j] - start_times[0]));
        }
        out.println("];\n");
        out.println("dat.endtimes = [");
        for(j=0; j < num_interactions; j++)
            out.println(""+(end_times[j] - start_times[0]));
        out.println("];\n");
        out.println("dat.numinteractions = " + num_interactions + ";");
        // Absolute UTC time (millis).
        out.println("dat.startRU =  " + start + ";");

        // Slowdown corrected UTC time (millis).
        out.println("dat.startMI =  " + (start + rbe.speed(startMI-start)) + ";");
        out.println("dat.startRD =  " + (start + rbe.speed(startRD-start)) + ";");
        out.println("dat.term    =  " + (start + rbe.speed(term-start)) + ";");

        // slow down factor.
        out.println("dat.slowDown = " + rbe.slowDown + ";");

        out.println("dat.memoryHeap = [");
        for (j = 0; j < memoryHeap.length; j++) {
            out.println(memoryHeap[j]);
        }
        out.println("];\n");

        out.println("dat.memoryNonHeap = [");
        for (j = 0; j < memoryNonHeap.length; j++) {
            out.println(memoryNonHeap[j]);
        }
        out.println("];\n");

        out.println("dat.cpu = [");
        for (j = 0; j < cpu.length; j++) {
            out.println(cpu[j]);
        }
        out.println("];\n");

        out.println("% Errors");
        for (i=0;i<errors.size();i++) {
            out.println("%" + errors.elementAt(i));
        }
        out.println("% Total Errors: " + errors.size());
    }

    public void waitForRampDown()
    {
        try {
            waitForStart();
            long w= term - System.currentTimeMillis();
            if (w<0) return;
            Thread.currentThread().sleep(w);
        }
        catch (InterruptedException ie) {
            System.out.println("In waitforrampdown, caught interrupted exception");
        }
    }

    public void waitForStart()
            throws InterruptedException
    {
        long w = start - System.currentTimeMillis();
        if (w<0) return;

        Thread.currentThread().sleep(w);
    }

    //PEDRO
    //in wips.m:
    //s = (dat.startMI-dat.startRU)/1000;
    //e = (dat.startRD-dat.startRU)/1000;
    public double getAvgWIPS() {
        return getAvg(through);
    }

    public double getAvgHeapMemoryUsage() {
        return getAvg(memoryHeap);
    }

    public double getAvgNonHeapMemoryUsage() {
        return getAvg(memoryNonHeap);
    }

    public double getAvgCPUUsage() {
        return getAvg(cpu);
    }

    private double getAvg(int[] array) {
        int startIdx = (int) (rbe.speed(startMI-start) / 1000);
        int stopIdx = (int) (rbe.speed(startRD-start) / 1000);

        int count = stopIdx - startIdx + 1;

        if(count > 0) {
            double sum = 0;
            for(int i = startIdx; i <= stopIdx; ++i) {
                sum += array[i];
            }
            return sum / count;
        }
        return -1;
    }

    private double getAvg(float[] array) {
        int startIdx = (int) (rbe.speed(startMI-start) / 1000);
        int stopIdx = (int) (rbe.speed(startRD-start) / 1000);

        int count = stopIdx - startIdx;

        if(count > 0) {
            double sum = 0;
            for(int i = startIdx; i <= stopIdx; ++i) {
                sum += array[i];
            }
            return sum / count;
        }
        return -1;
    }

}

class EBError {
    public String message;
    public String url;

    public EBError(String message, String url) {
        this.message = message;
        this.url = url;
    }

    public String toString() {
        return( "EB Error: " + message + "(" + url + ")");
    }
}

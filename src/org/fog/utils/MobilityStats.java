package org.fog.utils;

import java.util.Map;

/**
 * Utility class to track mobility-related statistics during a simulation.
 *
 * Statistics tracked:
 *  - Average end-to-end latency across completed tasks.
 *  - Deadline satisfaction ratio.
 *  - Fog-to-fog offload count.
 *  - Cloud usage ratio (fraction of offloads that went to the cloud).
 */
public class MobilityStats {

    private static double totalLatency = 0.0;
    private static int completedTasks = 0;

    private static int deadlineSatisfiedCount = 0;

    private static int fogToFogOffloadCount = 0;
    private static int cloudOffloadCount = 0;

    private MobilityStats() {
        // utility class
    }

    /**
     * Record the latency of a completed task and whether it met its deadline.
     *
     * @param latency  end-to-end latency Li of the task
     * @param satisfied true if Li <= Di (deadline met), false otherwise
     */
    public static synchronized void recordLatencyAndDeadline(double latency, boolean satisfied) {
        totalLatency += latency;
        completedTasks++;
        if (satisfied) {
            deadlineSatisfiedCount++;
        }
    }

    /** Increment count when a task is migrated from one fog to another fog. */
    public static synchronized void incrementFogToFogOffload() {
        fogToFogOffloadCount++;
    }

    /** Increment count when a task is offloaded from fog to cloud. */
    public static synchronized void incrementCloudOffload() {
        cloudOffloadCount++;
    }

    /**
     * Average latency = (1 / T) * sum_i Li over all completed tasks.
     *
     * If for some reason no per-task records were added to MobilityStats (completedTasks == 0),
     * we fall back to the loop-level averages maintained in TimeKeeper.
     */
    public static synchronized double getAverageLatency() {
        if (completedTasks > 0) {
            return totalLatency / completedTasks;
        }

        // Fallback: derive global average latency from TimeKeeper loop stats
        TimeKeeper tk = TimeKeeper.getInstance();
        Map<Integer, Double> loopAvg = tk.getLoopIdToCurrentAverage();
        Map<Integer, Integer> loopNum = tk.getLoopIdToCurrentNum();

        double weightedSum = 0.0;
        int totalCount = 0;
        for (Integer loopId : loopAvg.keySet()) {
            double avg = loopAvg.get(loopId);
            int cnt = loopNum.getOrDefault(loopId, 0);
            weightedSum += avg * cnt;
            totalCount += cnt;
        }
        if (totalCount == 0) {
            return 0.0;
        }
        return weightedSum / totalCount;
    }

    /**
     * Deadline satisfaction ratio = (# tasks with Li <= Di) / T.
     *
     * Note: if no explicit per-task records were added, this will remain 0 because
     * TimeKeeper does not track deadline satisfaction directly.
     */
    public static synchronized double getDeadlineSatisfactionRatio() {
        if (completedTasks == 0) {
            return 0.0;
        }
        return (double) deadlineSatisfiedCount / completedTasks;
    }

    /** Number of fog-to-fog offloads during mobility. */
    public static synchronized int getFogToFogOffloadCount() {
        return fogToFogOffloadCount;
    }

    /** Number of fog-to-cloud offloads during mobility. */
    public static synchronized int getCloudOffloadCount() {
        return cloudOffloadCount;
    }

    /** Total number of completed tasks (application loops). */
    public static synchronized int getCompletedTasks() {
        return completedTasks;
    }

    /**
     * Cloud usage ratio = cloud offload count / (fog-to-fog offload count + cloud offload count).
     * Returns 0 if there are no offload events.
     */
    public static synchronized double getCloudUsageRatio() {
        int totalOffloads = fogToFogOffloadCount + cloudOffloadCount;
        if (totalOffloads == 0) {
            return 0.0;
        }
        return (double) cloudOffloadCount / totalOffloads;
    }
}




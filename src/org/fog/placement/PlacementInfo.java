package org.fog.placement;

import java.util.ArrayList;
import java.util.List;

public class PlacementInfo {

    public int currentNode;
    public List<Integer> candidateParents;

    public double cpuLoad;
    public double networkLatency;
    public double priceScore;
    public int migrationsCount;

    public PlacementInfo(int currentNode) {
        this.currentNode = currentNode;
        this.candidateParents = new ArrayList<>();
    }
}

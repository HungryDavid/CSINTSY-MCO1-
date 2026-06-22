package solver;

import java.util.Arrays;
import java.util.Objects;

public class State {
    // 1. Core State Data
    public final int[] boxPositions;
    public final int normalizedPlayerPos; 

    // 2. Cost and Pathing
    public final int gCost; 
    public final String path; 

    // 3. Memory Optimization
    private final int cachedHash;

    public State(int[] boxPositions, int normalizedPlayerPos, int gCost, String path) {
        // We clone the array so external changes don't accidentally mutate this state
        this.boxPositions = boxPositions.clone(); 
        
        // CRITICAL: We MUST sort the boxes. 
        // If Box A is at index 5 and Box B is at 10, it is the exact same state 
        // as Box B at 5 and Box A at 10. Sorting ensures they hash identically.
        Arrays.sort(this.boxPositions);

        this.normalizedPlayerPos = normalizedPlayerPos;
        this.gCost = gCost;
        this.path = path;

        // Pre-compute the hash exactly once during creation. 
        // When checking millions of states in a HashSet, recalculating hashes is a massive bottleneck.
        this.cachedHash = 31 * Objects.hash(this.normalizedPlayerPos) + Arrays.hashCode(this.boxPositions);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof State)) return false;
        State state = (State) o;
        
        // Two states are identical ONLY if the player is in the same accessible "room" 
        // and the boxes are in the exact same positions.
        return normalizedPlayerPos == state.normalizedPlayerPos &&
               Arrays.equals(boxPositions, state.boxPositions);
    }

    @Override
    public int hashCode() {
        return cachedHash; // Instant O(1) return
    }
}
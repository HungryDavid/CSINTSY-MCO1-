package solver;

import java.util.*;

public class SokoBot {

    // --- GLOBAL VARIABLES ---
    private boolean[][] deadTiles;
    private int[][][] trueDistances;
    private int width, height;
    private List<Integer> targets;
    private boolean[] isTargetTile; // NEW: Instant target lookup
    
    // Zero-Allocation BFS Memory
    private int[] reachable;
    private String[] movePaths;
    private int bfsToken = 0;
    private int[] bfsQueue; // Kane's primitive queue

    // Kane's O(1) Instant Lookup Map
    private boolean[] crateMap; 

    // NEW: Zobrist Hashing Table
    private long[][] zobristTable;

    // THE FIX: One array to rule them all. Zero allocations!
    private static final int[] PUSH_DR = {-1, 1, 0, 0};
    private static final int[] PUSH_DC = {0, 0, -1, 1};
    private static final char[] PUSH_CHARS = {'u', 'd', 'l', 'r'};

    class GameState implements Comparable<GameState> {
        int playerR, playerC;
        int normalizedPlayerPos = -1; // Set dynamically during execution!
        List<Integer> cratePositions;
        String path;
        int h; 
        int gCost; 
        int lastPushedPos; 
        long crateHash; // THE UPGRADE: Only hashes the crates!

        public GameState(int pr, int pc, List<Integer> crates, String path, int gCost, int lastPushedPos, long crateHash) {
            this.playerR = pr;
            this.playerC = pc;
            this.cratePositions = new ArrayList<>(crates);
            Collections.sort(this.cratePositions);
            this.path = path;
            this.h = calculateHeuristic(this.cratePositions);
            this.gCost = gCost; 
            this.lastPushedPos = lastPushedPos;
            this.crateHash = crateHash;
        }

        @Override
        public int compareTo(GameState other) {
            int thisScore = this.gCost + (5 * this.h); 
            int otherScore = other.gCost + (5 * other.h);
            if (thisScore == otherScore) {
                return Integer.compare(this.h, other.h); 
            }
            return Integer.compare(thisScore, otherScore);
        }

        @Override
        public int hashCode() {
            // Combine the dynamic normalized player with the static crates!
            long fullHash = crateHash ^ zobristTable[normalizedPlayerPos][0];
            return (int) (fullHash ^ (fullHash >>> 32));
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof GameState)) return false;
            GameState other = (GameState) obj;
            // Two states are identical if their crates match AND their normalized room matches
            return this.crateHash == other.crateHash && 
                   this.normalizedPlayerPos == other.normalizedPlayerPos && 
                   this.cratePositions.equals(other.cratePositions);
        }
    }

    // --- MAIN EXECUTION LOOP ---
    public String solveSokobanPuzzle(int w, int h, char[][] mapData, char[][] itemsData) {
        this.width = w;
        this.height = h;
        long startTime = System.currentTimeMillis();

        int mapSize = width * height;
        reachable = new int[mapSize];
        movePaths = new String[mapSize];
        crateMap = new boolean[mapSize]; // Initialize the instant lookup
        bfsQueue = new int[mapSize];     // Initialize the primitive queue

        // 1. Pre-compute static traps and true distances
        initTargets(mapData);       // MUST GO FIRST! Creates the isTargetTile array.
        initDeadTiles(mapData);     // Now it can safely use the array.
        initTrueDistances(mapData);

        // 2. Find starting positions
        int startPr = 0, startPc = 0;
        List<Integer> startCrates = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (itemsData[r][c] == '@') {
                    startPr = r;
                    startPc = c;
                } else if (itemsData[r][c] == '$') {
                    startCrates.add(r * width + c);
                }
            }
        }

        // 3. UPGRADE: Initialize PriorityQueue for A* Search
        // Initialize Zobrist Table (Put this with your other init functions!)
        initZobristTable();

        PriorityQueue<GameState> queue = new PriorityQueue<>();
        HashSet<GameState> visited = new HashSet<>(); // NOW STORES GameStates directly!

        // Generate the starting hash from scratch (CRATES ONLY)
        long initialCrateHash = 0;
        for (int crate : startCrates) {
            initialCrateHash ^= zobristTable[crate][1];
        }

        GameState initialState = new GameState(startPr, startPc, startCrates, "", 0, -1, initialCrateHash); 
        queue.add(initialState);

        // 4. Execution Search
        while (!queue.isEmpty()) {
            // Safety Switch
            if (System.currentTimeMillis() - startTime > 14000) {
                System.out.println("Time limit reached! Returning best effort.");
                return queue.peek().path; 
            }

            GameState curr = queue.poll();

            // --- THE WIRING: Turn the crates ON in the instant lookup map ---
            for (int i = 0; i < curr.cratePositions.size(); i++) {
                crateMap[curr.cratePositions.get(i)] = true;
            }

            // 1. Map territory AND get the normalized Room ID
            int normalizedPlayerID = runZeroAllocationBFS(curr.playerR, curr.playerC, curr.cratePositions, mapData);

            // 2. Set the normalized ID so the HashSet can do its magic automatically
            curr.normalizedPlayerPos = normalizedPlayerID;
            
            if (visited.contains(curr)) {
                // CLEANUP: Turn crates OFF before skipping!
                for (int i = 0; i < curr.cratePositions.size(); i++) {
                    crateMap[curr.cratePositions.get(i)] = false;
                }
                continue; 
            }
            visited.add(curr);

            // Check if we won
            if (curr.h == 0) { 
                return curr.path; 
            }

            // 3. Iterate over EVERY crate on the board
            for (int i = 0; i < curr.cratePositions.size(); i++) {
                int cratePos = curr.cratePositions.get(i);
                int cr = cratePos / width;
                int cc = cratePos % width;

                // 4. For each crate, check all 4 possible push directions
                for (int dir = 0; dir < 4; dir++) {
                    
                    // Use the static constants!
                    int pushStandR = cr - PUSH_DR[dir];
                    int pushStandC = cc - PUSH_DC[dir];
                    int pushStandPos = pushStandR * width + pushStandC;

                    if (pushStandPos < 0 || pushStandPos >= (width*height) || reachable[pushStandPos] != bfsToken) continue;

                    int newCrateR = cr + PUSH_DR[dir];
                    int newCrateC = cc + PUSH_DC[dir];
                    int newCratePos = newCrateR * width + newCrateC;

                    // THE UPGRADE: Use instant O(1) array instead of curr.cratePositions.contains()
                    if (mapData[newCrateR][newCrateC] == '#' || crateMap[newCratePos]) {
                        continue; 
                    }

                    List<Integer> nextCrates = new ArrayList<>(curr.cratePositions);
                    nextCrates.remove(Integer.valueOf(cratePos)); 
                    nextCrates.add(newCratePos);

                    // --- THE HIGHWAY SYSTEM (Tunnel Macros) ---
                    int slideR = newCrateR;
                    int slideC = newCrateC;
                    int playerWalkR = cr;
                    int playerWalkC = cc;
                    int slidePushes = 1;
                    String tunnelPath = "" + PUSH_CHARS[dir];

                    while (true) {
                        // Are we boxed into a 1-tile wide hallway?
                        boolean isHorizTunnel = mapData[slideR - 1][slideC] == '#' && mapData[slideR + 1][slideC] == '#';
                        boolean isVertTunnel = mapData[slideR][slideC - 1] == '#' && mapData[slideR][slideC + 1] == '#';
                        
                        // STOP condition 1: We hit a target! Don't slide past it.
                        if (isTargetTile[slideR * width + slideC]) break;
                        
                        // STOP condition 2: We are no longer trapped in a strict highway
                        if ((dir == 0 || dir == 1) && !isVertTunnel) break; // Moving Up/Down needs Left/Right walls
                        if ((dir == 2 || dir == 3) && !isHorizTunnel) break; // Moving Left/Right needs Up/Down walls

                        int nextSlideR = slideR + PUSH_DR[dir];
                        int nextSlideC = slideC + PUSH_DC[dir];
                        int nextPos = nextSlideR * width + nextSlideC;

                        // THE O(1) UPGRADE: Check the crateMap for collisions!
                        // (We ignore cratePos because the box we are pushing just moved from there)
                        if (mapData[nextSlideR][nextSlideC] == '#' || (crateMap[nextPos] && nextPos != cratePos) || deadTiles[nextSlideR][nextSlideC]) {
                            break;
                        }

                        // Safe to slide! Fast-forward the physics.
                        nextCrates.remove(Integer.valueOf(slideR * width + slideC));
                        nextCrates.add(nextPos);
                        playerWalkR = slideR;
                        playerWalkC = slideC;
                        slideR = nextSlideR;
                        slideC = nextSlideC;
                        tunnelPath += PUSH_CHARS[dir];
                        slidePushes++;
                    }

                    // Filter 3: Check static & dynamic deadlocks at the FINAL destination
                    if (deadTiles[slideR][slideC] || 
                        isTwoByTwoDeadlock(slideR, slideC, nextCrates, mapData) ||
                        isFrozenDeadlock(nextCrates, mapData)) {
                        continue;
                    }

                    // --- THE SECRET SAUCE (The Dynamic Balance) ---
                    String walkPath = movePaths[pushStandPos];
                    int walkCost = walkPath.length();
                    
                    int targetLockPenalty = (isTargetTile[cratePos] && !isTargetTile[slideR * width + slideC]) ? 10 : 0;
                    int switchPenalty = (curr.lastPushedPos != -1 && curr.lastPushedPos != cratePos) ? 5 : 0;
                    
                    int newGCost = curr.gCost + walkCost + targetLockPenalty + slidePushes + switchPenalty;
                    String fullPath = curr.path + walkPath + tunnelPath;

                    // Calculate the new crate hash dynamically (Takes 1 nanosecond!)
                    long newCrateHash = curr.crateHash;
                    newCrateHash ^= zobristTable[cratePos][1];               // XOR crate out of old spot
                    newCrateHash ^= zobristTable[slideR * width + slideC][1]; // XOR crate into new spot

                    GameState nextState = new GameState(playerWalkR, playerWalkC, nextCrates, fullPath, newGCost, slideR * width + slideC, newCrateHash);
                    queue.add(nextState);
                }
            }

            // --- THE CLEANUP: Turn the crates OFF so the next state starts clean ---
            for (int i = 0; i < curr.cratePositions.size(); i++) {
                crateMap[curr.cratePositions.get(i)] = false;
            }
        }

        return ""; 
    }

    // --- HELPER METHODS ---

    /**
     * Instantly looks up the pre-calculated true distance for every crate.
     */
    /**
     * Calculates the heuristic by forcing every crate to claim a UNIQUE target.
     */
    private int calculateHeuristic(List<Integer> crates) {
        int totalDistance = 0;
        boolean[] targetUsed = new boolean[targets.size()];
        boolean[] crateUsed = new boolean[crates.size()];

        // Loop until every crate has claimed a target
        for (int step = 0; step < crates.size(); step++) {
            int minDistance = 999999;
            int bestCrateIndex = -1;
            int bestTargetIndex = -1;

            // Find the absolute closest unmatched Crate-Target pair
            for (int c = 0; c < crates.size(); c++) {
                if (crateUsed[c]) continue;
                int cr = crates.get(c) / width;
                int cc = crates.get(c) % width;

                for (int t = 0; t < targets.size(); t++) {
                    if (targetUsed[t]) continue;
                    
                    int dist = trueDistances[t][cr][cc];
                    if (dist < minDistance) {
                        minDistance = dist;
                        bestCrateIndex = c;
                        bestTargetIndex = t;
                    }
                }
            }

            // Lock in the claim and add the distance to our score
            if (bestCrateIndex != -1 && bestTargetIndex != -1) {
                crateUsed[bestCrateIndex] = true;
                targetUsed[bestTargetIndex] = true;
                totalDistance += minDistance;
            }
        }
        return totalDistance;
    }

    private void initTargets(char[][] mapData) {
        targets = new ArrayList<>();
        isTargetTile = new boolean[width * height];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (mapData[r][c] == '.') {
                    int pos = r * width + c;
                    targets.add(pos);
                    isTargetTile[pos] = true;
                }
            }
        }
    }

    private void initDeadTiles(char[][] mapData) {
        deadTiles = new boolean[height][width];
        
        // Step 1: Find all the standard 90-degree corners
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (mapData[r][c] == '#' || mapData[r][c] == '.') continue; 
                
                boolean wallUp = mapData[r - 1][c] == '#';
                boolean wallDown = mapData[r + 1][c] == '#';
                boolean wallLeft = mapData[r][c - 1] == '#';
                boolean wallRight = mapData[r][c + 1] == '#';
                
                if ((wallUp && wallLeft) || (wallUp && wallRight) || 
                    (wallDown && wallLeft) || (wallDown && wallRight)) {
                    deadTiles[r][c] = true;
                }
            }
        }
        
        // Step 2: Trace lines between corners to find Edge Deadlocks!
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (deadTiles[r][c]) {
                    if (mapData[r - 1][c] == '#' || mapData[r + 1][c] == '#') {
                        verifyAndMarkLine(r, c, 0, 1, mapData); // Trace horizontally
                    }
                    if (mapData[r][c - 1] == '#' || mapData[r][c + 1] == '#') {
                        verifyAndMarkLine(r, c, 1, 0, mapData); // Trace vertically
                    }
                }
            }
        }
    }

    private void verifyAndMarkLine(int startR, int startC, int dRow, int dCol, char[][] mapData) {
        int r = startR + dRow;
        int c = startC + dCol;
        List<int[]> pathCells = new ArrayList<>();
        
        while (r >= 0 && r < height && c >= 0 && c < width && mapData[r][c] != '#') {
            if (isTargetTile[r * width + c]) return; // Use the O(1) array!
            
            boolean hasWallSide1 = false;
            boolean hasWallSide2 = false;
            
            int side1R = r - dCol; int side1C = c - dRow;
            if (side1R >= 0 && side1R < height && side1C >= 0 && side1C < width) {
                hasWallSide1 = mapData[side1R][side1C] == '#';
            }
            int side2R = r + dCol; int side2C = c + dRow;
            if (side2R >= 0 && side2R < height && side2C >= 0 && side2C < width) {
                hasWallSide2 = mapData[side2R][side2C] == '#';
            }
            
            if (!hasWallSide1 && !hasWallSide2) return; // Wall gap, not a trap
            
            // If we hit another dead corner, the whole line is a trap!
            if (deadTiles[r][c]) {
                for (int[] cell : pathCells) {
                    deadTiles[cell[0]][cell[1]] = true;
                }
                return;
            }
            pathCells.add(new int[]{r, c});
            r += dRow;
            c += dCol;
        }
    }

    private boolean isTwoByTwoDeadlock(int crateR, int crateC, List<Integer> crates, char[][] mapData) {
        int[][] quadrants = {{-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        for (int[] quad : quadrants) {
            int r = crateR + quad[0];
            int c = crateC + quad[1];

            if (isWallOrCrate(r, c, crates, mapData) &&
                isWallOrCrate(r + 1, c, crates, mapData) &&
                isWallOrCrate(r, c + 1, crates, mapData) &&
                isWallOrCrate(r + 1, c + 1, crates, mapData)) {
                
                if (isCrateNotOnTarget(r, c, crates, mapData) ||
                    isCrateNotOnTarget(r + 1, c, crates, mapData) ||
                    isCrateNotOnTarget(r, c + 1, crates, mapData) ||
                    isCrateNotOnTarget(r + 1, c + 1, crates, mapData)) {
                    return true; 
                }
            }
        }
        return false;
    }

    private boolean isCrateNotOnTarget(int r, int c, List<Integer> crates, char[][] mapData) {
        return crates.contains(r * width + c) && !isTargetTile[r * width + c];
    }

    private boolean isWallOrCrate(int r, int c, List<Integer> crates, char[][] mapData) {
        return mapData[r][c] == '#' || crates.contains(r * width + c);
    }

    /**
     * Pre-computes the true walking distance from every floor tile to its nearest target.
     */
    /**
     * Pre-computes the perfect walking distance from every tile to EVERY SPECIFIC target.
     */
    private void initTrueDistances(char[][] mapData) {
        trueDistances = new int[targets.size()][height][width];
        
        for (int t = 0; t < targets.size(); t++) {
            int target = targets.get(t);
            int tr = target / width;
            int tc = target % width;

            // Fill this specific target's map with high numbers
            for (int r = 0; r < height; r++) {
                Arrays.fill(trueDistances[t][r], 999999);
            }

            Queue<int[]> queue = new LinkedList<>();
            trueDistances[t][tr][tc] = 0;
            queue.add(new int[]{tr, tc});

            while (!queue.isEmpty()) {
                int[] curr = queue.poll();
                int r = curr[0];
                int c = curr[1];
                int currentDist = trueDistances[t][r][c];

                for (int i = 0; i < 4; i++) {
                    int nr = r + PUSH_DR[i];
                    int nc = c + PUSH_DC[i];

                    if (nr >= 0 && nr < height && nc >= 0 && nc < width && mapData[nr][nc] != '#') {
                        if (currentDist + 1 < trueDistances[t][nr][nc]) {
                            trueDistances[t][nr][nc] = currentDist + 1;
                            queue.add(new int[]{nr, nc});
                        }
                    }
                }
            }
        }
    }

    /**
     * Maps the territory using zero-allocation arrays AND returns a normalized Room ID.
     */
    private int runZeroAllocationBFS(int startPr, int startPc, List<Integer> crates, char[][] mapData) {
        bfsToken++; 
        int startPos = startPr * width + startPc;
        int normalizedPos = startPos; 

        // Kane's Zero-Allocation Primitive Queue
        int head = 0;
        int tail = 0;
        bfsQueue[tail++] = startPos;
        reachable[startPos] = bfsToken;
        movePaths[startPos] = "";

        while (head < tail) {
            int curr = bfsQueue[head++];
            
            if (curr < normalizedPos) normalizedPos = curr; 

            int r = curr / width;
            int c = curr - r * width; // Kane's modulo bypass!
            String currentPath = movePaths[curr];

            for (int i = 0; i < 4; i++) {
                int nr = r + PUSH_DR[i];
                int nc = c + PUSH_DC[i];
                int nPos = nr * width + nc;

                if (nr < 0 || nr >= height || nc < 0 || nc >= width || mapData[nr][nc] == '#') continue;
                
                // Use Kane's instant O(1) lookup instead of crates.contains()!
                if (crateMap[nPos]) continue; 

                if (reachable[nPos] != bfsToken) {
                    reachable[nPos] = bfsToken;
                    movePaths[nPos] = currentPath + PUSH_CHARS[i];
                    bfsQueue[tail++] = nPos;
                }
            }
        }
        return normalizedPos;
    }

    private boolean isFrozenDeadlock(List<Integer> crates, char[][] mapData) {
        for (int cratePos : crates) {
            int r = cratePos / width;
            int c = cratePos % width;
            
            if (isTargetTile[cratePos]) continue; // Safe on a target

            boolean wallUp = mapData[r-1][c] == '#';
            boolean wallDown = mapData[r+1][c] == '#';
            boolean wallLeft = mapData[r][c-1] == '#';
            boolean wallRight = mapData[r][c+1] == '#';
            
            boolean boxUp = crates.contains((r-1)*width + c);
            boolean boxDown = crates.contains((r+1)*width + c);
            boolean boxLeft = crates.contains(r*width + c - 1);
            boolean boxRight = crates.contains(r*width + c + 1);
            
            // Is it pinned against a wall by another crate and another wall?
            if (wallLeft) {
                if (boxUp && mapData[r-1][c-1] == '#') return true; 
                if (boxDown && mapData[r+1][c-1] == '#') return true; 
            }
            if (wallRight) {
                if (boxUp && mapData[r-1][c+1] == '#') return true;
                if (boxDown && mapData[r+1][c+1] == '#') return true;
            }
            if (wallUp) {
                if (boxLeft && mapData[r-1][c-1] == '#') return true;
                if (boxRight && mapData[r-1][c+1] == '#') return true;
            }
            if (wallDown) {
                if (boxLeft && mapData[r+1][c-1] == '#') return true;
                if (boxRight && mapData[r+1][c+1] == '#') return true;
            }
        }
        return false;
    }

    private void initZobristTable() {
        Random rnd = new Random(12345); // Fixed seed for debugging consistency
        zobristTable = new long[width * height][2];
        for (int i = 0; i < width * height; i++) {
            zobristTable[i][0] = rnd.nextLong(); // Random 64-bit number for Player here
            zobristTable[i][1] = rnd.nextLong(); // Random 64-bit number for Crate here
        }
    }
}
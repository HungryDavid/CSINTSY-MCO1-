package solver;

import java.util.*;

public class SokoBot {

    private boolean[] deadTiles;
    private int[][] trueDistances;
    private int width, height;
    private List<Integer> targets;
    private boolean[] isTargetTile; // Instant target lookup
    private boolean[] isCornerTarget; //  The Parking Sensor
    private int numTargets;
    
    // Zero-Allocation BFS Memory
    private int[] reachable;
    private int bfsToken = 0;
    private int[] bfsQueue; // Kane's primitive queue

    // Kane's O(1) Instant Lookup Map
    private boolean[] crateMap; 

    // Zobrist Hashing Table
    private long[][] zobristTable;

    // One array for everything. Zero allocations
    private static final int[] PUSH_DR = {-1, 1, 0, 0};
    private static final int[] PUSH_DC = {0, 0, -1, 1};
    private static final char[] PUSH_CHARS = {'u', 'd', 'l', 'r'};

    // Global reusable arrays for Heuristic 
    private boolean[] targetUsedGlobal;
    private boolean[] crateUsedGlobal;
    
    // Zero-Allocation BFS Memory (Replaces movePaths)
    private int[] bfsDist;          
    private int[] bfsParentPos;     
    private char[] bfsParentDir;

    // THE TRANPOSITION TABLE (Heuristic Cache) 
    private static final int CACHE_CAPACITY = 1 << 20; // Roughly 1 million slots
    private static final int CACHE_MASK = CACHE_CAPACITY - 1;
    private long[] heuristicCacheKeys;
    private int[] heuristicCacheValues;

    class GameState implements Comparable<GameState> {
        int playerR, playerC;
        int[] crates;
        GameState parent; // A reference to the state that created this one
        String moveFromParent; // Only the 2-5 characters it took to get here
        int h; 
        int gCost; 
        int lastPushedPos; 
        long crateHash; 

        public GameState(int pr, int pc, int[] crates, GameState parent, String moveFromParent, int gCost, int lastPushedPos, long crateHash) {
            this.playerR = pr;
            this.playerC = pc;
            this.crates = crates;
            
            // Parent Pointers instead of massive Strings
            this.parent = parent; 
            this.moveFromParent = moveFromParent; 
            
            this.h = calculateHeuristic(this.crates, crateHash);
            this.gCost = gCost; 
            this.lastPushedPos = lastPushedPos;
            this.crateHash = crateHash;
        }

        @Override
        public int compareTo(GameState other) {
            int thisScore = this.gCost + (5 * this.h); 
            int otherScore = other.gCost + (5 * other.h);
            
            if (thisScore != otherScore) {
                return Integer.compare(thisScore, otherScore);
            }
            
            // TIE-BREAKER 1: Always prefer the state that is further along in the level (higher gCost)
            if (this.gCost != other.gCost) {
                return Integer.compare(other.gCost, this.gCost); 
            }
            
            // TIE-BREAKER 2: If everything is equal, forcefully break the tie using the Zobrist hash.
            // This prevents the Priority Queue from thrashing and creates a laser-focused depth search.
            return Long.compare(this.crateHash, other.crateHash);
        }
    }

    // ZERO-ALLOCATION ZOBRIST HASH SET 
    class ZobristHashSet {
        private static final int CAPACITY = 1 << 22; // roughtly like ~4.1 million slots
        private static final int MASK = CAPACITY - 1;
        private final long[] keys;

        public ZobristHashSet() {
            keys = new long[CAPACITY];
        }

        // Returns true if successfully added. Returns false if it already exists.
        public boolean add(long key) {
            if (key == 0) key = 1; // use 0 to represent an empty slot
            
            // Mix the bits to prevent clustering
            int idx = (int) ((key ^ (key >>> 16)) & MASK); 
            
            while (true) {
                long current = keys[idx];
                if (current == key) return false; // State has already been visited
                if (current == 0) {
                    keys[idx] = key; // State is new, then we claim the slot
                    return true;
                }
                idx = (idx + 1) & MASK; // Collision?? Linear will probe to the next slot
            }
        }
    }

    public String solveSokobanPuzzle(int w, int h, char[][] mapData, char[][] itemsData) {
        this.width = w;
        this.height = h;
        long startTime = System.currentTimeMillis();

        int mapSize = width * height;
        reachable = new int[mapSize];
        crateMap = new boolean[mapSize]; // Initialize the instant lookup
        bfsQueue = new int[mapSize];     // Initialize the primitive queue

        heuristicCacheKeys = new long[CACHE_CAPACITY];
        heuristicCacheValues = new int[CACHE_CAPACITY];

        // 1. Pre-compute static traps and true distances
        initTargets(mapData);
        initDeadTiles(mapData);    
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

        targetUsedGlobal = new boolean[targets.size()];
        crateUsedGlobal = new boolean[startCrates.size()];
        bfsDist = new int[mapSize];
        bfsParentPos = new int[mapSize];
        bfsParentDir = new char[mapSize];

        // Initialize Zobrist Table
        initZobristTable();

        // Initialize the Priority Queue and Visited Set
        PriorityQueue<GameState> queue = new PriorityQueue<>();
        ZobristHashSet visited = new ZobristHashSet();

        // Generate the starting hash from scratch
        long initialCrateHash = 0;
        for (int crate : startCrates) {
            initialCrateHash ^= zobristTable[crate][1];
        }

        // Convert initial list to primitive array
        int[] startCratesArr = new int[startCrates.size()];
        for (int i = 0; i < startCrates.size(); i++) {
            startCratesArr[i] = startCrates.get(i);
        }

        // Pass 'null' for the parent, and "" for the move!
        GameState initialState = new GameState(startPr, startPc, startCratesArr, null, "", 0, -1, initialCrateHash); 
        queue.add(initialState);

        // Track the best partial solution for the timeout fallback
        GameState bestState = initialState;
        int minH = initialState.h;

        // Execution Search
        while (!queue.isEmpty()) {
            // Safety Switch
            if (System.currentTimeMillis() - startTime > 14000) {
                System.out.println("Time limit reached! Returning best effort.");
                
                StringBuilder fallbackPath = new StringBuilder();
                GameState trace = bestState;
                while (trace != null && trace.parent != null) {
                    fallbackPath.insert(0, trace.moveFromParent);
                    trace = trace.parent;
                }
                return fallbackPath.toString(); 
            }

            GameState curr = queue.poll();

            // Update the deepest state tracker
            if (curr.h < minH) {
                minH = curr.h;
                bestState = curr;
            }

            for (int i = 0; i < curr.crates.length; i++) {
                crateMap[curr.crates[i]] = true;
            }

            int normalizedPlayerID = runZeroAllocationBFS(curr.playerR, curr.playerC, mapData);
            long fullStateHash = curr.crateHash ^ zobristTable[normalizedPlayerID][0];
            
            // If add() returns false, we've been here before. Skip it!
            if (!visited.add(fullStateHash)) {
                for (int i = 0; i < curr.crates.length; i++) crateMap[curr.crates[i]] = false;
                continue; 
            }

            if (curr.h == 0) { 
                StringBuilder winningPath = new StringBuilder();
                GameState trace = curr;
                while (trace.parent != null) {
                    winningPath.insert(0, trace.moveFromParent);
                    trace = trace.parent;
                }
                return winningPath.toString(); 
            }

            for (int i = 0; i < curr.crates.length; i++) {
                int cratePos = curr.crates[i];
                
                // THE PARKING PRUNE 
                // If this box is solved and locked in a corner, it becomes a ghost. 
                // So We skip generating any moves for it
                if (isCornerTarget[cratePos]) continue; 

                int cr = cratePos / width;
                int cc = cratePos % width;

                for (int dir = 0; dir < 4; dir++) {
                    int pushStandR = cr - PUSH_DR[dir];
                    int pushStandC = cc - PUSH_DC[dir];
                    int pushStandPos = pushStandR * width + pushStandC;

                    if (pushStandPos < 0 || pushStandPos >= (width*height) || reachable[pushStandPos] != bfsToken) continue;

                    int newCrateR = cr + PUSH_DR[dir];
                    int newCrateC = cc + PUSH_DC[dir];
                    int newCratePos = newCrateR * width + newCrateC;

                    if (mapData[newCrateR][newCrateC] == '#' || crateMap[newCratePos]) continue; 

                    int slideR = newCrateR;
                    int slideC = newCrateC;
                    int playerWalkR = cr;
                    int playerWalkC = cc;
                    int slidePushes = 1;

                    // THE PRIMITIVE HIGHWAY SYSTEM "THIS SOLVE ORIGINAL 2 AND ORIGINAL 3"
                    while (true) {
                        boolean isHorizTunnel = mapData[slideR - 1][slideC] == '#' && mapData[slideR + 1][slideC] == '#';
                        boolean isVertTunnel = mapData[slideR][slideC - 1] == '#' && mapData[slideR][slideC + 1] == '#';
                        
                        if (isTargetTile[slideR * width + slideC]) break;
                        if ((dir == 0 || dir == 1) && !isVertTunnel) break; 
                        if ((dir == 2 || dir == 3) && !isHorizTunnel) break; 

                        int nextSlideR = slideR + PUSH_DR[dir];
                        int nextSlideC = slideC + PUSH_DC[dir];
                        int nextPos = nextSlideR * width + nextSlideC;

                        if (mapData[nextSlideR][nextSlideC] == '#' || (crateMap[nextPos] && nextPos != cratePos) || deadTiles[nextSlideR * width + nextSlideC]) break;

                        // Intersection Look-ahead Prune
                        // Check if the next tile breaks the tunnel. If so, stop HERE before entering the intersection.
                        boolean nextHorizTunnel = mapData[nextSlideR - 1][nextSlideC] == '#' && mapData[nextSlideR + 1][nextSlideC] == '#';
                        boolean nextVertTunnel = mapData[nextSlideR][nextSlideC - 1] == '#' && mapData[nextSlideR][nextSlideC + 1] == '#';
                        
                        if ((dir == 0 || dir == 1) && !nextVertTunnel) break;
                        if ((dir == 2 || dir == 3) && !nextHorizTunnel) break;

                        playerWalkR = slideR;
                        playerWalkC = slideC;
                        slideR = nextSlideR;
                        slideC = nextSlideC;
                        slidePushes++;
                    }

                    int finalCratePos = slideR * width + slideC;

                    // THE O(1) CRATEMAP TOGGLE
                    crateMap[cratePos] = false; 
                    crateMap[finalCratePos] = true;

                    // Pass curr.crates, cratePos, and finalCratePos to avoid premature array allocation
                    boolean isDeadlocked = deadTiles[slideR * width + slideC] || 
                                           isTwoByTwoDeadlock(slideR, slideC, mapData) || 
                                           isFrozenDeadlock(finalCratePos, mapData);

                    crateMap[cratePos] = true; 
                    crateMap[finalCratePos] = false;

                    if (isDeadlocked) continue; // so I Safely skip without creating any objects

                    // DELAYED CONSTRUCTION: safe to build the primitive array 
                    int[] nextCrates = new int[curr.crates.length];
                    int idx = 0;
                    for (int j = 0; j < curr.crates.length; j++) {
                        if (curr.crates[j] != cratePos) {
                            nextCrates[idx++] = curr.crates[j];
                        }
                    }
                    nextCrates[idx] = finalCratePos;

                    int currentPlayerPos = curr.playerR * width + curr.playerC;
                    int walkCost = bfsDist[pushStandPos];
                    
                    String totalPath = reconstructCombinedPath(currentPlayerPos, pushStandPos, PUSH_CHARS[dir], slidePushes);
                    
                    int targetLockPenalty = (isTargetTile[cratePos] && !isTargetTile[finalCratePos]) ? 10 : 0;
                    int switchPenalty = (curr.lastPushedPos != -1 && curr.lastPushedPos != cratePos) ? 5 : 0;
                    int newGCost = curr.gCost + walkCost + targetLockPenalty + slidePushes + switchPenalty;
                    
                    long newCrateHash = curr.crateHash;
                    newCrateHash ^= zobristTable[cratePos][1];               
                    newCrateHash ^= zobristTable[finalCratePos][1]; 

                    // Pass the totalPath directly
                    GameState nextState = new GameState(playerWalkR, playerWalkC, nextCrates, curr, totalPath, newGCost, finalCratePos, newCrateHash);
                    queue.add(nextState);
                }
            }

            for (int i = 0; i < curr.crates.length; i++) crateMap[curr.crates[i]] = false;
        }

        return ""; 
    }

    // HELPER METHODS EEEEEEE

    private void initTargets(char[][] mapData) {
        targets = new ArrayList<>();
        isTargetTile = new boolean[width * height];
        isCornerTarget = new boolean[width * height]; // Initialize Parking Sensor
        
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (mapData[r][c] == '.') {
                    int pos = r * width + c;
                    targets.add(pos);
                    isTargetTile[pos] = true;

                    // TARGET PARKING (Corner Detection)
                    // If a target is in a hard corner, anything placed on it is permanently parked.
                    boolean wallU = mapData[r-1][c] == '#';
                    boolean wallD = mapData[r+1][c] == '#';
                    boolean wallL = mapData[r][c-1] == '#';
                    boolean wallR = mapData[r][c+1] == '#';
                    
                    if ((wallU && wallL) || (wallU && wallR) || (wallD && wallL) || (wallD && wallR)) {
                        isCornerTarget[pos] = true;
                    }
                }
            }
        }
        this.numTargets = targets.size();
    }

    // THE PULL-BFS PREPROCESSOR (Absolute Deadlock Detection)
    private void initDeadTiles(char[][] mapData) {
        deadTiles = new boolean[width * height];
        boolean[][] isLive = new boolean[height][width];
        Queue<int[]> queue = new LinkedList<>();

        // All targets are live starting points
        for (int t : targets) {
            int r = t / width;
            int c = t % width;
            isLive[r][c] = true;
            queue.add(new int[]{r, c});
        }

        // The Ghost Player simulates PULLING boxes backward through the map
        while (!queue.isEmpty()) {
            int[] curr = queue.poll();
            int r = curr[0];
            int c = curr[1];

            // Try reversing a push from all 4 directions
            for (int dir = 0; dir < 4; dir++) {
                // To reverse a push, we subtract the movement vectors.
                // If a box was pushed DOWN to (r,c), it came from (r-1, c),
                // and the player was standing at (r-2, c) to push it.
                int prevBoxR = r - PUSH_DR[dir];
                int prevBoxC = c - PUSH_DC[dir];
                
                int prevPlayerR = r - 2 * PUSH_DR[dir];
                int prevPlayerC = c - 2 * PUSH_DC[dir];

                // Bounds check to prevent out-of-bounds errors on map edges
                if (prevBoxR >= 0 && prevBoxR < height && prevBoxC >= 0 && prevBoxC < width &&
                    prevPlayerR >= 0 && prevPlayerR < height && prevPlayerC >= 0 && prevPlayerC < width) {
                    
                    // If the box's previous spot AND the player's pushing stance are not walls
                    if (mapData[prevBoxR][prevBoxC] != '#' && mapData[prevPlayerR][prevPlayerC] != '#') {
                        // The tile is reachable then Mark it live and add it to the queue
                        if (!isLive[prevBoxR][prevBoxC]) {
                            isLive[prevBoxR][prevBoxC] = true;
                            queue.add(new int[]{prevBoxR, prevBoxC});
                        }
                    }
                }
            }
        }

        deadTiles = new boolean[width * height];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (mapData[r][c] != '#' && !isLive[r][c]) {
                    deadTiles[r * width + c] = true;
                }
            }
        }
    }

    // Pre-computes the perfect walking distance from every tile to EVERY SPECIFIC target.
    private void initTrueDistances(char[][] mapData) {
        trueDistances = new int[numTargets][width * height];
        
        for (int t = 0; t < numTargets; t++) {
            int target = targets.get(t);
            int tr = target / width;
            int tc = target % width;

            // Fill with high no.
            Arrays.fill(trueDistances[t], 999999);

            Queue<int[]> queue = new LinkedList<>();
            trueDistances[t][target] = 0; 
            queue.add(new int[]{tr, tc});

            while (!queue.isEmpty()) {
                int[] curr = queue.poll();
                int r = curr[0];
                int c = curr[1];
                int currentDist = trueDistances[t][r * width + c];

                for (int i = 0; i < 4; i++) {
                    int nr = r + PUSH_DR[i];
                    int nc = c + PUSH_DC[i];
                    int nPos = nr * width + nc;

                    if (nr >= 0 && nr < height && nc >= 0 && nc < width && mapData[nr][nc] != '#') {
                        if (currentDist + 1 < trueDistances[t][nPos]) {
                            trueDistances[t][nPos] = currentDist + 1;
                            queue.add(new int[]{nr, nc});
                        }
                    }
                }
            }
        }
    }

    private int runZeroAllocationBFS(int startPr, int startPc, char[][] mapData) {
        bfsToken++; 
        int startPos = startPr * width + startPc;
        int normalizedPos = startPos; 

        int head = 0;
        int tail = 0;
        bfsQueue[tail++] = startPos;
        reachable[startPos] = bfsToken;
        bfsDist[startPos] = 0;

        while (head < tail) {
            int curr = bfsQueue[head++];
            if (curr < normalizedPos) normalizedPos = curr; 

            int r = curr / width;
            int c = curr - r * width; 

            for (int i = 0; i < 4; i++) {
                int nr = r + PUSH_DR[i];
                int nc = c + PUSH_DC[i];
                int nPos = nr * width + nc;

                if (nr < 0 || nr >= height || nc < 0 || nc >= width || mapData[nr][nc] == '#') continue;
                if (crateMap[nPos]) continue; 

                if (reachable[nPos] != bfsToken) {
                    reachable[nPos] = bfsToken;
                    bfsDist[nPos] = bfsDist[curr] + 1;
                    bfsParentPos[nPos] = curr;
                    bfsParentDir[nPos] = PUSH_CHARS[i];
                    bfsQueue[tail++] = nPos;
                }
            }
        }
        return normalizedPos;
    }

    // Heuristic accepts int[] array instead of List
    private int calculateHeuristic(int[] crates, long crateHash) {
        // FAST PATH: Check the Cache
        // We use bitwise AND to instantly wrap the hash into the array bounds
        int cacheIdx = (int) (crateHash & CACHE_MASK); 
        
        // If the key matches perfectly, we already did the math for this crate layout
        if (heuristicCacheKeys[cacheIdx] == crateHash) {
            return heuristicCacheValues[cacheIdx];
        }

        // SLOW PATH: Calculate it
        int totalDistance = 0;
        Arrays.fill(targetUsedGlobal, false);
        Arrays.fill(crateUsedGlobal, false);

        for (int step = 0; step < crates.length; step++) {
            int minDistance = 999999;
            int bestCrateIndex = -1;
            int bestTargetIndex = -1;

            for (int c = 0; c < crates.length; c++) {
                if (crateUsedGlobal[c]) continue;
                
                int cratePos = crates[c];

                for (int t = 0; t < numTargets; t++) {
                    if (targetUsedGlobal[t]) continue;
                    
                    int dist = trueDistances[t][cratePos]; // Instant 2D memory fetch
                    if (dist < minDistance) {
                        minDistance = dist;
                        bestCrateIndex = c;
                        bestTargetIndex = t;
                    }
                }
            }
            if (bestCrateIndex != -1 && bestTargetIndex != -1) {
                crateUsedGlobal[bestCrateIndex] = true;
                targetUsedGlobal[bestTargetIndex] = true;
                totalDistance += minDistance;
            }
        }

        // Save the result to the cache for next time
        heuristicCacheKeys[cacheIdx] = crateHash;
        heuristicCacheValues[cacheIdx] = totalDistance;

        return totalDistance;
    }

    // 3. O(1) Deadlock: queries crateMap directly.
    private boolean isTwoByTwoDeadlock(int crateR, int crateC, char[][] mapData) {
        // Top-Left
        int r = crateR - 1; int c = crateC - 1;
        if (isWallOrCrate(r, c, mapData) && isWallOrCrate(r + 1, c, mapData) && isWallOrCrate(r, c + 1, mapData) && isWallOrCrate(r + 1, c + 1, mapData)) {
            if (isCrateNotOnTarget(r, c) || isCrateNotOnTarget(r + 1, c) || isCrateNotOnTarget(r, c + 1) || isCrateNotOnTarget(r + 1, c + 1)) return true;
        }
        // Top-Right
        r = crateR - 1; c = crateC;
        if (isWallOrCrate(r, c, mapData) && isWallOrCrate(r + 1, c, mapData) && isWallOrCrate(r, c + 1, mapData) && isWallOrCrate(r + 1, c + 1, mapData)) {
            if (isCrateNotOnTarget(r, c) || isCrateNotOnTarget(r + 1, c) || isCrateNotOnTarget(r, c + 1) || isCrateNotOnTarget(r + 1, c + 1)) return true;
        }
        // Bottom-Left
        r = crateR; c = crateC - 1;
        if (isWallOrCrate(r, c, mapData) && isWallOrCrate(r + 1, c, mapData) && isWallOrCrate(r, c + 1, mapData) && isWallOrCrate(r + 1, c + 1, mapData)) {
            if (isCrateNotOnTarget(r, c) || isCrateNotOnTarget(r + 1, c) || isCrateNotOnTarget(r, c + 1) || isCrateNotOnTarget(r + 1, c + 1)) return true;
        }
        // Bottom-Right
        r = crateR; c = crateC;
        if (isWallOrCrate(r, c, mapData) && isWallOrCrate(r + 1, c, mapData) && isWallOrCrate(r, c + 1, mapData) && isWallOrCrate(r + 1, c + 1, mapData)) {
            if (isCrateNotOnTarget(r, c) || isCrateNotOnTarget(r + 1, c) || isCrateNotOnTarget(r, c + 1) || isCrateNotOnTarget(r + 1, c + 1)) return true;
        }
        return false;
    }

    private boolean isCrateNotOnTarget(int r, int c) {
        int pos = r * width + c;
        return crateMap[pos] && !isTargetTile[pos];
    }

    private boolean isWallOrCrate(int r, int c, char[][] mapData) {
        return mapData[r][c] == '#' || crateMap[r * width + c];
    }

    // Core logic to check if ONE specific tile position is frozen deadlocked
    private boolean checkSingleFrozenDeadlock(int cratePos, char[][] mapData) {
        if (isTargetTile[cratePos]) return false;

        int r = cratePos / width;
        int c = cratePos % width;
        
        boolean wallUp = (r == 0) || mapData[r-1][c] == '#';
        boolean wallDown = (r == height-1) || mapData[r+1][c] == '#';
        boolean wallLeft = (c == 0) || mapData[r][c-1] == '#';
        boolean wallRight = (c == width-1) || mapData[r][c+1] == '#';
        
        boolean boxUp = (r > 0) && crateMap[(r-1)*width + c];
        boolean boxDown = (r < height-1) && crateMap[(r+1)*width + c];
        boolean boxLeft = (c > 0) && crateMap[r*width + c - 1];
        boolean boxRight = (c < width-1) && crateMap[r*width + c + 1];
        
        if (wallLeft && ((boxUp && mapData[r-1][c-1] == '#') || (boxDown && mapData[r+1][c-1] == '#'))) return true;
        if (wallRight && ((boxUp && mapData[r-1][c+1] == '#') || (boxDown && mapData[r+1][c+1] == '#'))) return true;
        if (wallUp && ((boxLeft && mapData[r-1][c-1] == '#') || (boxRight && mapData[r-1][c+1] == '#'))) return true;
        return wallDown && ((boxLeft && mapData[r+1][c-1] == '#') || (boxRight && mapData[r+1][c+1] == '#'));
    }

    // High-speed O(1) Local Neighborhood Deadlock Checker
    private boolean isFrozenDeadlock(int newCratePos, char[][] mapData) {
        if (checkSingleFrozenDeadlock(newCratePos, mapData)) return true;
        
        int r = newCratePos / width;
        int c = newCratePos % width;
        
        if (r > 0) {
            int up = (r - 1) * width + c;
            if (crateMap[up] && checkSingleFrozenDeadlock(up, mapData)) return true;
        }
        if (r < height - 1) {
            int down = (r + 1) * width + c;
            if (crateMap[down] && checkSingleFrozenDeadlock(down, mapData)) return true;
        }
        if (c > 0) {
            int left = r * width + (c - 1);
            if (crateMap[left] && checkSingleFrozenDeadlock(left, mapData)) return true;
        }
        if (c < width - 1) {
            int right = r * width + (c + 1);
            if (crateMap[right] && checkSingleFrozenDeadlock(right, mapData)) return true;
        }
        return false;
    }

    private void initZobristTable() {
        Random rnd = new Random(12345); // Seed for debugging consistency
        zobristTable = new long[width * height][2];
        for (int i = 0; i < width * height; i++) {
            zobristTable[i][0] = rnd.nextLong(); // Random 64-bit number for Player 
            zobristTable[i][1] = rnd.nextLong(); // Random 64-bit number for Crate 
        }
    }

    private String reconstructCombinedPath(int startPos, int endPos, char pushChar, int pushCount) {
        int walkLen = (startPos == endPos) ? 0 : bfsDist[endPos];
        int totalLen = walkLen + pushCount;
        char[] path = new char[totalLen];
        
        int curr = endPos;
        for (int i = walkLen - 1; i >= 0; i--) {
            path[i] = bfsParentDir[curr];
            curr = bfsParentPos[curr];
        }
        for (int i = walkLen; i < totalLen; i++) {
            path[i] = pushChar;
        }
        return new String(path);
    }

}

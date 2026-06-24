package solver;

import java.util.*;

public class SokoBot {

    // --- GLOBAL VARIABLES ---
    private boolean[][] deadTiles;
    private int[][][] trueDistances;
    private int width, height;
    private List<Integer> targets;
    private boolean[] isTargetTile; 
    private boolean[] isCornerTarget; 
    
    // Zero-Allocation BFS Memory
    private int[] reachable;
    private String[] movePaths;
    private int bfsToken = 0;
    private int[] bfsQueue; 

    // O(1) Instant Lookup Map
    private boolean[] crateMap; 

    // Zobrist Hashing Table
    private long[][] zobristTable;

    private static final int[] PUSH_DR = {-1, 1, 0, 0};
    private static final int[] PUSH_DC = {0, 0, -1, 1};
    private static final char[] PUSH_CHARS = {'u', 'd', 'l', 'r'};

    class GameState implements Comparable<GameState> {
        int playerR, playerC;
        int normalizedPlayerPos = -1; 
        int[] crates; 
        GameState parent; 
        String moveFromParent; 
        int h; 
        int gCost; 
        int lastPushedPos; 
        long crateHash; 

        public GameState(int pr, int pc, int[] crates, GameState parent, String moveFromParent, int gCost, int lastPushedPos, long crateHash) {
            this.playerR = pr;
            this.playerC = pc;
            this.crates = crates;
            Arrays.sort(this.crates); 
            
            this.parent = parent; 
            this.moveFromParent = moveFromParent; 
            
            this.h = calculateHeuristic(this.crates);
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
            long fullHash = crateHash ^ zobristTable[normalizedPlayerPos][0];
            return (int) (fullHash ^ (fullHash >>> 32));
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof GameState)) return false;
            GameState other = (GameState) obj;
            return this.crateHash == other.crateHash && 
                   this.normalizedPlayerPos == other.normalizedPlayerPos && 
                   Arrays.equals(this.crates, other.crates); 
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
        crateMap = new boolean[mapSize]; 
        bfsQueue = new int[mapSize];     

        initTargets(mapData, itemsData);       
        initDeadTiles(mapData);     
        initTrueDistances(mapData);
        initZobristTable();

        int startPr = 0, startPc = 0;
        List<Integer> startCrates = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                char m = mapData[r][c];
                char i = (itemsData != null && r < itemsData.length && c < itemsData[r].length) ? itemsData[r][c] : ' ';
                
                if (m == '@' || m == '+' || i == '@' || i == '+') { // Checks both layers
                    startPr = r;
                    startPc = c;
                } 
                if (m == '$' || m == '*' || i == '$' || i == '*') { // Checks both layers
                    startCrates.add(r * width + c);
                }
            }
        }

        PriorityQueue<GameState> queue = new PriorityQueue<>();
        HashSet<GameState> visited = new HashSet<>(); 

        long initialCrateHash = 0;
        for (int crate : startCrates) {
            initialCrateHash ^= zobristTable[crate][1];
        }

        int[] startCratesArr = new int[startCrates.size()];
        for (int i = 0; i < startCrates.size(); i++) {
            startCratesArr[i] = startCrates.get(i);
        }

        GameState initialState = new GameState(startPr, startPc, startCratesArr, null, "", 0, -1, initialCrateHash); 
        queue.add(initialState);

        GameState bestState = initialState;
        int minH = initialState.h;

        while (!queue.isEmpty()) {
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

            if (curr.h < minH) {
                minH = curr.h;
                bestState = curr;
            }

            for (int i = 0; i < curr.crates.length; i++) {
                crateMap[curr.crates[i]] = true;
            }

            int normalizedPlayerID = runZeroAllocationBFS(curr.playerR, curr.playerC, mapData); 
            curr.normalizedPlayerPos = normalizedPlayerID;
            
            if (visited.contains(curr)) {
                for (int i = 0; i < curr.crates.length; i++) crateMap[curr.crates[i]] = false;
                continue; 
            }
            visited.add(curr);

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
                    String tunnelPath = "" + PUSH_CHARS[dir];

                    // --- ARMORED HIGHWAY SYSTEM ---
                    while (true) {
                        boolean isHorizTunnel = (slideR > 0 && mapData[slideR - 1][slideC] == '#') && 
                                                (slideR < height - 1 && mapData[slideR + 1][slideC] == '#');
                        boolean isVertTunnel = (slideC > 0 && mapData[slideR][slideC - 1] == '#') && 
                                               (slideC < width - 1 && mapData[slideR][slideC + 1] == '#');
                        
                        if (isTargetTile[slideR * width + slideC]) break;
                        if ((dir == 0 || dir == 1) && !isVertTunnel) break; 
                        if ((dir == 2 || dir == 3) && !isHorizTunnel) break; 

                        int nextSlideR = slideR + PUSH_DR[dir];
                        int nextSlideC = slideC + PUSH_DC[dir];
                        int nextPos = nextSlideR * width + nextSlideC;

                        if (mapData[nextSlideR][nextSlideC] == '#' || (crateMap[nextPos] && nextPos != cratePos) || deadTiles[nextSlideR][nextSlideC]) break;

                        playerWalkR = slideR;
                        playerWalkC = slideC;
                        slideR = nextSlideR;
                        slideC = nextSlideC;
                        tunnelPath += PUSH_CHARS[dir];
                        slidePushes++;
                    }

                    int finalCratePos = slideR * width + slideC;

                    int[] nextCrates = new int[curr.crates.length];
                    int idx = 0;
                    for (int j = 0; j < curr.crates.length; j++) {
                        if (curr.crates[j] != cratePos) {
                            nextCrates[idx++] = curr.crates[j];
                        }
                    }
                    nextCrates[idx] = finalCratePos;

                    crateMap[cratePos] = false; 
                    crateMap[finalCratePos] = true;

                    boolean isDeadlocked = deadTiles[slideR][slideC] || 
                                           isTwoByTwoDeadlock(slideR, slideC, mapData) || 
                                           isFrozenDeadlock(nextCrates, mapData);

                    crateMap[cratePos] = true; 
                    crateMap[finalCratePos] = false;

                    if (isDeadlocked) continue;

                    String walkPath = movePaths[pushStandPos];
                    int walkCost = walkPath.length();
                    
                    int targetLockPenalty = (isTargetTile[cratePos] && !isTargetTile[finalCratePos]) ? 10 : 0;
                    int switchPenalty = (curr.lastPushedPos != -1 && curr.lastPushedPos != cratePos) ? 5 : 0;
                    int newGCost = curr.gCost + walkCost + targetLockPenalty + slidePushes + switchPenalty;
                    
                    long newCrateHash = curr.crateHash;
                    newCrateHash ^= zobristTable[cratePos][1];               
                    newCrateHash ^= zobristTable[finalCratePos][1]; 

                    GameState nextState = new GameState(playerWalkR, playerWalkC, nextCrates, curr, walkPath + tunnelPath, newGCost, finalCratePos, newCrateHash);
                    queue.add(nextState);
                }
            }

            for (int i = 0; i < curr.crates.length; i++) crateMap[curr.crates[i]] = false;
        }

        return ""; 
    }

    // --- HELPER METHODS ---

    private void initTargets(char[][] mapData, char[][] itemsData) {
        targets = new ArrayList<>();
        isTargetTile = new boolean[width * height];
        isCornerTarget = new boolean[width * height]; 
        
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                char m = mapData[r][c];
                char i = (itemsData != null && r < itemsData.length && c < itemsData[r].length) ? itemsData[r][c] : ' ';
                
                // Now it safely checks BOTH the map background and the dynamic items layer!
                if (m == '.' || m == '+' || m == '*' || i == '.' || i == '+' || i == '*') {
                    int pos = r * width + c;
                    targets.add(pos);
                    isTargetTile[pos] = true;

                    // --- ARMORED TARGET PARKING ---
                    boolean wallU = r == 0 || mapData[r-1][c] == '#';
                    boolean wallD = r == height-1 || mapData[r+1][c] == '#';
                    boolean wallL = c == 0 || mapData[r][c-1] == '#';
                    boolean wallR = c == width-1 || mapData[r][c+1] == '#';
                    
                    if ((wallU && wallL) || (wallU && wallR) || (wallD && wallL) || (wallD && wallR)) {
                        isCornerTarget[pos] = true;
                    }
                }
            }
        }
    }

    private void initDeadTiles(char[][] mapData) {
        deadTiles = new boolean[height][width];
        boolean[][] isLive = new boolean[height][width];
        Queue<int[]> queue = new LinkedList<>();

        for (int t : targets) {
            int r = t / width;
            int c = t % width;
            isLive[r][c] = true;
            queue.add(new int[]{r, c});
        }

        while (!queue.isEmpty()) {
            int[] curr = queue.poll();
            int r = curr[0];
            int c = curr[1];

            for (int dir = 0; dir < 4; dir++) {
                int prevBoxR = r - PUSH_DR[dir];
                int prevBoxC = c - PUSH_DC[dir];
                
                int prevPlayerR = r - 2 * PUSH_DR[dir];
                int prevPlayerC = c - 2 * PUSH_DC[dir];

                if (prevBoxR >= 0 && prevBoxR < height && prevBoxC >= 0 && prevBoxC < width &&
                    prevPlayerR >= 0 && prevPlayerR < height && prevPlayerC >= 0 && prevPlayerC < width) {
                    
                    if (mapData[prevBoxR][prevBoxC] != '#' && mapData[prevPlayerR][prevPlayerC] != '#') {
                        if (!isLive[prevBoxR][prevBoxC]) {
                            isLive[prevBoxR][prevBoxC] = true;
                            queue.add(new int[]{prevBoxR, prevBoxC});
                        }
                    }
                }
            }
        }

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (mapData[r][c] != '#' && !isLive[r][c]) {
                    deadTiles[r][c] = true;
                }
            }
        }
    }

    private void initTrueDistances(char[][] mapData) {
        trueDistances = new int[targets.size()][height][width];
        
        for (int t = 0; t < targets.size(); t++) {
            int target = targets.get(t);
            int tr = target / width;
            int tc = target % width;

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

    private int runZeroAllocationBFS(int startPr, int startPc, char[][] mapData) {
        bfsToken++; 
        int startPos = startPr * width + startPc;
        int normalizedPos = startPos; 

        int head = 0;
        int tail = 0;
        bfsQueue[tail++] = startPos;
        reachable[startPos] = bfsToken;
        movePaths[startPos] = "";

        while (head < tail) {
            int curr = bfsQueue[head++];
            if (curr < normalizedPos) normalizedPos = curr; 

            int r = curr / width;
            int c = curr - r * width; 
            String currentPath = movePaths[curr];

            for (int i = 0; i < 4; i++) {
                int nr = r + PUSH_DR[i];
                int nc = c + PUSH_DC[i];
                int nPos = nr * width + nc;

                if (nr < 0 || nr >= height || nc < 0 || nc >= width || mapData[nr][nc] == '#') continue;
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

    private int calculateHeuristic(int[] crates) {
        int totalDistance = 0;
        boolean[] targetUsed = new boolean[targets.size()];
        boolean[] crateUsed = new boolean[crates.length];

        for (int step = 0; step < crates.length; step++) {
            int minDistance = 999999;
            int bestCrateIndex = -1;
            int bestTargetIndex = -1;

            for (int c = 0; c < crates.length; c++) {
                if (crateUsed[c]) continue;
                int cr = crates[c] / width;
                int cc = crates[c] % width;

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
            if (bestCrateIndex != -1 && bestTargetIndex != -1) {
                crateUsed[bestCrateIndex] = true;
                targetUsed[bestTargetIndex] = true;
                totalDistance += minDistance;
            }
        }
        return totalDistance;
    }

    // --- ARMORED 2x2 DETECTOR ---
    private boolean isTwoByTwoDeadlock(int crateR, int crateC, char[][] mapData) {
        int[][] quadrants = {{-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        for (int[] quad : quadrants) {
            int r = crateR + quad[0];
            int c = crateC + quad[1];

            if (isWallOrCrate(r, c, mapData) && isWallOrCrate(r + 1, c, mapData) &&
                isWallOrCrate(r, c + 1, mapData) && isWallOrCrate(r + 1, c + 1, mapData)) {
                
                if (isCrateNotOnTarget(r, c) || isCrateNotOnTarget(r + 1, c) ||
                    isCrateNotOnTarget(r, c + 1) || isCrateNotOnTarget(r + 1, c + 1)) {
                    return true; 
                }
            }
        }
        return false;
    }

    private boolean isCrateNotOnTarget(int r, int c) {
        if (r < 0 || r >= height || c < 0 || c >= width) return false;
        int pos = r * width + c;
        return crateMap[pos] && !isTargetTile[pos];
    }

    private boolean isWallOrCrate(int r, int c, char[][] mapData) {
        if (r < 0 || r >= height || c < 0 || c >= width) return true; // Bounds act as walls!
        return mapData[r][c] == '#' || crateMap[r * width + c];
    }

    // --- ARMORED FROZEN DETECTOR ---
    private boolean isFrozenDeadlock(int[] nextCrates, char[][] mapData) {
        for (int i = 0; i < nextCrates.length; i++) {
            int cratePos = nextCrates[i];
            int r = cratePos / width;
            int c = cratePos % width;
            
            if (isTargetTile[cratePos]) continue; 

            boolean wallUp = (r == 0) || mapData[r-1][c] == '#';
            boolean wallDown = (r == height-1) || mapData[r+1][c] == '#';
            boolean wallLeft = (c == 0) || mapData[r][c-1] == '#';
            boolean wallRight = (c == width-1) || mapData[r][c+1] == '#';
            
            boolean boxUp = (r > 0) && crateMap[(r-1)*width + c];
            boolean boxDown = (r < height-1) && crateMap[(r+1)*width + c];
            boolean boxLeft = (c > 0) && crateMap[r*width + c - 1];
            boolean boxRight = (c < width-1) && crateMap[r*width + c + 1];
            
            boolean cornerUL = (r == 0 || c == 0) || mapData[r-1][c-1] == '#';
            boolean cornerUR = (r == 0 || c == width-1) || mapData[r-1][c+1] == '#';
            boolean cornerDL = (r == height-1 || c == 0) || mapData[r+1][c-1] == '#';
            boolean cornerDR = (r == height-1 || c == width-1) || mapData[r+1][c+1] == '#';

            if (wallLeft && ((boxUp && cornerUL) || (boxDown && cornerDL))) return true;
            if (wallRight && ((boxUp && cornerUR) || (boxDown && cornerDR))) return true;
            if (wallUp && ((boxLeft && cornerUL) || (boxRight && cornerUR))) return true;
            if (wallDown && ((boxLeft && cornerDL) || (boxRight && cornerDR))) return true;
        }
        return false;
    }

    private void initZobristTable() {
        Random rnd = new Random(12345); 
        zobristTable = new long[width * height][2];
        for (int i = 0; i < width * height; i++) {
            zobristTable[i][0] = rnd.nextLong(); 
            zobristTable[i][1] = rnd.nextLong(); 
        }
    }
}
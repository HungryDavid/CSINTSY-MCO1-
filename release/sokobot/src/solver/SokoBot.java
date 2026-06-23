package solver;

import java.util.*;

public class SokoBot {

    // --- GLOBAL VARIABLES ---
    private boolean[][] deadTiles;
    private int[][][] trueDistances;
    private int width, height;
    private List<Integer> targets;
    private boolean[] isTargetTile; 
    
    // Zero-Allocation BFS Memory
    private int[] reachable;
    private String[] movePaths;
    private int bfsToken = 0;

    // Zero-Allocation Crate Map for O(1) Lookups
    private boolean[] crateMap;

    private int heuristicWeight = 4;

    // Direction constants (hoisted out of loop)
    private static final int[] PUSH_DR = {-1, 1, 0, 0};
    private static final int[] PUSH_DC = {0, 0, -1, 1};
    private static final char[] PUSH_CHARS = {'u', 'd', 'l', 'r'};

    class GameState implements Comparable<GameState> {
        int playerR, playerC;
        List<Integer> cratePositions;
        String path;
        int h; 
        int gCost; 
        int lastPushedPos; 

        public GameState(int pr, int pc, List<Integer> crates, String path, int gCost, int lastPushedPos) {
            this.playerR = pr;
            this.playerC = pc;
            this.cratePositions = new ArrayList<>(crates);
            Collections.sort(this.cratePositions);
            this.path = path;
            this.h = calculateHeuristic(this.cratePositions);
            this.gCost = gCost; 
            this.lastPushedPos = lastPushedPos;
        }

        @Override
        public int compareTo(GameState other) {
            int thisScore = this.gCost + (heuristicWeight * this.h); 
            int otherScore = other.gCost + (heuristicWeight * other.h);

            if (thisScore == otherScore) {
                return Integer.compare(this.h, other.h); 
            }
            return Integer.compare(thisScore, otherScore);
        }
    }

    public String solveSokobanPuzzle(int w, int h, char[][] mapData, char[][] itemsData) {
        this.width = w;
        this.height = h;
        long startTime = System.currentTimeMillis();

        int mapSize = width * height;
        reachable = new int[mapSize];
        movePaths = new String[mapSize];
        crateMap = new boolean[mapSize];

        initTargets(mapData);       
        initDeadTiles(mapData);     
        initTrueDistances(mapData);

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

        if (startCrates.size() >= 11) {
            heuristicWeight = 4;
        } else {
            heuristicWeight = 6;
        }

        PriorityQueue<GameState> queue = new PriorityQueue<>();
        HashSet<StateKey> visited = new HashSet<>();

        GameState initialState = new GameState(startPr, startPc, startCrates, "", 0, -1);
        if (initialState.h >= 999999) {
            return ""; // Deadlocked initial state
        }
        queue.add(initialState);

        // Transition loop
        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() - startTime > 14000) {
                System.out.println("Time limit reached! Returning best effort.");
                return queue.peek().path; 
            }

            GameState curr = queue.poll();

            // Populate crateMap for O(1) lookups
            for (int i = 0; i < curr.cratePositions.size(); i++) {
                crateMap[curr.cratePositions.get(i)] = true;
            }

            int normalizedPlayerID = runZeroAllocationBFS(curr.playerR, curr.playerC, mapData);

            // Use add() return value: returns false if already present
            StateKey key = new StateKey(normalizedPlayerID, curr.cratePositions);
            if (!visited.add(key)) {
                // Already visited — clear crateMap and skip
                for (int i = 0; i < curr.cratePositions.size(); i++) {
                    crateMap[curr.cratePositions.get(i)] = false;
                }
                continue; 
            }

            if (curr.h == 0) { 
                return curr.path; 
            }

            for (int i = 0; i < curr.cratePositions.size(); i++) {
                int cratePos = curr.cratePositions.get(i);
                int cr = cratePos / width;
                int cc = cratePos - cr * width;

                // Temporarily remove this crate from crateMap for tunnel sliding check
                crateMap[cratePos] = false;

                for (int dir = 0; dir < 4; dir++) {
                    int pushStandR = cr - PUSH_DR[dir];
                    int pushStandC = cc - PUSH_DC[dir];
                    int pushStandPos = pushStandR * width + pushStandC;

                    if (pushStandPos < 0 || pushStandPos >= mapSize || reachable[pushStandPos] != bfsToken) continue;

                    int newCrateR = cr + PUSH_DR[dir];
                    int newCrateC = cc + PUSH_DC[dir];
                    int newCratePos = newCrateR * width + newCrateC;

                    // O(1) occupancy check
                    if (mapData[newCrateR][newCrateC] == '#' || crateMap[newCratePos]) {
                        continue; 
                    }

                    List<Integer> nextCrates = new ArrayList<>(curr.cratePositions);
                    nextCrates.remove(Integer.valueOf(cratePos)); 
                    nextCrates.add(newCratePos);

                    int slideR = newCrateR;
                    int slideC = newCrateC;
                    int playerWalkR = cr;
                    int playerWalkC = cc;
                    int slidePushes = 1;
                    StringBuilder tunnelPath = new StringBuilder();
                    tunnelPath.append(PUSH_CHARS[dir]);

                    while (true) {
                        boolean isHorizTunnel = mapData[slideR - 1][slideC] == '#' && mapData[slideR + 1][slideC] == '#';
                        boolean isVertTunnel = mapData[slideR][slideC - 1] == '#' && mapData[slideR][slideC + 1] == '#';
                        
                        if (isTargetTile[slideR * width + slideC]) break;
                        
                        if ((dir == 0 || dir == 1) && !isVertTunnel) break; 
                        if ((dir == 2 || dir == 3) && !isHorizTunnel) break; 

                        int nextSlideR = slideR + PUSH_DR[dir];
                        int nextSlideC = slideC + PUSH_DC[dir];
                        int nextPos = nextSlideR * width + nextSlideC;

                        // O(1) crate check
                        if (mapData[nextSlideR][nextSlideC] == '#' || crateMap[nextPos] || deadTiles[nextSlideR][nextSlideC]) {
                            break;
                        }

                        nextCrates.remove(Integer.valueOf(slideR * width + slideC));
                        nextCrates.add(nextPos);
                        playerWalkR = slideR;
                        playerWalkC = slideC;
                        slideR = nextSlideR;
                        slideC = nextSlideC;
                        tunnelPath.append(PUSH_CHARS[dir]);
                        slidePushes++;
                    }

                    int slidePos = slideR * width + slideC;
                    
                    // Temporarily set the new crate position in crateMap for deadlock checks
                    crateMap[slidePos] = true;

                    boolean deadlock = deadTiles[slideR][slideC] || 
                                       isTwoByTwoDeadlock(slideR, slideC, mapData) ||
                                       isFrozenDeadlock(nextCrates, mapData);

                    if (deadlock) {
                        crateMap[slidePos] = false;
                        continue;
                    }

                    String walkPath = movePaths[pushStandPos];
                    int walkCost = walkPath.length();
                    
                    int targetLockPenalty = (isTargetTile[cratePos] && !isTargetTile[slidePos]) ? 10 : 0;
                    int switchPenalty = (curr.lastPushedPos != -1 && curr.lastPushedPos != cratePos) ? 5 : 0;
                    
                    int newGCost = curr.gCost + walkCost + targetLockPenalty + slidePushes + switchPenalty;
                    String fullPath = curr.path + walkPath + tunnelPath.toString();

                    GameState nextState = new GameState(playerWalkR, playerWalkC, nextCrates, fullPath, newGCost, slidePos);
                    
                    // Prune deadlocked states (h >= 999999)
                    if (nextState.h < 999999) {
                        queue.add(nextState);
                    }

                    crateMap[slidePos] = false; // Restore
                }

                // Restore this crate in crateMap
                crateMap[cratePos] = true;
            }

            // Clear crateMap for curr.cratePositions
            for (int i = 0; i < curr.cratePositions.size(); i++) {
                crateMap[curr.cratePositions.get(i)] = false;
            }
        }

        return ""; 
    }

    private int calculateHeuristic(List<Integer> crates) {
        int totalDistance = 0;
        int n = crates.size();
        int numTargets = targets.size();
        boolean[] targetUsed = new boolean[numTargets];
        boolean[] crateUsed = new boolean[n];

        int[] crateR = new int[n];
        int[] crateC = new int[n];
        for (int i = 0; i < n; i++) {
            int pos = crates.get(i);
            crateR[i] = pos / width;
            crateC[i] = pos - crateR[i] * width;
        }

        for (int step = 0; step < n; step++) {
            int minDistance = 999999;
            int bestCrateIndex = -1;
            int bestTargetIndex = -1;

            for (int c = 0; c < n; c++) {
                if (crateUsed[c]) continue;
                int cr = crateR[c];
                int cc = crateC[c];

                for (int t = 0; t < numTargets; t++) {
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
                if (minDistance >= 999999) {
                    return 999999; // Deadlock prune
                }
                crateUsed[bestCrateIndex] = true;
                targetUsed[bestTargetIndex] = true;
                totalDistance += minDistance;
            } else {
                return 999999; // Deadlock prune
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
        
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (deadTiles[r][c]) {
                    if (mapData[r - 1][c] == '#' || mapData[r + 1][c] == '#') {
                        verifyAndMarkLine(r, c, 0, 1, mapData); 
                    }
                    if (mapData[r][c - 1] == '#' || mapData[r][c + 1] == '#') {
                        verifyAndMarkLine(r, c, 1, 0, mapData); 
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
            if (isTargetTile[r * width + c]) return; 
            
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
            
            if (!hasWallSide1 && !hasWallSide2) return; 
            
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

    private boolean isTwoByTwoDeadlock(int crateR, int crateC, char[][] mapData) {
        int[][] quadrants = {{-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        for (int[] quad : quadrants) {
            int r = crateR + quad[0];
            int c = crateC + quad[1];

            if (isWallOrCrate(r, c, mapData) &&
                isWallOrCrate(r + 1, c, mapData) &&
                isWallOrCrate(r, c + 1, mapData) &&
                isWallOrCrate(r + 1, c + 1, mapData)) {
                
                if (isCrateNotOnTarget(r, c) ||
                    isCrateNotOnTarget(r + 1, c) ||
                    isCrateNotOnTarget(r, c + 1) ||
                    isCrateNotOnTarget(r + 1, c + 1)) {
                    return true; 
                }
            }
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

    private void initTrueDistances(char[][] mapData) {
        trueDistances = new int[targets.size()][height][width];
        
        for (int t = 0; t < targets.size(); t++) {
            int target = targets.get(t);
            int tr = target / width;
            int tc = target - tr * width;

            for (int r = 0; r < height; r++) {
                Arrays.fill(trueDistances[t][r], 999999);
            }

            ArrayDeque<int[]> queue = new ArrayDeque<>();
            trueDistances[t][tr][tc] = 0;
            queue.add(new int[]{tr, tc});

            while (!queue.isEmpty()) {
                int[] curr = queue.poll();
                int r = curr[0];
                int c = curr[1];
                int currentDist = trueDistances[t][r][c];

                for (int i = 0; i < 4; i++) {
                    int nr = r + PUSH_DR[i]; // Box previous position
                    int nc = c + PUSH_DC[i];
                    
                    int pr = r + 2 * PUSH_DR[i]; // Player position before push
                    int pc = c + 2 * PUSH_DC[i];

                    if (nr >= 0 && nr < height && nc >= 0 && nc < width && mapData[nr][nc] != '#' &&
                        pr >= 0 && pr < height && pc >= 0 && pc < width && mapData[pr][pc] != '#') {
                        
                        if (currentDist + 1 < trueDistances[t][nr][nc]) {
                            trueDistances[t][nr][nc] = currentDist + 1;
                            queue.add(new int[]{nr, nc});
                        }
                    }
                }
            }
        }
    }

    private int[] bfsQueue;

    private int runZeroAllocationBFS(int startPr, int startPc, char[][] mapData) {
        bfsToken++; 
        int startPos = startPr * width + startPc;
        int normalizedPos = startPos; 

        if (bfsQueue == null || bfsQueue.length < width * height) {
            bfsQueue = new int[width * height];
        }

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

    private boolean isFrozenDeadlock(List<Integer> crates, char[][] mapData) {
        for (int i = 0; i < crates.size(); i++) {
            int cratePos = crates.get(i);
            int r = cratePos / width;
            int c = cratePos - r * width;
            
            if (isTargetTile[cratePos]) continue; 

            boolean wallUp = mapData[r-1][c] == '#';
            boolean wallDown = mapData[r+1][c] == '#';
            boolean wallLeft = mapData[r][c-1] == '#';
            boolean wallRight = mapData[r][c+1] == '#';
            
            boolean boxUp = crateMap[(r-1)*width + c];
            boolean boxDown = crateMap[(r+1)*width + c];
            boolean boxLeft = crateMap[r*width + c - 1];
            boolean boxRight = crateMap[r*width + c + 1];
            
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

    static class StateKey {
        final int playerID;
        final List<Integer> crates;
        private final int hashCode;

        public StateKey(int playerID, List<Integer> crates) {
            this.playerID = playerID;
            this.crates = crates;
            
            int hash = 17;
            hash = 31 * hash + playerID;
            for (int i = 0; i < crates.size(); i++) {
                hash = 31 * hash + crates.get(i);
            }
            this.hashCode = hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof StateKey)) return false;
            StateKey other = (StateKey) obj;
            if (this.playerID != other.playerID) return false;
            if (this.crates.size() != other.crates.size()) return false;
            for (int i = 0; i < crates.size(); i++) {
                if (!this.crates.get(i).equals(other.crates.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
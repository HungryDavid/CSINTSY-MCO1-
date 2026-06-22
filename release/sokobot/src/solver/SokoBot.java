package solver;

import java.util.*;

public class SokoBot {

    // 1. Declare class-level arrays outside methods (no size allocation yet)
    private int[] globalReachable;
    private int bfsToken = 0;

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
        long startTime = System.currentTimeMillis();

        // 2. Allocate memory once right here at the start of the puzzle!
        globalReachable = new int[width * height];
        bfsToken = 0;
        // =========================================================
        // 1. ROBUST BOARD PARSING (Supports combined layers & symbols)
        // =========================================================
        List<Integer> targetNodes = new ArrayList<>();
        List<Integer> startBoxNodes = new ArrayList<>();
        int startPlayerPos = -1;

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int posId = r * width + c; 
                
                // Safe lookup in case rows have varying lengths
                char m = (r < mapData.length && c < mapData[r].length) ? mapData[r][c] : ' ';
                char i = (itemsData != null && r < itemsData.length && c < itemsData[r].length) ? itemsData[r][c] : ' ';
                
                // Targets can be represented as '.', '*' (box on target), or '+' (player on target)
                if (m == '.' || i == '.' || m == '*' || i == '*' || m == '+' || i == '+') {
                    targetNodes.add(posId);
                }
                // Player can be '@' or '+'
                if (m == '@' || i == '@' || m == '+' || i == '+') {
                    startPlayerPos = posId;
                }
                // Boxes can be '$' or '*'
                if (m == '$' || i == '$' || m == '*' || i == '*') {
                    startBoxNodes.add(posId);
                }
            }
        }

        int[] startBoxes = startBoxNodes.stream().mapToInt(box -> box).toArray();
        int[] targets = targetNodes.stream().mapToInt(target -> target).toArray();
        
        Arrays.sort(startBoxes);
        Arrays.sort(targets);

        // Safety fallback: if parsing completely failed, abort safely
        if (startPlayerPos == -1 || startBoxes.length == 0 || targets.length == 0) {
            return "";
        }

        // =========================================================
        // 2. DEADLOCK MAP (Pre-computing unplayable tiles)
        // =========================================================
        boolean[] staticDeadlockMap = new boolean[width * height];

        // PASS 1: Find all the Corners (Passed targets here)
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int posId = r * width + c;
                if (r < mapData.length && c < mapData[r].length && mapData[r][c] == '#') continue;

                if (isDeadlock(posId, width, mapData, targets)) {
                    staticDeadlockMap[posId] = true;
                }
            }
        }

        // PASS 2: Connect corners to find Dead Walls (Passed targets here)
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                int posId = r * width + c;
                if (staticDeadlockMap[posId]) {
                    scanAndMarkDeadWall(r, c, 0, 1, staticDeadlockMap, mapData, width, height, targets); 
                    scanAndMarkDeadWall(r, c, 1, 0, staticDeadlockMap, mapData, width, height, targets); 
                }
            }
        }
        
        // =========================================================
        // 3. INITIAL STATE CREATION (Normalizing the starting point)
        // =========================================================
        // Create your initial state safely
        int[] initialReachable = getReachableTiles(startPlayerPos, startBoxes, mapData, width, height);
        int initialNormalizedPlayer = getNormalizedPlayerPos(initialReachable);
        int initialHCost = getHeuristic(startBoxes, targets, exactDistances);
        
        // ADDED -1 HERE AS THE 6TH ARGUMENT!
        State initialState = new State(startBoxes, startPlayerPos, initialNormalizedPlayer, 0, initialHCost, -1, "");
        

        // =========================================================
        // 4. A* GRAPH SEARCH LOOP
        // =========================================================
        PriorityQueue<State> openSet = new PriorityQueue<>(new Comparator<State>() {
            @Override
            public int compare(State s1, State s2) {
                int fCompare = Integer.compare(s1.fCost, s2.fCost);
                if (fCompare == 0) {
                    // Tie-breaker: If costs are equal, pick the one closer to the goal
                    return Integer.compare(s1.hCost, s2.hCost);
                }
                return fCompare;
            }
        });

        // Initialize the first state using the new constructor
        


        State initialState = new State(startBoxes, startPlayerPos, initialNormalizedPlayer, 0, initialHCost, -1, "");

        Set<State> closedSet = new HashSet<>();
        openSet.add(initialState);

        while (!openSet.isEmpty()) {
            State curr = openSet.poll();

            if (closedSet.contains(curr)) continue;
            closedSet.add(curr);

            // Win Condition
            if (Arrays.equals(curr.boxPositions, targets)) {
                System.out.println("SOLUTION FOUND: " + curr.path);
                return curr.path;
            }

            // Change it to this:
            for (State nextState : getSuccessors(curr, mapData, staticDeadlockMap, width, height, targets, exactDistances)) {
                if (!closedSet.contains(nextState)) {
                    openSet.add(nextState);
                }
            }
        }

        System.out.println("NO SOLUTION, path length: " + "".length());
        return "";
    }

    private boolean isDeadlock(int pos, int width, char[][] mapData, int[] targets) {
        int r = pos / width;
        int c = pos % width;
        int height = mapData.length;
    
        if (Arrays.binarySearch(targets, pos) >= 0) return false;
    
        boolean wallUp    = (r == 0)          || mapData[r - 1][c] == '#';
        boolean wallDown  = (r == height - 1) || mapData[r + 1][c] == '#';
        boolean wallLeft  = (c == 0)          || mapData[r][c - 1] == '#';
        boolean wallRight = (c == width - 1)  || mapData[r][c + 1] == '#';
    
        // Only a deadlock if it's a TRUE corner: blocked on BOTH horizontal AND vertical
        if ((wallUp && wallDown)) return false; // open corridor vertically, not a corner
        if ((wallLeft && wallRight)) return false; // open corridor horizontally, not a corner
    
        return (wallUp && wallLeft) || (wallUp && wallRight) || (wallDown && wallLeft) || (wallDown && wallRight);
    }
    
    private void scanAndMarkDeadWall(int startR, int startC, int rowDir, int colDir, boolean[] deadlockMap, char[][] mapData, int width, int height, int[] targets) {
        int r = startR + rowDir;
        int c = startC + colDir;
        
        List<Integer> path = new ArrayList<>();
        boolean wallSide1Continuous = true;
        boolean wallSide2Continuous = true;
    
        int side1R = colDir; 
        int side1C = rowDir; 
        int side2R = -colDir; 
        int side2C = -rowDir;
    
        while (r > 0 && r < height - 1 && c > 0 && c < width - 1) {
            int pos = r * width + c;
            
            if (c >= mapData[r].length) return;
            if (mapData[r][c] == '#') return;
            if (Arrays.binarySearch(targets, pos) >= 0) return; // Fixed target validation
    
            if (c + side1C >= mapData[r + side1R].length || mapData[r + side1R][c + side1C] != '#') wallSide1Continuous = false;
            if (c + side2C >= mapData[r + side2R].length || mapData[r + side2R][c + side2C] != '#') wallSide2Continuous = false;
    
            if (!wallSide1Continuous && !wallSide2Continuous) return;
    
            if (deadlockMap[pos]) {
                for (int p : path) {
                    deadlockMap[p] = true;
                }
                return;
            }
    
            path.add(pos);
            r += rowDir;
            c += colDir;
        }
    }

    private int[] getReachableTiles(int playerPos, int[] boxPositions, char[][] mapData, int width, int height) {
        bfsToken++; // Token increments to instantly "clear" the array without reallocating memory!
        
        boolean[] hasBox = new boolean[width * height];
        for (int box : boxPositions) hasBox[box] = true;

        Queue<Integer> queue = new ArrayDeque<>(); 
        queue.add(playerPos);
        globalReachable[playerPos] = bfsToken;

        // Up, Down, Left, Right offsets
        int[] dirs = {-width, width, -1, 1};

        while (!queue.isEmpty()) {
            int curr = queue.poll();
            int cr = curr / width;
            int cc = curr % width;

            for (int dir : dirs) {
                int next = curr + dir;
                int nr = next / width;
                int nc = next % width;

                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                if (mapData[nr].length <= nc) continue;

                if (mapData[nr][nc] == '#' || hasBox[next]) continue;

                if (globalReachable[next] != bfsToken) {
                    globalReachable[next] = bfsToken;
                    queue.add(next);
                }
            }
        }
        return globalReachable;
    }

    private int getNormalizedPlayerPos(int[] reachableTiles) {
        for (int i = 0; i < reachableTiles.length; i++) {
            if (reachableTiles[i] == bfsToken) {
                return i; 
            }
        }
        return -1;
    }

    // Change it to this:
    private List<State> getSuccessors(State state, char[][] mapData, boolean[] staticDeadlockMap, int width, int height, int[] targets, int[][] exactDistances) {
        List<State> successors = new ArrayList<>();
        int[] boxes = state.boxPositions;
        
        int[] reachable = getReachableTiles(state.actualPlayerPos, boxes, mapData, width, height);
        
        boolean[] hasBox = new boolean[width * height];
        for (int b : boxes) hasBox[b] = true;
        
        int[] rowDirs = {-1, 1, 0, 0};
        int[] colDirs = {0, 0, -1, 1};
        char[] pushChars = {'u', 'd', 'l', 'r'}; // Or uppercase 'U','D','L','R' if your platform requires it

        for (int i = 0; i < boxes.length; i++) {
            int boxPos = boxes[i];
            int br = boxPos / width;
            int bc = boxPos % width;
            
            for (int d = 0; d < 4; d++) {
                int rDir = rowDirs[d];
                int cDir = colDirs[d];
                
                int pr = br - rDir; 
                int pc = bc - cDir;
                int nr = br + rDir; 
                int nc = bc + cDir;
                
                if (pr < 0 || pr >= height || pc < 0 || pc >= width) continue;
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                if (mapData[nr].length <= nc || mapData[pr].length <= pc) continue;
                
                int playerPushPos = pr * width + pc;
                int nextBoxPos = nr * width + nc;
                
                // Legality Checks
                if (reachable[playerPushPos] != bfsToken) continue;
                if (mapData[nr][nc] == '#' || hasBox[nextBoxPos]) continue;
                if (staticDeadlockMap[nextBoxPos]) continue;
                if (creates2x2Deadlock(nextBoxPos, boxes, boxPos, mapData, width, targets)) continue;
                
                // Pathfinding & Setup
                String walkPath = findWalkPath(state.actualPlayerPos, playerPushPos, boxes, mapData, width, height);
                String fullMoveSequence = walkPath + pushChars[d];
                
                int[] nextBoxes = boxes.clone();
                nextBoxes[i] = nextBoxPos;
                Arrays.sort(nextBoxes);
                
                int nextActualPlayer = boxPos;
                int[] nextReachable = getReachableTiles(nextActualPlayer, nextBoxes, mapData, width, height);
                int nextNormalizedPlayer = getNormalizedPlayerPos(nextReachable);
                int nextHCost = getHeuristic(nextBoxes, targets, exactDistances);

                // =========================================================
                // THE FOCUS RULES (Safely inside the loop!)
                // =========================================================
                boolean wasOnTarget = Arrays.binarySearch(targets, boxPos) >= 0;
                boolean willBeOnTarget = Arrays.binarySearch(targets, nextBoxPos) >= 0;
                int targetLockPenalty = (wasOnTarget && !willBeOnTarget) ? 100 : 0;

                int switchPenalty = (state.lastPushedBoxPos != -1 && state.lastPushedBoxPos != boxPos) ? 15 : 0;

                int newGCost = state.gCost + 1 + walkPath.length() + targetLockPenalty + switchPenalty;

                // Add the valid state to successors (Requires 7 arguments)
                successors.add(new State(nextBoxes, nextActualPlayer, nextNormalizedPlayer, newGCost, nextHCost, nextBoxPos, state.path + fullMoveSequence));
            }
        }
        return successors;
    }

    private String findWalkPath(int start, int end, int[] boxPositions, char[][] mapData, int width, int height) {
        if (start == end) return "";
        
        boolean[] hasBox = new boolean[width * height];
        for (int b : boxPositions) hasBox[b] = true;
        
        Queue<Integer> queue = new LinkedList<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Character> moveChar = new HashMap<>();
        
        queue.add(start);
        parent.put(start, -1);
        
        int[] dr = {-1, 1, 0, 0};
        int[] dc = {0, 0, -1, 1};
        char[] dirs = {'u', 'd', 'l', 'r'};
        
        boolean found = false;
        while (!queue.isEmpty()) {
            int curr = queue.poll();
            if (curr == end) {
                found = true;
                break;
            }
            
            int cr = curr / width;
            int cc = curr % width;

            for (int i = 0; i < 4; i++) {
                int nr = cr + dr[i];
                int nc = cc + dc[i];
                
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                if (nc >= mapData[nr].length) continue;

                int next = nr * width + nc;
                if (mapData[nr][nc] == '#' || hasBox[next]) continue;
                
                if (!parent.containsKey(next)) {
                    parent.put(next, curr);
                    moveChar.put(next, dirs[i]);
                    queue.add(next);
                }
            }
        }
        
        if (!found) return "";
        
        StringBuilder sb = new StringBuilder();
        int curr = end;
        while (curr != start) {
            sb.append(moveChar.get(curr));
            curr = parent.get(curr);
        }
        return sb.reverse().toString();
    }

    private int getHeuristic(int[] boxPositions, int[] targets, int[][] exactDistances) {
        int totalDistance = 0;
        long targetMatched = 0L; 
        long boxMatched = 0L;    

        for (int step = 0; step < boxPositions.length; step++) {
            int globalMin = Integer.MAX_VALUE;
            int bestB = -1;
            int bestT = -1;

            for (int b = 0; b < boxPositions.length; b++) {
                if ((boxMatched & (1L << b)) != 0) continue; 
                int boxPos = boxPositions[b];

                for (int t = 0; t < targets.length; t++) {
                    if ((targetMatched & (1L << t)) != 0) continue; 
                    
                    int dist = exactDistances[t][boxPos];
                    if (dist < globalMin) {
                        globalMin = dist;
                        bestB = b;
                        bestT = t;
                    }
                }
            }

            // If a box cannot reach ANY target, this state is impossible! Punish it heavily.
            if (globalMin >= Integer.MAX_VALUE / 2) return Integer.MAX_VALUE / 2; 

            if (bestB != -1 && bestT != -1) {
                boxMatched |= (1L << bestB);
                targetMatched |= (1L << bestT);
                totalDistance += globalMin;
            }
        }
        return totalDistance;
    }

    // =========================================================
    // UPGRADED 2X2 DEADLOCK LOGIC
    // =========================================================
    private boolean creates2x2Deadlock(int newBoxPos, int[] currentBoxes, int oldBoxPos, char[][] mapData, int width, int[] targets) {
        Set<Integer> boxes = new HashSet<>();
        for (int b : currentBoxes) {
            if (b != oldBoxPos) boxes.add(b);
        }
        boxes.add(newBoxPos);
        
        int r = newBoxPos / width;
        int c = newBoxPos % width;
        
        int[][] checks = {{-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        for (int[] offset : checks) {
            int br = r + offset[0];
            int bc = c + offset[1];
            
            if (br >= 0 && bc >= 0) {
                if (is2x2Filled(br, bc, boxes, mapData, width)) {
                    // It is ONLY a deadlock if we have a box in here that isn't on a target!
                    if (hasBoxOffTarget(br, bc, boxes, width, targets)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasBoxOffTarget(int r, int c, Set<Integer> boxes, int width, int[] targets) {
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int pos = (r + dr) * width + (c + dc);
                if (boxes.contains(pos)) {
                    if (Arrays.binarySearch(targets, pos) < 0) return true; 
                }
            }
        }
        return false;
    }

    // Fixed: Changed "return true;" to "continue;" so out-of-bounds acts like a wall without breaking the loop
    private boolean is2x2Filled(int r, int c, Set<Integer> boxes, char[][] mapData, int width) {
        int height = mapData.length;
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int nr = r + dr;
                int nc = c + dc;
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue; 
                if (nc >= mapData[nr].length) continue;
                if (mapData[nr][nc] == '#') continue;
                if (boxes.contains(nr * width + nc)) continue;
                return false;
            }
        }
        return true;
    }

    private boolean is2x2AllTargets(int r, int c, char[][] mapData, int width, int[] targets) {
        int height = mapData.length;
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int nr = r + dr;
                int nc = c + dc;
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) return false;
                if (nc >= mapData[nr].length) return false;
                
                int posId = nr * width + nc;
                
                // If the position ID isn't found in the targets array, it's not a target
                if (Arrays.binarySearch(targets, posId) < 0) {
                    return false; 
                }
            }
        }
        return true;
    }

    // Calculates true walking distances from targets to every tile on the board, respecting walls.
    private int[][] precomputeExactDistances(int[] targets, int width, int height, char[][] mapData) {
        int[][] distMatrix = new int[targets.length][width * height];
        for (int[] row : distMatrix) Arrays.fill(row, Integer.MAX_VALUE / 2);

        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        for (int i = 0; i < targets.length; i++) {
            int targetId = targets[i];
            Queue<Integer> q = new ArrayDeque<>();
            q.add(targetId);
            distMatrix[i][targetId] = 0;

            while (!q.isEmpty()) {
                int curr = q.poll();
                int r = curr / width;
                int c = curr % width;

                for (int[] d : dirs) {
                    int nr = r + d[0];
                    int nc = c + d[1];

                    if (nr >= 0 && nr < height && nc >= 0 && nc < width && mapData[nr][nc] != '#') {
                        int nId = nr * width + nc;
                        if (distMatrix[i][nId] > distMatrix[i][curr] + 1) {
                            distMatrix[i][nId] = distMatrix[i][curr] + 1;
                            q.add(nId);
                        }
                    }
                }
            }
        }
        return distMatrix;
    }
}
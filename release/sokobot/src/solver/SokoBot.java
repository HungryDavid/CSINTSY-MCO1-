package solver;

import java.util.*;

public class SokoBot {

    static class State implements Comparable<State> {
        int actualPlayerPos; 
        int normalizedPlayerPos = -1; // Calculated during BFS to neutralize open space
        int[] boxPositions;
        int gCost; 
        int hCost; 
        String path; 

        public State(int actualPlayerPos, int[] boxes, int gCost, int hCost, String path) {
            this.actualPlayerPos = actualPlayerPos;
            this.boxPositions = boxes;
            this.gCost = gCost;
            this.hCost = hCost;
            this.path = path;
        }

        public int getFCost() {
          // WEIGHTED A*: Multiply the heuristic by a large weight (e.g., 5 or 10)
          // This sacrifices guaranteed optimal path length for massive speed gains.
          return gCost + (5 * hCost); 
      }

        @Override 
        public int compareTo(State other) { 
            int fCompare = Integer.compare(this.getFCost(), other.getFCost());
            if (fCompare == 0) {
                return Integer.compare(this.hCost, other.hCost); 
            }
            return fCompare;
        }

        @Override 
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State state = (State) o;
            // Equality is based on the normalized region, not exact coordinates
            return normalizedPlayerPos == state.normalizedPlayerPos && 
                   Arrays.equals(this.boxPositions, state.boxPositions); 
        }

        @Override
        public int hashCode() { 
            int result = Objects.hash(normalizedPlayerPos);
            return 31 * result + Arrays.hashCode(boxPositions);
        }
    }

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
        long startTime = System.currentTimeMillis();
        long timeLimit = 14500; // 14.5 seconds bailout timer

        List<Integer> targetList = new ArrayList<>();
        List<Integer> startBoxList = new ArrayList<>();
        int startPId = -1;
        
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int posId = r * width + c; 
                if (mapData[r][c] == '.') targetList.add(posId);
                if (itemsData[r][c] == '@') {
                    startPId = posId;
                } else if (itemsData[r][c] == '$') {
                    startBoxList.add(posId);
                }
            }
        }

        int[] targets = targetList.stream().mapToInt(i -> i).toArray();
        int[] startBoxes = startBoxList.stream().mapToInt(i -> i).toArray();
        Arrays.sort(startBoxes);
        Arrays.sort(targets);
        
        boolean[][] deadlockGrid = precomputeDeadlocks(width, height, mapData, targetList);
        
        PriorityQueue<State> openList = new PriorityQueue<>();
        Set<State> visited = new HashSet<>();
        
        int initialH = calculateGreedyHeuristic(startBoxes, targets, width);
        State startState = new State(startPId, startBoxes, 0, initialH, "");
        openList.add(startState);

        State bestPartialState = startState;
        int[][] directions = {{-1, 0, 'u'}, {1, 0, 'd'}, {0, -1, 'l'}, {0, 1, 'r'}};

        while (!openList.isEmpty()) {
            // Check bailout timer to prevent failure
            if (System.currentTimeMillis() - startTime > timeLimit) {
                return bestPartialState.path; 
            }

            State current = openList.poll();
            
            // Check win condition
            if (Arrays.equals(current.boxPositions, targets)) {
                return current.path; 
            }

            // Track best state in case of timeout
            if (current.hCost < bestPartialState.hCost) {
                bestPartialState = current;
            }

            // --- MACRO-MOVE BFS (Flood Fill) ---
            Queue<Integer> bfsQueue = new ArrayDeque<>();
            bfsQueue.add(current.actualPlayerPos);
            
            boolean[] reachable = new boolean[width * height];
            reachable[current.actualPlayerPos] = true;
            
            int[] parent = new int[width * height];
            Arrays.fill(parent, -1);
            char[] moveLog = new char[width * height];
            
            int topOfRoomPosId = current.actualPlayerPos;

            // Phase 1: Flood the room and find normalized pos
            while (!bfsQueue.isEmpty()) {
                int currId = bfsQueue.poll();
                if (currId < topOfRoomPosId) topOfRoomPosId = currId; // Normalize

                int r = currId / width;
                int c = currId % width;

                for (int[] dir : directions) {
                    int nr = r + dir[0];
                    int nc = c + dir[1];
                    int nId = nr * width + nc;

                    if (nr < 0 || nr >= height || nc < 0 || nc >= width || mapData[nr][nc] == '#') continue;

                    // If it's a box, we can't walk on it, but we note we can reach its edge
                    if (Arrays.binarySearch(current.boxPositions, nId) >= 0) {
                        continue; 
                    } else if (!reachable[nId]) {
                        reachable[nId] = true;
                        parent[nId] = currId;
                        moveLog[nId] = (char) dir[2];
                        bfsQueue.add(nId);
                    }
                }
            }

            // Lock in the normalized position and check if visited
            current.normalizedPlayerPos = topOfRoomPosId;
            if (visited.contains(current)) continue;
            visited.add(current);

            // Phase 2: Generate Crate Pushes from reachable area
            for (int posId = 0; posId < width * height; posId++) {
                if (!reachable[posId]) continue; // Only process edges player can stand on

                int pr = posId / width;
                int pc = posId % width;

                for (int[] dir : directions) {
                    int br = pr + dir[0];
                    int bc = pc + dir[1];
                    int boxId = br * width + bc;

                    // If player is next to a box...
                    if (br >= 0 && br < height && bc >= 0 && bc < width && Arrays.binarySearch(current.boxPositions, boxId) >= 0) {
                        
                        // Check if we can push it
                        int pushR = br + dir[0];
                        int pushC = bc + dir[1];
                        int pushId = pushR * width + pushC;

                        if (pushR >= 0 && pushR < height && pushC >= 0 && pushC < width) {
                            // ... (inside Phase 2's valid push check) ...

                            if (mapData[pushR][pushC] != '#' && !deadlockGrid[pushR][pushC] && Arrays.binarySearch(current.boxPositions, pushId) < 0) {
                                
                              // Valid Push! Generate the move
                              String walkPath = reconstructPath(posId, current.actualPlayerPos, parent, moveLog);
                              char pushMoveChar = (char) dir[2];

                              int[] newBoxes = current.boxPositions.clone();
                              int bIdx = Arrays.binarySearch(newBoxes, boxId);
                              newBoxes[bIdx] = pushId;
                              Arrays.sort(newBoxes);

                              // ==========================================
                              // PLACE THE DEADLOCK CHECK RIGHT HERE
                              // (Notice we pass 'newBoxes' to check the future state)
                              // ==========================================
                              if (isDynamicDeadlock(pushR, pushC, newBoxes, targets, mapData, width, height)) {
                                  continue; // Prune this branch, we formed an unmovable block!
                              }

                              // Calculate the actual length of the walk
                              int walkCost = walkPath.length();
                              int newH = calculateGreedyHeuristic(newBoxes, targets, width);

                              // Add the walkCost to the gCost to penalize wandering aimlessly
                              State nextState = new State(boxId, newBoxes, current.gCost + walkCost + 1, newH, current.path + walkPath + pushMoveChar);

                              openList.add(nextState);
                            }
                        }
                    }
                }
            }
        }
        return bestPartialState.path; 
    }

    // Helper to extract the walk path from the BFS
    private String reconstructPath(int targetNode, int startNode, int[] parent, char[] moveLog) {
        StringBuilder sb = new StringBuilder();
        int curr = targetNode;
        while (curr != startNode && curr != -1) {
            sb.append(moveLog[curr]);
            curr = parent[curr];
        }
        return sb.reverse().toString();
    }

    public int calculateGreedyHeuristic(int[] boxPositions, int[] targets, int width) {
        int totalDistance = 0;
        boolean[] targetMatched = new boolean[targets.length];

        for (int box : boxPositions) {
            int br = box / width;
            int bc = box % width;
            int minBoxDist = Integer.MAX_VALUE; 
            int bestTargetIdx = -1;

            for (int i = 0; i < targets.length; i++) {
                if (targetMatched[i]) continue; 
                int tr = targets[i] / width;
                int tc = targets[i] % width;
                int dist = Math.abs(br - tr) + Math.abs(bc - tc);
                if (dist < minBoxDist) {
                    minBoxDist = dist;
                    bestTargetIdx = i;
                }
            }
            if (bestTargetIdx != -1) {
                targetMatched[bestTargetIdx] = true;
                totalDistance += minBoxDist;
            }
        }
        return totalDistance;
    }

    private boolean[][] precomputeDeadlocks(int width, int height, char[][] mapData, List<Integer> targets) {
        boolean[][] deadlocks = new boolean[height][width];
        Set<Integer> targetSet = new HashSet<>(targets);
        
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (mapData[r][c] == '#' || targetSet.contains(r * width + c)) continue;
                
                boolean wallUp = mapData[r - 1][c] == '#';
                boolean wallDown = mapData[r + 1][c] == '#';
                boolean wallLeft = mapData[r][c - 1] == '#';
                boolean wallRight = mapData[r][c + 1] == '#';
                
                if ((wallUp && wallLeft) || (wallUp && wallRight) || (wallDown && wallLeft) || (wallDown && wallRight)) {
                    deadlocks[r][c] = true;
                }
            }
        }
        
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                if (deadlocks[r][c]) {
                    if (mapData[r - 1][c] == '#' || mapData[r + 1][c] == '#') {
                        verifyAndMarkLine(r, c, 0, 1, width, mapData, targetSet, deadlocks);
                    }
                    if (mapData[r][c - 1] == '#' || mapData[r][c + 1] == '#') {
                        verifyAndMarkLine(r, c, 1, 0, width, mapData, targetSet, deadlocks);
                    }
                }
            }
        }
        return deadlocks;
    }

    private void verifyAndMarkLine(int startR, int startC, int dRow, int dCol, int width, char[][] mapData, Set<Integer> targets, boolean[][] deadlocks) {
        int height = mapData.length;
        int mapWidth = mapData[0].length;
        int r = startR + dRow;
        int c = startC + dCol;
        List<int[]> pathCells = new ArrayList<>();
        
        while (r >= 0 && r < height && c >= 0 && c < mapWidth && mapData[r][c] != '#') {
            if (targets.contains(r * width + c)) return;
            
            boolean hasWallSide1 = false;
            boolean hasWallSide2 = false;
            
            int side1R = r - dCol; int side1C = c - dRow;
            if (side1R >= 0 && side1R < height && side1C >= 0 && side1C < mapWidth) {
                hasWallSide1 = mapData[side1R][side1C] == '#';
            }
            int side2R = r + dCol; int side2C = c + dRow;
            if (side2R >= 0 && side2R < height && side2C >= 0 && side2C < mapWidth) {
                hasWallSide2 = mapData[side2R][side2C] == '#';
            }
            
            if (!hasWallSide1 && !hasWallSide2) return;
            
            if (deadlocks[r][c]) {
                for (int[] cell : pathCells) {
                    deadlocks[cell[0]][cell[1]] = true;
                }
                return;
            }
            pathCells.add(new int[]{r, c});
            r += dRow;
            c += dCol;
        }
    }

    private boolean isDynamicDeadlock(int pushR, int pushC, int[] boxes, int[] targets, char[][] mapData, int width, int height) {
      int[][] dirs = {{0,0}, {-1,0}, {0,-1}, {-1,-1}}; 
      for (int[] d : dirs) {
          int startR = pushR + d[0];
          int startC = pushC + d[1];
          
          int solids = 0;
          boolean hasBoxOffTarget = false;
          
          for (int r = startR; r < startR + 2; r++) {
              for (int c = startC; c < startC + 2; c++) {
                  if (r < 0 || r >= height || c < 0 || c >= width) break;
                  
                  if (mapData[r][c] == '#') {
                      solids++;
                  } else {
                      int posId = r * width + c;
                      if (Arrays.binarySearch(boxes, posId) >= 0) {
                          solids++;
                          if (Arrays.binarySearch(targets, posId) < 0) hasBoxOffTarget = true;
                      }
                  }
              }
          }
          // If it forms a 2x2 solid block and at least one box isn't on a target, it's dead.
          if (solids == 4 && hasBoxOffTarget) return true; 
      }
      return false;
  }
}
package solver;

import java.util.*;

public class SokoBot {
  
    static class State implements Comparable<State> {
      int playerRow, playerCol; // player location
      int[] boxPositions; // Swapped HashSet for a primitive sorted array to eliminate memory allocation overhead
      int gCost; 
      int hCost; 
      String path; 

      public State(int pRow, int pCol, int[] boxes, int gCost, int hCost, String path) {
        this.playerRow = pRow;
        this.playerCol = pCol;
        this.boxPositions = boxes;
        this.gCost = gCost;
        this.hCost = hCost;
        this.path = path;
      }

      public int getFCost() {
        return gCost + hCost;
      }

      @Override 
      public int compareTo(State other) { // returns the lowest fcost
        return Integer.compare(this.getFCost(), other.getFCost());
      }
 
      @Override // checks if state is identical/visited before
      public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof State)) return false;
        State state = (State) o;
        return playerRow == state.playerRow && 
               playerCol == state.playerCol && 
               Arrays.equals(this.boxPositions, state.boxPositions); 
      }

      @Override
      public int hashCode() { // checks if the states are the same
        // Fast hashing function for primitive arrays
        int result = Objects.hash(playerRow, playerCol);
        return 31 * result + Arrays.hashCode(boxPositions);
      }
    }

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
      List<Integer> targetList = new ArrayList<>();
      List<Integer> startBoxList = new ArrayList<>();
      int startPRow = -1, startPCol = -1;
      // uses A* search
      // scans the map for the target/boxes and player
      for (int r = 0; r < height; r++) {
          for (int c = 0; c < width; c++) {
              int posId = r * width + c; 
              if (mapData[r][c] == '.') targetList.add(posId);
              if (itemsData[r][c] == '@') {
                  startPRow = r;
                  startPCol = c;
              } else if (itemsData[r][c] == '$') {
                  startBoxList.add(posId);
              }
          }
      }

      int[] targets = targetList.stream().mapToInt(i -> i).toArray();
      int[] startBoxes = startBoxList.stream().mapToInt(i -> i).toArray();
      Arrays.sort(startBoxes);
      
      // computes the deadlocks of the boxes
      boolean[][] deadlockGrid = precomputeDeadlocks(width, height, mapData, targetList);
      
      // checks every possible state/path
      PriorityQueue<State> openList = new PriorityQueue<>();
      Set<State> visited = new HashSet<>();
      
      // uses greedy search to calculate box to target matching
      int initialH = calculateGreedyHeuristic(startBoxes, targets, width);
      State startState = new State(startPRow, startPCol, startBoxes, 0, initialH, "");
      openList.add(startState);

      while (!openList.isEmpty()) {
        State current = openList.poll();
        
        if (Arrays.equals(current.boxPositions, targets)) {
          return current.path; 
        }

        if (visited.contains(current)) continue;
        visited.add(current);

        int[][] directions = {{-1, 0, 'u'}, {1, 0, 'd'}, {0, -1, 'l'}, {0, 1, 'r'}};

        for (int[] dir : directions) {
          int nextPRow = current.playerRow + dir[0];
          int nextPCol = current.playerCol + dir[1];
          char moveChar = (char) dir[2];

          if (nextPRow < 0 || nextPRow >= height || nextPCol < 0 || nextPCol >= width) continue;
          if (mapData[nextPRow][nextPCol] == '#') continue;

          int nextPosId = nextPRow * width + nextPCol;
          
          // simulates pushing the boxes
          int boxIdx = Arrays.binarySearch(current.boxPositions, nextPosId);
          boolean isBox = boxIdx >= 0;

          int[] newBoxes = current.boxPositions;

          if (isBox) {
            int nextBRow = nextPRow + dir[0];
            int nextBCol = nextPCol + dir[1];
            int newBoxPosId = nextBRow * width + nextBCol;

            if (nextBRow < 0 || nextBRow >= height || nextBCol < 0 || nextBCol >= width) continue;
            if (mapData[nextBRow][nextBCol] == '#') continue;
            if (Arrays.binarySearch(current.boxPositions, newBoxPosId) >= 0) continue;
            if (deadlockGrid[nextBRow][nextBCol]) continue;

            // Instantly clone and update the array inline
            newBoxes = current.boxPositions.clone();
            newBoxes[boxIdx] = newBoxPosId;
            Arrays.sort(newBoxes);
          }
          
          int nextH = calculateGreedyHeuristic(newBoxes, targets, width);
          State neighbor = new State(nextPRow, nextPCol, newBoxes, current.gCost + 1, nextH, current.path + moveChar);
          
          if (!visited.contains(neighbor)) {
            openList.add(neighbor);
          }
        }
      }
      return ""; 
    }

    // assigns targets dynamically using greedy match elimination
    public int calculateGreedyHeuristic(int[] boxPositions, int[] targets, int width) {
      int totalDistance = 0;
      boolean[] targetMatched = new boolean[targets.length];

      for (int box : boxPositions) {
          int br = box / width;
          int bc = box % width;
          
          int minBoxDist = Integer.MAX_VALUE; 
          int bestTargetIdx = -1;

          for (int i = 0; i < targets.length; i++) {
              if (targetMatched[i]) continue; // Skip already assigned targets
              
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
}
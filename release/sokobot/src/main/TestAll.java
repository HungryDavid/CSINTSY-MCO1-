package main;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import reader.FileReader;
import reader.MapData;
import solver.SokoBot;

public class TestAll {
    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("          SOKOBOT AUTOMATED TEST SUITE            ");
        System.out.println("==================================================\n");

        File mapsDir = new File("maps");
        if (!mapsDir.exists() || !mapsDir.isDirectory()) {
            System.err.println("Error: 'maps' directory not found.");
            System.exit(1);
        }

        File[] files = mapsDir.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            System.err.println("No map files found in 'maps' directory.");
            System.exit(1);
        }

        // Sort files alphabetically
        Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));

        List<TestResult> results = new ArrayList<>();
        int passedCount = 0;

        for (File file : files) {
            String fileName = file.getName();
            String mapName = fileName.substring(0, fileName.lastIndexOf('.'));
            
            System.out.print("Solving " + mapName + "... ");
            System.out.flush();

            FileReader fileReader = new FileReader();
            MapData mapData = fileReader.readFile(mapName);

            if (mapData == null) {
                System.out.println("FAILED (Could not read map)");
                results.add(new TestResult(mapName, "FAIL (Read Error)", 0, 0));
                continue;
            }

            // Parse map and items data
            int rows = mapData.rows;
            int cols = mapData.columns;
            char[][] map = new char[rows][cols];
            char[][] items = new char[rows][cols];

            int startPr = 0, startPc = 0;
            List<Integer> initialCrates = new ArrayList<>();
            List<Integer> goals = new ArrayList<>();

            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    switch (mapData.tiles[i][j]) {
                        case '#':
                            map[i][j] = '#';
                            items[i][j] = ' ';
                            break;
                        case '@':
                            map[i][j] = ' ';
                            items[i][j] = '@';
                            startPr = i;
                            startPc = j;
                            break;
                        case '$':
                            map[i][j] = ' ';
                            items[i][j] = '$';
                            initialCrates.add(i * cols + j);
                            break;
                        case '.':
                            map[i][j] = '.';
                            items[i][j] = ' ';
                            goals.add(i * cols + j);
                            break;
                        case '+':
                            map[i][j] = '.';
                            items[i][j] = '@';
                            startPr = i;
                            startPc = j;
                            goals.add(i * cols + j);
                            break;
                        case '*':
                            map[i][j] = '.';
                            items[i][j] = '$';
                            initialCrates.add(i * cols + j);
                            goals.add(i * cols + j);
                            break;
                        case ' ':
                            map[i][j] = ' ';
                            items[i][j] = ' ';
                            break;
                    }
                }
            }

            long startTime = System.currentTimeMillis();
            SokoBot bot = new SokoBot();
            String solution = bot.solveSokobanPuzzle(cols, rows, map, items);
            long endTime = System.currentTimeMillis();
            double timeTaken = (endTime - startTime) / 1000.0;

            if (solution == null || solution.isEmpty()) {
                System.out.println("NO SOLUTION");
                results.add(new TestResult(mapName, "NO SOL", 0, timeTaken));
            } else {
                // Verify the solution
                boolean valid = verifySolution(cols, rows, map, startPr, startPc, initialCrates, goals, solution);
                if (valid) {
                    System.out.println("PASSED (" + solution.length() + " moves in " + String.format("%.3f", timeTaken) + "s)");
                    results.add(new TestResult(mapName, "PASS", solution.length(), timeTaken));
                    passedCount++;
                } else {
                    System.out.println("FAILED VERIFICATION");
                    results.add(new TestResult(mapName, "INVALID", solution.length(), timeTaken));
                }
            }
        }

        // Print Summary Table
        System.out.println("\n=================================================================");
        System.out.printf("| %-18s | %-10s | %-10s | %-12s |\n", "Map Name", "Status", "Moves", "Time Taken");
        System.out.println("=================================================================");
        double totalTime = 0;
        for (TestResult res : results) {
            System.out.printf("| %-18s | %-10s | %-10s | %-11ss |\n", 
                res.mapName, 
                res.status, 
                res.status.equals("PASS") ? String.valueOf(res.moves) : "-", 
                String.format("%.3f", res.timeTaken)
            );
            totalTime += res.timeTaken;
        }
        System.out.println("=================================================================");
        System.out.println("Total Maps: " + files.length);
        System.out.println("Passed:     " + passedCount + " / " + files.length);
        System.out.printf("Total Time: %.3fs\n", totalTime);
        System.out.println("=================================================================");
    }

    private static boolean verifySolution(int width, int height, char[][] map, int pr, int pc, List<Integer> initialCrates, List<Integer> goals, String solution) {
        // Copy crate positions to a mutable list
        List<Integer> crates = new ArrayList<>(initialCrates);

        int playerR = pr;
        int playerC = pc;

        for (int i = 0; i < solution.length(); i++) {
            char move = solution.charAt(i);
            int dr = 0, dc = 0;
            switch (move) {
                case 'u': dr = -1; break;
                case 'd': dr = 1; break;
                case 'l': dc = -1; break;
                case 'r': dc = 1; break;
                default:
                    return false; // Invalid move character
            }

            int nr = playerR + dr;
            int nc = playerC + dc;
            int nPos = nr * width + nc;

            // Check boundaries
            if (nr < 0 || nr >= height || nc < 0 || nc >= width) return false;
            
            // Check wall collision
            if (map[nr][nc] == '#') return false;

            // Check if there is a crate at the next position
            if (crates.contains(nPos)) {
                int nnr = nr + dr;
                int nnc = nc + dc;
                int nnPos = nnr * width + nnc;

                // Check boundaries for pushed crate
                if (nnr < 0 || nnr >= height || nnc < 0 || nnc >= width) return false;

                // Check wall collision for pushed crate
                if (map[nnr][nnc] == '#') return false;

                // Check crate collision (cannot push two crates)
                if (crates.contains(nnPos)) return false;

                // Move the crate
                crates.remove(Integer.valueOf(nPos));
                crates.add(nnPos);
            }

            // Move the player
            playerR = nr;
            playerC = nc;
        }

        // Verify if all goals are filled
        for (int goal : goals) {
            if (!crates.contains(goal)) {
                return false; // A goal is not covered by any crate
            }
        }

        return true;
    }

    private static class TestResult {
        String mapName;
        String status;
        int moves;
        double timeTaken;

        public TestResult(String mapName, String status, int moves, double timeTaken) {
            this.mapName = mapName;
            this.status = status;
            this.moves = moves;
            this.timeTaken = timeTaken;
        }
    }
}

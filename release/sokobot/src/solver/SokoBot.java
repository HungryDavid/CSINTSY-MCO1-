package solver;

import java.util.*;

public class SokoBot {

    private int width, height, totalCells;
    private boolean[] isWall, isTarget, deadSquare;
    private int[] targetList;
    private int numBoxes;
    private int[][] distToTarget; // [targetIdx][cellPos]
    private long[] zobristBox, zobristPlayer;

    private static final int[] DR = {-1, 1, 0, 0};
    private static final int[] DC = {0, 0, -1, 1};
    private static final char[] MC = {'u', 'd', 'l', 'r'};

    public String solveSokobanPuzzle(int width, int height, char[][] mapData, char[][] itemsData) {
        this.width = width;
        this.height = height;
        this.totalCells = width * height;

        isWall = new boolean[totalCells];
        isTarget = new boolean[totalCells];
        List<Integer> tList = new ArrayList<>(), bList = new ArrayList<>();
        int playerPos = -1;

        for (int r = 0; r < height; r++)
            for (int c = 0; c < width; c++) {
                int p = r * width + c;
                if (mapData[r][c] == '#') isWall[p] = true;
                if (mapData[r][c] == '.') { isTarget[p] = true; tList.add(p); }
                if (itemsData[r][c] == '@') playerPos = p;
                else if (itemsData[r][c] == '$') bList.add(p);
            }

        targetList = tList.stream().mapToInt(i -> i).toArray();
        numBoxes = bList.size();

        Random rng = new Random(42);
        zobristBox = new long[totalCells];
        zobristPlayer = new long[totalCells];
        for (int i = 0; i < totalCells; i++) {
            zobristBox[i] = rng.nextLong();
            zobristPlayer[i] = rng.nextLong();
        }

        distToTarget = new int[targetList.length][totalCells];
        for (int i = 0; i < targetList.length; i++) bfs(targetList[i], distToTarget[i]);

        deadSquare = computeDeadSquares(mapData);

        int[] initBoxes = bList.stream().mapToInt(i -> i).toArray();
        Arrays.sort(initBoxes);
        for (int b : initBoxes) if (deadSquare[b]) return "";

        boolean[] iBits = new boolean[totalCells];
        long iHash = 0;
        for (int b : initBoxes) { iBits[b] = true; iHash ^= zobristBox[b]; }
        int iNorm = norm(playerPos, iBits);
        long iSH = iHash ^ zobristPlayer[iNorm];

        int iH = heuristic(initBoxes);
        if (iH >= 999999) return "";

        // State storage
        ArrayList<int[]> sBoxes = new ArrayList<>();
        ArrayList<Integer> sPlayer = new ArrayList<>(), sParent = new ArrayList<>(),
                           sDir = new ArrayList<>(), sPushFrom = new ArrayList<>();

        sBoxes.add(initBoxes); sPlayer.add(playerPos);
        sParent.add(-1); sDir.add(-1); sPushFrom.add(-1);

        // Greedy Best-First: priority = h only
        PriorityQueue<int[]> open = new PriorityQueue<>((a, b) ->
            a[0] != b[0] ? a[0] - b[0] : b[1] - a[1]);
        open.add(new int[]{iH, 0, 0});

        HashMap<Long, Byte> visited = new HashMap<>();
        visited.put(iSH, (byte) 1);

        long startTime = System.currentTimeMillis();
        int statesExplored = 0;
        int minH = Integer.MAX_VALUE;

        while (!open.isEmpty()) {
            if (System.currentTimeMillis() - startTime > 14000) break;

            int[] e = open.poll();
            statesExplored++;
            if (e[0] < minH) {
                minH = e[0];
                // System.out.println("New minH: " + minH + " at state " + statesExplored);
            }
            if (statesExplored % 20000 == 0) {
                // System.out.println("Explored: " + statesExplored + ", open: " + open.size() + ", visited: " + visited.size() + ", minH: " + minH);
            }
            int sid = e[2];
            int[] cb = sBoxes.get(sid);
            int cp = sPlayer.get(sid);

            // Goal?
            boolean goal = true;
            for (int b : cb) if (!isTarget[b]) { goal = false; break; }
            if (goal) return buildPath(sid, sBoxes, sPlayer, sParent, sDir, sPushFrom);

            boolean[] bits = new boolean[totalCells];
            long bHash = 0;
            for (int b : cb) { bits[b] = true; bHash ^= zobristBox[b]; }

            // Player reachability
            int[] rf = new int[totalCells];
            Arrays.fill(rf, -1);
            rf[cp] = cp;
            int[] q = new int[totalCells];
            int qh = 0, qt = 0;
            q[qt++] = cp;
            while (qh < qt) {
                int pos = q[qh++];
                int r = pos / width, c = pos % width;
                for (int d = 0; d < 4; d++) {
                    int nr = r + DR[d], nc = c + DC[d];
                    if (nr >= 0 && nr < height && nc >= 0 && nc < width) {
                        int np = nr * width + nc;
                        if (rf[np] == -1 && !isWall[np] && !bits[np]) { rf[np] = pos; q[qt++] = np; }
                    }
                }
            }

            // Try pushes
            int cg = e[1];
            for (int bi = 0; bi < cb.length; bi++) {
                int bp = cb[bi], br = bp / width, bc = bp % width;
                for (int d = 0; d < 4; d++) {
                    int pr = br - DR[d], pc = bc - DC[d];
                    if (pr < 0 || pr >= height || pc < 0 || pc >= width) continue;
                    int pn = pr * width + pc;
                    if (rf[pn] == -1) continue;

                    int nr = br + DR[d], nc = bc + DC[d];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                    int nbp = nr * width + nc;
                    if (isWall[nbp] || bits[nbp]) continue;
                    if (deadSquare[nbp]) continue;

                    int[] nb = cb.clone();
                    nb[bi] = nbp;
                    Arrays.sort(nb);

                    boolean[] nBits = new boolean[totalCells];
                    for (int b : nb) nBits[b] = true;
                    if (is2x2Dead(nBits, nbp)) continue;
                    if (isFrozen(nb, nBits, nbp)) continue;

                    int npl = bp;
                    int nn = norm(npl, nBits);
                    long nBH = bHash ^ zobristBox[bp] ^ zobristBox[nbp];
                    long nSH = nBH ^ zobristPlayer[nn];

                    if (visited.containsKey(nSH)) continue;
                    visited.put(nSH, (byte) 1);

                    int h = heuristic(nb);
                    if (h >= 999999) continue;

                    int nsid = sBoxes.size();
                    sBoxes.add(nb); sPlayer.add(npl);
                    sParent.add(sid); sDir.add(d); sPushFrom.add(pn);

                    open.add(new int[]{h, cg + 1, nsid});
                }
            }
        }
        return "";
    }

    /** Greedy bipartite matching heuristic: O(n^2). Faster than Hungarian. */
    private int heuristic(int[] boxes) {
        int n = boxes.length;
        if (n == 0) return 0;

        // Build cost matrix
        int[][] c = new int[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                c[i][j] = distToTarget[j][boxes[i]];
                if (c[i][j] >= 999999) return 999999;
            }

        // Greedy assignment: repeatedly pick the globally cheapest (box,target) pair
        boolean[] bUsed = new boolean[n], tUsed = new boolean[n];
        int total = 0;
        for (int step = 0; step < n; step++) {
            int best = Integer.MAX_VALUE, bi2 = -1, ti2 = -1;
            for (int i = 0; i < n; i++) {
                if (bUsed[i]) continue;
                for (int j = 0; j < n; j++) {
                    if (tUsed[j]) continue;
                    if (c[i][j] < best) { best = c[i][j]; bi2 = i; ti2 = j; }
                }
            }
            if (bi2 == -1) return 999999;
            bUsed[bi2] = true; tUsed[ti2] = true;
            total += best;
        }
        return total;
    }

    /** Check if newly pushed box creates a frozen deadlock (box can't move). */
    private boolean isFrozen(int[] boxes, boolean[] boxBits, int pos) {
        if (isTarget[pos]) return false;
        return frozenCheck(pos, boxBits, new boolean[totalCells]);
    }

    private boolean frozenCheck(int pos, boolean[] boxBits, boolean[] vis) {
        if (isTarget[pos]) return false;
        vis[pos] = true;
        int r = pos / width, c = pos % width;

        // Check vertical lock
        boolean vLocked;
        int up = (r - 1) * width + c, down = (r + 1) * width + c;
        boolean upWall = r == 0 || isWall[up];
        boolean downWall = r == height - 1 || isWall[down];
        if (upWall || downWall) {
            vLocked = true;
        } else {
            boolean upBox = boxBits[up] && !vis[up] && frozenCheck(up, boxBits, vis);
            boolean downBox = boxBits[down] && !vis[down] && frozenCheck(down, boxBits, vis);
            vLocked = upBox || downBox;
        }
        if (!vLocked) return false;

        // Check horizontal lock
        int left = r * width + c - 1, right = r * width + c + 1;
        boolean leftWall = c == 0 || isWall[left];
        boolean rightWall = c == width - 1 || isWall[right];
        if (leftWall || rightWall) return true;
        boolean leftBox = boxBits[left] && !vis[left] && frozenCheck(left, boxBits, vis);
        boolean rightBox = boxBits[right] && !vis[right] && frozenCheck(right, boxBits, vis);
        return leftBox || rightBox;
    }

    private String buildPath(int sid, ArrayList<int[]> sb, ArrayList<Integer> sp,
                             ArrayList<Integer> spar, ArrayList<Integer> sd, ArrayList<Integer> spf) {
        List<int[]> seq = new ArrayList<>();
        int cur = sid;
        while (spar.get(cur) != -1) {
            seq.add(new int[]{spar.get(cur), sd.get(cur), spf.get(cur)});
            cur = spar.get(cur);
        }
        Collections.reverse(seq);

        StringBuilder res = new StringBuilder();
        for (int[] push : seq) {
            int pSid = push[0], dir = push[1], pNeeded = push[2];
            int[] pBoxes = sb.get(pSid);
            int pPlayer = sp.get(pSid);

            boolean[] bb = new boolean[totalCells];
            for (int b : pBoxes) bb[b] = true;

            int[] from = new int[totalCells];
            int[] fd = new int[totalCells];
            Arrays.fill(from, -1);
            from[pPlayer] = pPlayer;
            int[] q = new int[totalCells];
            int qh2 = 0, qt2 = 0;
            q[qt2++] = pPlayer;
            while (qh2 < qt2) {
                int pos = q[qh2++];
                if (pos == pNeeded) break;
                int r = pos / width, c = pos % width;
                for (int d = 0; d < 4; d++) {
                    int nr = r + DR[d], nc = c + DC[d];
                    if (nr >= 0 && nr < height && nc >= 0 && nc < width) {
                        int np = nr * width + nc;
                        if (from[np] == -1 && !isWall[np] && !bb[np]) { from[np] = pos; fd[np] = d; q[qt2++] = np; }
                    }
                }
            }

            if (pNeeded != pPlayer) {
                List<Character> wp = new ArrayList<>();
                int c2 = pNeeded;
                while (c2 != pPlayer) { wp.add(MC[fd[c2]]); c2 = from[c2]; }
                Collections.reverse(wp);
                for (char ch : wp) res.append(ch);
            }
            res.append(MC[dir]);
        }
        return res.toString();
    }

    private void bfs(int start, int[] dist) {
        Arrays.fill(dist, 999999);
        dist[start] = 0;
        int[] q = new int[totalCells];
        int qh = 0, qt = 0;
        q[qt++] = start;
        while (qh < qt) {
            int pos = q[qh++];
            int r = pos / width, c = pos % width;
            for (int d = 0; d < 4; d++) {
                int nr = r + DR[d], nc = c + DC[d];
                if (nr >= 0 && nr < height && nc >= 0 && nc < width) {
                    int np = nr * width + nc;
                    if (!isWall[np] && dist[np] == 999999) { dist[np] = dist[pos] + 1; q[qt++] = np; }
                }
            }
        }
    }

    private boolean[] computeDeadSquares(char[][] mapData) {
        boolean[] reach = new boolean[totalCells];
        for (int t : targetList) {
            boolean[] vis = new boolean[totalCells];
            int[] q = new int[totalCells];
            int qh = 0, qt = 0;
            q[qt++] = t; vis[t] = true;
            while (qh < qt) {
                int pos = q[qh++];
                int r = pos / width, c = pos % width;
                for (int d = 0; d < 4; d++) {
                    int fr = r - DR[d], fc = c - DC[d];
                    int plr = r - 2 * DR[d], plc = c - 2 * DC[d];
                    if (fr >= 0 && fr < height && fc >= 0 && fc < width &&
                        plr >= 0 && plr < height && plc >= 0 && plc < width) {
                        int fp = fr * width + fc, pp = plr * width + plc;
                        if (!isWall[fp] && !isWall[pp] && !vis[fp]) { vis[fp] = true; q[qt++] = fp; }
                    }
                }
            }
            for (int i = 0; i < totalCells; i++) if (vis[i]) reach[i] = true;
        }
        boolean[] dead = new boolean[totalCells];
        for (int i = 0; i < totalCells; i++)
            if (!isWall[i] && !isTarget[i] && !reach[i]) dead[i] = true;
        return dead;
    }

    private boolean is2x2Dead(boolean[] bb, int pos) {
        int r = pos / width, c = pos % width;
        int[][] off = {{0,0},{0,-1},{-1,0},{-1,-1}};
        for (int[] o : off) {
            int tr = r + o[0], tc = c + o[1];
            if (tr < 0 || tr + 1 >= height || tc < 0 || tc + 1 >= width) continue;
            int p0 = tr*width+tc, p1 = tr*width+tc+1, p2 = (tr+1)*width+tc, p3 = (tr+1)*width+tc+1;
            if ((isWall[p0]||bb[p0]) && (isWall[p1]||bb[p1]) && (isWall[p2]||bb[p2]) && (isWall[p3]||bb[p3])) {
                if ((bb[p0]&&!isTarget[p0]) || (bb[p1]&&!isTarget[p1]) || (bb[p2]&&!isTarget[p2]) || (bb[p3]&&!isTarget[p3]))
                    return true;
            }
        }
        return false;
    }

    private int norm(int pos, boolean[] bb) {
        int min = pos;
        boolean[] seen = new boolean[totalCells];
        int[] q = new int[totalCells];
        int qh = 0, qt = 0;
        q[qt++] = pos; seen[pos] = true;
        while (qh < qt) {
            int p = q[qh++];
            if (p < min) min = p;
            int r = p / width, c = p % width;
            for (int d = 0; d < 4; d++) {
                int nr = r + DR[d], nc = c + DC[d];
                if (nr >= 0 && nr < height && nc >= 0 && nc < width) {
                    int np = nr * width + nc;
                    if (!seen[np] && !isWall[np] && !bb[np]) { seen[np] = true; q[qt++] = np; }
                }
            }
        }
        return min;
    }
}
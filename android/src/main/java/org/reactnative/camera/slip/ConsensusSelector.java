package org.reactnative.camera.slip;

import android.graphics.Point;
import java.util.ArrayList;
import java.util.List;

public class ConsensusSelector {

    public static class Candidate {
        public final Point pt;
        public final double conf;
        public final String method;

        public Candidate(Point pt, double conf, String method) {
            this.pt = pt;
            this.conf = conf;
            this.method = method;
        }
    }

    public static class Result {
        public final Point pt;
        public final double conf;
        public final List<String> methods;

        public Result(Point pt, double conf, List<String> methods) {
            this.pt = pt;
            this.conf = conf;
            this.methods = methods;
        }

        public static Result empty() {
            return new Result(null, 0.0, new ArrayList<>());
        }

        public boolean isValid() {
            return pt != null;
        }
    }

    private static double ptDistance(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static Result select(List<Candidate> candidates, double confidenceMin,
                                double confidenceStrong, double consensusDist,
                                int consensusMinMethods) {
        if (candidates == null || candidates.isEmpty()) {
            return Result.empty();
        }

        List<Candidate> viable = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.conf >= confidenceMin) {
                viable.add(c);
            }
        }

        if (viable.isEmpty()) {
            Candidate strongest = null;
            for (Candidate c : candidates) {
                if (strongest == null || c.conf > strongest.conf) {
                    strongest = c;
                }
            }
            if (strongest != null && strongest.conf >= confidenceStrong) {
                List<String> methods = new ArrayList<>();
                methods.add(strongest.method);
                return new Result(strongest.pt, strongest.conf, methods);
            }
            return Result.empty();
        }

        if (viable.size() == 1) {
            if (viable.get(0).conf >= confidenceStrong) {
                List<String> methods = new ArrayList<>();
                methods.add(viable.get(0).method);
                return new Result(viable.get(0).pt, viable.get(0).conf, methods);
            }
            return Result.empty();
        }

        List<Candidate> bestGroup = new ArrayList<>();
        double bestScore = -1.0;

        for (int i = 0; i < viable.size(); i++) {
            List<Candidate> group = new ArrayList<>();
            group.add(viable.get(i));
            for (int j = 0; j < viable.size(); j++) {
                if (i == j) continue;
                if (ptDistance(viable.get(i).pt, viable.get(j).pt) <= consensusDist) {
                    group.add(viable.get(j));
                }
            }
            double score = 0;
            for (Candidate g : group) score += g.conf;

            if (group.size() > bestGroup.size() ||
                (group.size() == bestGroup.size() && score > bestScore)) {
                bestGroup = group;
                bestScore = score;
            }
        }

        if (bestGroup.size() < consensusMinMethods) {
            Candidate strongest = null;
            for (Candidate c : viable) {
                if (strongest == null || c.conf > strongest.conf) {
                    strongest = c;
                }
            }
            if (strongest != null && strongest.conf >= confidenceStrong) {
                List<String> methods = new ArrayList<>();
                methods.add(strongest.method);
                return new Result(strongest.pt, strongest.conf, methods);
            }
            return Result.empty();
        }

        double sumConf = 0;
        for (Candidate g : bestGroup) sumConf += g.conf;
        if (sumConf <= 0) return Result.empty();

        double x = 0, y = 0;
        for (Candidate g : bestGroup) {
            x += g.pt.x * g.conf;
            y += g.pt.y * g.conf;
        }
        x /= sumConf;
        y /= sumConf;
        double avgConf = sumConf / bestGroup.size();

        List<String> methods = new ArrayList<>();
        for (Candidate g : bestGroup) methods.add(g.method);

        return new Result(new Point((int) x, (int) y), avgConf, methods);
    }
}

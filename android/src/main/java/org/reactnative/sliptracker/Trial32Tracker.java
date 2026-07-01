package org.reactnative.sliptracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

/**
 * Java/OpenCV port of {@code trial_32.py}.
 *
 * IMPORTANT:
 * - Variable names, thresholds, and control flow intentionally mirror the Python code as closely as possible.
 * - Only UI-related behavior (keyboard -> buttons, bars placement) should be changed outside this class.
 */
public final class Trial32Tracker {
    public static final int FRAME_WIDTH = 960;
    public static final int FRAME_HEIGHT = 720;

    public static final int MARKER_SIZE = 12;
    public static final int DISTANCE_THRESHOLD = 260;
    public static final int CENTER_GUARD_RADIUS = Math.max(1, (int) (DISTANCE_THRESHOLD * 0.35));
    public static final int CENTER_GRACE_FRAMES = 3;
    public static final int SCALE_OUTLIER_FRAMES = 3;
    public static final int DISTANCE_CONFIRM_FRAMES = 2;
    public static final double DISTANCE_FAST_FACTOR = 1.4;
    public static final double JUMP_FAST_FACTOR = 1.1;
    // Salvage re-run of select_consensus (see the consensus-null path): drop the
    // confidence floor to 0 and raise the "strong" bar above any achievable
    // confidence (all method confidences are clamped to <=1.0), so consensus can
    // only succeed through the >=2 agreeing group path — never a lone method.
    public static final double SALVAGE_CONFIDENCE_MIN = 0.0;
    public static final double SALVAGE_CONFIDENCE_STRONG = 1.1;

    public int overlay_counter = 0;
    public static final int OVERLAY_FRAMES = 20;

    public static final int PHASE_DS_WIDTH = 320;
    public static final int PHASE_DS_HEIGHT = 240;

    public static final double PHASE_RESPONSE_MIN = 0.05;
    public static final double PHASE_RESPONSE_MIN_POOR = 0.02;

    public static final int PATCH_SIZE = 220;
    public static final double[] TEMPLATE_SCALES = new double[] { 0.7, 0.85, 1.0, 1.15 };
    public static final double TEMPLATE_MATCH_THRESH = 0.55;
    public static final double TEMPLATE_STRONG_BONUS = 0.15;
    public static final double TEMPLATE_MATCH_THRESH_POOR = 0.40;
    public static final double TEMPLATE_STRONG_BONUS_POOR = 0.10;
    public static final int SEARCH_RADIUS = 350;

    public static final int MIN_GOOD_MATCHES = 12;
    public static final int MIN_INLIERS = 8;
    public static final double INLIER_RATIO_MIN = 0.20;
    public static final double RANSAC_REPROJ_THRESH = 8.0;

    public static final int MIN_KP_CURRENT = 30;
    public static final int FEATURE_LOSS_FRAMES = 4;
    public int feature_loss_counter = 0;

    public static final double ENTROPY_MIN = 3.5;
    public static final int LOW_ENTROPY_FRAMES = 4;
    public int low_entropy_counter = 0;

    // Vertical lift
    public static final double SCALE_MIN = 0.85;
    public static final double SCALE_MAX = 1.75;

    public static final int BAR_HEIGHT = 300;
    public static final int BAR_WIDTH = 30;
    public static final int BAR_TOP = 100;
    public static final int RADIAL_BAR_RIGHT_MARGIN = 30;
    public static final int BAR_GAP = 50;

    public double last_scale_est = 1.0; // AXIAL DISPLAY

    // Consensus / confidence gating
    public static final double CONFIDENCE_MIN = 0.55;
    public static final double CONFIDENCE_STRONG = 0.75;
    public static final int CONSENSUS_DIST = 40;
    public static final int CONSENSUS_MIN_METHODS = 2;
    public static final int LOST_FRAMES_MAX = 3;
    public static final double CONFIDENCE_MIN_POOR = 0.45;
    public static final double CONFIDENCE_STRONG_POOR = 0.60;
    public static final int CONSENSUS_MIN_METHODS_POOR = 1;
    public static final int LOST_FRAMES_MAX_POOR = 6;

    // Optical flow quality threshold
    public static final double FLOW_ERR_MAX = 12.0;

    // OpenCV objects (match Python settings)
    private final ORB orb = ORB.create(1500);
    private final BFMatcher bf = BFMatcher.create(Core.NORM_HAMMING, false);
    private final CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));

    public Mat reference_frame = null;
    public KeyPoint[] ref_kp = null;
    public Mat ref_desc = null;
    public Mat ref_patch = null;
    public Mat ref_patch_grad = null;
    public int[] ref_patch_center = null;

    public final int[] ref_center = new int[] { FRAME_WIDTH / 2, FRAME_HEIGHT / 2 };

    public int[] live_pt = null;
    public int[] prev_live_pt = null;
    public Mat prev_gray = null;

    public int lost_counter = 0;
    public int last_distance = 0;
    public int scale_outlier_counter = 0;
    public int distance_exceed_counter = 0;

    // ======= feature-rich gating variables & thresholds =======
    public boolean was_feature_rich = false; // True only when a valid (feature-rich) reference was set
    public static final int FEATURE_RICH_KP_MIN = 45; // min keypoints to be considered feature-rich
    public static final double FEATURE_RICH_ENTROPY_MIN = 4.0; // min entropy to be considered feature-rich
    // ==========================================================

    private final Mat last_gray = new Mat(); // most recent CLAHE gray (FRAME_WIDTH x FRAME_HEIGHT)
    private final Mat last_gray_raw = new Mat(); // most recent raw gray (FRAME_WIDTH x FRAME_HEIGHT)

    public String last_status_message = null;

    public static final class FrameState {
        public final int[] ref_center;
        public final int[] live_pt;
        public final int distance;
        public final double last_scale_est;
        public final int overlay_counter;
        public final String status_message;
        // Worst counter-to-limit ratio across lost / scale-outlier / feature-loss /
        // low-entropy. Climbs frame-by-frame as the tracker drifts toward an
        // auto-reset; lets the classifier surface warning/critical before the
        // reference is dropped.
        public final double instability;

        public FrameState(int[] ref_center, int[] live_pt, int distance, double last_scale_est, int overlay_counter, String status_message, double instability) {
            this.ref_center = ref_center;
            this.live_pt = live_pt;
            this.distance = distance;
            this.last_scale_est = last_scale_est;
            this.overlay_counter = overlay_counter;
            this.status_message = status_message;
            this.instability = instability;
        }
    }

    private static final class Candidate {
        final int[] pt;
        final double conf;
        final String method;

        Candidate(int[] pt, double conf, String method) {
            this.pt = pt;
            this.conf = conf;
            this.method = method;
        }
    }

    private static final class ConsensusResult {
        final int[] pt;
        final double conf;
        final List<String> methods;

        ConsensusResult(int[] pt, double conf, List<String> methods) {
            this.pt = pt;
            this.conf = conf;
            this.methods = methods;
        }
    }

    private static final class TemplateMatchResult {
        final int[] pt;
        final Double score;
        final Double scale;

        TemplateMatchResult(int[] pt, Double score, Double scale) {
            this.pt = pt;
            this.score = score;
            this.scale = scale;
        }
    }

    private static final class PhaseResult {
        final Double sx;
        final Double sy;
        final double resp;

        PhaseResult(Double sx, Double sy, double resp) {
            this.sx = sx;
            this.sy = sy;
            this.resp = resp;
        }
    }

    private static final class FlowResult {
        final int[] pt;
        final double conf;

        FlowResult(int[] pt, double conf) {
            this.pt = pt;
            this.conf = conf;
        }
    }

    private static final class TemplateThresholds {
        final double tmpl_thresh;
        final double tmpl_bonus;

        TemplateThresholds(double tmpl_thresh, double tmpl_bonus) {
            this.tmpl_thresh = tmpl_thresh;
            this.tmpl_bonus = tmpl_bonus;
        }
    }

    private static final class ConsensusParams {
        final double confidence_min;
        final double confidence_strong;
        final int consensus_min_methods;
        final int lost_frames_max;

        ConsensusParams(double confidence_min, double confidence_strong, int consensus_min_methods, int lost_frames_max) {
            this.confidence_min = confidence_min;
            this.confidence_strong = confidence_strong;
            this.consensus_min_methods = consensus_min_methods;
            this.lost_frames_max = lost_frames_max;
        }
    }

    public Trial32Tracker() {
    }

    public Mat getLastGrayForReference() {
        return last_gray;
    }

    public void setReferenceFromLastFrame() {
        if (last_gray.empty()) {
            return;
        }
        set_reference(last_gray);
    }

    public void resetTrackingManual() {
        reset_tracking("Manual reset.");
    }

    // Max of per-counter progress toward its auto-reset limit, clamped to [0, 1].
    // Used to surface drift to the UI before the tracker gives up on the reference.
    private double computeInstability(boolean near_center, boolean tracking_feature_rich) {
        int center_grace = near_center ? CENTER_GRACE_FRAMES : 0;
        int lost_limit = (tracking_feature_rich ? LOST_FRAMES_MAX : LOST_FRAMES_MAX_POOR) + center_grace;
        int feature_loss_limit = FEATURE_LOSS_FRAMES + center_grace;
        int low_entropy_limit = LOW_ENTROPY_FRAMES + center_grace;
        int scale_outlier_limit = SCALE_OUTLIER_FRAMES + center_grace;

        double r_lost = (double) lost_counter / Math.max(lost_limit, 1);
        double r_scale = (double) scale_outlier_counter / Math.max(scale_outlier_limit, 1);
        double r_feature = (double) feature_loss_counter / Math.max(feature_loss_limit, 1);
        double r_entropy = (double) low_entropy_counter / Math.max(low_entropy_limit, 1);
        double max = Math.max(Math.max(r_lost, r_scale), Math.max(r_feature, r_entropy));
        if (max < 0.0) return 0.0;
        if (max > 1.0) return 1.0;
        return max;
    }

    public void reset_tracking(String reason) {
        SlipTrackerDebug.i("reset_tracking: " + reason
                + " (last_distance=" + last_distance
                + " last_scale_est=" + String.format("%.3f", last_scale_est)
                + " feature_loss=" + feature_loss_counter
                + " low_entropy=" + low_entropy_counter
                + " scale_outlier=" + scale_outlier_counter
                + " distance_exceed=" + distance_exceed_counter
                + " lost=" + lost_counter + ")");
        if (reference_frame != null) {
            reference_frame.release();
        }
        if (ref_desc != null) {
            ref_desc.release();
        }
        if (ref_patch != null) {
            ref_patch.release();
        }
        if (ref_patch_grad != null) {
            ref_patch_grad.release();
        }
        if (prev_gray != null) {
            prev_gray.release();
        }

        reference_frame = null;
        ref_kp = null;
        ref_desc = null;
        ref_patch = null;
        ref_patch_center = null;
        ref_patch_grad = null;
        live_pt = null;
        prev_live_pt = null;
        overlay_counter = OVERLAY_FRAMES;

        feature_loss_counter = 0;
        low_entropy_counter = 0;
        last_scale_est = 1.0;
        was_feature_rich = false;
        prev_gray = null;
        lost_counter = 0;
        last_distance = 0;
        scale_outlier_counter = 0;
        distance_exceed_counter = 0;

        last_status_message = reason;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double pt_distance(int[] a, int[] b) {
        double dx = (double) a[0] - (double) b[0];
        double dy = (double) a[1] - (double) b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean in_frame(int[] pt) {
        return pt != null && 0 <= pt[0] && pt[0] < FRAME_WIDTH && 0 <= pt[1] && pt[1] < FRAME_HEIGHT;
    }

    private static boolean is_near_center(int distance) {
        return distance <= CENTER_GUARD_RADIUS;
    }

    private static double compute_entropy(Mat gray) {
        Mat hist = new Mat();
        Imgproc.calcHist(
            Collections.singletonList(gray),
            new MatOfInt(0),
            new Mat(),
            hist,
            new MatOfInt(64),
            new MatOfFloat(0f, 256f)
        );
        double total = Core.sumElems(hist).val[0];
        if (total <= 0.0) {
            return 0.0;
        }
        Core.divide(hist, new Scalar(total), hist);

        double entropy = 0.0;
        for (int i = 0; i < hist.rows(); i++) {
            double p = hist.get(i, 0)[0];
            if (p > 0.0) {
                entropy -= p * (Math.log(p) / Math.log(2.0));
            }
        }
        hist.release();
        return entropy;
    }

    private static Mat compute_grad_mag(Mat gray) {
        Mat gx = new Mat();
        Mat gy = new Mat();
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3);
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3);

        Mat mag = new Mat();
        Core.magnitude(gx, gy, mag);
        Core.normalize(mag, mag, 0, 255, Core.NORM_MINMAX);
        mag.convertTo(mag, CvType.CV_8U);
        gx.release();
        gy.release();
        return mag;
    }

    private static int[] extract_ref_patch_center(int half, int cx, int cy, int x0, int y0) {
        int px = Math.min(half, cx - x0);
        int py = Math.min(half, cy - y0);
        return new int[] { px, py };
    }

    private PatchResult extract_ref_patch(Mat ref_gray) {
        int half = PATCH_SIZE / 2;
        int cx = ref_center[0];
        int cy = ref_center[1];
        int x0 = Math.max(cx - half, 0);
        int y0 = Math.max(cy - half, 0);

        // NOTE: Mirrors the Python code exactly (end index is exclusive, but it clamps to shape-1).
        int x1 = Math.min(cx + half, ref_gray.cols() - 1);
        int y1 = Math.min(cy + half, ref_gray.rows() - 1);

        Rect roi = new Rect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        Mat patch = new Mat(ref_gray, roi).clone();
        int[] center_in_patch = extract_ref_patch_center(half, cx, cy, x0, y0);
        return new PatchResult(patch, center_in_patch);
    }

    private static final class PatchResult {
        final Mat patch;
        final int[] center_in_patch;

        PatchResult(Mat patch, int[] center_in_patch) {
            this.patch = patch;
            this.center_in_patch = center_in_patch;
        }
    }

    private static TemplateThresholds get_template_thresholds(boolean feature_rich) {
        if (feature_rich) {
            return new TemplateThresholds(TEMPLATE_MATCH_THRESH, TEMPLATE_STRONG_BONUS);
        }
        return new TemplateThresholds(TEMPLATE_MATCH_THRESH_POOR, TEMPLATE_STRONG_BONUS_POOR);
    }

    private static double get_phase_min(boolean feature_rich) {
        return feature_rich ? PHASE_RESPONSE_MIN : PHASE_RESPONSE_MIN_POOR;
    }

    private static ConsensusParams get_consensus_params(boolean feature_rich) {
        if (feature_rich) {
            return new ConsensusParams(CONFIDENCE_MIN, CONFIDENCE_STRONG, CONSENSUS_MIN_METHODS, LOST_FRAMES_MAX);
        }
        return new ConsensusParams(CONFIDENCE_MIN_POOR, CONFIDENCE_STRONG_POOR, CONSENSUS_MIN_METHODS_POOR, LOST_FRAMES_MAX_POOR);
    }

    private static boolean is_feature_rich(Mat gray, KeyPoint[] kp) {
        if (kp == null) {
            return false;
        }
        if (kp.length < FEATURE_RICH_KP_MIN) {
            return false;
        }
        double ent = compute_entropy(gray);
        return ent >= FEATURE_RICH_ENTROPY_MIN;
    }

    private TemplateMatchResult template_match_fallback(Mat curr_img, Mat template, int[] search_center) {
        if (template == null) {
            return new TemplateMatchResult(null, null, null);
        }

        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(template, mean, stddev);
        double std = stddev.toArray().length > 0 ? stddev.toArray()[0] : 0.0;
        mean.release();
        stddev.release();
        if (std < 1e-3) {
            return new TemplateMatchResult(null, null, null);
        }

        double best_val = -1.0;
        int[] best_pt = null;
        Double best_scale = null;
        int h_ref0 = template.rows();
        int w_ref0 = template.cols();

        for (double scale : TEMPLATE_SCALES) {
            int w_ref = Math.max(3, (int) (w_ref0 * scale));
            int h_ref = Math.max(3, (int) (h_ref0 * scale));

            Mat scaled_patch = new Mat();
            Imgproc.resize(template, scaled_patch, new Size(w_ref, h_ref));

            Mat search_img;
            int offx = 0;
            int offy = 0;
            if (search_center != null) {
                int sx = (int) Math.max(0, search_center[0] - SEARCH_RADIUS);
                int sy = (int) Math.max(0, search_center[1] - SEARCH_RADIUS);
                int ex = (int) Math.min(curr_img.cols(), search_center[0] + SEARCH_RADIUS);
                int ey = (int) Math.min(curr_img.rows(), search_center[1] + SEARCH_RADIUS);
                if (ex - sx < w_ref || ey - sy < h_ref) {
                    scaled_patch.release();
                    continue;
                }
                search_img = new Mat(curr_img, new Rect(sx, sy, ex - sx, ey - sy));
                offx = sx;
                offy = sy;
            } else {
                search_img = curr_img;
            }

            int res_cols = search_img.cols() - scaled_patch.cols() + 1;
            int res_rows = search_img.rows() - scaled_patch.rows() + 1;
            if (res_cols <= 0 || res_rows <= 0) {
                if (search_center != null) {
                    search_img.release();
                }
                scaled_patch.release();
                continue;
            }

            Mat res = new Mat(res_rows, res_cols, CvType.CV_32FC1);
            Imgproc.matchTemplate(search_img, scaled_patch, res, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(res);
            double max_val = mm.maxVal;
            Point max_loc = mm.maxLoc;

            if (max_val > best_val) {
                best_val = max_val;
                int cx = (int) (max_loc.x + w_ref / 2.0 + offx);
                int cy = (int) (max_loc.y + h_ref / 2.0 + offy);
                best_pt = new int[] { cx, cy };
                best_scale = scale;
            }

            res.release();
            scaled_patch.release();
            if (search_center != null) {
                search_img.release();
            }
        }

        return new TemplateMatchResult(best_pt, best_val, best_scale);
    }

    private PhaseResult phase_correlation_fallback(Mat curr_gray) {
        if (reference_frame == null) {
            return new PhaseResult(null, null, 0.0);
        }

        Mat ref_ds = new Mat();
        Mat cur_ds = new Mat();
        Imgproc.resize(reference_frame, ref_ds, new Size(PHASE_DS_WIDTH, PHASE_DS_HEIGHT));
        Imgproc.resize(curr_gray, cur_ds, new Size(PHASE_DS_WIDTH, PHASE_DS_HEIGHT));

        Mat ref_f = new Mat();
        Mat cur_f = new Mat();
        ref_ds.convertTo(ref_f, CvType.CV_32F);
        cur_ds.convertTo(cur_f, CvType.CV_32F);

        Scalar ref_mean = Core.mean(ref_f);
        Scalar cur_mean = Core.mean(cur_f);
        Core.subtract(ref_f, ref_mean, ref_f);
        Core.subtract(cur_f, cur_mean, cur_f);

        Mat window = new Mat();
        Imgproc.createHanningWindow(window, new Size(PHASE_DS_WIDTH, PHASE_DS_HEIGHT), CvType.CV_32F);
        Core.multiply(ref_f, window, ref_f);
        Core.multiply(cur_f, window, cur_f);

        // IMPORTANT: Mirror Python exactly:
        // Python applies the Hanning window manually (ref_f *= window; cur_f *= window)
        // and then calls cv2.phaseCorrelate(ref_f, cur_f) WITHOUT passing the window.
        // Passing the window here would apply windowing a second time and change behavior.
        double[] response = new double[1];
        Point shift = Imgproc.phaseCorrelate(ref_f, cur_f, new Mat(), response);

        double scale_x = (double) FRAME_WIDTH / (double) PHASE_DS_WIDTH;
        double scale_y = (double) FRAME_HEIGHT / (double) PHASE_DS_HEIGHT;

        ref_ds.release();
        cur_ds.release();
        ref_f.release();
        cur_f.release();
        window.release();

        return new PhaseResult(shift.x * scale_x, shift.y * scale_y, response[0]);
    }

    private static FlowResult optical_flow_fallback(Mat prev_gray_frame, Mat curr_gray_frame, int[] prev_point) {
        if (prev_gray_frame == null || prev_point == null) {
            return new FlowResult(null, 0.0);
        }

        MatOfPoint2f p0 = new MatOfPoint2f(new Point(prev_point[0], prev_point[1]));
        MatOfPoint2f p1 = new MatOfPoint2f();
        MatOfByte st = new MatOfByte();
        MatOfFloat err = new MatOfFloat();

        TermCriteria criteria = new TermCriteria(TermCriteria.EPS | TermCriteria.COUNT, 30, 0.01);
        Video.calcOpticalFlowPyrLK(
            prev_gray_frame,
            curr_gray_frame,
            p0,
            p1,
            st,
            err,
            new Size(21, 21),
            3,
            criteria
        );

        byte[] stArr = st.toArray();
        if (stArr.length == 0 || stArr[0] != 1) {
            p0.release();
            p1.release();
            st.release();
            err.release();
            return new FlowResult(null, 0.0);
        }

        float[] errArr = err.toArray();
        double e = errArr.length > 0 ? (double) errArr[0] : 0.0;
        double conf = clamp01(1.0 - (e / FLOW_ERR_MAX));

        Point[] p1Arr = p1.toArray();
        int[] pt = null;
        if (p1Arr.length > 0) {
            pt = new int[] { (int) p1Arr[0].x, (int) p1Arr[0].y };
        }

        p0.release();
        p1.release();
        st.release();
        err.release();

        return new FlowResult(pt, conf);
    }

    private static ConsensusResult select_consensus(
        List<Candidate> candidates,
        double confidence_min,
        double confidence_strong,
        int consensus_dist,
        int consensus_min_methods
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ConsensusResult(null, 0.0, Collections.emptyList());
        }

        List<Candidate> viable = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.conf >= confidence_min) {
                viable.add(c);
            }
        }

        if (viable.isEmpty()) {
            Candidate strongest = Collections.max(candidates, Comparator.comparingDouble(o -> o.conf));
            if (strongest.conf >= confidence_strong) {
                return new ConsensusResult(strongest.pt, strongest.conf, Collections.singletonList(strongest.method));
            }
            return new ConsensusResult(null, 0.0, Collections.emptyList());
        }

        if (viable.size() == 1) {
            if (viable.get(0).conf >= confidence_strong) {
                return new ConsensusResult(viable.get(0).pt, viable.get(0).conf, Collections.singletonList(viable.get(0).method));
            }
            return new ConsensusResult(null, 0.0, Collections.emptyList());
        }

        List<Candidate> best_group = new ArrayList<>();
        double best_score = -1.0;

        for (int i = 0; i < viable.size(); i++) {
            Candidate ci = viable.get(i);
            List<Candidate> group = new ArrayList<>();
            group.add(ci);
            for (int j = 0; j < viable.size(); j++) {
                if (i == j) {
                    continue;
                }
                Candidate cj = viable.get(j);
                if (pt_distance(ci.pt, cj.pt) <= (double) consensus_dist) {
                    group.add(cj);
                }
            }
            double score = 0.0;
            for (Candidate g : group) {
                score += g.conf;
            }
            if (group.size() > best_group.size() || (group.size() == best_group.size() && score > best_score)) {
                best_group = group;
                best_score = score;
            }
        }

        if (best_group.size() < consensus_min_methods) {
            Candidate strongest = Collections.max(viable, Comparator.comparingDouble(o -> o.conf));
            if (strongest.conf >= confidence_strong) {
                return new ConsensusResult(strongest.pt, strongest.conf, Collections.singletonList(strongest.method));
            }
            return new ConsensusResult(null, 0.0, Collections.emptyList());
        }

        double sum_conf = 0.0;
        for (Candidate g : best_group) {
            sum_conf += g.conf;
        }
        if (sum_conf <= 0.0) {
            return new ConsensusResult(null, 0.0, Collections.emptyList());
        }

        double x = 0.0;
        double y = 0.0;
        for (Candidate g : best_group) {
            x += (double) g.pt[0] * g.conf;
            y += (double) g.pt[1] * g.conf;
        }
        x /= sum_conf;
        y /= sum_conf;

        double avg_conf = sum_conf / (double) best_group.size();
        List<String> methods = new ArrayList<>();
        for (Candidate g : best_group) {
            methods.add(g.method);
        }

        return new ConsensusResult(new int[] { (int) x, (int) y }, avg_conf, methods);
    }

    private void set_reference(Mat gray) {
        if (reference_frame != null) {
            reference_frame.release();
            reference_frame = null;
        }
        if (ref_desc != null) {
            ref_desc.release();
            ref_desc = null;
        }
        if (ref_patch != null) {
            ref_patch.release();
            ref_patch = null;
        }
        if (ref_patch_grad != null) {
            ref_patch_grad.release();
            ref_patch_grad = null;
        }

        MatOfKeyPoint kp_ref_m = new MatOfKeyPoint();
        Mat desc_ref = new Mat();
        orb.detectAndCompute(gray, new Mat(), kp_ref_m, desc_ref);
        KeyPoint[] kp_ref = kp_ref_m.toArray();

        boolean ref_feature_rich;
        try {
            ref_feature_rich = is_feature_rich(gray, kp_ref);
        } catch (Exception e) {
            ref_feature_rich = false;
        }

        reference_frame = gray.clone();
        ref_kp = kp_ref;
        ref_desc = desc_ref;

        PatchResult p = extract_ref_patch(reference_frame);
        ref_patch = p.patch;
        ref_patch_center = p.center_in_patch;

        Mat grad = compute_grad_mag(reference_frame);
        PatchResult pg = extract_ref_patch(grad);
        ref_patch_grad = pg.patch;
        grad.release();

        live_pt = new int[] { ref_center[0], ref_center[1] };
        prev_live_pt = new int[] { ref_center[0], ref_center[1] };

        if (prev_gray == null) {
            prev_gray = new Mat();
        }
        gray.copyTo(prev_gray);

        last_scale_est = 1.0;
        was_feature_rich = ref_feature_rich;
        lost_counter = 0;
        last_distance = 0;
        scale_outlier_counter = 0;
        distance_exceed_counter = 0;

        if (was_feature_rich) {
            last_status_message = "Reference set (feature-rich). Tracking enabled.";
        } else {
            last_status_message = "Reference set (feature-poor mode). Tracking enabled.";
        }

        kp_ref_m.release();
    }

    /**
     * Process one frame.
     *
     * @param gray_raw input grayscale frame (any size); internally resized to {@link #FRAME_WIDTH}x{@link #FRAME_HEIGHT}
     */
    public FrameState process(Mat gray_raw) {
        last_status_message = null;

        // Mirror: frame = resize -> gray_raw = BGR2GRAY -> clahe.apply
        Imgproc.resize(gray_raw, last_gray_raw, new Size(FRAME_WIDTH, FRAME_HEIGHT));
        clahe.apply(last_gray_raw, last_gray);

        Mat gray = last_gray;
        MatOfKeyPoint kp_m = new MatOfKeyPoint();
        Mat desc = new Mat();

        int distance = 0;
        double frame_instability = 0.0;

        if (reference_frame != null) {
            orb.detectAndCompute(gray, new Mat(), kp_m, desc);
            KeyPoint[] kp = kp_m.toArray();

            boolean curr_feature_rich;
            try {
                curr_feature_rich = is_feature_rich(gray, kp);
            } catch (Exception e) {
                curr_feature_rich = false;
            }

            boolean tracking_feature_rich = was_feature_rich && curr_feature_rich;
            boolean near_center = is_near_center(last_distance);

            if (tracking_feature_rich) {
                if (kp != null && kp.length < MIN_KP_CURRENT) {
                    feature_loss_counter += 1;
                } else {
                    feature_loss_counter = 0;
                }

                int feature_loss_limit = FEATURE_LOSS_FRAMES + (near_center ? CENTER_GRACE_FRAMES : 0);
                if (feature_loss_counter >= feature_loss_limit) {
                    reset_tracking("Auto-reset (low feature density / axial motion)");
                    kp_m.release();
                    desc.release();
                    return new FrameState(ref_center, live_pt, 0, last_scale_est, overlay_counter, last_status_message, 0.0);
                }

                double entropy_val = compute_entropy(gray);
                if (entropy_val < ENTROPY_MIN) {
                    low_entropy_counter += 1;
                } else {
                    low_entropy_counter = 0;
                }

                int low_entropy_limit = LOW_ENTROPY_FRAMES + (near_center ? CENTER_GRACE_FRAMES : 0);
                if (low_entropy_counter >= low_entropy_limit) {
                    reset_tracking("Auto-reset (low texture entropy / axial motion)");
                    kp_m.release();
                    desc.release();
                    return new FrameState(ref_center, live_pt, 0, last_scale_est, overlay_counter, last_status_message, 0.0);
                }
            } else {
                feature_loss_counter = 0;
                low_entropy_counter = 0;
            }

            TemplateThresholds tth = get_template_thresholds(tracking_feature_rich);
            double tmpl_thresh = tth.tmpl_thresh;
            double tmpl_bonus = tth.tmpl_bonus;
            double strong_thresh = tmpl_thresh + tmpl_bonus;
            double phase_min = get_phase_min(tracking_feature_rich);
            ConsensusParams cp = get_consensus_params(tracking_feature_rich);

            double confidence_min = cp.confidence_min;
            double confidence_strong = cp.confidence_strong;
            int consensus_min_methods = cp.consensus_min_methods;
            int lost_frames_max = cp.lost_frames_max;

            List<Candidate> candidates = new ArrayList<>();

            // --- Method 1: ORB + homography ---
            try {
                if (desc != null && !desc.empty() && ref_desc != null && !ref_desc.empty() && ref_kp != null) {
                    List<MatOfDMatch> matches = new ArrayList<>();
                    bf.knnMatch(ref_desc, desc, matches, 2);

                    List<org.opencv.core.DMatch> good = new ArrayList<>();
                    for (MatOfDMatch m : matches) {
                        org.opencv.core.DMatch[] dm = m.toArray();
                        if (dm.length >= 2) {
                            org.opencv.core.DMatch d0 = dm[0];
                            org.opencv.core.DMatch d1 = dm[1];
                            if (d0.distance < 0.7f * d1.distance) {
                                good.add(d0);
                            }
                        }
                    }

                    if (good.size() >= MIN_GOOD_MATCHES) {
                        List<Point> srcPts = new ArrayList<>();
                        List<Point> dstPts = new ArrayList<>();
                        for (org.opencv.core.DMatch m : good) {
                            srcPts.add(ref_kp[m.queryIdx].pt);
                            dstPts.add(kp[m.trainIdx].pt);
                        }

                        MatOfPoint2f srcMat = new MatOfPoint2f();
                        srcMat.fromList(srcPts);
                        MatOfPoint2f dstMat = new MatOfPoint2f();
                        dstMat.fromList(dstPts);

                        Mat mask = new Mat();
                        Mat H = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, RANSAC_REPROJ_THRESH, mask);

                        if (H != null && !H.empty()) {
                            int inliers = Core.countNonZero(mask);
                            double inlier_ratio = (double) inliers / Math.max((double) good.size(), 1.0);

                            if (inliers >= MIN_INLIERS && inlier_ratio >= INLIER_RATIO_MIN) {
                                double h00 = H.get(0, 0)[0];
                                double h01 = H.get(0, 1)[0];
                                double h10 = H.get(1, 0)[0];
                                double h11 = H.get(1, 1)[0];

                                double sx = Math.sqrt(h00 * h00 + h10 * h10);
                                double sy = Math.sqrt(h01 * h01 + h11 * h11);
                                double scale_est = (sx + sy) / 2.0;
                                last_scale_est = scale_est;

                                int scale_outlier_limit = SCALE_OUTLIER_FRAMES + (near_center ? CENTER_GRACE_FRAMES : 0);
                                boolean scale_ok = (SCALE_MIN <= scale_est) && (scale_est <= SCALE_MAX);
                                if (!scale_ok) {
                                    scale_outlier_counter += 1;
                                    if (scale_outlier_counter >= scale_outlier_limit) {
                                        reset_tracking(String.format("Auto-reset (axial Z scale): %.2f", scale_est));
                                        srcMat.release();
                                        dstMat.release();
                                        mask.release();
                                        H.release();
                                        kp_m.release();
                                        desc.release();
                                        return new FrameState(ref_center, live_pt, 0, last_scale_est, overlay_counter, last_status_message, 0.0);
                                    }
                                } else {
                                    scale_outlier_counter = 0;
                                }

                                if (scale_ok) {
                                    double x = ref_center[0];
                                    double y = ref_center[1];
                                    double hx = H.get(0, 0)[0] * x + H.get(0, 1)[0] * y + H.get(0, 2)[0];
                                    double hy = H.get(1, 0)[0] * x + H.get(1, 1)[0] * y + H.get(1, 2)[0];
                                    double hz = H.get(2, 0)[0] * x + H.get(2, 1)[0] * y + H.get(2, 2)[0];

                                    int[] pt = new int[] { (int) (hx / hz), (int) (hy / hz) };
                                    if (in_frame(pt)) {
                                        double conf_inliers = Math.min(1.0, (double) inliers / Math.max((double) MIN_INLIERS, 1.0));
                                        double conf_ratio = Math.min(1.0, inlier_ratio / Math.max(INLIER_RATIO_MIN, 1e-6));
                                        double conf_matches = Math.min(1.0, (double) good.size() / (MIN_GOOD_MATCHES * 1.5));
                                        double conf = clamp01((0.6 * conf_inliers + 0.4 * conf_ratio) * conf_matches);
                                        candidates.add(new Candidate(pt, conf, "homography"));
                                    }
                                }
                            } else {
                                scale_outlier_counter = 0;
                            }
                        } else {
                            scale_outlier_counter = 0;
                        }

                        srcMat.release();
                        dstMat.release();
                        mask.release();
                        if (H != null) {
                            H.release();
                        }
                    } else {
                        scale_outlier_counter = 0;
                    }
                } else {
                    scale_outlier_counter = 0;
                }
            } catch (Exception e) {
                // mirror Python "pass"
            }

            // --- Method 2: Template matching ---
            TemplateMatchResult tmpl = template_match_fallback(gray, ref_patch, prev_live_pt);
            if (tmpl.pt != null && tmpl.score != null && tmpl.score >= strong_thresh) {
                if (in_frame(tmpl.pt)) {
                    double conf = clamp01((tmpl.score - strong_thresh) / Math.max(1.0 - strong_thresh, 1e-6));
                    candidates.add(new Candidate(tmpl.pt, conf, "template"));
                    if (tmpl.scale != null && conf >= 0.4) {
                        last_scale_est = tmpl.scale;
                    }
                }
            }

            if (!tracking_feature_rich && ref_patch_grad != null) {
                Mat grad_gray = compute_grad_mag(gray);
                TemplateMatchResult grad = template_match_fallback(grad_gray, ref_patch_grad, prev_live_pt);
                double grad_strong = Math.max(0.0, strong_thresh - 0.05);
                if (grad.pt != null && grad.score != null && grad.score >= grad_strong) {
                    if (in_frame(grad.pt)) {
                        double conf = clamp01((grad.score - grad_strong) / Math.max(1.0 - grad_strong, 1e-6));
                        candidates.add(new Candidate(grad.pt, conf * 0.9, "template_grad"));
                        if (grad.scale != null && conf >= 0.4) {
                            last_scale_est = grad.scale;
                        }
                    }
                }
                grad_gray.release();
            }

            // --- Method 3: Phase correlation ---
            PhaseResult ph = phase_correlation_fallback(gray);
            if (ph.resp >= phase_min && ph.sx != null && ph.sy != null) {
                int[] pt = new int[] { (int) (ref_center[0] + ph.sx), (int) (ref_center[1] + ph.sy) };
                if (in_frame(pt)) {
                    double conf = clamp01((ph.resp - phase_min) / Math.max(1.0 - phase_min, 1e-6));
                    candidates.add(new Candidate(pt, conf, "phase"));
                }
            }

            // --- Method 4: Optical flow (prev -> current) ---
            FlowResult flow = optical_flow_fallback(prev_gray, gray, prev_live_pt);
            if (flow.pt != null && flow.conf > 0.0) {
                if (in_frame(flow.pt)) {
                    candidates.add(new Candidate(flow.pt, flow.conf, "flow"));
                }
            }

            // Consensus selection
            ConsensusResult consensus = select_consensus(
                candidates,
                confidence_min,
                confidence_strong,
                CONSENSUS_DIST,
                consensus_min_methods
            );

            // Salvage a real slip that the strict consensus dropped. A coil
            // moving off target matches poorly, so every method's confidence can
            // fall below the viability floor even while 2+ methods still agree
            // where it is — consensus then returns null and the coil is declared
            // "lost" (distance -> 0, re-lock on the slipped spot) instead of
            // surfacing as a warning/critical. Re-run consensus with the floor
            // removed and the strong bar raised (so only a >=2 agreeing group can
            // pass, never a lone method), and accept it only when the agreed
            // position is outside the center guard — a genuine displacement, not
            // the background-locked near cluster that would mask a still coil.
            if (consensus.pt == null) {
                ConsensusResult agree = select_consensus(
                    candidates, SALVAGE_CONFIDENCE_MIN, SALVAGE_CONFIDENCE_STRONG,
                    CONSENSUS_DIST, CONSENSUS_MIN_METHODS);
                if (agree.pt != null
                        && pt_distance(agree.pt, ref_center) > (double) CENTER_GUARD_RADIUS) {
                    consensus = agree;
                }
            }

            // DIAGNOSTIC (temporary): unconditional per-frame candidate dump. On
            // the "bad camera" the consensus flips frame-to-frame between a
            // near-center group (static background, scale~1.0) and a far group
            // (real coil). Those frames have non-null consensus, so the null-path
            // log below never sees them. This prints every method's confidence and
            // distance-from-center plus which group won, so the two clusters are
            // visible directly. Remove once the consensus flip is confirmed/fixed.
            StringBuilder allCand = new StringBuilder();
            for (Candidate c : candidates) {
                allCand.append(c.method)
                       .append("(conf=").append(String.format("%.2f", c.conf))
                       .append(",d=").append((int) pt_distance(c.pt, ref_center))
                       .append(") ");
            }
            SlipTrackerDebug.i("frame: featureRich=" + tracking_feature_rich
                    + " chosen=" + (consensus.pt == null
                            ? "NONE" : String.valueOf((int) pt_distance(consensus.pt, ref_center)))
                    + " chosenMethods=" + consensus.methods
                    + " chosenConf=" + String.format("%.2f", consensus.conf)
                    + " confMin=" + String.format("%.2f", confidence_min)
                    + " quorum=" + consensus_min_methods
                    + " consDist=" + CONSENSUS_DIST
                    + " candidates=[" + (allCand.length() == 0 ? "none" : allCand.toString().trim()) + "]");

            if (consensus.pt == null) {
                // DIAGNOSTIC (temporary): record why consensus failed — each candidate
                // method with its confidence and distance from ref_center, plus the
                // thresholds. Distinguishes a degenerate static-scene loss (all methods
                // low-confidence / too few candidates) from a single outlier method
                // breaking the group. Remove once the static-target false "lost
                // consensus" root cause is confirmed.
                StringBuilder candDbg = new StringBuilder();
                for (Candidate c : candidates) {
                    candDbg.append(c.method)
                           .append("(conf=").append(String.format("%.2f", c.conf))
                           .append(",d=").append((int) pt_distance(c.pt, ref_center))
                           .append(") ");
                }
                SlipTrackerDebug.i("consensus NULL: lastDist=" + last_distance
                        + " nearCenter=" + near_center
                        + " lostCounter=" + lost_counter
                        + " quorum=" + consensus_min_methods
                        + " confMin=" + String.format("%.2f", confidence_min)
                        + " confStrong=" + String.format("%.2f", confidence_strong)
                        + " candidates=[" + (candDbg.length() == 0 ? "none" : candDbg.toString().trim()) + "]");

                distance_exceed_counter = 0;
                Candidate fast_far = null;
                if (!candidates.isEmpty()) {
                    for (Candidate c : candidates) {
                        if (c.conf >= confidence_strong) {
                            if (pt_distance(c.pt, ref_center) >= (double) DISTANCE_THRESHOLD * DISTANCE_FAST_FACTOR) {
                                fast_far = c;
                                break;
                            }
                        }
                    }
                }
                if (fast_far != null) {
                    reset_tracking("Auto-reset (large sudden movement)");
                    kp_m.release();
                    desc.release();
                    return new FrameState(ref_center, live_pt, 0, last_scale_est, overlay_counter, last_status_message, 0.0);
                }

                lost_counter += 1;
                // Near center, last_distance is stale (~0) so a sudden slip that
                // outruns the tracker looks "near center" on its first lost frame.
                // Use a short fixed limit there instead of adding center grace, so
                // a real slip isn't masked by frames meant to absorb axial jitter.
                int lost_limit = near_center ? 2 : lost_frames_max;
                if (lost_counter >= lost_limit) {
                    reset_tracking("Auto-reset (lost consensus)");
                    kp_m.release();
                    desc.release();
                    return new FrameState(ref_center, live_pt, 0, last_scale_est, overlay_counter, last_status_message, 0.0);
                }
            } else {
                lost_counter = 0;
                int[] prev_pt = prev_live_pt;
                live_pt = consensus.pt;
                prev_live_pt = live_pt;

                if (prev_gray == null) {
                    prev_gray = new Mat();
                }
                gray.copyTo(prev_gray);

                int dx = live_pt[0] - ref_center[0];
                int dy = live_pt[1] - ref_center[1];
                distance = (int) Math.sqrt((double) dx * (double) dx + (double) dy * (double) dy);
                last_distance = distance;

                double jump_dist = (prev_pt != null) ? pt_distance(live_pt, prev_pt) : 0.0;
                boolean fast_distance = distance >= (double) DISTANCE_THRESHOLD * DISTANCE_FAST_FACTOR;
                boolean fast_jump = jump_dist >= (double) DISTANCE_THRESHOLD * JUMP_FAST_FACTOR && consensus.conf >= confidence_strong;

                if (distance > DISTANCE_THRESHOLD) {
                    if (fast_distance || fast_jump) {
                        reset_tracking("Auto-reset (large sudden movement)");
                        kp_m.release();
                        desc.release();
                        return new FrameState(ref_center, live_pt, 0, last_scale_est, overlay_counter, last_status_message, 0.0);
                    }

                    // No center grace here: fast movement already reset above, so
                    // anything reaching this point is a sustained over-threshold slip.
                    // Adding grace near center only delays confirming a real slip.
                    int distance_limit = DISTANCE_CONFIRM_FRAMES;
                    distance_exceed_counter += 1;
                    if (distance_exceed_counter >= distance_limit) {
                        reset_tracking("Auto-reset (distance threshold)");
                        kp_m.release();
                        desc.release();
                        return new FrameState(ref_center, live_pt, 0, last_scale_est, overlay_counter, last_status_message, 0.0);
                    }
                } else {
                    distance_exceed_counter = 0;
                }
            }

            frame_instability = computeInstability(near_center, tracking_feature_rich);
        }

        if (overlay_counter > 0) {
            overlay_counter -= 1;
        }

        kp_m.release();
        desc.release();

        return new FrameState(ref_center, live_pt, distance, last_scale_est, overlay_counter, last_status_message, frame_instability);
    }
}

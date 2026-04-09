package org.reactnative.camera.slip;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.List;

public class SlipDetector {
    private static final String TAG = "SlipDetector";

    // Frame dimensions (set dynamically from first frame)
    private int frameWidth = 0;
    private int frameHeight = 0;

    // Thresholds (from Python trial_32.py)
    private static final int DISTANCE_THRESHOLD = 260;
    private static final double CENTER_GUARD_RATIO = 0.35;
    private static final int CENTER_GRACE_FRAMES = 3;
    private static final int SCALE_OUTLIER_FRAMES = 3;
    private static final int DISTANCE_CONFIRM_FRAMES = 2;
    private static final double DISTANCE_FAST_FACTOR = 1.4;
    private static final double JUMP_FAST_FACTOR = 1.1;
    private static final int OVERLAY_FRAMES = 20;

    // Phase correlation downscale
    private static final int PHASE_DS_WIDTH = 320;
    private static final int PHASE_DS_HEIGHT = 240;
    private static final double PHASE_RESPONSE_MIN = 0.05;
    private static final double PHASE_RESPONSE_MIN_POOR = 0.02;

    // Template matching
    private static final int PATCH_SIZE = 220;
    private static final double[] TEMPLATE_SCALES = {0.7, 0.85, 1.0, 1.15};
    private static final double TEMPLATE_MATCH_THRESH = 0.55;
    private static final double TEMPLATE_STRONG_BONUS = 0.15;
    private static final double TEMPLATE_MATCH_THRESH_POOR = 0.40;
    private static final double TEMPLATE_STRONG_BONUS_POOR = 0.10;
    private static final int SEARCH_RADIUS = 350;

    // ORB + Homography
    private static final int MIN_GOOD_MATCHES = 12;
    private static final int MIN_INLIERS = 8;
    private static final double INLIER_RATIO_MIN = 0.20;
    private static final double RANSAC_REPROJ_THRESH = 8.0;
    private static final int MIN_KP_CURRENT = 30;
    private static final int FEATURE_LOSS_FRAMES = 4;
    private static final double ENTROPY_MIN = 3.5;
    private static final int LOW_ENTROPY_FRAMES = 4;

    // Scale (axial)
    private static final double SCALE_MIN = 0.85;
    private static final double SCALE_MAX = 1.75;

    // Consensus
    private static final double CONFIDENCE_MIN = 0.55;
    private static final double CONFIDENCE_STRONG = 0.75;
    private static final double CONSENSUS_DIST = 40;
    private static final int CONSENSUS_MIN_METHODS = 2;
    private static final int LOST_FRAMES_MAX = 3;
    private static final double CONFIDENCE_MIN_POOR = 0.45;
    private static final double CONFIDENCE_STRONG_POOR = 0.60;
    private static final int CONSENSUS_MIN_METHODS_POOR = 1;
    private static final int LOST_FRAMES_MAX_POOR = 6;

    // Feature-rich thresholds
    private static final int FEATURE_RICH_KP_MIN = 45;
    private static final double FEATURE_RICH_ENTROPY_MIN = 4.0;

    // Optical flow
    private static final double FLOW_ERR_MAX = 12.0;

    // OpenCV objects
    private final ORB orb;
    private final BFMatcher bf;

    // Reference state
    private Mat referenceFrame;
    private MatOfKeyPoint refKp;
    private Mat refDesc;
    private Mat refPatch;
    private Mat refPatchGrad;
    private Point refCenter;

    // Tracking state
    private Point livePt;
    private Point prevLivePt;
    private Mat prevGray;
    private double lastScaleEst = 1.0;
    private int lastDistance = 0;
    private boolean wasFeatureRich = false;

    // Counters
    private int lostCounter = 0;
    private int featureLossCounter = 0;
    private int lowEntropyCounter = 0;
    private int scaleOutlierCounter = 0;
    private int distanceExceedCounter = 0;
    private int overlayCounter = 0;

    // Frame skip for performance
    private int frameCounter = 0;
    private static final int PROCESS_EVERY_N_FRAMES = 3;

    // Active state
    private boolean active = false;
    private boolean referenceSet = false;

    // Temp storage for multi-scale template match results
    private double templateMatchBestVal = -1;
    private double templateMatchBestScale = -1;

    public SlipDetector() {
        orb = ORB.create(1500);
        bf = BFMatcher.create(BFMatcher.BRUTEFORCE_HAMMING, false);
    }

    public boolean isActive() {
        return active;
    }

    public int getDistanceThreshold() {
        return DISTANCE_THRESHOLD;
    }

    public void start() {
        active = true;
        referenceSet = false;
        resetTrackingState();
        Log.d(TAG, "Slip detection started, waiting for reference frame");
    }

    public void stop() {
        active = false;
        referenceSet = false;
        resetTrackingState();
        releaseMatResources();
        Log.d(TAG, "Slip detection stopped");
    }

    private void releaseMatResources() {
        if (referenceFrame != null) { referenceFrame.release(); referenceFrame = null; }
        if (refKp != null) { refKp.release(); refKp = null; }
        if (refDesc != null) { refDesc.release(); refDesc = null; }
        if (refPatch != null) { refPatch.release(); refPatch = null; }
        if (refPatchGrad != null) { refPatchGrad.release(); refPatchGrad = null; }
        if (prevGray != null) { prevGray.release(); prevGray = null; }
    }

    private void resetTrackingState() {
        livePt = null;
        prevLivePt = null;
        if (prevGray != null) { prevGray.release(); prevGray = null; }
        lastScaleEst = 1.0;
        lastDistance = 0;
        lostCounter = 0;
        featureLossCounter = 0;
        lowEntropyCounter = 0;
        scaleOutlierCounter = 0;
        distanceExceedCounter = 0;
        overlayCounter = 0;
        frameCounter = 0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double ptDistance(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean inFrame(Point pt) {
        return pt.x >= 0 && pt.x < frameWidth && pt.y >= 0 && pt.y < frameHeight;
    }

    private boolean isNearCenter(int distance) {
        return distance <= (int)(DISTANCE_THRESHOLD * CENTER_GUARD_RATIO);
    }

    private double computeEntropy(Mat gray) {
        List<Mat> images = new ArrayList<>();
        images.add(gray);
        Mat hist = new Mat();
        Imgproc.calcHist(images, new MatOfInt(0), new Mat(), hist, new MatOfInt(64), new MatOfFloat(0, 256));
        double total = Core.sumElems(hist).val[0];
        if (total <= 0) { hist.release(); return 0; }
        double entropy = 0;
        for (int i = 0; i < hist.rows(); i++) {
            double p = hist.get(i, 0)[0] / total;
            if (p > 0) entropy -= p * (Math.log(p) / Math.log(2));
        }
        hist.release();
        return entropy;
    }

    private Mat computeGradMag(Mat gray) {
        Mat gx = new Mat();
        Mat gy = new Mat();
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3);
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3);
        Mat mag = new Mat();
        Core.magnitude(gx, gy, mag);
        Core.normalize(mag, mag, 0, 255, Core.NORM_MINMAX);
        Mat result = new Mat();
        mag.convertTo(result, CvType.CV_8U);
        gx.release(); gy.release(); mag.release();
        return result;
    }

    private boolean isFeatureRich(Mat gray, MatOfKeyPoint kp) {
        if (kp == null || kp.toArray().length < FEATURE_RICH_KP_MIN) return false;
        return computeEntropy(gray) >= FEATURE_RICH_ENTROPY_MIN;
    }

    private void setReference(Mat gray) {
        releaseMatResources();

        frameWidth = gray.cols();
        frameHeight = gray.rows();
        refCenter = new Point(frameWidth / 2, frameHeight / 2);

        // Apply CLAHE
        Mat claheGray = new Mat();
        Imgproc.createCLAHE(2.0, new Size(8, 8)).apply(gray, claheGray);

        referenceFrame = claheGray;
        refKp = new MatOfKeyPoint();
        refDesc = new Mat();
        orb.detectAndCompute(referenceFrame, new Mat(), refKp, refDesc);

        // Extract reference patch
        int half = PATCH_SIZE / 2;
        int cx = (int) refCenter.x;
        int cy = (int) refCenter.y;
        int x0 = Math.max(cx - half, 0);
        int y0 = Math.max(cy - half, 0);
        int x1 = Math.min(cx + half, frameWidth - 1);
        int y1 = Math.min(cy + half, frameHeight - 1);
        refPatch = referenceFrame.submat(y0, y1, x0, x1).clone();

        // Gradient patch
        Mat gradRef = computeGradMag(referenceFrame);
        refPatchGrad = gradRef.submat(y0, y1, x0, x1).clone();
        gradRef.release();

        wasFeatureRich = isFeatureRich(referenceFrame, refKp);
        livePt = new Point((int) refCenter.x, (int) refCenter.y);
        prevLivePt = new Point((int) refCenter.x, (int) refCenter.y);
        prevGray = referenceFrame.clone();
        lastScaleEst = 1.0;
        lastDistance = 0;

        referenceSet = true;
        Log.d(TAG, "Reference set. Feature-rich: " + wasFeatureRich +
              ", keypoints: " + refKp.toArray().length);
    }

    public SlipResult processFrame(Bitmap bitmap) {
        if (!active) return SlipResult.notTracking();

        // Convert bitmap to Mat
        Mat rgba = new Mat();
        Utils.bitmapToMat(bitmap, rgba);
        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
        rgba.release();

        // If no reference yet, capture it from first frame
        if (!referenceSet) {
            setReference(gray);
            gray.release();
            return new SlipResult(0, 1.0, true, false, null,
                (int) refCenter.x, (int) refCenter.y, (int) refCenter.x, (int) refCenter.y);
        }

        // Frame skip: only run CV every Nth frame
        frameCounter++;
        if (frameCounter % PROCESS_EVERY_N_FRAMES != 0) {
            gray.release();
            return new SlipResult(lastDistance, lastScaleEst, true, false, null,
                livePt != null ? livePt.x : (int) refCenter.x,
                livePt != null ? livePt.y : (int) refCenter.y,
                (int) refCenter.x, (int) refCenter.y);
        }

        // Apply CLAHE
        Mat claheGray = new Mat();
        Imgproc.createCLAHE(2.0, new Size(8, 8)).apply(gray, claheGray);
        gray.release();

        SlipResult result = track(claheGray);

        if (prevGray != null) prevGray.release();
        prevGray = claheGray;

        return result;
    }

    private SlipResult track(Mat gray) {
        MatOfKeyPoint kp = new MatOfKeyPoint();
        Mat desc = new Mat();
        orb.detectAndCompute(gray, new Mat(), kp, desc);

        boolean currFeatureRich;
        try { currFeatureRich = isFeatureRich(gray, kp); }
        catch (Exception e) { currFeatureRich = false; }

        boolean trackingFeatureRich = wasFeatureRich && currFeatureRich;
        boolean nearCenter = isNearCenter(lastDistance);

        // Feature loss / entropy checks (only in feature-rich mode)
        if (trackingFeatureRich) {
            if (kp.toArray().length < MIN_KP_CURRENT) featureLossCounter++;
            else featureLossCounter = 0;

            int featureLossLimit = FEATURE_LOSS_FRAMES + (nearCenter ? CENTER_GRACE_FRAMES : 0);
            if (featureLossCounter >= featureLossLimit) {
                resetTrackingForRecovery("low_feature_density");
                kp.release(); desc.release();
                return makeResetResult("low_feature_density");
            }

            double entropy = computeEntropy(gray);
            if (entropy < ENTROPY_MIN) lowEntropyCounter++;
            else lowEntropyCounter = 0;

            int lowEntropyLimit = LOW_ENTROPY_FRAMES + (nearCenter ? CENTER_GRACE_FRAMES : 0);
            if (lowEntropyCounter >= lowEntropyLimit) {
                resetTrackingForRecovery("low_entropy");
                kp.release(); desc.release();
                return makeResetResult("low_entropy");
            }
        } else {
            featureLossCounter = 0;
            lowEntropyCounter = 0;
        }

        // Thresholds based on feature richness
        double tmplThresh, tmplBonus, phaseMin, confMin, confStrong;
        int consMinMethods, lostMax;
        if (trackingFeatureRich) {
            tmplThresh = TEMPLATE_MATCH_THRESH; tmplBonus = TEMPLATE_STRONG_BONUS;
            phaseMin = PHASE_RESPONSE_MIN;
            confMin = CONFIDENCE_MIN; confStrong = CONFIDENCE_STRONG;
            consMinMethods = CONSENSUS_MIN_METHODS; lostMax = LOST_FRAMES_MAX;
        } else {
            tmplThresh = TEMPLATE_MATCH_THRESH_POOR; tmplBonus = TEMPLATE_STRONG_BONUS_POOR;
            phaseMin = PHASE_RESPONSE_MIN_POOR;
            confMin = CONFIDENCE_MIN_POOR; confStrong = CONFIDENCE_STRONG_POOR;
            consMinMethods = CONSENSUS_MIN_METHODS_POOR; lostMax = LOST_FRAMES_MAX_POOR;
        }
        double strongThresh = tmplThresh + tmplBonus;

        List<ConsensusSelector.Candidate> candidates = new ArrayList<>();

        // Method 1: ORB + Homography
        orbHomography(desc, kp, candidates, nearCenter);

        // Method 2: Template matching
        templateMatch(gray, candidates, strongThresh, trackingFeatureRich);

        // Method 3: Phase correlation
        phaseCorrelation(gray, candidates, phaseMin);

        // Method 4: Optical flow
        opticalFlow(gray, candidates);

        // Consensus
        ConsensusSelector.Result consensus = ConsensusSelector.select(
            candidates, confMin, confStrong, CONSENSUS_DIST, consMinMethods);

        kp.release(); desc.release();

        if (!consensus.isValid()) {
            distanceExceedCounter = 0;

            // Check for fast large movement
            for (ConsensusSelector.Candidate c : candidates) {
                if (c.conf >= confStrong &&
                    ptDistance(c.pt, new Point((int) refCenter.x, (int) refCenter.y))
                        >= DISTANCE_THRESHOLD * DISTANCE_FAST_FACTOR) {
                    resetTrackingForRecovery("sudden_movement");
                    return makeResetResult("sudden_movement");
                }
            }

            lostCounter++;
            int lostLimit = lostMax + (nearCenter ? CENTER_GRACE_FRAMES : 0);
            if (lostCounter >= lostLimit) {
                resetTrackingForRecovery("lost_consensus");
                return makeResetResult("lost_consensus");
            }

            // Return last known values
            return new SlipResult(lastDistance, lastScaleEst, true, false, null,
                livePt != null ? livePt.x : (int) refCenter.x,
                livePt != null ? livePt.y : (int) refCenter.y,
                (int) refCenter.x, (int) refCenter.y);
        }

        // Valid consensus
        lostCounter = 0;
        Point prevPt = prevLivePt;
        livePt = consensus.pt;
        prevLivePt = new Point(livePt.x, livePt.y);

        double dx = livePt.x - refCenter.x;
        double dy = livePt.y - refCenter.y;
        int distance = (int) Math.sqrt(dx * dx + dy * dy);
        lastDistance = distance;

        // Check for fast jump
        double jumpDist = prevPt != null ? ptDistance(livePt, prevPt) : 0;
        boolean fastDistance = distance >= DISTANCE_THRESHOLD * DISTANCE_FAST_FACTOR;
        boolean fastJump = jumpDist >= DISTANCE_THRESHOLD * JUMP_FAST_FACTOR && consensus.conf >= confStrong;

        if (distance > DISTANCE_THRESHOLD) {
            if (fastDistance || fastJump) {
                resetTrackingForRecovery("sudden_movement");
                return makeResetResult("sudden_movement");
            }
            int distLimit = DISTANCE_CONFIRM_FRAMES + (nearCenter ? CENTER_GRACE_FRAMES : 0);
            distanceExceedCounter++;
            if (distanceExceedCounter >= distLimit) {
                resetTrackingForRecovery("distance_threshold");
                return makeResetResult("distance_threshold");
            }
        } else {
            distanceExceedCounter = 0;
        }

        return new SlipResult(distance, lastScaleEst, true, false, null,
            livePt.x, livePt.y, (int) refCenter.x, (int) refCenter.y);
    }

    private void resetTrackingForRecovery(String reason) {
        Log.d(TAG, "Tracking reset: " + reason);
        livePt = null;
        prevLivePt = null;
        lostCounter = 0;
        featureLossCounter = 0;
        lowEntropyCounter = 0;
        scaleOutlierCounter = 0;
        distanceExceedCounter = 0;
        lastScaleEst = 1.0;
        lastDistance = 0;
        overlayCounter = OVERLAY_FRAMES;
        // NOTE: referenceFrame is NOT cleared — drift stays relative to session start
    }

    private SlipResult makeResetResult(String reason) {
        overlayCounter = OVERLAY_FRAMES;
        return new SlipResult(0, 1.0, true, true, reason,
            (int) refCenter.x, (int) refCenter.y, (int) refCenter.x, (int) refCenter.y);
    }

    public int getOverlayCounter() {
        return overlayCounter;
    }

    public void decrementOverlayCounter() {
        if (overlayCounter > 0) overlayCounter--;
    }

    // ---- Method 1: ORB + Homography ----
    private void orbHomography(Mat desc, MatOfKeyPoint kp, List<ConsensusSelector.Candidate> candidates,
                               boolean nearCenter) {
        if (desc == null || desc.empty() || refDesc == null || refDesc.empty()) return;

        try {
            List<MatOfDMatch> matchesList = new ArrayList<>();
            bf.knnMatch(refDesc, desc, matchesList, 2);

            List<DMatch> good = new ArrayList<>();
            for (MatOfDMatch matOfDMatch : matchesList) {
                DMatch[] arr = matOfDMatch.toArray();
                if (arr.length >= 2 && arr[0].distance < 0.7 * arr[1].distance) {
                    good.add(arr[0]);
                }
            }

            if (good.size() < MIN_GOOD_MATCHES) {
                scaleOutlierCounter = 0;
                return;
            }

            KeyPoint[] refKpArr = refKp.toArray();
            KeyPoint[] kpArr = kp.toArray();

            org.opencv.core.Point[] srcArr = new org.opencv.core.Point[good.size()];
            org.opencv.core.Point[] dstArr = new org.opencv.core.Point[good.size()];

            for (int i = 0; i < good.size(); i++) {
                srcArr[i] = refKpArr[good.get(i).queryIdx].pt;
                dstArr[i] = kpArr[good.get(i).trainIdx].pt;
            }
            MatOfPoint2f srcPts = new MatOfPoint2f(srcArr);
            MatOfPoint2f dstPts = new MatOfPoint2f(dstArr);

            Mat mask = new Mat();
            Mat H = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, RANSAC_REPROJ_THRESH, mask);

            if (H == null || H.empty()) {
                scaleOutlierCounter = 0;
                srcPts.release(); dstPts.release(); mask.release();
                return;
            }

            int inliers = Core.countNonZero(mask);
            double inlierRatio = (double) inliers / Math.max(good.size(), 1);

            if (inliers < MIN_INLIERS || inlierRatio < INLIER_RATIO_MIN) {
                scaleOutlierCounter = 0;
                H.release(); srcPts.release(); dstPts.release(); mask.release();
                return;
            }

            double sx = Math.sqrt(Math.pow(H.get(0, 0)[0], 2) + Math.pow(H.get(1, 0)[0], 2));
            double sy = Math.sqrt(Math.pow(H.get(0, 1)[0], 2) + Math.pow(H.get(1, 1)[0], 2));
            double scaleEst = (sx + sy) / 2.0;
            lastScaleEst = scaleEst;

            boolean scaleOk = scaleEst >= SCALE_MIN && scaleEst <= SCALE_MAX;
            if (!scaleOk) {
                int scaleLimit = SCALE_OUTLIER_FRAMES + (nearCenter ? CENTER_GRACE_FRAMES : 0);
                scaleOutlierCounter++;
                if (scaleOutlierCounter >= scaleLimit) {
                    H.release(); srcPts.release(); dstPts.release(); mask.release();
                    return;
                }
            } else {
                scaleOutlierCounter = 0;
            }

            if (scaleOk) {
                Mat refCenterMat = new Mat(3, 1, CvType.CV_64F);
                refCenterMat.put(0, 0, refCenter.x);
                refCenterMat.put(1, 0, refCenter.y);
                refCenterMat.put(2, 0, 1.0);
                Mat liveH = new Mat();
                Core.gemm(H, refCenterMat, 1, new Mat(), 0, liveH);

                int px = (int) (liveH.get(0, 0)[0] / liveH.get(2, 0)[0]);
                int py = (int) (liveH.get(1, 0)[0] / liveH.get(2, 0)[0]);
                Point pt = new Point(px, py);

                if (inFrame(pt)) {
                    double confInliers = Math.min(1.0, (double) inliers / MIN_INLIERS);
                    double confRatio = Math.min(1.0, inlierRatio / INLIER_RATIO_MIN);
                    double confMatches = Math.min(1.0, (double) good.size() / (MIN_GOOD_MATCHES * 1.5));
                    double conf = clamp01((0.6 * confInliers + 0.4 * confRatio) * confMatches);
                    candidates.add(new ConsensusSelector.Candidate(pt, conf, "homography"));
                }

                refCenterMat.release();
                liveH.release();
            }

            H.release(); srcPts.release(); dstPts.release(); mask.release();
        } catch (Exception e) {
            Log.w(TAG, "ORB homography error: " + e.getMessage());
        }
    }

    // ---- Method 2: Template matching ----
    private void templateMatch(Mat gray, List<ConsensusSelector.Candidate> candidates,
                               double strongThresh, boolean trackingFeatureRich) {
        if (refPatch == null || refPatch.empty()) return;

        MatOfDouble meanMat = new MatOfDouble();
        MatOfDouble stdDevMat = new MatOfDouble();
        Core.meanStdDev(refPatch, meanMat, stdDevMat);
        double stdDev = stdDevMat.toArray().length > 0 ? stdDevMat.toArray()[0] : 0;
        meanMat.release(); stdDevMat.release();
        if (stdDev < 1e-3) return;

        Point searchCenter = prevLivePt;
        Point bestPt = templateMatchMultiScale(gray, refPatch, searchCenter);
        double bestVal = templateMatchBestVal;
        double bestScale = templateMatchBestScale;

        if (bestPt != null && bestVal >= strongThresh && inFrame(bestPt)) {
            double conf = clamp01((bestVal - strongThresh) / Math.max(1.0 - strongThresh, 1e-6));
            candidates.add(new ConsensusSelector.Candidate(bestPt, conf, "template"));
            if (bestScale > 0 && conf >= 0.4) lastScaleEst = bestScale;
        }

        // Gradient template for feature-poor mode
        if (!trackingFeatureRich && refPatchGrad != null && !refPatchGrad.empty()) {
            Mat gradGray = computeGradMag(gray);
            Point gradPt = templateMatchMultiScale(gradGray, refPatchGrad, searchCenter);
            double gradVal = templateMatchBestVal;
            double gradScale = templateMatchBestScale;
            double gradStrong = Math.max(0.0, strongThresh - 0.05);

            if (gradPt != null && gradVal >= gradStrong && inFrame(gradPt)) {
                double conf = clamp01((gradVal - gradStrong) / Math.max(1.0 - gradStrong, 1e-6));
                candidates.add(new ConsensusSelector.Candidate(gradPt, conf * 0.9, "template_grad"));
                if (gradScale > 0 && conf >= 0.4) lastScaleEst = gradScale;
            }
            gradGray.release();
        }
    }

    private Point templateMatchMultiScale(Mat searchImg, Mat template, Point searchCenter) {
        templateMatchBestVal = -1;
        templateMatchBestScale = -1;
        Point bestPt = null;

        int hRef0 = template.rows();
        int wRef0 = template.cols();

        for (double scale : TEMPLATE_SCALES) {
            int wRef = Math.max(3, (int)(wRef0 * scale));
            int hRef = Math.max(3, (int)(hRef0 * scale));
            Mat scaledPatch = new Mat();
            Imgproc.resize(template, scaledPatch, new Size(wRef, hRef));

            Mat region;
            int offsetX = 0, offsetY = 0;
            boolean regionIsSubmat = false;

            if (searchCenter != null) {
                int sx = Math.max(0, searchCenter.x - SEARCH_RADIUS);
                int sy = Math.max(0, searchCenter.y - SEARCH_RADIUS);
                int ex = Math.min(searchImg.cols(), searchCenter.x + SEARCH_RADIUS);
                int ey = Math.min(searchImg.rows(), searchCenter.y + SEARCH_RADIUS);
                if (ex - sx < wRef || ey - sy < hRef) { scaledPatch.release(); continue; }
                region = searchImg.submat(sy, ey, sx, ex);
                offsetX = sx;
                offsetY = sy;
                regionIsSubmat = true;
            } else {
                region = searchImg;
            }

            Mat result = new Mat();
            Imgproc.matchTemplate(region, scaledPatch, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

            if (mmr.maxVal > templateMatchBestVal) {
                templateMatchBestVal = mmr.maxVal;
                int cx = (int)(mmr.maxLoc.x + wRef / 2.0 + offsetX);
                int cy = (int)(mmr.maxLoc.y + hRef / 2.0 + offsetY);
                bestPt = new Point(cx, cy);
                templateMatchBestScale = scale;
            }

            result.release();
            scaledPatch.release();
            if (regionIsSubmat) region.release();
        }

        return bestPt;
    }

    // ---- Method 3: Phase correlation ----
    private void phaseCorrelation(Mat gray, List<ConsensusSelector.Candidate> candidates, double phaseMin) {
        if (referenceFrame == null) return;

        try {
            Mat refDs = new Mat();
            Mat curDs = new Mat();
            Imgproc.resize(referenceFrame, refDs, new Size(PHASE_DS_WIDTH, PHASE_DS_HEIGHT));
            Imgproc.resize(gray, curDs, new Size(PHASE_DS_WIDTH, PHASE_DS_HEIGHT));

            Mat refF = new Mat();
            Mat curF = new Mat();
            refDs.convertTo(refF, CvType.CV_32F);
            curDs.convertTo(curF, CvType.CV_32F);

            Scalar refMean = Core.mean(refF);
            Scalar curMean = Core.mean(curF);
            Core.subtract(refF, refMean, refF);
            Core.subtract(curF, curMean, curF);

            Mat window = new Mat();
            Imgproc.createHanningWindow(window, new Size(PHASE_DS_WIDTH, PHASE_DS_HEIGHT), CvType.CV_32F);
            Core.multiply(refF, window, refF);
            Core.multiply(curF, window, curF);

            double[] response = new double[1];
            org.opencv.core.Point shift = Imgproc.phaseCorrelate(refF, curF, window, response);

            if (response[0] >= phaseMin) {
                double scaleX = (double) frameWidth / PHASE_DS_WIDTH;
                double scaleY = (double) frameHeight / PHASE_DS_HEIGHT;
                int px = (int)(refCenter.x + shift.x * scaleX);
                int py = (int)(refCenter.y + shift.y * scaleY);
                Point pt = new Point(px, py);
                if (inFrame(pt)) {
                    double conf = clamp01((response[0] - phaseMin) / Math.max(1.0 - phaseMin, 1e-6));
                    candidates.add(new ConsensusSelector.Candidate(pt, conf, "phase"));
                }
            }

            refDs.release(); curDs.release(); refF.release(); curF.release(); window.release();
        } catch (Exception e) {
            Log.w(TAG, "Phase correlation error: " + e.getMessage());
        }
    }

    // ---- Method 4: Optical flow ----
    private void opticalFlow(Mat gray, List<ConsensusSelector.Candidate> candidates) {
        if (prevGray == null || prevLivePt == null) return;

        try {
            MatOfPoint2f prevPts = new MatOfPoint2f(
                new org.opencv.core.Point(prevLivePt.x, prevLivePt.y));
            MatOfPoint2f nextPts = new MatOfPoint2f();
            MatOfByte status = new MatOfByte();
            MatOfFloat err = new MatOfFloat();

            Video.calcOpticalFlowPyrLK(prevGray, gray, prevPts, nextPts, status, err,
                new Size(21, 21), 3,
                new TermCriteria(TermCriteria.EPS | TermCriteria.COUNT, 30, 0.01));

            byte[] statusArr = status.toArray();
            if (statusArr.length > 0 && statusArr[0] == 1) {
                org.opencv.core.Point[] nextArr = nextPts.toArray();
                float[] errArr = err.toArray();
                double e = errArr.length > 0 ? errArr[0] : 0;
                double conf = clamp01(1.0 - (e / FLOW_ERR_MAX));
                Point pt = new Point((int) nextArr[0].x, (int) nextArr[0].y);
                if (conf > 0 && inFrame(pt)) {
                    candidates.add(new ConsensusSelector.Candidate(pt, conf, "flow"));
                }
            }

            prevPts.release(); nextPts.release(); status.release(); err.release();
        } catch (Exception e) {
            Log.w(TAG, "Optical flow error: " + e.getMessage());
        }
    }
}

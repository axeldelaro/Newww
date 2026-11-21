#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace astro {

enum class CfaPattern : int {
    RGGB = 0,
    GRBG = 1,
    GBRG = 2,
    BGGR = 3
};

enum class StackMode : int {
    DEEP_SKY = 0,
    LUCKY_PLANETARY = 1,
    STAR_TRAILS_STANDARD = 2,
    STAR_TRAILS_COMET = 3,
    SINGLE_PRO = 4,
    HDR_ASTRO = 5
};

enum class CalibrationType : int {
    DARK = 0,
    FLAT = 1,
    BIAS = 2
};

struct RawParams {
    int width = 0;
    int height = 0;
    int rowStrideBytes = 0;
    CfaPattern cfa = CfaPattern::RGGB;
    float blackLevel[4] = {64.0f, 64.0f, 64.0f, 64.0f};
    float whiteLevel = 1023.0f;
    float wbGains[3] = {1.0f, 1.0f, 1.0f};
    float ccm[9] = {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    bool hasCcm = false;
    bool bin2x2 = true;
};

struct FloatImageRGB {
    int width = 0;
    int height = 0;
    std::vector<float> r;
    std::vector<float> g;
    std::vector<float> b;

    void resize(int w, int h) {
        width = w;
        height = h;
        const size_t n = static_cast<size_t>(w) * static_cast<size_t>(h);
        r.assign(n, 0.0f);
        g.assign(n, 0.0f);
        b.assign(n, 0.0f);
    }

    void clear() {
        width = 0;
        height = 0;
        r.clear();
        g.clear();
        b.clear();
    }

    size_t pixelCount() const {
        return static_cast<size_t>(width) * static_cast<size_t>(height);
    }

    bool valid() const {
        return width > 0 && height > 0 &&
               r.size() == pixelCount() &&
               g.size() == pixelCount() &&
               b.size() == pixelCount();
    }
};

struct CalibrationMaster {
    int width = 0;
    int height = 0;
    int channels = 1; // 1 = Bayer RAW float32, 3 = RGB planar float32
    CfaPattern cfa = CfaPattern::RGGB;
    CalibrationType type = CalibrationType::DARK;
    float iso = 0.0f;
    float exposureSec = 0.0f;
    float focalMm = 0.0f;
    int frameCount = 0;
    std::vector<float> data;

    bool valid() const {
        return width > 0 && height > 0 && (channels == 1 || channels == 3) &&
               data.size() == static_cast<size_t>(width) * height * channels;
    }
};

struct StarPoint {
    float x = 0.0f;
    float y = 0.0f;
    float flux = 0.0f;
    float peak = 0.0f;
    float fwhm = 0.0f;
    float hfd = 0.0f;
    float snr = 0.0f;
};

struct FocusMetrics {
    float fwhm = 0.0f;
    float hfd = 0.0f;
    float snr = 0.0f;
    float peakValue = 0.0f;
    float bgMedian = 0.0f;
    float bgStd = 0.0f;
    float starX = 0.0f;
    float starY = 0.0f;
    float tenengrad = 0.0f;
    int starFound = 0;
};

struct BestFocusStarResult {
    bool found = false;
    bool isStar = false; // true = unsaturated star, false = sharp lunar/horizon peak fallback
    float normX = 0.5f;  // [0..1] in YUV frame
    float normY = 0.5f;  // [0..1] in YUV frame
    float peak = 0.0f;
    float snr = 0.0f;
};

struct AlignResult {
    bool success = false;
    float dx = 0.0f;
    float dy = 0.0f;
    float angleDeg = 0.0f;
    float scale = 1.0f;
    float residualPx = 0.0f;
    int starsDetected = 0;
    int starsMatched = 0;
    // Maps source (x, y) -> reference (x', y'):
    // x' = m[0]*x + m[1]*y + m[2]
    // y' = m[3]*x + m[4]*y + m[5]
    float matrix[6] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f};
};

struct FrameStackReport {
    bool accepted = false;
    int totalReceived = 0;
    int totalAccepted = 0;
    int totalRejected = 0;
    int starsDetected = 0;
    int starsMatched = 0;
    float residualPx = 0.0f;
    float sharpnessScore = 0.0f;
    int64_t rejectedPixels = 0;
};

struct LabParams {
    bool autoStf = true;
    float blackPoint = 0.0f;            // [0.0 .. 0.5]
    float midtones = 0.5f;              // [0.01 .. 0.99] MTF parameter (0.5 = identity)
    float whitePoint = 1.0f;            // [0.5 .. 1.0]
    float asinhBeta = 0.0f;             // [0 .. 100] Lupton arcsinh stretch strength
    float bgRemovalStrength = 0.0f;     // [0 .. 1] 2D quadratic polynomial background extraction
    bool bgNeutralize = true;           // Align sky RGB medians
    float scnrStrength = 0.0f;          // [0 .. 1] Green cast removal (SCNR Average Neutral)
    float waveletFine = 0.0f;           // [-1 .. 3] À Trous scale 1
    float waveletMedium = 0.0f;         // [-1 .. 3] À Trous scale 2
    float waveletCoarse = 0.0f;         // [-1 .. 3] À Trous scale 3
    float denoiseStrength = 0.0f;       // [0 .. 1] Edge/star-preserving bilateral denoise
    float saturation = 1.0f;            // [0 .. 2.0]
    float temperature = 0.0f;           // [-1 .. 1]
    float tint = 0.0f;                  // [-1 .. 1]
    float deconvolution = 0.0f;         // [0 .. 1] Regularized Richardson-Lucy deconvolution
    float chromaticAberrationFix = 0.0f;// [0 .. 1] Radial lateral CA + purple/green fringe fix
};

struct AstroMetadata {
    std::string cameraModel = "Xiaomi 14 Ultra";
    std::string lensName = "23mm LYT-900";
    std::string dateObs = "2026-01-01T00:00:00Z";
    std::string modeName = "ASTRO PRO";
    float focalLengthMm = 23.0f;
    float apertureF = 1.63f;
    float exposureSec = 8.0f;
    float iso = 1600.0f;
    int stackCount = 1;
    int rejectedCount = 0;
    float avgResidualPx = 0.0f;
};

// ---------------- raw_demosaic.cpp ----------------
void calibrateRawToFloat(
    const uint8_t* rawBytes,
    const RawParams& params,
    const CalibrationMaster* bias,
    const CalibrationMaster* dark,
    const CalibrationMaster* flat,
    std::vector<float>& outBayer
);

void demosaicBayerToFloatRGB(
    const float* bayer,
    const RawParams& params,
    FloatImageRGB& out
);

void processRaw16ToFloatRGB(
    const uint8_t* rawBytes,
    const RawParams& params,
    const CalibrationMaster* bias,
    const CalibrationMaster* dark,
    const CalibrationMaster* flat,
    FloatImageRGB& out
);

void convertYuv420ToFloatRGB(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    const uint8_t* uPlane, int uRowStride, int uPixelStride,
    const uint8_t* vPlane, int vRowStride, int vPixelStride,
    int width, int height,
    int downsampleStep,
    const CalibrationMaster* darkRgb,
    const CalibrationMaster* flatRgb,
    FloatImageRGB& out
);

void analyzePreviewYuv(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    const uint8_t* uPlane, int uRowStride, int uPixelStride,
    const uint8_t* vPlane, int vRowStride, int vPixelStride,
    int width, int height,
    int histBins,
    int32_t* histR, int32_t* histG, int32_t* histB,
    bool computeLoupe,
    float loupeNormX, float loupeNormY,
    int loupeOutSize,
    uint8_t* loupeRgbaOut,
    FocusMetrics& outMetrics
);

int computeFocusPeakingPoints(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    int width, int height,
    float threshold,
    int maxPoints,
    float* outNormXY
);

BestFocusStarResult findBestFocusStar(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    int width, int height
);

// ---------------- star_engine.cpp ----------------
FocusMetrics measureFocusCrop(const float* luma, int w, int h);

std::vector<StarPoint> detectStars(const FloatImageRGB& img, int maxStars = 80);

AlignResult alignStarsTriangleRansac(
    const std::vector<StarPoint>& refStars,
    const std::vector<StarPoint>& srcStars
);

void warpImageAffine(
    const FloatImageRGB& src,
    const float matrix[6],
    int dstW, int dstH,
    FloatImageRGB& dst,
    std::vector<uint8_t>& validMask
);

float computeSharpnessTenengrad(const FloatImageRGB& img);

AlignResult alignPlanetaryTranslation(
    const FloatImageRGB& ref,
    const FloatImageRGB& src,
    int maxShift = 64
);

// ---------------- stacker_engine.cpp ----------------
bool autoDetectHorizon(const FloatImageRGB& refFrame, std::vector<float>& horizonY);

class Drizzle2xAccumulator {
public:
    void reset(int baseW, int baseH, float pixfrac = 0.72f);
    void addFrame(const FloatImageRGB& src, const float matrix[6], const std::vector<uint8_t>* rejectMask = nullptr);
    void finalize(FloatImageRGB& out) const;
    int outWidth() const { return outW_; }
    int outHeight() const { return outH_; }
    int frameCount() const { return frameCount_; }

private:
    int baseW_ = 0;
    int baseH_ = 0;
    int outW_ = 0;
    int outH_ = 0;
    float pixfrac_ = 0.72f;
    int frameCount_ = 0;
    std::vector<float> sumR_, sumG_, sumB_, weight_;
};

class DeepSkyStacker {
public:
    void reset(float kappa = 2.5f, float maxResidualPx = 3.0f, bool landscapeMode = false, bool drizzle2x = false);
    FrameStackReport addFrame(const FloatImageRGB& frame);
    void getCurrentImage(FloatImageRGB& out) const;
    int acceptedCount() const { return accepted_; }
    int rejectedCount() const { return rejected_; }
    float avgResidual() const { return alignedCount_ > 0 ? static_cast<float>(sumResidual_ / alignedCount_) : 0.0f; }
    bool hasDetectedForeground() const { return hasForeground_; }
    const std::vector<float>& horizonContour() const { return horizonY_; }

private:
    struct CompactFrame16 {
        int width = 0;
        int height = 0;
        float maxScale = 1.0f;
        std::vector<uint16_t> r, g, b;
        std::vector<uint8_t> mask;
    };

    void seedFromInitialFrames();

    float kappa_ = 2.5f;
    float maxResidualPx_ = 3.0f;
    bool landscapeMode_ = false;
    bool drizzle2x_ = false;
    bool hasForeground_ = false;
    int width_ = 0;
    int height_ = 0;
    int received_ = 0;
    int accepted_ = 0;
    int rejected_ = 0;
    int alignedCount_ = 0;
    double sumResidual_ = 0.0;
    int64_t totalRejectedPixels_ = 0;
    float skySigmaR_ = 0.002f;
    float skySigmaG_ = 0.002f;
    float skySigmaB_ = 0.002f;

    FloatImageRGB refImage_;
    std::vector<StarPoint> refStars_;
    std::vector<CompactFrame16> initialFrames_;
    bool seeded_ = false;

    std::vector<float> meanR_, meanG_, meanB_;
    std::vector<float> m2R_, m2G_, m2B_;
    std::vector<uint16_t> count_;

    // Automatic horizon sky/ground separation (Sequator-style ground-locked accumulator)
    std::vector<float> horizonY_;
    std::vector<float> fgSumR_, fgSumG_, fgSumB_;
    int fgCount_ = 0;

    // Optional Drizzle 2x sub-pixel reconstruction
    Drizzle2xAccumulator drizzle_;
};

class LuckyImagingStacker {
public:
    void reset(int targetFrames, float keepPercent, float waveletStrength, bool enableDrizzle2x = false);
    FrameStackReport addFrame(const FloatImageRGB& frame);
    bool finalize(FloatImageRGB& out);
    int acceptedCount() const { return static_cast<int>(pool_.size()); }
    int receivedCount() const { return received_; }

private:
    struct ScoredFrame {
        float sharpness = 0.0f;
        float maxScale = 1.0f;
        int width = 0;
        int height = 0;
        std::vector<uint16_t> r, g, b;
    };

    int maxKeep_ = 12;
    float waveletStrength_ = 1.0f;
    bool drizzle2x_ = false;
    int received_ = 0;
    std::vector<ScoredFrame> pool_;
};

class HdrAstroStacker {
public:
    void reset(int expectedBrackets = 3);
    FrameStackReport addBracket(const FloatImageRGB& frame, float evMultiplier);
    bool finalize(FloatImageRGB& out);
    int bracketCount() const { return static_cast<int>(brackets_.size()); }

private:
    struct BracketFrame {
        FloatImageRGB img;
        float evMultiplier = 1.0f;
    };

    int expectedBrackets_ = 3;
    std::vector<BracketFrame> brackets_;
};

class StarTrailsStacker {
public:
    void reset(bool cometMode, float decayFactor);
    FrameStackReport addFrame(const FloatImageRGB& frame);
    const FloatImageRGB& currentImage() const { return accum_; }
    int frameCount() const { return count_; }

private:
    bool cometMode_ = false;
    float decayFactor_ = 0.96f;
    int count_ = 0;
    FloatImageRGB accum_;
};

class CalibrationBuilder {
public:
    void reset(CalibrationType type, int width, int height, int channels, CfaPattern cfa,
               float iso, float exposureSec, float focalMm);
    void addBayerFrame(const uint8_t* rawBytes, const RawParams& params);
    void addRgbFrame(const FloatImageRGB& rgb);
    CalibrationMaster build(const CalibrationMaster* masterBias = nullptr) const;
    int frameCount() const { return static_cast<int>(frames_.size()); }

private:
    CalibrationType type_ = CalibrationType::DARK;
    int width_ = 0;
    int height_ = 0;
    int channels_ = 1;
    CfaPattern cfa_ = CfaPattern::RGGB;
    float iso_ = 0.0f;
    float exposureSec_ = 0.0f;
    float focalMm_ = 0.0f;
    std::vector<std::vector<float>> frames_;
};

// ---------------- astro_lab.cpp ----------------
void applyQuadraticBgRemoval(FloatImageRGB& img, float strength);
void applyBackgroundNeutralization(FloatImageRGB& img);
void applyScnrGreen(FloatImageRGB& img, float strength);
void applyATrousWavelets(FloatImageRGB& img, float fineGain, float medGain, float coarseGain, bool fiveScale = false);
void applyBilateralDenoise(FloatImageRGB& img, float strength);
void applyChromaticAberrationFix(FloatImageRGB& img, float strength);
void applyRichardsonLucyDeconv(FloatImageRGB& img, float strength, float psfSigma = 1.15f);

void processAstroLab(
    const FloatImageRGB& inputLinear,
    const LabParams& params,
    FloatImageRGB& outputLinearOrStretched
);

void renderFloatRGBToRgba8(
    const FloatImageRGB& img,
    uint8_t* rgbaOut,
    int outW, int outH,
    bool applySrgbGamma,
    int histBins = 0,
    int32_t* histR = nullptr,
    int32_t* histG = nullptr,
    int32_t* histB = nullptr
);

// ---------------- astro_io.cpp ----------------
bool writeTiff16RGB(const std::string& path, const FloatImageRGB& img, const AstroMetadata& meta);
bool writeFitsImage(const std::string& path, const FloatImageRGB& img, const AstroMetadata& meta, bool asFloat32 = true);
bool saveAstro32(const std::string& path, const FloatImageRGB& img, const AstroMetadata& meta);
bool loadAstro32(const std::string& path, FloatImageRGB& img, AstroMetadata& meta);
bool saveCalibrationMaster(const std::string& path, const CalibrationMaster& master);
bool loadCalibrationMaster(const std::string& path, CalibrationMaster& master);

} // namespace astro

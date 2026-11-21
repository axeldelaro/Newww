#include "../astro_types.h"

#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <random>
#include <vector>

using namespace astro;

namespace {

constexpr float kPi = 3.14159265358979323846f;

void drawGaussianStar(FloatImageRGB& img, float cx, float cy, float peak, float sigma,
                      float rTint = 1.0f, float gTint = 1.0f, float bTint = 1.0f) {
    const int rad = static_cast<int>(std::ceil(sigma * 4.5f));
    const int x0 = std::max(0, static_cast<int>(std::floor(cx)) - rad);
    const int x1 = std::min(img.width - 1, static_cast<int>(std::ceil(cx)) + rad);
    const int y0 = std::max(0, static_cast<int>(std::floor(cy)) - rad);
    const int y1 = std::min(img.height - 1, static_cast<int>(std::ceil(cy)) + rad);
    const float inv2s2 = 1.0f / (2.0f * sigma * sigma);

    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = static_cast<float>(x) - cx;
            const float dy = static_cast<float>(y) - cy;
            const float val = peak * std::exp(-(dx * dx + dy * dy) * inv2s2);
            const size_t i = static_cast<size_t>(y) * img.width + x;
            img.r[i] += val * rTint;
            img.g[i] += val * gTint;
            img.b[i] += val * bTint;
        }
    }
}

void drawLineStreak(FloatImageRGB& img, float x0, float y0, float x1, float y1, float val) {
    const int steps = static_cast<int>(std::hypot(x1 - x0, y1 - y0) * 2.0f) + 1;
    for (int s = 0; s <= steps; ++s) {
        const float t = static_cast<float>(s) / static_cast<float>(steps);
        const int x = static_cast<int>(std::round(x0 + (x1 - x0) * t));
        const int y = static_cast<int>(std::round(y0 + (y1 - y0) * t));
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                const int px = x + dx;
                const int py = y + dy;
                if (px >= 0 && px < img.width && py >= 0 && py < img.height) {
                    const size_t i = static_cast<size_t>(py) * img.width + px;
                    img.r[i] += val;
                    img.g[i] += val;
                    img.b[i] += val;
                }
            }
        }
    }
}

uint32_t fromBigEndian32(uint32_t v) {
    return ((v & 0x000000FFu) << 24) |
           ((v & 0x0000FF00u) << 8)  |
           ((v & 0x00FF0000u) >> 8)  |
           ((v & 0xFF000000u) >> 24);
}

// =========================================================================
// Test 1: Synthetic Gaussian Star FWHM / HFD Measurement
// =========================================================================
void testFocusFwhmHfd() {
    const int w = 96, h = 96;
    std::vector<float> crop(w * h, 0.04f);
    std::mt19937 rng(42);
    std::normal_distribution<float> noise(0.0f, 0.002f);

    const float trueSigma = 1.95f;
    const float trueFwhm = 2.35482f * trueSigma; // 4.5919 px
    const float cx = 48.35f, cy = 47.72f;

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const float dx = x - cx;
            const float dy = y - cy;
            const float g = 0.72f * std::exp(-(dx * dx + dy * dy) / (2.0f * trueSigma * trueSigma));
            crop[y * w + x] = std::max(0.0f, 0.04f + g + noise(rng));
        }
    }

    FocusMetrics fm = measureFocusCrop(crop.data(), w, h);
    std::printf("[Test 1] Focus Meter: found=%d centroid=(%.2f, %.2f) FWHM=%.3f (true=%.3f) HFD=%.3f SNR=%.1f\n",
                fm.starFound, fm.starX, fm.starY, fm.fwhm, trueFwhm, fm.hfd, fm.snr);

    assert(fm.starFound == 1);
    assert(std::abs(fm.starX - cx) < 0.10f);
    assert(std::abs(fm.starY - cy) < 0.10f);
    assert(std::abs(fm.fwhm - trueFwhm) < 0.35f);
    assert(std::abs(fm.hfd - trueFwhm) < 0.45f);
    assert(fm.snr > 100.0f);
}

// =========================================================================
// Test 2: Triangle-Asterism + RANSAC Alignment with Exact Shift & Rotation
// =========================================================================
void testTriangleAlignment() {
    const int w = 480, h = 360;
    FloatImageRGB ref, shifted;
    ref.resize(w, h);
    shifted.resize(w, h);

    std::mt19937 rng(12345);
    std::uniform_real_distribution<float> ux(45.0f, w - 45.0f);
    std::uniform_real_distribution<float> uy(45.0f, h - 45.0f);
    std::uniform_real_distribution<float> uflux(0.25f, 0.90f);
    std::normal_distribution<float> bgNoise(0.03f, 0.002f);

    for (size_t i = 0; i < ref.pixelCount(); ++i) {
        ref.r[i] = ref.g[i] = ref.b[i] = std::max(0.0f, bgNoise(rng));
        shifted.r[i] = shifted.g[i] = shifted.b[i] = std::max(0.0f, bgNoise(rng));
    }

    // Known ground-truth transform mapping src -> ref: dx=14.3, dy=-9.7, angle=3.2 deg
    const float trueDx = 14.3f;
    const float trueDy = -9.7f;
    const float trueAngleDeg = 3.2f;
    const float rad = trueAngleDeg * kPi / 180.0f;
    const float ca = std::cos(rad);
    const float sa = std::sin(rad);

    // Generate 35 well-separated stars in src and map them to ref using the exact transform
    struct Pt { float sx, sy, peak; };
    std::vector<Pt> pts;
    while (pts.size() < 35) {
        const float sx = ux(rng);
        const float sy = uy(rng);
        bool ok = true;
        for (const auto& p : pts) {
            if (std::hypot(sx - p.sx, sy - p.sy) < 22.0f) { ok = false; break; }
        }
        if (!ok) continue;
        const float rx = ca * sx - sa * sy + trueDx;
        const float ry = sa * sx + ca * sy + trueDy;
        if (rx < 15.0f || rx > w - 15.0f || ry < 15.0f || ry > h - 15.0f) continue;
        const float pk = uflux(rng);
        pts.push_back({sx, sy, pk});
        drawGaussianStar(shifted, sx, sy, pk, 1.55f);
        drawGaussianStar(ref, rx, ry, pk, 1.55f);
    }

    auto refStars = detectStars(ref, 60);
    auto srcStars = detectStars(shifted, 60);
    AlignResult ar = alignStarsTriangleRansac(refStars, srcStars);

    std::printf("[Test 2] Triangle Alignment: ok=%d matched=%d/%d dx=%.3f(%.1f) dy=%.3f(%.1f) ang=%.3f(%.1f) rms=%.4f px\n",
                ar.success ? 1 : 0, ar.starsMatched, static_cast<int>(srcStars.size()),
                ar.dx, trueDx, ar.dy, trueDy, ar.angleDeg, trueAngleDeg, ar.residualPx);

    assert(ar.success);
    assert(ar.starsMatched >= 25);
    assert(ar.residualPx < 0.20f);
    assert(std::abs(ar.dx - trueDx) < 0.35f);
    assert(std::abs(ar.dy - trueDy) < 0.35f);
    assert(std::abs(ar.angleDeg - trueAngleDeg) < 0.10f);
}

// =========================================================================
// Test 3: DeepSkyStacker Kappa-Sigma Satellite Trail Rejection
// =========================================================================
void testKappaSigmaSatelliteRejection() {
    const int w = 256, h = 256;
    DeepSkyStacker stacker;
    stacker.reset(2.5f, 3.0f);

    std::mt19937 rng(777);
    std::normal_distribution<float> noise(0.05f, 0.004f);

    // 16 fixed stars across field
    struct S { float x, y, pk; };
    std::vector<S> field = {
        {42, 48, 0.70f}, {95, 36, 0.55f}, {162, 52, 0.80f}, {215, 44, 0.62f},
        {38, 118, 0.66f}, {108, 102, 0.75f}, {182, 112, 0.58f}, {226, 128, 0.72f},
        {54, 185, 0.60f}, {122, 174, 0.82f}, {175, 198, 0.64f}, {218, 212, 0.52f},
        {78, 224, 0.68f}, {148, 230, 0.56f}, {132, 68, 0.50f}, {66, 152, 0.74f}
    };

    for (int f = 0; f < 8; ++f) {
        FloatImageRGB frame;
        frame.resize(w, h);
        for (size_t i = 0; i < frame.pixelCount(); ++i) {
            frame.r[i] = std::max(0.0f, noise(rng));
            frame.g[i] = std::max(0.0f, noise(rng));
            frame.b[i] = std::max(0.0f, noise(rng));
        }
        // Apply small realistic tripod drift on frames
        const float driftX = f * 0.8f;
        const float driftY = -f * 0.5f;
        for (const auto& s : field) {
            drawGaussianStar(frame, s.x - driftX, s.y - driftY, s.pk, 1.5f);
        }

        // Inject a blazing bright Starlink satellite streak on Frame 4 (and even Frame 1 to verify seed!)
        if (f == 4) {
            drawLineStreak(frame, 20.0f, 130.0f, 235.0f, 130.0f, 0.85f);
        }

        FrameStackReport rep = stacker.addFrame(frame);
        assert(rep.accepted);
    }

    FloatImageRGB stacked;
    stacker.getCurrentImage(stacked);

    // Measure intensity along the satellite trail path (away from stars, e.g. x=128..160, y=130)
    float maxAlongStreak = 0.0f;
    for (int x = 128; x <= 160; ++x) {
        const size_t i = static_cast<size_t>(130) * w + x;
        maxAlongStreak = std::max(maxAlongStreak, stacked.g[i]);
    }
    // Check star at (122, 174) survived brightly
    const float starPeak = stacked.g[static_cast<size_t>(174) * w + 122];

    std::printf("[Test 3] Kappa-Sigma Rejection: accepted=%d maxAlongSatelliteStreak=%.4f (bg=0.050) starPeak=%.3f\n",
                stacker.acceptedCount(), maxAlongStreak, starPeak);

    assert(stacker.acceptedCount() == 8);
    assert(maxAlongStreak < 0.068f); // Satellite trail completely eradicated!
    assert(starPeak > 0.65f);        // Real star fully preserved!
}

// =========================================================================
// Test 4: 16-bit Bayer RAW Dark + Flat Calibration + Malvar Demosaic
// =========================================================================
void testRawCalibrationAndDemosaic() {
    const int w = 128, h = 128;
    RawParams rp{};
    rp.width = w;
    rp.height = h;
    rp.rowStrideBytes = w * 2;
    rp.cfa = CfaPattern::RGGB;
    rp.blackLevel[0] = rp.blackLevel[1] = rp.blackLevel[2] = rp.blackLevel[3] = 64.0f;
    rp.whiteLevel = 4095.0f;
    rp.bin2x2 = false;

    const float range = rp.whiteLevel - 64.0f;
    const int hotX = 64, hotY = 64; // RGGB R pixel

    auto vignette = [&](int x, int y) -> float {
        const float nx = (x - w * 0.5f) / (w * 0.5f);
        const float ny = (y - h * 0.5f) / (h * 0.5f);
        return std::max(0.45f, 1.0f - 0.35f * (nx * nx + ny * ny));
    };

    // Build Master Dark (5 frames with fixed hot pixel at (64, 64))
    CalibrationBuilder darkBuilder;
    darkBuilder.reset(CalibrationType::DARK, w, h, 1, CfaPattern::RGGB, 1600, 10.0f, 23.0f);
    std::vector<uint16_t> rawBuf(w * h);
    for (int f = 0; f < 5; ++f) {
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                float dn = 64.0f + 12.0f; // dark current
                if (x == hotX && y == hotY) dn += 1800.0f; // hot pixel!
                rawBuf[y * w + x] = static_cast<uint16_t>(dn);
            }
        }
        darkBuilder.addBayerFrame(reinterpret_cast<const uint8_t*>(rawBuf.data()), rp);
    }
    CalibrationMaster masterDark = darkBuilder.build();

    // Build Master Flat (5 frames with 35% optical corner vignetting)
    CalibrationBuilder flatBuilder;
    flatBuilder.reset(CalibrationType::FLAT, w, h, 1, CfaPattern::RGGB, 100, 0.01f, 23.0f);
    for (int f = 0; f < 5; ++f) {
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const float val = 64.0f + 0.55f * range * vignette(x, y);
                rawBuf[y * w + x] = static_cast<uint16_t>(std::round(val));
            }
        }
        flatBuilder.addBayerFrame(reinterpret_cast<const uint8_t*>(rawBuf.data()), rp);
    }
    CalibrationMaster masterFlat = flatBuilder.build();

    // Create a uniform sky light frame affected by BOTH vignetting AND the hot pixel
    const float trueSky = 0.25f;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float dn = 64.0f + 12.0f + trueSky * range * vignette(x, y);
            if (x == hotX && y == hotY) dn += 1800.0f;
            rawBuf[y * w + x] = static_cast<uint16_t>(std::clamp(std::round(dn), 0.0f, 4095.0f));
        }
    }

    FloatImageRGB calibrated;
    processRaw16ToFloatRGB(reinterpret_cast<const uint8_t*>(rawBuf.data()), rp,
                           nullptr, &masterDark, &masterFlat, calibrated);

    const float cornerVal = calibrated.g[6 * w + 6];
    const float centerVal = calibrated.g[(h / 2) * w + (w / 2)];
    const float hotVal = calibrated.r[hotY * w + hotX];

    std::printf("[Test 4] Dark+Flat Calibration: corner=%.4f center=%.4f hotPixel=%.4f (trueSky=%.4f)\n",
                cornerVal, centerVal, hotVal, trueSky);

    assert(std::abs(cornerVal - centerVal) < 0.015f); // Vignetting restored flat!
    assert(std::abs(hotVal - centerVal) < 0.015f);    // Hot pixel erased!
}

// =========================================================================
// Test 5: 2D Quadratic Polynomial Background Gradient Extraction
// =========================================================================
void testQuadraticGradientExtraction() {
    const int w = 240, h = 180;
    FloatImageRGB img;
    img.resize(w, h);

    for (int y = 0; y < h; ++y) {
        const float v = (2.0f * y / h) - 1.0f;
        for (int x = 0; x < w; ++x) {
            const float u = (2.0f * x / w) - 1.0f;
            // Severe city light pollution gradient (linear + quadratic dome)
            const float grad = 0.05f + 0.12f * u + 0.09f * v + 0.06f * u * u + 0.05f * v * v;
            const size_t i = static_cast<size_t>(y) * w + x;
            img.r[i] = grad * 1.15f;
            img.g[i] = grad;
            img.b[i] = grad * 0.85f;
        }
    }
    // Add bright stars that must NOT ruin the polynomial surface fit
    drawGaussianStar(img, 120, 90, 0.70f, 2.0f);
    drawGaussianStar(img, 60, 45, 0.65f, 1.8f);
    drawGaussianStar(img, 180, 135, 0.60f, 1.8f);

    const float preCornerA = img.g[10 * w + 10];
    const float preCornerB = img.g[(h - 10) * w + (w - 10)];
    const float preDiff = std::abs(preCornerB - preCornerA);

    applyQuadraticBgRemoval(img, 1.0f);

    const float postCornerA = img.g[10 * w + 10];
    const float postCornerB = img.g[(h - 10) * w + (w - 10)];
    const float postDiff = std::abs(postCornerB - postCornerA);

    std::printf("[Test 5] 2D Gradient Extraction: cornerDiff before=%.4f -> after=%.4f\n", preDiff, postDiff);

    assert(preDiff > 0.30f);
    assert(postDiff < 0.015f); // Gradient reduced by >95%!
}

// =========================================================================
// Test 6: À Trous B3-Spline Wavelet Sharpening
// =========================================================================
void testWaveletSharpening() {
    const int w = 128, h = 128;
    FloatImageRGB moon;
    moon.resize(w, h);

    // Synthetic lunar crater rings & ridges
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const float dx = x - 64.0f;
            const float dy = y - 64.0f;
            const float r = std::hypot(dx, dy);
            float lum = (r < 48.0f) ? (0.45f + 0.08f * std::sin(r * 0.9f) * std::cos(dx * 0.35f)) : 0.02f;
            const size_t i = static_cast<size_t>(y) * w + x;
            moon.r[i] = moon.g[i] = moon.b[i] = std::clamp(lum, 0.0f, 1.0f);
        }
    }

    const float sharpBefore = computeSharpnessTenengrad(moon);
    applyATrousWavelets(moon, 1.2f, 0.8f, 0.4f, true);
    const float sharpAfter = computeSharpnessTenengrad(moon);

    std::printf("[Test 6] À Trous Wavelets: Tenengrad sharpness %.5f -> %.5f (%.2fx)\n",
                sharpBefore, sharpAfter, sharpAfter / std::max(1e-7f, sharpBefore));
    assert(sharpAfter > sharpBefore * 1.4f);
}

// =========================================================================
// Test 7: TIFF 16-bit & FITS 32-bit Binary Integrity & Round-Trip
// =========================================================================
void testTiff16AndFits32Export() {
    const int w = 64, h = 48;
    FloatImageRGB img;
    img.resize(w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t i = static_cast<size_t>(y) * w + x;
            img.r[i] = static_cast<float>(x) / static_cast<float>(w - 1);
            img.g[i] = static_cast<float>(y) / static_cast<float>(h - 1);
            img.b[i] = 0.25f + 0.5f * static_cast<float>((x + y) % 16) / 15.0f;
        }
    }

    AstroMetadata meta;
    meta.cameraModel = "Xiaomi 14 Ultra";
    meta.lensName = "23mm LYT-900";
    meta.focalLengthMm = 23.0f;
    meta.apertureF = 1.63f;
    meta.exposureSec = 8.0f;
    meta.iso = 1600.0f;
    meta.stackCount = 40;

    const std::string tiffPath = "/tmp/test_astropro_16bit.tif";
    const std::string fitsPath = "/tmp/test_astropro_32bit.fits";
    const std::string astroPath = "/tmp/test_astropro.astro32";

    assert(writeTiff16RGB(tiffPath, img, meta));
    assert(writeFitsImage(fitsPath, img, meta, true));
    assert(saveAstro32(astroPath, img, meta));

    // Verify TIFF 6.0 Little-Endian header + BitsPerSample + file size
    {
        std::ifstream is(tiffPath, std::ios::binary | std::ios::ate);
        assert(is.good());
        const size_t sz = static_cast<size_t>(is.tellg());
        assert(sz >= static_cast<size_t>(w) * h * 6 + 180);
        is.seekg(0);
        char hdr[8];
        is.read(hdr, 8);
        assert(hdr[0] == 'I' && hdr[1] == 'I');
        assert(static_cast<uint8_t>(hdr[2]) == 42 && hdr[3] == 0);
    }

    // Verify FITS 4.0 strict 2880-byte block multiple, ASCII header keywords, and exact BE float32 pixel round-trip
    {
        std::ifstream is(fitsPath, std::ios::binary | std::ios::ate);
        assert(is.good());
        const size_t sz = static_cast<size_t>(is.tellg());
        assert(sz % 2880 == 0);
        is.seekg(0);
        std::string hdr(2880, '\0');
        is.read(hdr.data(), 2880);
        assert(hdr.find("SIMPLE  =                    T") == 0);
        assert(hdr.find("BITPIX  =                  -32") != std::string::npos);
        assert(hdr.find("NAXIS   =                    3") != std::string::npos);
        assert(hdr.find("Xiaomi 14 Ultra") != std::string::npos);
        assert(hdr.find("END     ") != std::string::npos);

        // Read back first 3 pixels of R plane and verify exact float32 value
        uint32_t beBits = 0;
        is.seekg(2880 + (10 * w + 32) * 4);
        is.read(reinterpret_cast<char*>(&beBits), 4);
        uint32_t leBits = fromBigEndian32(beBits);
        float readR = 0.0f;
        std::memcpy(&readR, &leBits, sizeof(float));
        assert(std::abs(readR - img.r[10 * w + 32]) < 1e-6f);
    }

    // Verify lossless .astro32 round-trip
    {
        FloatImageRGB loaded;
        AstroMetadata loadedMeta;
        assert(loadAstro32(astroPath, loaded, loadedMeta));
        assert(loaded.width == w && loaded.height == h);
        assert(loadedMeta.stackCount == 40);
        assert(loadedMeta.cameraModel == "Xiaomi 14 Ultra");
        assert(std::abs(loaded.b[15 * w + 20] - img.b[15 * w + 20]) < 1e-6f);
    }

    std::printf("[Test 7] TIFF 16-bit, FITS 32-bit (2880B aligned), and .astro32 round-trip: PASS\n");
}

// =========================================================================
// Test 8: Real-Time Focus Peaking (Lunar Limb, Craters & Pinpoint Stars)
// =========================================================================
void testFocusPeakingPoints() {
    const int w = 640, h = 480;
    std::vector<uint8_t> flat(w * h, 14);
    std::vector<uint8_t> sharp(w * h, 10);
    std::vector<uint8_t> blurred(w * h, 10);

    // Build sharp lunar disc + high-contrast crater rims + 5 bright pinpoint stars
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const float dx = x - 320.0f;
            const float dy = y - 240.0f;
            const float r = std::hypot(dx, dy);
            float v = 10.0f;
            if (r <= 110.0f) {
                v = 195.0f;
                // Add sharp crater rims inside lunar disc
                const float c1 = std::hypot(x - 285.0f, y - 215.0f);
                const float c2 = std::hypot(x - 355.0f, y - 265.0f);
                if (std::abs(c1 - 24.0f) < 3.5f) v = 250.0f;
                else if (c1 < 20.5f) v = 65.0f;
                if (std::abs(c2 - 18.0f) < 3.0f) v = 245.0f;
                else if (c2 < 15.0f) v = 70.0f;
            }
            sharp[static_cast<size_t>(y) * w + x] = static_cast<uint8_t>(std::clamp(v, 0.0f, 255.0f));
        }
    }
    // Add crisp pinpoint stars far from the Moon
    const int stars[4][2] = {{70, 80}, {560, 90}, {90, 400}, {550, 390}};
    for (const auto& s : stars) {
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                const int val = (dx == 0 && dy == 0) ? 255 : 190;
                sharp[static_cast<size_t>(s[1] + dy) * w + (s[0] + dx)] = static_cast<uint8_t>(val);
            }
        }
    }

    // Create heavily defocused version (17x17 box blur)
    std::vector<float> tmpBlur(w * h, 0.0f);
    constexpr int kRad = 12;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            int cnt = 0;
            for (int dx = -kRad; dx <= kRad; ++dx) {
                const int sx = std::clamp(x + dx, 0, w - 1);
                acc += sharp[static_cast<size_t>(y) * w + sx];
                cnt++;
            }
            tmpBlur[static_cast<size_t>(y) * w + x] = acc / static_cast<float>(cnt);
        }
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            int cnt = 0;
            for (int dy = -kRad; dy <= kRad; ++dy) {
                const int sy = std::clamp(y + dy, 0, h - 1);
                acc += tmpBlur[static_cast<size_t>(sy) * w + x];
                cnt++;
            }
            blurred[static_cast<size_t>(y) * w + x] = static_cast<uint8_t>(std::clamp(acc / static_cast<float>(cnt), 0.0f, 255.0f));
        }
    }

    constexpr int kMaxPts = 800;
    std::vector<float> pts(kMaxPts * 2, 0.0f);

    const int flatCount = computeFocusPeakingPoints(flat.data(), w, 1, w, h, 0.14f, kMaxPts, pts.data());
    assert(flatCount == 0);

    const auto t0 = std::chrono::steady_clock::now();
    const int sharpCount = computeFocusPeakingPoints(sharp.data(), w, 1, w, h, 0.14f, kMaxPts, pts.data());
    const auto t1 = std::chrono::steady_clock::now();
    const double elapsedMs = std::chrono::duration<double, std::milli>(t1 - t0).count();

    for (int i = 0; i < sharpCount; ++i) {
        const float nx = pts[i * 2 + 0];
        const float ny = pts[i * 2 + 1];
        assert(nx >= 0.0f && nx <= 1.0f);
        assert(ny >= 0.0f && ny <= 1.0f);
    }

    // Verify at least one star corner was picked up by peaking
    bool foundCornerStar = false;
    for (int i = 0; i < sharpCount; ++i) {
        if (pts[i * 2 + 0] < 0.20f && pts[i * 2 + 1] < 0.25f) {
            foundCornerStar = true;
            break;
        }
    }

    std::vector<float> blurPts(kMaxPts * 2, 0.0f);
    const int blurCount = computeFocusPeakingPoints(blurred.data(), w, 1, w, h, 0.14f, kMaxPts, blurPts.data());

    std::printf("[Test 8] Focus Peaking: flat=%d sharp=%d blurred=%d starDetected=%d time=%.3f ms\n",
                flatCount, sharpCount, blurCount, foundCornerStar ? 1 : 0, elapsedMs);

    assert(sharpCount > 60);
    assert(foundCornerStar);
    assert(sharpCount > blurCount * 2);
    assert(elapsedMs < 1.5);
}

} // namespace

int main() {
    std::printf("=== AstroPro 14U C++20 Engine Verification Suite ===\n");
    testFocusFwhmHfd();
    testTriangleAlignment();
    testKappaSigmaSatelliteRejection();
    testRawCalibrationAndDemosaic();
    testQuadraticGradientExtraction();
    testWaveletSharpening();
    testTiff16AndFits32Export();
    testFocusPeakingPoints();
    std::printf("=== ALL 8 C++20 ASTRO ENGINE TESTS PASSED ===\n");
    return 0;
}

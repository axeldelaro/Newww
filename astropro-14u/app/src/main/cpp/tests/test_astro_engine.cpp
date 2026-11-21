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

// =========================================================================
// Test 9: Auto-Horizon Sky/Ground Separation (Zero-Effort Dual-Stacker)
// =========================================================================
void testAutoHorizonSkyGroundSeparation() {
    const int w = 256, h = 256;
    auto ridgeYAt = [&](int x) -> float {
        // Synthetic mountain silhouette spanning bottom ~32% of frame
        const float peak1 = 22.0f * std::exp(-std::pow((x - 84.0f) / 28.0f, 2.0f));
        const float peak2 = 28.0f * std::exp(-std::pow((x - 182.0f) / 34.0f, 2.0f));
        return 178.0f - peak1 - peak2;
    };

    // 1. Verify Auto-No-Op Protection on pure starry sky (no foreground)
    {
        FloatImageRGB pureSky;
        pureSky.resize(w, h);
        std::mt19937 rngPure(101);
        std::normal_distribution<float> skyNoise(0.06f, 0.002f);
        for (size_t i = 0; i < pureSky.pixelCount(); ++i) {
            pureSky.r[i] = pureSky.g[i] = pureSky.b[i] = std::max(0.0f, skyNoise(rngPure));
        }
        const float pts[12][2] = {
            {35, 30}, {110, 42}, {210, 38}, {60, 115}, {148, 128}, {225, 118},
            {44, 195}, {132, 205}, {205, 198}, {52, 236}, {140, 240}, {218, 234}
        };
        for (const auto& p : pts) drawGaussianStar(pureSky, p[0], p[1], 0.65f, 1.5f);
        std::vector<float> dummyH;
        const bool detectedOnZenith = autoDetectHorizon(pureSky, dummyH);
        assert(!detectedOnZenith);
    }

    // 2. Verify Sky + Mountain Dual-Stacking across 5 sky-drifting frames
    struct S { float x, y, pk; };
    std::vector<S> skyStars = {
        {32, 32, 0.72f}, {84, 24, 0.60f}, {138, 38, 0.78f}, {198, 28, 0.64f}, {232, 46, 0.56f},
        {48, 76, 0.68f}, {108, 64, 0.80f}, {162, 80, 0.62f}, {216, 72, 0.74f},
        {36, 118, 0.65f}, {92, 108, 0.70f}, {146, 116, 0.76f}, {204, 106, 0.58f},
        {64, 136, 0.54f}, {126, 138, 0.66f}
    };

    DeepSkyStacker landscapeStacker;
    landscapeStacker.reset(2.5f, 3.0f, /*landscapeMode=*/true, /*drizzle2x=*/false);

    DeepSkyStacker skyOnlyStacker;
    skyOnlyStacker.reset(2.5f, 3.0f, /*landscapeMode=*/false, /*drizzle2x=*/false);

    std::mt19937 rng(909);
    std::normal_distribution<float> noise(0.0f, 0.002f);

    // Build 5 frames: stars drift 2.5px/frame (+10px total!) while mountain silhouette stays FIXED
    for (int f = 0; f < 5; ++f) {
        FloatImageRGB frame;
        frame.resize(w, h);
        const float driftX = f * 2.5f;
        const float driftY = f * 0.6f;

        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const size_t idx = static_cast<size_t>(y) * w + x;
                const float nVal = noise(rng);
                const float rY = ridgeYAt(x);
                if (static_cast<float>(y) < rY) {
                    const float skyVal = std::max(0.0f, 0.065f + nVal);
                    frame.r[idx] = skyVal;
                    frame.g[idx] = skyVal;
                    frame.b[idx] = skyVal;
                } else {
                    // Dark mountain silhouette with crisp fixed vertical rock cliff stripe at x=128..131, y=210..235
                    float fgVal = 0.014f + nVal;
                    if (y >= 205 && y <= 238 && x >= 126 && x <= 130) {
                        fgVal = 0.048f + nVal; // crisp fixed foreground lit ridge detail!
                    }
                    fgVal = std::max(0.0f, fgVal);
                    frame.r[idx] = fgVal;
                    frame.g[idx] = fgVal;
                    frame.b[idx] = fgVal;
                }
            }
        }
        // Paint drifting stars in the sky zone
        for (const auto& s : skyStars) {
            const float sx = s.x - driftX;
            const float sy = s.y - driftY;
            if (sx > 8 && sx < w - 8 && sy > 8 && sy < ridgeYAt(static_cast<int>(sx)) - 8) {
                drawGaussianStar(frame, sx, sy, s.pk, 1.45f);
            }
        }

        assert(landscapeStacker.addFrame(frame).accepted);
        assert(skyOnlyStacker.addFrame(frame).accepted);
    }

    assert(landscapeStacker.hasDetectedForeground());

    FloatImageRGB landOut, skyOnlyOut;
    landscapeStacker.getCurrentImage(landOut);
    skyOnlyStacker.getCurrentImage(skyOnlyOut);

    // Check sky star at (108, 64) stayed razor-sharp in landscape stack
    const float skyStarPeak = landOut.g[64 * w + 108];

    // Measure horizontal edge contrast of the fixed rock detail at x=126..130, y=220
    // In landscape mode (ground-locked), edge stays crisp; in sky-only stack (shifted 0..10px), it smears!
    auto measureFgDetailContrast = [&](const FloatImageRGB& im) -> float {
        float peakVal = 0.0f;
        float bgVal = 0.0f;
        for (int x = 127; x <= 129; ++x) peakVal += im.g[220 * w + x];
        for (int x = 115; x <= 120; ++x) bgVal += im.g[220 * w + x];
        return (peakVal / 3.0f) - (bgVal / 6.0f);
    };
    const float fgContrastLand = measureFgDetailContrast(landOut);
    const float fgContrastSkyOnly = measureFgDetailContrast(skyOnlyOut);

    std::printf("[Test 9] Auto-Horizon Dual-Stack: fgDetected=1 starPeak=%.3f fgContrast=%.4f (vs unseparated=%.4f)\n",
                skyStarPeak, fgContrastLand, fgContrastSkyOnly);

    assert(skyStarPeak > 0.65f);
    assert(fgContrastLand > 0.030f);                  // Razor-sharp fixed foreground!
    assert(fgContrastLand > fgContrastSkyOnly * 1.8f); // Zero motion blur vs naive sky-only stack!
}

// =========================================================================
// Test 10: HDR Astro 3-Bracket Linear Radiance Fusion
// =========================================================================
void testHdrAstroFusion() {
    const int w = 160, h = 160;
    const float cx = 80.0f, cy = 80.0f;

    // True physical linear radiance scene (Orion Nebula M42):
    // - Sky background: 0.004
    // - Faint outer nebula ring (r=38..54): 0.022
    // - Blazing Trapezium core (r<10): peak 2.40 (exceeds 1.0!)
    auto trueRadiance = [&](float x, float y) -> float {
        const float r = std::hypot(x - cx, y - cy);
        const float core = 2.40f * std::exp(-(r * r) / (2.0f * 6.5f * 6.5f));
        const float outer = 0.022f * std::exp(-std::pow((r - 42.0f) / 10.0f, 2.0f));
        return 0.004f + core + outer;
    };

    const float evMuls[3] = {0.25f, 1.0f, 4.0f}; // -2 EV, 0 EV, +2 EV
    const float shifts[3][2] = {{-1.5f, 1.0f}, {0.0f, 0.0f}, {1.2f, -0.8f}};

    std::mt19937 rng(1010);
    std::normal_distribution<float> readNoise(0.0f, 0.0045f);

    HdrAstroStacker hdr;
    hdr.reset(3);

    FloatImageRGB shortFrame;
    for (int b = 0; b < 3; ++b) {
        FloatImageRGB frame;
        frame.resize(w, h);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const float rad = trueRadiance(x - shifts[b][0], y - shifts[b][1]);
                // Sensor clips linearly at 1.0 with fixed read noise per frame
                const float sig = std::clamp(rad * evMuls[b] + readNoise(rng), 0.0f, 1.0f);
                const size_t i = static_cast<size_t>(y) * w + x;
                frame.r[i] = sig;
                frame.g[i] = sig;
                frame.b[i] = sig;
            }
        }
        if (b == 0) shortFrame = frame;
        assert(hdr.addBracket(frame, evMuls[b]).accepted);
    }

    FloatImageRGB fused;
    assert(hdr.finalize(fused));
    assert(fused.width == w && fused.height == h);

    // Core must preserve unclipped linear radiance > 1.5 (near true 2.40!)
    const float coreVal = fused.g[80 * w + 80];

    // Faint outer nebula around radius 42 (y=80, x=122) must recover ~0.026 (bg 0.004 + nebula 0.022) cleanly!
    double outerSum = 0.0;
    double outerSq = 0.0;
    int outerCnt = 0;
    double shortOuterSq = 0.0;
    for (int y = 76; y <= 84; ++y) {
        for (int x = 119; x <= 125; ++x) {
            const float expected = trueRadiance(static_cast<float>(x), static_cast<float>(y));
            const float got = fused.g[static_cast<size_t>(y) * w + x];
            outerSum += got;
            const double err = got - expected;
            outerSq += err * err;

            const float shortEst = shortFrame.g[static_cast<size_t>(y) * w + x] / evMuls[0];
            const double sErr = shortEst - expected;
            shortOuterSq += sErr * sErr;
            outerCnt++;
        }
    }
    const float outerMean = static_cast<float>(outerSum / outerCnt);
    const float outerRms = static_cast<float>(std::sqrt(outerSq / outerCnt));
    const float shortOuterRms = static_cast<float>(std::sqrt(shortOuterSq / outerCnt));

    // Also verify Lab HDR tone compression maps >1.0 master cleanly into [0..1] without blowing core flat
    FloatImageRGB developed;
    LabParams lp{};
    lp.autoStf = true;
    lp.bgNeutralize = false;
    processAstroLab(fused, lp, developed);
    const float devCore = developed.g[80 * w + 80];
    const float devRing = developed.g[80 * w + 88];

    std::printf("[Test 10] HDR Astro 3-EV Fusion: core=%.3f (true=2.40) outer=%.4f rms=%.5f (vs short=%.5f) devCore=%.3f>ring=%.3f\n",
                coreVal, outerMean, outerRms, shortOuterRms, devCore, devRing);

    assert(coreVal > 1.85f && coreVal < 2.75f); // Unclipped bright core > 1.0 preserved!
    assert(outerMean > 0.018f && outerMean < 0.034f); // Faint outer nebula recovered!
    assert(outerRms < shortOuterRms * 0.45f);         // Huge SNR improvement over short bracket!
    assert(devCore > devRing && devCore <= 1.0f);     // HDR tone map preserves core gradient!
}

// =========================================================================
// Test 11: Super-Resolution Drizzle 2x Sub-Pixel Reconstruction
// =========================================================================
void testDrizzle2xSuperResolution() {
    const int w = 96, h = 96;
    const float trueStarX = 48.25f;
    const float trueStarY = 47.75f;
    const float starSigma = 0.85f; // Undersampled pinpoint star

    // 4 sub-pixel dithered frames
    const float dithers[4][2] = {{0.0f, 0.0f}, {0.5f, 0.0f}, {0.0f, 0.5f}, {0.5f, 0.5f}};

    Drizzle2xAccumulator drizzle;
    drizzle.reset(w, h, 0.72f);

    FloatImageRGB firstFrame;
    for (int f = 0; f < 4; ++f) {
        FloatImageRGB frame;
        frame.resize(w, h);
        for (size_t i = 0; i < frame.pixelCount(); ++i) {
            frame.r[i] = frame.g[i] = frame.b[i] = 0.03f;
        }
        // Star shifted by (-dx, -dy) in source frame; alignment matrix maps src -> ref by (+dx, +dy)
        drawGaussianStar(frame, trueStarX - dithers[f][0], trueStarY - dithers[f][1], 0.85f, starSigma);
        if (f == 0) firstFrame = frame;

        const float M[6] = {1.0f, 0.0f, dithers[f][0], 0.0f, 1.0f, dithers[f][1]};
        drizzle.addFrame(frame, M, nullptr);
    }

    FloatImageRGB out2x;
    drizzle.finalize(out2x);

    assert(out2x.valid());
    assert(out2x.width == w * 2 && out2x.height == h * 2);

    // Check zero NaN / zero hole artifacts and valid background normalization
    for (size_t i = 0; i < out2x.pixelCount(); ++i) {
        assert(std::isfinite(out2x.g[i]));
        assert(out2x.g[i] >= 0.02f);
    }

    // Measure star FWHM on 2x drizzled crop (centered near 2*trueStarX, 2*trueStarY)
    const int cropW = 48;
    std::vector<float> crop(cropW * cropW);
    const int cx = static_cast<int>(std::round(2.0f * trueStarX));
    const int cy = static_cast<int>(std::round(2.0f * trueStarY));
    for (int dy = 0; dy < cropW; ++dy) {
        for (int dx = 0; dx < cropW; ++dx) {
            crop[dy * cropW + dx] = out2x.g[static_cast<size_t>(cy - cropW / 2 + dy) * out2x.width + (cx - cropW / 2 + dx)];
        }
    }
    const FocusMetrics fm2x = measureFocusCrop(crop.data(), cropW, cropW);

    // Compare against naive 2x bilinear upsampling of single frame
    FloatImageRGB bilinear2x;
    bilinear2x.resize(w * 2, h * 2);
    for (int y = 0; y < h * 2; ++y) {
        for (int x = 0; x < w * 2; ++x) {
            const float sx = std::clamp((x - 0.5f) * 0.5f, 0.0f, static_cast<float>(w - 1));
            const float sy = std::clamp((y - 0.5f) * 0.5f, 0.0f, static_cast<float>(h - 1));
            const int x0 = static_cast<int>(sx), y0 = static_cast<int>(sy);
            const int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
            const float fx = sx - x0, fy = sy - y0;
            const float v = (1 - fx) * (1 - fy) * firstFrame.g[y0 * w + x0] +
                            fx * (1 - fy) * firstFrame.g[y0 * w + x1] +
                            (1 - fx) * fy * firstFrame.g[y1 * w + x0] +
                            fx * fy * firstFrame.g[y1 * w + x1];
            bilinear2x.r[y * w * 2 + x] = bilinear2x.g[y * w * 2 + x] = bilinear2x.b[y * w * 2 + x] = v;
        }
    }
    std::vector<float> cropBilin(cropW * cropW);
    for (int dy = 0; dy < cropW; ++dy) {
        for (int dx = 0; dx < cropW; ++dx) {
            cropBilin[dy * cropW + dx] = bilinear2x.g[static_cast<size_t>(cy - cropW / 2 + dy) * bilinear2x.width + (cx - cropW / 2 + dx)];
        }
    }
    const FocusMetrics fmBilin = measureFocusCrop(cropBilin.data(), cropW, cropW);

    std::printf("[Test 11] Drizzle 2x: %dx%d -> %dx%d peak=%.3f FWHM=%.2fpx (vs bilinear2x=%.2fpx)\n",
                w, h, out2x.width, out2x.height, fm2x.peakValue, fm2x.fwhm, fmBilin.fwhm);

    assert(fm2x.starFound == 1);
    assert(fm2x.peakValue > 0.72f);
    assert(fm2x.fwhm < fmBilin.fwhm); // Genuine super-resolution sharper than bilinear upsample!
}

// =========================================================================
// Test 12: Richardson-Lucy Deconvolution & Chromatic Aberration Fix
// =========================================================================
void testRichardsonLucyAndChromaticAberration() {
    // Part A: Regularized Richardson-Lucy Deconvolution (sharpens star without amplifying sky noise!)
    {
        const int w = 128, h = 128;
        FloatImageRGB img;
        img.resize(w, h);
        std::mt19937 rng(1212);
        std::normal_distribution<float> bgNoise(0.05f, 0.0025f);
        for (size_t i = 0; i < img.pixelCount(); ++i) {
            const float v = std::max(0.0f, bgNoise(rng));
            img.r[i] = img.g[i] = img.b[i] = v;
        }
        drawGaussianStar(img, 64.0f, 64.0f, 0.65f, 2.35f);

        auto measureCornerStd = [&](const FloatImageRGB& im) -> float {
            double mean = 0.0, sq = 0.0;
            int cnt = 0;
            for (int y = 4; y < 32; ++y) {
                for (int x = 4; x < 32; ++x) {
                    const float v = im.g[static_cast<size_t>(y) * w + x];
                    mean += v;
                    cnt++;
                }
            }
            mean /= cnt;
            for (int y = 4; y < 32; ++y) {
                for (int x = 4; x < 32; ++x) {
                    const double d = im.g[static_cast<size_t>(y) * w + x] - mean;
                    sq += d * d;
                }
            }
            return static_cast<float>(std::sqrt(sq / cnt));
        };

        const FocusMetrics beforeFm = measureFocusCrop(img.g.data(), w, h);
        const float bgStdBefore = measureCornerStd(img);

        applyRichardsonLucyDeconv(img, 0.90f, 1.35f);

        const FocusMetrics afterFm = measureFocusCrop(img.g.data(), w, h);
        const float bgStdAfter = measureCornerStd(img);

        std::printf("[Test 12A] R-L Deconv: FWHM %.3f -> %.3f px | skyNoiseStd %.5f -> %.5f\n",
                    beforeFm.fwhm, afterFm.fwhm, bgStdBefore, bgStdAfter);

        assert(afterFm.starFound == 1);
        assert(afterFm.fwhm < beforeFm.fwhm * 0.87f); // Noticeably tighter PSF!
        assert(bgStdAfter <= bgStdBefore * 1.12f);    // Sky noise protected by damping mask!
    }

    // Part B: Lateral Radial Chromatic Aberration + Purple Edge Fringe Removal
    {
        const int w = 220, h = 220;
        const float cx = 0.5f * (w - 1), cy = 0.5f * (h - 1);
        FloatImageRGB img;
        img.resize(w, h);
        for (size_t i = 0; i < img.pixelCount(); ++i) {
            img.r[i] = img.g[i] = img.b[i] = 0.03f;
        }

        // 8 bright peripheral stars with +0.8% outward Red lateral CA and -0.8% inward Blue lateral CA
        for (int k = 0; k < 8; ++k) {
            const float ang = k * (2.0f * kPi / 8.0f) + 0.25f;
            const float rad = 82.0f;
            const float gx = cx + rad * std::cos(ang);
            const float gy = cy + rad * std::sin(ang);
            const float rx = cx + (rad * 1.008f) * std::cos(ang);
            const float ry = cy + (rad * 1.008f) * std::sin(ang);
            const float bx = cx + (rad * 0.992f) * std::cos(ang);
            const float by = cy + (rad * 0.992f) * std::sin(ang);
            drawGaussianStar(img, rx, ry, 0.72f, 1.8f, 1.0f, 0.0f, 0.0f);
            drawGaussianStar(img, gx, gy, 0.72f, 1.8f, 0.0f, 1.0f, 0.0f);
            drawGaussianStar(img, bx, by, 0.72f, 1.8f, 0.0f, 0.0f, 1.0f);
        }

        // Also add a bright central lunar disc with an axial purple fringe around its limb
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const float r = std::hypot(x - cx, y - cy);
                const size_t i = static_cast<size_t>(y) * w + x;
                if (r < 26.0f) {
                    img.r[i] = img.g[i] = img.b[i] = 0.78f;
                } else if (r < 28.0f) {
                    // Axial purple CA halo on lunar limb
                    img.r[i] = 0.42f;
                    img.g[i] = 0.14f;
                    img.b[i] = 0.44f;
                }
            }
        }

        auto measureRadialColorErr = [&](const FloatImageRGB& im) -> float {
            double err = 0.0;
            int cnt = 0;
            for (int y = 10; y < h - 10; ++y) {
                for (int x = 10; x < w - 10; ++x) {
                    const float r = std::hypot(x - cx, y - cy);
                    if (r < 55.0f) continue;
                    const size_t i = static_cast<size_t>(y) * w + x;
                    err += std::abs(im.r[i] - im.g[i]) + std::abs(im.b[i] - im.g[i]);
                    cnt++;
                }
            }
            return static_cast<float>(err / std::max(1, cnt));
        };

        auto measureLimbPurple = [&](const FloatImageRGB& im) -> float {
            double sum = 0.0;
            int cnt = 0;
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    const float r = std::hypot(x - cx, y - cy);
                    if (r >= 26.0f && r < 28.0f) {
                        const size_t i = static_cast<size_t>(y) * w + x;
                        sum += std::max(0.0f, std::min(im.r[i], im.b[i]) - im.g[i]);
                        cnt++;
                    }
                }
            }
            return static_cast<float>(sum / std::max(1, cnt));
        };

        const float colorErrBefore = measureRadialColorErr(img);
        const float purpleBefore = measureLimbPurple(img);

        applyChromaticAberrationFix(img, 1.0f);

        const float colorErrAfter = measureRadialColorErr(img);
        const float purpleAfter = measureLimbPurple(img);

        std::printf("[Test 12B] Chromatic Aberration Fix: radialErr %.5f -> %.5f | purpleLimb %.4f -> %.4f\n",
                    colorErrBefore, colorErrAfter, purpleBefore, purpleAfter);

        assert(colorErrAfter < colorErrBefore * 0.35f); // Radial R/B shift cleanly corrected!
        assert(purpleAfter < purpleBefore * 0.35f);     // Purple lunar limb fringe suppressed!
    }
}

// =========================================================================
// Test 13: Auto Best Focus Star Finder (Rejects Hot Border & Saturated Pixels)
// =========================================================================
void testAutoBestFocusStarFinder() {
    const int w = 640, h = 480;
    std::vector<uint8_t> yPlane(static_cast<size_t>(w) * h, 14);

    // Inject deceptive blazing hot pixels on borders (outside central 70%)
    yPlane[12 * w + 15] = 255;
    yPlane[12 * w + 16] = 255;
    yPlane[13 * w + 15] = 255;
    yPlane[465 * w + 622] = 255;
    yPlane[465 * w + 623] = 255;
    // Inject isolated single-pixel hot pixel right near center
    yPlane[240 * w + 320] = 255;

    // Inject a clipped/saturated star at (210, 180) (flat 255 core)
    for (int dy = -5; dy <= 5; ++dy) {
        for (int dx = -5; dx <= 5; ++dx) {
            const float g = 340.0f * std::exp(-(dx * dx + dy * dy) / (2.0f * 2.1f * 2.1f));
            yPlane[static_cast<size_t>(180 + dy) * w + (210 + dx)] =
                static_cast<uint8_t>(std::clamp(14.0f + g, 0.0f, 255.0f));
        }
    }

    // Inject the ideal bright UNSATURATED focus star at (388, 266) (peak ~212)
    const float targetX = 388.4f, targetY = 266.2f;
    for (int dy = -8; dy <= 8; ++dy) {
        for (int dx = -8; dx <= 8; ++dx) {
            const float ddx = (388 + dx) - targetX;
            const float ddy = (266 + dy) - targetY;
            const float g = 198.0f * std::exp(-(ddx * ddx + ddy * ddy) / (2.0f * 1.9f * 1.9f));
            yPlane[static_cast<size_t>(266 + dy) * w + (388 + dx)] =
                static_cast<uint8_t>(std::clamp(14.0f + g, 0.0f, 255.0f));
        }
    }

    const BestFocusStarResult r = findBestFocusStar(yPlane.data(), w, 1, w, h);
    const float foundX = r.normX * w;
    const float foundY = r.normY * h;

    std::printf("[Test 13A] Auto Focus Star Finder: found=%d isStar=%d pos=(%.1f, %.1f) target=(%.1f, %.1f) snr=%.1f\n",
                r.found ? 1 : 0, r.isStar ? 1 : 0, foundX, foundY, targetX, targetY, r.snr);

    assert(r.found);
    assert(r.isStar);
    assert(std::abs(foundX - targetX) < 1.5f);
    assert(std::abs(foundY - targetY) < 1.5f);

    // Part B: Lunar high-contrast crater fallback when zero stars are present
    std::vector<uint8_t> moonY(static_cast<size_t>(w) * h, 10);
    for (int y = 160; y < 320; ++y) {
        for (int x = 220; x < 420; ++x) {
            const float rDist = std::hypot(x - 320.0f, y - 240.0f);
            if (rDist < 75.0f) {
                float v = 175.0f;
                if (std::abs(rDist - 35.0f) < 3.0f) v = 240.0f;
                else if (rDist < 32.0f) v = 60.0f;
                moonY[static_cast<size_t>(y) * w + x] = static_cast<uint8_t>(v);
            }
        }
    }
    const BestFocusStarResult rMoon = findBestFocusStar(moonY.data(), w, 1, w, h);
    std::printf("[Test 13B] Lunar Fallback Focus Finder: found=%d isStar=%d pos=(%.2f, %.2f)\n",
                rMoon.found ? 1 : 0, rMoon.isStar ? 1 : 0, rMoon.normX, rMoon.normY);
    assert(rMoon.found);
    assert(!rMoon.isStar);
    assert(rMoon.normX > 0.25f && rMoon.normX < 0.75f);
    assert(rMoon.normY > 0.25f && rMoon.normY < 0.75f);
}

} // namespace

int main() {
    std::printf("=== AstroPro 14U C++20 Engine Verification Suite (v2.0.0) ===\n");
    testFocusFwhmHfd();
    testTriangleAlignment();
    testKappaSigmaSatelliteRejection();
    testRawCalibrationAndDemosaic();
    testQuadraticGradientExtraction();
    testWaveletSharpening();
    testTiff16AndFits32Export();
    testFocusPeakingPoints();
    testAutoHorizonSkyGroundSeparation();
    testHdrAstroFusion();
    testDrizzle2xSuperResolution();
    testRichardsonLucyAndChromaticAberration();
    testAutoBestFocusStarFinder();
    std::printf("=== ALL 13 C++20 ASTRO ENGINE TESTS PASSED ===\n");
    return 0;
}

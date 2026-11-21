#include "astro_types.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

namespace astro {

namespace {

void writeU16LE(std::ostream& os, uint16_t v) {
    uint8_t b[2] = {
        static_cast<uint8_t>(v & 0xFFu),
        static_cast<uint8_t>((v >> 8) & 0xFFu)
    };
    os.write(reinterpret_cast<const char*>(b), 2);
}

void writeU32LE(std::ostream& os, uint32_t v) {
    uint8_t b[4] = {
        static_cast<uint8_t>(v & 0xFFu),
        static_cast<uint8_t>((v >> 8) & 0xFFu),
        static_cast<uint8_t>((v >> 16) & 0xFFu),
        static_cast<uint8_t>((v >> 24) & 0xFFu)
    };
    os.write(reinterpret_cast<const char*>(b), 4);
}

void writeIfdEntry(std::ostream& os, uint16_t tag, uint16_t type, uint32_t count, uint32_t valueOrOffset) {
    writeU16LE(os, tag);
    writeU16LE(os, type);
    writeU32LE(os, count);
    writeU32LE(os, valueOrOffset);
}

inline uint32_t toBigEndian32(uint32_t v) {
    return ((v & 0x000000FFu) << 24) |
           ((v & 0x0000FF00u) << 8)  |
           ((v & 0x00FF0000u) >> 8)  |
           ((v & 0xFF000000u) >> 24);
}

inline uint16_t toBigEndian16(uint16_t v) {
    return static_cast<uint16_t>((v << 8) | (v >> 8));
}

void appendFitsCard(std::string& hdr, const std::string& rawCard) {
    std::string card = rawCard;
    if (card.size() > 80) card.resize(80);
    if (card.size() < 80) card.append(80 - card.size(), ' ');
    hdr.append(card);
}

std::string fitsIntCard(const char* key, int64_t val, const char* comment) {
    char buf[96];
    std::snprintf(buf, sizeof(buf), "%-8.8s= %20lld / %s",
                  key, static_cast<long long>(val), comment);
    return std::string(buf);
}

std::string fitsFloatCard(const char* key, double val, const char* comment) {
    char buf[96];
    std::snprintf(buf, sizeof(buf), "%-8.8s= %20.6f / %s", key, val, comment);
    return std::string(buf);
}

std::string fitsStrCard(const char* key, const std::string& val, const char* comment) {
    std::string clean = val;
    for (char& ch : clean) {
        if (ch == '\'') ch = ' ';
        if (ch < 32 || ch > 126) ch = ' ';
    }
    if (clean.size() > 36) clean.resize(36);
    char buf[128];
    std::snprintf(buf, sizeof(buf), "%-8.8s= '%-18s' / %s", key, clean.c_str(), comment);
    return std::string(buf);
}

void writeStringBin(std::ostream& os, const std::string& s) {
    uint32_t len = static_cast<uint32_t>(s.size());
    os.write(reinterpret_cast<const char*>(&len), sizeof(len));
    if (len > 0) os.write(s.data(), len);
}

bool readStringBin(std::istream& is, std::string& s) {
    uint32_t len = 0;
    if (!is.read(reinterpret_cast<char*>(&len), sizeof(len))) return false;
    if (len > 4096) return false;
    s.resize(len);
    if (len > 0 && !is.read(s.data(), len)) return false;
    return true;
}

} // namespace

// =========================================================================
// 16-bit Uncompressed RGB TIFF 6.0 Writer
// =========================================================================

bool writeTiff16RGB(const std::string& path, const FloatImageRGB& img, const AstroMetadata& meta) {
    if (!img.valid()) return false;
    std::ofstream os(path, std::ios::binary | std::ios::trunc);
    if (!os) return false;

    const uint32_t w = static_cast<uint32_t>(img.width);
    const uint32_t h = static_cast<uint32_t>(img.height);
    const uint64_t pixelBytes64 = static_cast<uint64_t>(w) * h * 6ULL;
    if (pixelBytes64 > 0xEFFFFFFFULL) return false;
    const uint32_t stripBytes = static_cast<uint32_t>(pixelBytes64);

    const std::string makeStr = "Xiaomi";
    const std::string modelStr = meta.cameraModel.empty() ? "Xiaomi 14 Ultra" : meta.cameraModel;
    const std::string swStr = "AstroPro 14U";

    // Layout:
    // [0..7]: Header ("II", 42, ifdOffset=8)
    // [8..]:  IFD (2 bytes count + 13*12 bytes entries + 4 bytes nextIfd=0) = 162 bytes => offset 170
    // [170]:  BitsPerSample (3 * uint16 = 6 bytes) => offset 176
    // [176]:  Make string (null-terminated)
    // [...]:  Model string (null-terminated)
    // [...]:  Software string (null-terminated)
    // [aligned to 4]: Pixel strip (width * height * 3 * uint16_t)
    constexpr uint16_t kNumEntries = 13;
    const uint32_t ifdOffset = 8;
    const uint32_t bpsOffset = ifdOffset + 2 + kNumEntries * 12 + 4; // 170
    const uint32_t makeOffset = bpsOffset + 6;                       // 176
    const uint32_t makeLen = static_cast<uint32_t>(makeStr.size() + 1);
    const uint32_t modelOffset = makeOffset + makeLen;
    const uint32_t modelLen = static_cast<uint32_t>(modelStr.size() + 1);
    const uint32_t swOffset = modelOffset + modelLen;
    const uint32_t swLen = static_cast<uint32_t>(swStr.size() + 1);
    const uint32_t rawDataOffset = (swOffset + swLen + 3u) & ~3u;

    // Header
    os.write("II", 2);
    writeU16LE(os, 42);
    writeU32LE(os, ifdOffset);

    // IFD entries in strict ascending tag ID order (TIFF 6.0 spec)
    writeU16LE(os, kNumEntries);
    writeIfdEntry(os, 256, 4, 1, w);                   // ImageWidth (LONG)
    writeIfdEntry(os, 257, 4, 1, h);                   // ImageLength (LONG)
    writeIfdEntry(os, 258, 3, 3, bpsOffset);           // BitsPerSample (SHORT x3 -> [16,16,16])
    writeIfdEntry(os, 259, 3, 1, 1);                   // Compression = 1 (Uncompressed)
    writeIfdEntry(os, 262, 3, 1, 2);                   // PhotometricInterpretation = 2 (RGB)
    writeIfdEntry(os, 271, 2, makeLen, makeOffset);    // Make (ASCII)
    writeIfdEntry(os, 272, 2, modelLen, modelOffset);  // Model (ASCII)
    writeIfdEntry(os, 273, 4, 1, rawDataOffset);       // StripOffsets (LONG)
    writeIfdEntry(os, 277, 3, 1, 3);                   // SamplesPerPixel = 3
    writeIfdEntry(os, 278, 4, 1, h);                   // RowsPerStrip = height
    writeIfdEntry(os, 279, 4, 1, stripBytes);          // StripByteCounts (LONG)
    writeIfdEntry(os, 284, 3, 1, 1);                   // PlanarConfiguration = 1 (Chunky RGB)
    writeIfdEntry(os, 305, 2, swLen, swOffset);        // Software (ASCII)
    writeU32LE(os, 0);                                 // Next IFD = 0

    // Extra tag values
    writeU16LE(os, 16);
    writeU16LE(os, 16);
    writeU16LE(os, 16);
    os.write(makeStr.c_str(), makeLen);
    os.write(modelStr.c_str(), modelLen);
    os.write(swStr.c_str(), swLen);

    const uint32_t curPos = swOffset + swLen;
    for (uint32_t p = curPos; p < rawDataOffset; ++p) os.put('\0');

    // Pixel data row-by-row (chunky 16-bit LE RGB)
    std::vector<uint16_t> rowBuf(static_cast<size_t>(w) * 3);
    for (uint32_t y = 0; y < h; ++y) {
        const size_t rowOff = static_cast<size_t>(y) * w;
        for (uint32_t x = 0; x < w; ++x) {
            rowBuf[x * 3 + 0] = static_cast<uint16_t>(std::clamp(img.r[rowOff + x] * 65535.0f + 0.5f, 0.0f, 65535.0f));
            rowBuf[x * 3 + 1] = static_cast<uint16_t>(std::clamp(img.g[rowOff + x] * 65535.0f + 0.5f, 0.0f, 65535.0f));
            rowBuf[x * 3 + 2] = static_cast<uint16_t>(std::clamp(img.b[rowOff + x] * 65535.0f + 0.5f, 0.0f, 65535.0f));
        }
        os.write(reinterpret_cast<const char*>(rowBuf.data()), static_cast<std::streamsize>(rowBuf.size() * 2));
    }

    return os.good();
}

// =========================================================================
// Astronomical FITS 4.0 Writer (32-bit IEEE Float or 16-bit Int, 2880-byte blocks)
// =========================================================================

bool writeFitsImage(const std::string& path, const FloatImageRGB& img, const AstroMetadata& meta, bool asFloat32) {
    if (!img.valid()) return false;
    std::ofstream os(path, std::ios::binary | std::ios::trunc);
    if (!os) return false;

    std::string hdr;
    hdr.reserve(2880);
    appendFitsCard(hdr, "SIMPLE  =                    T / Conforms to FITS 4.0 standard");
    appendFitsCard(hdr, fitsIntCard("BITPIX", asFloat32 ? -32 : 16,
                                    asFloat32 ? "IEEE 754 32-bit float" : "16-bit signed integer"));
    appendFitsCard(hdr, fitsIntCard("NAXIS", 3, "3D RGB image cube"));
    appendFitsCard(hdr, fitsIntCard("NAXIS1", img.width, "Image width in pixels"));
    appendFitsCard(hdr, fitsIntCard("NAXIS2", img.height, "Image height in pixels"));
    appendFitsCard(hdr, fitsIntCard("NAXIS3", 3, "Color planes (R, G, B)"));
    if (!asFloat32) {
        appendFitsCard(hdr, fitsFloatCard("BZERO", 32768.0, "Unsigned 16-bit offset"));
        appendFitsCard(hdr, fitsFloatCard("BSCALE", 1.0, "Linear scale factor"));
    }
    appendFitsCard(hdr, fitsFloatCard("EXPTIME", meta.exposureSec, "Single frame exposure [s]"));
    appendFitsCard(hdr, fitsFloatCard("TOTALEXP", static_cast<double>(meta.exposureSec) * std::max(1, meta.stackCount), "Total integration time [s]"));
    appendFitsCard(hdr, fitsFloatCard("ISOSPEED", meta.iso, "ISO sensitivity"));
    appendFitsCard(hdr, fitsFloatCard("FOCALLEN", meta.focalLengthMm, "Focal length [mm]"));
    appendFitsCard(hdr, fitsFloatCard("APERTURE", meta.apertureF, "F-number"));
    appendFitsCard(hdr, fitsIntCard("STACKCNT", std::max(1, meta.stackCount), "Stacked frames count"));
    appendFitsCard(hdr, fitsStrCard("INSTRUME", meta.cameraModel.empty() ? "Xiaomi 14 Ultra" : meta.cameraModel, "Camera instrument"));
    appendFitsCard(hdr, fitsStrCard("OPTICS", meta.lensName, "Optical lens"));
    appendFitsCard(hdr, fitsStrCard("CREATOR", "AstroPro 14U", "Capture & stacking engine"));
    appendFitsCard(hdr, fitsStrCard("DATE-OBS", meta.dateObs, "UTC observation timestamp"));
    appendFitsCard(hdr, fitsStrCard("ROWORDER", "TOP-DOWN", "Row storage orientation"));
    appendFitsCard(hdr, "END");

    // Pad header with ASCII spaces to exact multiple of 2880 bytes
    const size_t hdrRem = hdr.size() % 2880;
    if (hdrRem != 0) {
        hdr.append(2880 - hdrRem, ' ');
    }
    os.write(hdr.data(), static_cast<std::streamsize>(hdr.size()));

    const size_t planeN = img.pixelCount();
    const std::vector<float>* planes[3] = {&img.r, &img.g, &img.b};
    size_t dataBytesWritten = 0;

    if (asFloat32) {
        std::vector<uint32_t> rowBuf(static_cast<size_t>(img.width));
        for (int c = 0; c < 3; ++c) {
            const auto& pl = *planes[c];
            for (int y = 0; y < img.height; ++y) {
                const size_t off = static_cast<size_t>(y) * img.width;
                for (int x = 0; x < img.width; ++x) {
                    uint32_t bits = 0;
                    const float fv = pl[off + x];
                    std::memcpy(&bits, &fv, sizeof(float));
                    rowBuf[x] = toBigEndian32(bits);
                }
                os.write(reinterpret_cast<const char*>(rowBuf.data()), static_cast<std::streamsize>(rowBuf.size() * 4));
            }
        }
        dataBytesWritten = planeN * 3 * 4;
    } else {
        std::vector<uint16_t> rowBuf(static_cast<size_t>(img.width));
        for (int c = 0; c < 3; ++c) {
            const auto& pl = *planes[c];
            for (int y = 0; y < img.height; ++y) {
                const size_t off = static_cast<size_t>(y) * img.width;
                for (int x = 0; x < img.width; ++x) {
                    const int32_t u16 = static_cast<int32_t>(std::clamp(pl[off + x] * 65535.0f + 0.5f, 0.0f, 65535.0f));
                    const int16_t s16 = static_cast<int16_t>(u16 - 32768);
                    uint16_t rawBits = 0;
                    std::memcpy(&rawBits, &s16, sizeof(int16_t));
                    rowBuf[x] = toBigEndian16(rawBits);
                }
                os.write(reinterpret_cast<const char*>(rowBuf.data()), static_cast<std::streamsize>(rowBuf.size() * 2));
            }
        }
        dataBytesWritten = planeN * 3 * 2;
    }

    // Pad payload with zero bytes to exact multiple of 2880 bytes
    const size_t dataRem = dataBytesWritten % 2880;
    if (dataRem != 0) {
        std::vector<char> zeros(2880 - dataRem, '\0');
        os.write(zeros.data(), static_cast<std::streamsize>(zeros.size()));
    }

    return os.good();
}

// =========================================================================
// Native Lossless .astro32 Float32 Session Save / Load
// =========================================================================

bool saveAstro32(const std::string& path, const FloatImageRGB& img, const AstroMetadata& meta) {
    if (!img.valid()) return false;
    std::ofstream os(path, std::ios::binary | std::ios::trunc);
    if (!os) return false;

    const char magic[4] = {'A', 'S', 'T', '3'};
    const uint32_t version = 1;
    os.write(magic, 4);
    os.write(reinterpret_cast<const char*>(&version), sizeof(version));
    os.write(reinterpret_cast<const char*>(&img.width), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&img.height), sizeof(int32_t));

    writeStringBin(os, meta.cameraModel);
    writeStringBin(os, meta.lensName);
    writeStringBin(os, meta.dateObs);
    writeStringBin(os, meta.modeName);
    os.write(reinterpret_cast<const char*>(&meta.focalLengthMm), sizeof(float));
    os.write(reinterpret_cast<const char*>(&meta.apertureF), sizeof(float));
    os.write(reinterpret_cast<const char*>(&meta.exposureSec), sizeof(float));
    os.write(reinterpret_cast<const char*>(&meta.iso), sizeof(float));
    os.write(reinterpret_cast<const char*>(&meta.stackCount), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&meta.rejectedCount), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&meta.avgResidualPx), sizeof(float));

    const std::streamsize planeBytes = static_cast<std::streamsize>(img.pixelCount() * sizeof(float));
    os.write(reinterpret_cast<const char*>(img.r.data()), planeBytes);
    os.write(reinterpret_cast<const char*>(img.g.data()), planeBytes);
    os.write(reinterpret_cast<const char*>(img.b.data()), planeBytes);
    return os.good();
}

bool loadAstro32(const std::string& path, FloatImageRGB& img, AstroMetadata& meta) {
    std::ifstream is(path, std::ios::binary);
    if (!is) return false;

    char magic[4] = {};
    uint32_t version = 0;
    int32_t w = 0, h = 0;
    if (!is.read(magic, 4) || std::memcmp(magic, "AST3", 4) != 0) return false;
    if (!is.read(reinterpret_cast<char*>(&version), sizeof(version)) || version != 1) return false;
    if (!is.read(reinterpret_cast<char*>(&w), sizeof(w)) ||
        !is.read(reinterpret_cast<char*>(&h), sizeof(h))) return false;
    if (w <= 0 || h <= 0 || w > 12000 || h > 12000) return false;

    if (!readStringBin(is, meta.cameraModel) ||
        !readStringBin(is, meta.lensName) ||
        !readStringBin(is, meta.dateObs) ||
        !readStringBin(is, meta.modeName)) return false;

    if (!is.read(reinterpret_cast<char*>(&meta.focalLengthMm), sizeof(float)) ||
        !is.read(reinterpret_cast<char*>(&meta.apertureF), sizeof(float)) ||
        !is.read(reinterpret_cast<char*>(&meta.exposureSec), sizeof(float)) ||
        !is.read(reinterpret_cast<char*>(&meta.iso), sizeof(float)) ||
        !is.read(reinterpret_cast<char*>(&meta.stackCount), sizeof(int32_t)) ||
        !is.read(reinterpret_cast<char*>(&meta.rejectedCount), sizeof(int32_t)) ||
        !is.read(reinterpret_cast<char*>(&meta.avgResidualPx), sizeof(float))) return false;

    img.resize(w, h);
    const std::streamsize planeBytes = static_cast<std::streamsize>(img.pixelCount() * sizeof(float));
    if (!is.read(reinterpret_cast<char*>(img.r.data()), planeBytes) ||
        !is.read(reinterpret_cast<char*>(img.g.data()), planeBytes) ||
        !is.read(reinterpret_cast<char*>(img.b.data()), planeBytes)) {
        img.clear();
        return false;
    }
    return true;
}

// =========================================================================
// Calibration Master .cal32 Save / Load
// =========================================================================

bool saveCalibrationMaster(const std::string& path, const CalibrationMaster& master) {
    if (!master.valid()) return false;
    std::ofstream os(path, std::ios::binary | std::ios::trunc);
    if (!os) return false;

    const char magic[4] = {'A', 'C', 'A', 'L'};
    const uint32_t version = 1;
    const int32_t cfaInt = static_cast<int32_t>(master.cfa);
    const int32_t typeInt = static_cast<int32_t>(master.type);

    os.write(magic, 4);
    os.write(reinterpret_cast<const char*>(&version), sizeof(version));
    os.write(reinterpret_cast<const char*>(&master.width), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&master.height), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&master.channels), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&cfaInt), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&typeInt), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(&master.iso), sizeof(float));
    os.write(reinterpret_cast<const char*>(&master.exposureSec), sizeof(float));
    os.write(reinterpret_cast<const char*>(&master.focalMm), sizeof(float));
    os.write(reinterpret_cast<const char*>(&master.frameCount), sizeof(int32_t));
    os.write(reinterpret_cast<const char*>(master.data.data()),
             static_cast<std::streamsize>(master.data.size() * sizeof(float)));
    return os.good();
}

bool loadCalibrationMaster(const std::string& path, CalibrationMaster& master) {
    std::ifstream is(path, std::ios::binary);
    if (!is) return false;

    char magic[4] = {};
    uint32_t version = 0;
    int32_t cfaInt = 0, typeInt = 0;
    if (!is.read(magic, 4) || std::memcmp(magic, "ACAL", 4) != 0) return false;
    if (!is.read(reinterpret_cast<char*>(&version), sizeof(version)) || version != 1) return false;
    if (!is.read(reinterpret_cast<char*>(&master.width), sizeof(int32_t)) ||
        !is.read(reinterpret_cast<char*>(&master.height), sizeof(int32_t)) ||
        !is.read(reinterpret_cast<char*>(&master.channels), sizeof(int32_t)) ||
        !is.read(reinterpret_cast<char*>(&cfaInt), sizeof(int32_t)) ||
        !is.read(reinterpret_cast<char*>(&typeInt), sizeof(int32_t)) ||
        !is.read(reinterpret_cast<char*>(&master.iso), sizeof(float)) ||
        !is.read(reinterpret_cast<char*>(&master.exposureSec), sizeof(float)) ||
        !is.read(reinterpret_cast<char*>(&master.focalMm), sizeof(float)) ||
        !is.read(reinterpret_cast<char*>(&master.frameCount), sizeof(int32_t))) {
        return false;
    }

    if (master.width <= 0 || master.height <= 0 || (master.channels != 1 && master.channels != 3)) {
        return false;
    }
    master.cfa = static_cast<CfaPattern>(std::clamp(cfaInt, 0, 3));
    master.type = static_cast<CalibrationType>(std::clamp(typeInt, 0, 2));

    const size_t count = static_cast<size_t>(master.width) * master.height * master.channels;
    master.data.resize(count);
    if (!is.read(reinterpret_cast<char*>(master.data.data()),
                 static_cast<std::streamsize>(count * sizeof(float)))) {
        master.data.clear();
        return false;
    }
    return true;
}

} // namespace astro

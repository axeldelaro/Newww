using System.Text;
using CleanForge.Core.Models;
using CleanForge.Core.Safety;
using CleanForge.Core.Scanners;
using Xunit;

namespace CleanForge.Tests;

public class ScannerEnginesTests : IDisposable
{
    private readonly string _sandbox;

    public ScannerEnginesTests()
    {
        _sandbox = Path.Combine(Path.GetTempPath(), $"CleanForgeTests_{Guid.NewGuid():N}");
        Directory.CreateDirectory(_sandbox);
    }

    public void Dispose()
    {
        try { Directory.Delete(_sandbox, recursive: true); } catch { }
    }

    [Fact]
    public void FullDiskExplorer_ScansTreeSizesExtensionsAndGlobalSearch()
    {
        string subA = Path.Combine(_sandbox, "Cours");
        string subB = Path.Combine(_sandbox, "Videos");
        Directory.CreateDirectory(subA);
        Directory.CreateDirectory(subB);

        File.WriteAllBytes(Path.Combine(subA, "memoire_final.pdf"), new byte[300 * 1024]);
        File.WriteAllBytes(Path.Combine(subB, "montage_vacances.mp4"), new byte[1200 * 1024]);
        File.WriteAllBytes(Path.Combine(subB, "archive_projet.zip"), new byte[500 * 1024]);

        var scanner = new FullDiskExplorerScanner();
        var res = scanner.ScanAllDrives([_sandbox]);

        Assert.Equal(3, res.TotalScannedFiles);
        Assert.Equal((300 + 1200 + 500) * 1024L, res.TotalScannedBytes);
        Assert.Single(res.RootNodes);
        Assert.Equal("montage_vacances.mp4", res.LargestFiles[0].Name);

        var pdfHits = FullDiskExplorerScanner.SearchGlobal(res, "memoire", ".pdf");
        Assert.Single(pdfHits);
        Assert.Equal("memoire_final.pdf", pdfHits[0].Name);

        var bigHits = FullDiskExplorerScanner.SearchGlobal(res, "", null, minSizeBytes: 1000 * 1024, includeFolders: false);
        Assert.Single(bigHits);
        Assert.Equal("montage_vacances.mp4", bigHits[0].Name);
    }

    [Fact]
    public void DeepCacheCleaner_MeasuresSafeCachesAndNeverCountsProtectedBrowserCredentials()
    {
        string fakeBrowser = Path.Combine(_sandbox, "FakeChrome");
        Directory.CreateDirectory(fakeBrowser);
        File.WriteAllBytes(Path.Combine(fakeBrowser, "cache_001.bin"), new byte[100 * 1024]);
        File.WriteAllBytes(Path.Combine(fakeBrowser, "Login Data"), new byte[200 * 1024]); // Must be IGNORED by SafetyGuard!
        File.WriteAllBytes(Path.Combine(fakeBrowser, "Bookmarks"), new byte[50 * 1024]);   // Must be IGNORED!

        var scanner = new DeepCacheCleanerScanner();
        var candidates = scanner.ScanSpecs([
            new CacheTargetSpec("Test Browser Cache", "Navigateurs", fakeBrowser, "Test", CleanupCategory.BrowserCacheOnly, SafetyTier.SafeAuto)
        ]);

        Assert.Single(candidates);
        Assert.Equal(1, candidates[0].ItemCount);
        Assert.Equal(100 * 1024L, candidates[0].SizeBytes);

        // Execute real cleanup of directory contents -> ONLY cache_001.bin deleted; Login Data & Bookmarks MUST survive!
        var deleter = new RecycleBinDeleter(new AuditLogger(Path.Combine(_sandbox, "_logs")));
        var report = deleter.ExecuteCleanup(candidates, dryRun: false, useRecycleBin: false);

        Assert.Equal(1, report.SucceededCount);
        Assert.True(report.BlockedBySafetyCount >= 2);
        Assert.False(File.Exists(Path.Combine(fakeBrowser, "cache_001.bin")));
        Assert.True(File.Exists(Path.Combine(fakeBrowser, "Login Data")));
        Assert.True(File.Exists(Path.Combine(fakeBrowser, "Bookmarks")));
    }

    [Fact]
    public void DuplicateFileScanner_DetectsExactSha256MatchesAvoidsFalsePositivesAndKeepsOriginal()
    {
        string dirKeep = Path.Combine(_sandbox, "Documents", "Photos");
        string dirDl = Path.Combine(_sandbox, "Downloads");
        Directory.CreateDirectory(dirKeep);
        Directory.CreateDirectory(dirDl);

        byte[] dataA = new byte[128 * 1024];
        byte[] dataB = new byte[128 * 1024]; // Same size, same head/tail, different middle!
        new Random(100).NextBytes(dataA);
        Array.Copy(dataA, dataB, dataA.Length);
        dataB[64 * 1024] ^= 0xFF;

        string orig = Path.Combine(dirKeep, "vacances.jpg");
        string copy1 = Path.Combine(dirDl, "vacances (1).jpg");
        string copy2 = Path.Combine(dirDl, "vacances - copie.jpg");
        string almostSame = Path.Combine(dirDl, "vacances_edited.jpg");

        File.WriteAllBytes(orig, dataA);
        File.WriteAllBytes(copy1, dataA);
        File.WriteAllBytes(copy2, dataA);
        File.WriteAllBytes(almostSame, dataB);

        var groups = new DuplicateFileScanner().ScanForDuplicates([_sandbox], minSizeBytes: 1024);

        Assert.Single(groups);
        Assert.Equal(3, groups[0].Entries.Count); // almostSame NOT grouped!
        Assert.Equal(orig, groups[0].Entries.First(e => e.IsOriginalKept).FullPath);
        Assert.Equal(2, groups[0].Entries.Count(e => e.IsSelectedForDelete));
        Assert.Equal(256 * 1024L, groups[0].ReclaimableBytes);

        // Verify safety invariant: impossible to mark all entries for delete
        foreach (var e in groups[0].Entries) e.IsSelectedForDelete = true;
        DuplicateFileScanner.EnforceAtLeastOneKept(groups[0]);
        Assert.Contains(groups[0].Entries, e => !e.IsSelectedForDelete && e.IsOriginalKept);
    }

    [Fact]
    public void StudyGraduationScanner_FindsNodeModulesCsprojObjInstallersAndOldDocsWithoutAutoSelectingDocs()
    {
        string projJs = Path.Combine(_sandbox, "Projets", "SiteFac", "node_modules");
        string projCs = Path.Combine(_sandbox, "Projets", "AppCSharp");
        string projCsObj = Path.Combine(projCs, "obj");
        string downloads = Path.Combine(_sandbox, "Downloads");
        string cours = Path.Combine(_sandbox, "Cours2023");
        Directory.CreateDirectory(projJs);
        Directory.CreateDirectory(projCsObj);
        Directory.CreateDirectory(downloads);
        Directory.CreateDirectory(cours);

        File.WriteAllBytes(Path.Combine(projJs, "bundle.js"), new byte[700 * 1024]);
        File.WriteAllText(Path.Combine(projCs, "App.csproj"), "<Project />");
        File.WriteAllBytes(Path.Combine(projCsObj, "App.pdb"), new byte[600 * 1024]);
        File.WriteAllBytes(Path.Combine(downloads, "Matlab_Setup.exe"), new byte[2 * 1024 * 1024]);

        string oldPdf = Path.Combine(cours, "Cours_Algebre.pdf");
        File.WriteAllBytes(oldPdf, new byte[400 * 1024]);
        DateTime oldDate = DateTime.UtcNow.AddDays(-240);
        File.SetLastWriteTimeUtc(oldPdf, oldDate);
        File.SetLastAccessTimeUtc(oldPdf, oldDate);

        var items = new StudyGraduationScanner().ScanStudyClutter([_sandbox], oldDocDaysThreshold: 180, minOldDocBytes: 100 * 1024);

        Assert.Contains(items, i => i.Kind == StudyClutterKind.RegenerableDevBuild && i.FullPath == projJs);
        Assert.Contains(items, i => i.Kind == StudyClutterKind.RegenerableDevBuild && i.FullPath == projCsObj);
        Assert.Contains(items, i => i.Kind == StudyClutterKind.ForgottenInstallerOrArchive && i.Name == "Matlab_Setup.exe");

        var doc = Assert.Single(items, i => i.Kind == StudyClutterKind.OldStudyDocument);
        Assert.Equal("Cours_Algebre.pdf", doc.Name);
        Assert.False(doc.IsSelected); // NEVER auto-selected!
    }

    [Fact]
    public void ShortcutAndEmptyFolderScanner_ParsesBinaryLnkDetectsBrokenShortcutsAndEmptyFolders()
    {
        string shortcutsDir = Path.Combine(_sandbox, "DesktopShortcuts");
        string emptyTree = Path.Combine(_sandbox, "OldEmptyProject", "NestedEmpty");
        string nonEmpty = Path.Combine(_sandbox, "ActiveFolder");
        Directory.CreateDirectory(shortcutsDir);
        Directory.CreateDirectory(emptyTree);
        Directory.CreateDirectory(nonEmpty);

        string existingTarget = Path.Combine(nonEmpty, "app.exe");
        File.WriteAllText(existingTarget, "binary");
        string missingTarget = Path.Combine(_sandbox, "DeletedStudyApp", "ghost.exe");

        string validLnk = Path.Combine(shortcutsDir, "ValidApp.lnk");
        string brokenLnk = Path.Combine(shortcutsDir, "BrokenStudyApp.lnk");
        WriteMinimalMsShllink(validLnk, existingTarget);
        WriteMinimalMsShllink(brokenLnk, missingTarget);

        Assert.Equal(existingTarget, ShortcutAndEmptyFolderScanner.TryExtractLnkTargetPath(validLnk));
        Assert.Equal(missingTarget, ShortcutAndEmptyFolderScanner.TryExtractLnkTargetPath(brokenLnk));

        var scanner = new ShortcutAndEmptyFolderScanner();
        var broken = scanner.ScanBrokenShortcuts([("TestBureau", shortcutsDir)]);
        Assert.Single(broken);
        Assert.Equal("BrokenStudyApp.lnk", broken[0].ShortcutName);
        Assert.Equal(missingTarget, broken[0].MissingTarget);

        var empties = scanner.ScanEmptyFolders([_sandbox]);
        Assert.Single(empties);
        Assert.Equal(Path.Combine(_sandbox, "OldEmptyProject"), empties[0].FullPath);
    }

    [Fact]
    public void GhostSoftwareScanner_DetectsDormantOrphansWhileProtectingActiveAndSystemVendors()
    {
        string fakeAppData = Path.Combine(_sandbox, "FakeLocalAppData");
        string orphanDir = Path.Combine(fakeAppData, "OldUninstalledCadTool");
        string activeAppDir = Path.Combine(fakeAppData, "InstalledEditor");
        string protectedDir = Path.Combine(fakeAppData, "NVIDIA Corporation");
        string recentPortable = Path.Combine(fakeAppData, "RecentPortableApp");

        Directory.CreateDirectory(orphanDir);
        Directory.CreateDirectory(activeAppDir);
        Directory.CreateDirectory(protectedDir);
        Directory.CreateDirectory(recentPortable);

        File.WriteAllBytes(Path.Combine(orphanDir, "data.bin"), new byte[120 * 1024]);
        File.WriteAllBytes(Path.Combine(activeAppDir, "data.bin"), new byte[120 * 1024]);
        File.WriteAllBytes(Path.Combine(protectedDir, "data.bin"), new byte[120 * 1024]);
        File.WriteAllBytes(Path.Combine(recentPortable, "data.bin"), new byte[120 * 1024]);

        DateTime old = DateTime.UtcNow.AddDays(-90);
        Directory.SetLastWriteTimeUtc(orphanDir, old);
        Directory.SetLastWriteTimeUtc(activeAppDir, old);
        Directory.SetLastWriteTimeUtc(protectedDir, old);

        var installedTokens = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "InstalledEditor" };
        var installedPubs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        var orphans = GhostSoftwareScanner.ScanOrphanDirectories([fakeAppData], installedTokens, installedPubs, minDormantDays: 30);

        var hit = Assert.Single(orphans);
        Assert.Equal("OldUninstalledCadTool", hit.DisplayName);
        Assert.Equal(GhostItemKind.OrphanResidualFolder, hit.Kind);
        Assert.False(hit.IsSelected); // Manual check by default!
    }

    /// <summary>
    /// Writes a valid minimal binary MS-SHLLINK (.lnk) file with LinkInfo LocalBasePath for testing.
    /// </summary>
    private static void WriteMinimalMsShllink(string outputPath, string targetPath)
    {
        byte[] pathBytes = Encoding.Latin1.GetBytes(targetPath + "\0");
        byte[] suffixBytes = [0]; // empty CommonPathSuffix

        // LinkInfo structure (headerSize = 28, flags = 1 VolumeIDAndLocalBasePath)
        uint volumeIdSize = 16;
        uint volumeIdOffset = 28;
        uint localBasePathOffset = volumeIdOffset + volumeIdSize;
        uint commonPathSuffixOffset = localBasePathOffset + (uint)pathBytes.Length;
        uint linkInfoSize = commonPathSuffixOffset + (uint)suffixBytes.Length;

        using var ms = new MemoryStream();
        using var bw = new BinaryWriter(ms);

        // 76-byte ShellLinkHeader
        bw.Write(0x0000004Cu);                  // HeaderSize = 76
        bw.Write(new byte[16]);                 // LinkCLSID placeholder
        bw.Write(0x00000002u);                  // LinkFlags = HasLinkInfo
        bw.Write(new byte[76 - 24]);            // Remaining header fields

        // LinkInfo Header (28 bytes)
        bw.Write(linkInfoSize);
        bw.Write(28u);                          // LinkInfoHeaderSize
        bw.Write(1u);                           // LinkInfoFlags = VolumeIDAndLocalBasePath
        bw.Write(volumeIdOffset);
        bw.Write(localBasePathOffset);
        bw.Write(0u);                           // CommonNetworkRelativeLinkOffset
        bw.Write(commonPathSuffixOffset);

        // Dummy 16-byte VolumeID
        bw.Write(volumeIdSize);
        bw.Write(new byte[12]);

        // LocalBasePath + CommonPathSuffix
        bw.Write(pathBytes);
        bw.Write(suffixBytes);

        File.WriteAllBytes(outputPath, ms.ToArray());
    }
}

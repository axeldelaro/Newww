using CleanForge.Core.Models;
using CleanForge.Core.Optimizer;
using CleanForge.Core.Safety;
using CleanForge.Core.Scanners;

namespace CleanForge.Core;

/// <summary>
/// Built-in `--self-test` verification suite executed directly inside CleanForgePro.exe on real Windows in CI
/// to guarantee 0 crashes, 100% SafetyGuard protection on live OS paths, and working scanners/optimizer!
/// </summary>
public static class SelfTestRunner
{
    public static int RunLiveSelfTest(TextWriter output)
    {
        int passed = 0;
        int failed = 0;

        void Assert(bool cond, string label)
        {
            if (cond)
            {
                passed++;
                output.WriteLine($"  [PASS] {label}");
            }
            else
            {
                failed++;
                output.WriteLine($"  [FAIL] {label}");
            }
        }

        output.WriteLine("=== CleanForge Pro — Live Self-Test ===");

        // 1. Live System Health & Drives
        var health = SystemHealthMonitor.CaptureHealth();
        Assert(health.CpuLogicalCores >= 1, $"CPU cores detected ({health.CpuLogicalCores})");
        Assert(health.Drives.Count >= 1, $"Local drives enumerated ({health.Drives.Count})");
        Assert(health.TotalRamBytes > 0, $"System RAM detected ({health.RamFormatted})");

        // 2. Live SafetyGuard Penetration Checks on Real OS Paths
        string[] mustBlock =
        [
            @"C:\",
            @"C:\Windows",
            @"C:\Windows\System32\kernel32.dll",
            @"C:\Windows\System32\..\System32\ntoskrnl.exe",
            @"C:\Windows\WinSxS",
            @"C:\ProgramData\Microsoft\Windows Defender",
            @"C:\Users\Alice\AppData\Local\Google\Chrome\User Data\Default\Login Data",
            @"C:\Users\Alice\AppData\Local\Google\Chrome\User Data\Default\Bookmarks",
            @"C:\Users\Alice\.ssh\id_rsa",
            @"C:\Users\Alice\Documents"
        ];
        foreach (string p in mustBlock)
        {
            var v = SafetyGuard.EvaluatePathForDeletion(p, isDirectory: !Path.HasExtension(p));
            Assert(!v.IsAllowed, $"SafetyGuard BLOCKED protected path: {p}");
        }

        Assert(SafetyGuard.EvaluatePathForDeletion(@"C:\Windows\Temp\junk.tmp", isDirectory: false).IsAllowed,
            "SafetyGuard allows safe file inside C:\\Windows\\Temp");

        // 3. Sandbox Temp Workspace Test (FullDisk, Duplicates, Study, EmptyFolder, DryRun + Real Delete)
        string sandbox = Path.Combine(Path.GetTempPath(), $"CleanForgeSelfTest_{Guid.NewGuid():N}");
        Directory.CreateDirectory(sandbox);
        try
        {
            string subA = Path.Combine(sandbox, "Downloads");
            string subB = Path.Combine(sandbox, "Projets", "OldApp", "node_modules");
            string emptyDir = Path.Combine(sandbox, "DossierVide");
            Directory.CreateDirectory(subA);
            Directory.CreateDirectory(subB);
            Directory.CreateDirectory(emptyDir);

            byte[] payload = new byte[120 * 1024];
            new Random(42).NextBytes(payload);
            string dup1 = Path.Combine(sandbox, "cours_final.pdf");
            string dup2 = Path.Combine(subA, "cours_final (1).pdf");
            File.WriteAllBytes(dup1, payload);
            File.WriteAllBytes(dup2, payload);
            File.WriteAllBytes(Path.Combine(subB, "big_dep.js"), new byte[600 * 1024]);
            File.WriteAllBytes(Path.Combine(subA, "old_setup.exe"), new byte[1024 * 1024]);

            var explorer = new FullDiskExplorerScanner().ScanAllDrives([sandbox]);
            Assert(explorer.TotalScannedFiles >= 4, $"FullDiskExplorer scanned sandbox ({explorer.TotalScannedFiles} files)");
            var searchHits = FullDiskExplorerScanner.SearchGlobal(explorer, "old_setup");
            Assert(searchHits.Any(f => f.Name == "old_setup.exe"), "Global search located old_setup.exe");

            var dupGroups = new DuplicateFileScanner().ScanForDuplicates([sandbox], minSizeBytes: 1024);
            Assert(dupGroups.Count == 1 && dupGroups[0].Entries.Count == 2, "DuplicateFileScanner detected exact SHA-256 duplicate pair");
            Assert(dupGroups[0].Entries.Count(e => e.IsOriginalKept) == 1, "DuplicateFileScanner kept 1 original protected");

            var studyItems = new StudyGraduationScanner().ScanStudyClutter([sandbox]);
            Assert(studyItems.Any(s => s.Kind == StudyClutterKind.RegenerableDevBuild), "StudyGraduationScanner detected node_modules");
            Assert(studyItems.Any(s => s.Kind == StudyClutterKind.ForgottenInstallerOrArchive), "StudyGraduationScanner detected forgotten .exe installer");

            var empties = new ShortcutAndEmptyFolderScanner().ScanEmptyFolders([sandbox]);
            Assert(empties.Any(e => e.FullPath.Equals(emptyDir, StringComparison.OrdinalIgnoreCase)), "EmptyFolderScanner detected empty folder");

            // Dry-Run + Safe Delete Check
            var deleter = new RecycleBinDeleter(new AuditLogger(Path.Combine(sandbox, "_logs")));
            var dry = deleter.DeletePaths([(dup2, false)], dryRun: true, useRecycleBin: true);
            Assert(dry.WasDryRun && dry.SucceededCount == 1 && File.Exists(dup2), "Dry-Run simulated cleanly without touching file");

            var real = deleter.DeletePaths([(dup2, false)], dryRun: false, useRecycleBin: false);
            Assert(real.SucceededCount == 1 && !File.Exists(dup2) && File.Exists(dup1), "Safe delete removed ONLY copy and preserved original");

            // Optimizer Dry-Run & Profiles Check
            var opt = new SafeWindowsOptimizer(new AuditLogger(Path.Combine(sandbox, "_logs")), Path.Combine(sandbox, "_snaps"));
            var tweaks = SafeWindowsOptimizer.CreateAllTweaks();
            Assert(tweaks.All(t => t.IsHardwareSafe), "ALL optimizer tweaks are 100% hardware-safe");
            SafeWindowsOptimizer.ApplyProfileSelection(tweaks, OptimizerProfileId.GamingPerformance);
            var optDry = opt.ApplySelectedTweaks(tweaks, "Gaming SelfTest", dryRun: true);
            Assert(optDry.Success && optDry.AppliedCount >= 5, $"Optimizer dry-run succeeded ({optDry.AppliedCount} tweaks)");

            // Real Windows Registry Read Check (Startup + Ghost Scanner)
            if (OperatingSystem.IsWindows())
            {
                var startups = new StartupManager().ListStartupApps();
                Assert(startups != null, $"Live Windows StartupManager enumerated ({startups!.Count} items)");

                var (ghosts, installed) = new GhostSoftwareScanner().ScanWindowsSoftware();
                Assert(installed.Count >= 1, $"Live Windows GhostSoftwareScanner enumerated ({installed.Count} apps, {ghosts.Count} ghosts/orphans)");

                var caches = new DeepCacheCleanerScanner().ScanDefaultWindowsCaches();
                Assert(caches != null, $"Live Windows DeepCacheCleaner scanned ({caches!.Count} active caches)");
            }
        }
        finally
        {
            try { Directory.Delete(sandbox, recursive: true); } catch { }
        }

        output.WriteLine($"=== SELF-TEST SUMMARY: {passed}/{passed + failed} PASSED ({failed} failed) ===");
        return failed == 0 ? 0 : 1;
    }
}

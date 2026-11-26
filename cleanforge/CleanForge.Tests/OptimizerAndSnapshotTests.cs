using CleanForge.Core;
using CleanForge.Core.Models;
using CleanForge.Core.Optimizer;
using CleanForge.Core.Safety;
using Xunit;

namespace CleanForge.Tests;

public class OptimizerAndSnapshotTests : IDisposable
{
    private readonly string _sandbox;

    public OptimizerAndSnapshotTests()
    {
        _sandbox = Path.Combine(Path.GetTempPath(), $"CleanForgeOptTests_{Guid.NewGuid():N}");
        Directory.CreateDirectory(_sandbox);
    }

    public void Dispose()
    {
        try { Directory.Delete(_sandbox, recursive: true); } catch { }
    }

    [Fact]
    public void SafeWindowsOptimizer_AllTweaksAreHardwareSafeAndPassSafetyGuard()
    {
        var profiles = SafeWindowsOptimizer.GetProfiles();
        var tweaks = SafeWindowsOptimizer.CreateAllTweaks();

        Assert.Equal(4, profiles.Count);
        Assert.True(tweaks.Count >= 14);

        foreach (var t in tweaks)
        {
            Assert.True(t.IsHardwareSafe, $"Tweak {t.Id} must be hardware-safe!");
            if (t.RegistryHive != null && t.RegistrySubKey != null)
            {
                var v = SafetyGuard.EvaluateRegistryPath(t.RegistryHive, t.RegistrySubKey);
                Assert.True(v.IsAllowed, $"Tweak {t.Id} registry path blocked: {v.Reason}");
            }
        }

        // Every profile selects valid tweak IDs
        var tweakIdSet = tweaks.Select(t => t.Id).ToHashSet();
        foreach (var p in profiles)
        {
            Assert.NotEmpty(p.TweakIds);
            foreach (string tid in p.TweakIds)
                Assert.Contains(tid, tweakIdSet);
        }
    }

    [Fact]
    public void SafeWindowsOptimizer_CreatesJsonSnapshotAndRestoresRoundTrip()
    {
        var logger = new AuditLogger(Path.Combine(_sandbox, "logs"));
        string snapDir = Path.Combine(_sandbox, "snapshots");
        var optimizer = new SafeWindowsOptimizer(logger, snapDir);

        var tweaks = SafeWindowsOptimizer.CreateAllTweaks();
        SafeWindowsOptimizer.ApplyProfileSelection(tweaks, OptimizerProfileId.GamingPerformance);

        // Dry run creates no snapshot
        var dry = optimizer.ApplySelectedTweaks(tweaks, "Gaming DryRun", dryRun: true);
        Assert.True(dry.Success);
        Assert.True(dry.WasDryRun);
        Assert.True(dry.AppliedCount >= 6);
        Assert.Empty(optimizer.GetSavedSnapshots());

        // Real run saves snapshot JSON cleanly
        var real = optimizer.ApplySelectedTweaks(tweaks, "Gaming & FPS", dryRun: false);
        Assert.True(real.Success);
        Assert.True(File.Exists(real.SnapshotPath));

        var loaded = optimizer.GetSavedSnapshots();
        Assert.Single(loaded);
        Assert.Equal("Gaming & FPS", loaded[0].ProfileName);
        Assert.NotEmpty(loaded[0].RegistryEntries);

        var restore = optimizer.RestoreSnapshot(loaded[0]);
        Assert.True(restore.Success);
        Assert.True(restore.AppliedCount >= 1);
    }

    [Fact]
    public void StartupManager_LocksSecurityAndDriversWhileAllowingChatAndLaunchers()
    {
        Assert.Equal(StartupRiskLevel.ProtectedSystemLocked, StartupManager.ClassifyStartupRisk("SecurityHealth", @"C:\Windows\System32\SecurityHealthSystray.exe"));
        Assert.Equal(StartupRiskLevel.ProtectedSystemLocked, StartupManager.ClassifyStartupRisk("RtkAudUService", @"C:\Program Files\Realtek\Audio\rtk.exe"));
        Assert.Equal(StartupRiskLevel.ProtectedSystemLocked, StartupManager.ClassifyStartupRisk("NvBackend", @"C:\Program Files\NVIDIA\nvbackend.exe"));

        Assert.Equal(StartupRiskLevel.SafeToDisable, StartupManager.ClassifyStartupRisk("Discord", @"C:\Users\Axel\AppData\Local\Discord\Update.exe"));
        Assert.Equal(StartupRiskLevel.SafeToDisable, StartupManager.ClassifyStartupRisk("Spotify", @"C:\Users\Axel\AppData\Roaming\Spotify\Spotify.exe"));
        Assert.Equal(StartupRiskLevel.SafeToDisable, StartupManager.ClassifyStartupRisk("Steam", @"C:\Program Files (x86)\Steam\steam.exe"));

        // Verify engine refuses disabling a locked startup entry
        var mgr = new StartupManager();
        var locked = new StartupAppItem
        {
            Name = "Windows Defender",
            Command = "SecurityHealthSystray.exe",
            RiskLevel = StartupRiskLevel.ProtectedSystemLocked,
            IsEnabled = true
        };
        Assert.False(mgr.SetStartupEnabled(locked, false, out string msg));
        Assert.Contains("verrouillé", msg, StringComparison.OrdinalIgnoreCase);
        Assert.True(locked.IsEnabled);
    }

    [Fact]
    public void SelfTestRunner_Passes100Percent()
    {
        using var sw = new StringWriter();
        int exitCode = SelfTestRunner.RunLiveSelfTest(sw);
        Assert.True(exitCode == 0, sw.ToString());
        Assert.Contains("0 failed", sw.ToString());
    }
}

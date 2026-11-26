using CleanForge.Core.Models;
using CleanForge.Core.Safety;

namespace CleanForge.Core.Scanners;

public sealed record CacheTargetSpec(
    string DisplayName,
    string GroupName,
    string Path,
    string Description,
    CleanupCategory Category,
    SafetyTier SafetyTier,
    bool RequiresAdmin = false,
    string? FilePattern = null,
    bool SelectedByDefault = true);

/// <summary>
/// Scans 60+ deep cache & residual locations far beyond Windows Disk Cleanup.
/// Strictly cleans ONLY regenerable caches/temp/shaders — NEVER touches passwords, bookmarks, cookies, history, or OS binaries!
/// </summary>
public sealed class DeepCacheCleanerScanner
{
    public List<CleanCandidate> ScanDefaultWindowsCaches(
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var specs = BuildWindowsCacheTargets();
        return ScanSpecs(specs, progress, ct);
    }

    public List<CleanCandidate> ScanSpecs(
        IReadOnlyList<CacheTargetSpec> specs,
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var results = new List<CleanCandidate>();

        for (int i = 0; i < specs.Count; i++)
        {
            if (ct.IsCancellationRequested) break;
            var spec = specs[i];

            progress?.Report(new ScanProgress
            {
                Stage = "Analyse des caches profonds...",
                CurrentPath = spec.Path,
                Percent = specs.Count > 0 ? (double)i / specs.Count * 100.0 : 100.0,
                FilesScanned = results.Count
            });

            if (string.IsNullOrWhiteSpace(spec.Path) || !Directory.Exists(spec.Path))
                continue;

            var rootCheck = SafetyGuard.EvaluatePathForDeletion(spec.Path, isDirectory: true, allowCleanContentsOfKnownCacheRoot: true);
            if (!rootCheck.IsAllowed)
                continue;

            var (bytes, count, latestUtc) = MeasureSafeCacheTarget(spec.Path, spec.FilePattern, ct);
            if (count == 0 || bytes <= 0)
                continue;

            results.Add(new CleanCandidate
            {
                DisplayName = spec.DisplayName,
                GroupName = spec.GroupName,
                Path = spec.Path,
                Description = spec.Description,
                Category = spec.Category,
                SafetyTier = spec.SafetyTier,
                IsDirectory = true,
                CleanContentsOnly = true,
                RequiresAdmin = spec.RequiresAdmin,
                SizeBytes = bytes,
                ItemCount = count,
                LastModifiedUtc = latestUtc,
                IsSelected = spec.SelectedByDefault && spec.SafetyTier == SafetyTier.SafeAuto
            });
        }

        return results.OrderByDescending(r => r.SizeBytes).ToList();
    }

    private static (long Bytes, int Count, DateTime LatestUtc) MeasureSafeCacheTarget(
        string dirPath,
        string? filePattern,
        CancellationToken ct)
    {
        long bytes = 0;
        int count = 0;
        DateTime latest = DateTime.MinValue;

        var enumOpts = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            RecurseSubdirectories = string.IsNullOrEmpty(filePattern),
            ReturnSpecialDirectories = false,
            AttributesToSkip = FileAttributes.ReparsePoint
        };

        try
        {
            string pattern = string.IsNullOrEmpty(filePattern) ? "*" : filePattern;
            foreach (string file in Directory.EnumerateFiles(dirPath, pattern, enumOpts))
            {
                if (ct.IsCancellationRequested) break;
                if (!SafetyGuard.EvaluatePathForDeletion(file, isDirectory: false).IsAllowed)
                    continue;
                try
                {
                    var fi = new FileInfo(file);
                    if (SafetyGuard.IsReparsePointOrCloudStub(fi.Attributes)) continue;
                    bytes += fi.Length;
                    count++;
                    if (fi.LastWriteTimeUtc > latest) latest = fi.LastWriteTimeUtc;
                }
                catch { }
            }
        }
        catch { }

        return (bytes, count, latest);
    }

    public static List<CacheTargetSpec> BuildWindowsCacheTargets()
    {
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string roaming = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string progData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        string winDir = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
        string userTemp = Path.GetTempPath().TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

        var list = new List<CacheTargetSpec>();

        void Add(string name, string group, string path, string desc, CleanupCategory cat, SafetyTier tier = SafetyTier.SafeAuto, bool admin = false, string? pattern = null, bool defSel = true)
        {
            if (!string.IsNullOrWhiteSpace(path))
                list.Add(new CacheTargetSpec(name, group, path, desc, cat, tier, admin, pattern, defSel));
        }

        // ==================== 1. WINDOWS SAFE CACHES ====================
        Add("Fichiers Temporaires Utilisateur (%TEMP%)", "Système Windows (Sûr)", userTemp, "Fichiers temporaires laissés par les logiciels et installeurs.", CleanupCategory.WindowsSafeCache);
        if (!string.IsNullOrWhiteSpace(winDir))
        {
            Add("Windows Temp (C:\\Windows\\Temp)", "Système Windows (Sûr)", Path.Combine(winDir, "Temp"), "Temporaires système sûrs à vider.", CleanupCategory.WindowsSafeCache, admin: true);
            Add("Cache Téléchargements Windows Update", "Système Windows (Sûr)", Path.Combine(winDir, "SoftwareDistribution", "Download"), "Paquets de mises à jour déjà installées (se régénère si besoin).", CleanupCategory.WindowsSafeCache, admin: true);
            Add("Rapports Minidump (BSOD anciens)", "Système Windows (Sûr)", Path.Combine(winDir, "Minidump"), "Anciens dumps mémoire de diagnostic.", CleanupCategory.WindowsSafeCache, admin: true);
            Add("Rapports LiveKernelReports", "Système Windows (Sûr)", Path.Combine(winDir, "LiveKernelReports"), "Dumps de diagnostic noyau anciens.", CleanupCategory.WindowsSafeCache, admin: true);
            Add("Prefetch Windows (Anciens raccourcis boot)", "Système Windows (Sûr)", Path.Combine(winDir, "Prefetch"), "Cache d'accélération d'anciens programmes supprimés (se recrée automatiquement).", CleanupCategory.WindowsSafeCache, SafetyTier.SafeReview, admin: true, defSel: false);
        }
        if (!string.IsNullOrWhiteSpace(local))
        {
            Add("CrashDumps Utilisateur", "Système Windows (Sûr)", Path.Combine(local, "CrashDumps"), "Dumps mémoire générés lors d'anciens plantages d'applications.", CleanupCategory.WindowsSafeCache);
            Add("Rapports d'erreurs Windows (WER)", "Système Windows (Sûr)", Path.Combine(local, "Microsoft", "Windows", "WER"), "Anciens rapports d'erreur Windows Error Reporting.", CleanupCategory.WindowsSafeCache);
            Add("Cache Miniatures Explorer (ThumbCache)", "Système Windows (Sûr)", Path.Combine(local, "Microsoft", "Windows", "Explorer"), "Miniatures d'images mises en cache (régénérées automatiquement).", CleanupCategory.WindowsSafeCache, pattern: "thumbcache_*.db");
            Add("Cache DirectX Shader (D3DSCache)", "Shaders GPU (Sûr)", Path.Combine(local, "D3DSCache"), "Cache shaders Direct3D régénéré automatiquement par vos jeux.", CleanupCategory.GpuShaderCache);
            Add("Cache Optimisation Livraison (Windows)", "Système Windows (Sûr)", Path.Combine(local, "Microsoft", "Windows", "INetCache"), "Cache web interne des composants Windows.", CleanupCategory.WindowsSafeCache);
        }
        if (!string.IsNullOrWhiteSpace(progData))
        {
            Add("Rapports d'erreurs système (ProgramData WER)", "Système Windows (Sûr)", Path.Combine(progData, "Microsoft", "Windows", "WER"), "Rapports d'erreurs globaux Windows.", CleanupCategory.WindowsSafeCache, admin: true);
        }

        // ==================== 2. GPU SHADERS (NVIDIA / AMD / INTEL) ====================
        if (!string.IsNullOrWhiteSpace(local))
        {
            Add("NVIDIA DirectX Shader Cache (DXCache)", "Shaders GPU (Sûr)", Path.Combine(local, "NVIDIA", "DXCache"), "Anciens shaders DirectX NVIDIA (recompilés au besoin).", CleanupCategory.GpuShaderCache);
            Add("NVIDIA OpenGL Cache (GLCache)", "Shaders GPU (Sûr)", Path.Combine(local, "NVIDIA", "GLCache"), "Anciens shaders OpenGL NVIDIA.", CleanupCategory.GpuShaderCache);
            Add("AMD DirectX Shader Cache", "Shaders GPU (Sûr)", Path.Combine(local, "AMD", "DxCache"), "Cache shaders DirectX AMD Radeon.", CleanupCategory.GpuShaderCache);
            Add("AMD OpenGL Cache", "Shaders GPU (Sûr)", Path.Combine(local, "AMD", "GLCache"), "Cache shaders OpenGL AMD Radeon.", CleanupCategory.GpuShaderCache);
            Add("Intel Shader Cache", "Shaders GPU (Sûr)", Path.Combine(local, "Intel", "ShaderCache"), "Cache shaders GPU Intel.", CleanupCategory.GpuShaderCache);
        }
        if (!string.IsNullOrWhiteSpace(progData))
        {
            Add("NVIDIA Global NV_Cache", "Shaders GPU (Sûr)", Path.Combine(progData, "NVIDIA Corporation", "NV_Cache"), "Cache global compilé NVIDIA.", CleanupCategory.GpuShaderCache, admin: true);
        }

        // ==================== 3. BROWSERS (CACHE ONLY — ZERO PASSWORDS/COOKIES/HISTORY!) ====================
        if (!string.IsNullOrWhiteSpace(local))
        {
            AddChromiumBrowserCaches(list, "Google Chrome", Path.Combine(local, "Google", "Chrome", "User Data"));
            AddChromiumBrowserCaches(list, "Microsoft Edge", Path.Combine(local, "Microsoft", "Edge", "User Data"));
            AddChromiumBrowserCaches(list, "Brave", Path.Combine(local, "BraveSoftware", "Brave-Browser", "User Data"));
            AddChromiumBrowserCaches(list, "Vivaldi", Path.Combine(local, "Vivaldi", "User Data"));
            AddChromiumBrowserCaches(list, "Opera", Path.Combine(local, "Opera Software", "Opera Stable"));
            AddChromiumBrowserCaches(list, "Opera GX", Path.Combine(local, "Opera Software", "Opera GX Stable"));

            // Mozilla Firefox (cache2 ONLY — never places.sqlite / logins.json / key4.db!)
            string ffProfiles = Path.Combine(local, "Mozilla", "Firefox", "Profiles");
            if (Directory.Exists(ffProfiles))
            {
                try
                {
                    foreach (string profDir in Directory.EnumerateDirectories(ffProfiles))
                    {
                        string pName = Path.GetFileName(profDir);
                        Add($"Firefox Cache ({pName})", "Navigateurs (Caches Seuls)", Path.Combine(profDir, "cache2"), "Cache web Firefox uniquement (mots de passe, favoris et sessions préservés).", CleanupCategory.BrowserCacheOnly);
                        Add($"Firefox StartupCache ({pName})", "Navigateurs (Caches Seuls)", Path.Combine(profDir, "startupCache"), "Cache de démarrage Firefox régénéré automatiquement.", CleanupCategory.BrowserCacheOnly);
                    }
                }
                catch { }
            }
        }

        // ==================== 4. APPS DESKTOP & GAMING (CACHES SEULS) ====================
        if (!string.IsNullOrWhiteSpace(roaming))
        {
            Add("Discord Cache Média", "Apps & Gaming (Caches)", Path.Combine(roaming, "discord", "Cache"), "Images/vidéos mises en cache par Discord (compte intact).", CleanupCategory.AppMediaCache);
            Add("Discord Code Cache", "Apps & Gaming (Caches)", Path.Combine(roaming, "discord", "Code Cache"), "Cache JS compilé Discord.", CleanupCategory.AppMediaCache);
            Add("Discord GPUCache", "Apps & Gaming (Caches)", Path.Combine(roaming, "discord", "GPUCache"), "Cache GPU Discord.", CleanupCategory.AppMediaCache);
            Add("Slack Cache", "Apps & Gaming (Caches)", Path.Combine(roaming, "Slack", "Cache"), "Cache média Slack.", CleanupCategory.AppMediaCache);
            Add("Microsoft Teams Cache", "Apps & Gaming (Caches)", Path.Combine(roaming, "Microsoft", "Teams", "Cache"), "Cache média Teams.", CleanupCategory.AppMediaCache);
            Add("OBS Studio Logs anciens", "Apps & Gaming (Caches)", Path.Combine(roaming, "obs-studio", "logs"), "Anciens journaux texte OBS Studio.", CleanupCategory.AppMediaCache);
            Add("Adobe Media Cache Files", "Apps & Gaming (Caches)", Path.Combine(roaming, "Adobe", "Common", "Media Cache Files"), "Gros fichiers cache vidéo/audio Adobe Premiere & After Effects.", CleanupCategory.AppMediaCache);
            Add("Adobe Media Cache", "Apps & Gaming (Caches)", Path.Combine(roaming, "Adobe", "Common", "Media Cache"), "Index cache média Adobe.", CleanupCategory.AppMediaCache);
            Add("VS Code CachedData", "Outils Dev & Études (Caches)", Path.Combine(roaming, "Code", "CachedData"), "Cache interne VS Code (extensions & réglages intacts).", CleanupCategory.DevBuildCache);
            Add("VS Code Code Cache", "Outils Dev & Études (Caches)", Path.Combine(roaming, "Code", "Code Cache"), "Cache JS VS Code.", CleanupCategory.DevBuildCache);
            Add("VS Code Cache", "Outils Dev & Études (Caches)", Path.Combine(roaming, "Code", "Cache"), "Cache web VS Code.", CleanupCategory.DevBuildCache);
        }

        if (!string.IsNullOrWhiteSpace(local))
        {
            Add("Spotify Cache Audio (Storage)", "Apps & Gaming (Caches)", Path.Combine(local, "Spotify", "Storage"), "Cache de streaming Spotify (peut peser plusieurs Go ; playlists intactes).", CleanupCategory.AppMediaCache);
            Add("Spotify Data Cache", "Apps & Gaming (Caches)", Path.Combine(local, "Spotify", "Data"), "Cache pochettes & données Spotify.", CleanupCategory.AppMediaCache);
            Add("Steam HTTP Cache", "Apps & Gaming (Caches)", Path.Combine(local, "Steam", "htmlcache"), "Cache navigateur interne Steam (jeux & sauvegardes intacts).", CleanupCategory.AppMediaCache);
            Add("Epic Games WebCache", "Apps & Gaming (Caches)", Path.Combine(local, "EpicGamesLauncher", "Saved", "webcache_4430"), "Cache web Epic Games Launcher.", CleanupCategory.AppMediaCache);
            Add("Epic Games Logs", "Apps & Gaming (Caches)", Path.Combine(local, "EpicGamesLauncher", "Saved", "Logs"), "Anciens logs Epic Games.", CleanupCategory.AppMediaCache);
            Add("Battle.net Browser Cache", "Apps & Gaming (Caches)", Path.Combine(local, "Battle.net", "BrowserCache"), "Cache interne Battle.net.", CleanupCategory.AppMediaCache);
            Add("EA App Cache", "Apps & Gaming (Caches)", Path.Combine(local, "Electronic Arts", "EA Desktop", "cache"), "Cache interne EA App.", CleanupCategory.AppMediaCache);
            Add("Zoom Logs", "Apps & Gaming (Caches)", Path.Combine(roaming, "Zoom", "logs"), "Anciens journaux Zoom.", CleanupCategory.AppMediaCache);

            // ==================== 5. DEV / STUDY PACKAGE CACHES ====================
            Add("Cache npm (Node.js)", "Outils Dev & Études (Caches)", Path.Combine(local, "npm-cache"), "Anciens paquets téléchargés par npm pendant les études.", CleanupCategory.DevBuildCache);
            Add("Cache pip (Python)", "Outils Dev & Études (Caches)", Path.Combine(local, "pip", "Cache"), "Archives wheel/tar.gz Python conservées par pip.", CleanupCategory.DevBuildCache);
            Add("Cache Yarn (Node.js)", "Outils Dev & Études (Caches)", Path.Combine(local, "Yarn", "Cache"), "Cache global Yarn.", CleanupCategory.DevBuildCache);
            Add("Cache pnpm Store", "Outils Dev & Études (Caches)", Path.Combine(local, "pnpm", "store"), "Cache global pnpm.", CleanupCategory.DevBuildCache, SafetyTier.SafeReview, defSel: false);
            Add("Cache NuGet (.NET)", "Outils Dev & Études (Caches)", Path.Combine(local, "NuGet", "v3-cache"), "Cache HTTP NuGet.", CleanupCategory.DevBuildCache);
            Add("Cache JetBrains (IntelliJ/PyCharm/WebStorm)", "Outils Dev & Études (Caches)", Path.Combine(local, "JetBrains"), "Anciens index & caches IDE JetBrains (projets intacts).", CleanupCategory.DevBuildCache, SafetyTier.SafeReview, defSel: false);
        }

        if (!string.IsNullOrWhiteSpace(user))
        {
            Add("Cache Gradle (.gradle/caches)", "Outils Dev & Études (Caches)", Path.Combine(user, ".gradle", "caches"), "Dépendances Java/Android mises en cache par Gradle (souvent plusieurs Go).", CleanupCategory.DevBuildCache, SafetyTier.SafeReview, defSel: false);
            Add("Cache Maven (.m2/repository)", "Outils Dev & Études (Caches)", Path.Combine(user, ".m2", "repository"), "Dépendances Java téléchargées par Maven pendant les cours.", CleanupCategory.DevBuildCache, SafetyTier.SafeReview, defSel: false);
            Add("Cache Cargo Rust (.cargo/registry/cache)", "Outils Dev & Études (Caches)", Path.Combine(user, ".cargo", "registry", "cache"), "Archives crates Rust téléchargées.", CleanupCategory.DevBuildCache);
            Add("Cache npm Roaming (npm-cache)", "Outils Dev & Études (Caches)", Path.Combine(roaming, "npm-cache"), "Cache global npm Roaming.", CleanupCategory.DevBuildCache);
            Add("Cache NuGet Packages (.nuget/packages)", "Outils Dev & Études (Caches)", Path.Combine(user, ".nuget", "packages"), "Paquets C#/.NET téléchargés (re-téléchargés si besoin).", CleanupCategory.DevBuildCache, SafetyTier.SafeReview, defSel: false);
        }

        return list;
    }

    private static void AddChromiumBrowserCaches(List<CacheTargetSpec> list, string browserName, string userDataDir)
    {
        if (!Directory.Exists(userDataDir)) return;

        // Direct Opera-style single profile or Chromium multi-profile ("Default", "Profile 1", ...)
        var profileDirs = new List<(string Label, string Dir)>();
        if (Directory.Exists(Path.Combine(userDataDir, "Cache")) || Directory.Exists(Path.Combine(userDataDir, "Code Cache")))
        {
            profileDirs.Add(("Principal", userDataDir));
        }
        else
        {
            try
            {
                foreach (string d in Directory.EnumerateDirectories(userDataDir))
                {
                    string name = Path.GetFileName(d);
                    if (name.Equals("Default", StringComparison.OrdinalIgnoreCase) || name.StartsWith("Profile ", StringComparison.OrdinalIgnoreCase))
                    {
                        profileDirs.Add((name, d));
                    }
                }
            }
            catch { }
        }

        foreach (var (label, pDir) in profileDirs)
        {
            string desc = $"Cache temporaire {browserName} ({label}) uniquement — mots de passe, favoris, historique et sessions 100% préservés.";
            list.Add(new CacheTargetSpec($"{browserName} Cache ({label})", "Navigateurs (Caches Seuls)", Path.Combine(pDir, "Cache", "Cache_Data"), desc, CleanupCategory.BrowserCacheOnly, SafetyTier.SafeAuto));
            list.Add(new CacheTargetSpec($"{browserName} Code Cache ({label})", "Navigateurs (Caches Seuls)", Path.Combine(pDir, "Code Cache"), desc, CleanupCategory.BrowserCacheOnly, SafetyTier.SafeAuto));
            list.Add(new CacheTargetSpec($"{browserName} GPUCache ({label})", "Navigateurs (Caches Seuls)", Path.Combine(pDir, "GPUCache"), desc, CleanupCategory.BrowserCacheOnly, SafetyTier.SafeAuto));
            list.Add(new CacheTargetSpec($"{browserName} Service Worker Cache ({label})", "Navigateurs (Caches Seuls)", Path.Combine(pDir, "Service Worker", "CacheStorage"), desc, CleanupCategory.BrowserCacheOnly, SafetyTier.SafeAuto));
        }
    }
}

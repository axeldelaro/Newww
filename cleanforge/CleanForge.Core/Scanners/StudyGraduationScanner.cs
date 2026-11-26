using CleanForge.Core.Models;
using CleanForge.Core.Safety;

namespace CleanForge.Core.Scanners;

/// <summary>
/// Dedicated "Fin d'Études / Nouveau Départ" scanner:
/// 1. Regenerable Dev/Study build & dependency folders (node_modules, .gradle, venv, __pycache__, target, bin/obj...)
/// 2. Forgotten installers & archives (.exe, .msi, .iso, .zip, .rar, .7z in Downloads/Desktop or large old files)
/// 3. Old study documents (.pdf, .pptx, .docx, .xlsx, .odt, .tex, .ipynb > N days untouched) — NEVER auto-selected!
/// </summary>
public sealed class StudyGraduationScanner
{
    private static readonly HashSet<string> RegenerableBuildDirNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "node_modules", ".gradle", "__pycache__", ".venv", "venv", ".pytest_cache",
        ".mypy_cache", ".ruff_cache", ".next", ".nuxt", ".parcel-cache", ".turbo",
        "cmake-build-debug", "cmake-build-release", ".dart_tool", ".flutter-plugins-dependencies"
    };

    private static readonly HashSet<string> InstallerExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".exe", ".msi", ".msix", ".appx", ".iso", ".img", ".dmg", ".cab"
    };

    private static readonly HashSet<string> ArchiveExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".zip", ".rar", ".7z", ".tar", ".gz", ".tgz", ".xz"
    };

    private static readonly HashSet<string> StudyDocExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".pdf", ".pptx", ".ppt", ".docx", ".doc", ".xlsx", ".xls",
        ".odt", ".odp", ".ods", ".tex", ".ipynb", ".drawio", ".psd", ".ai", ".dwg", ".blend"
    };

    public List<StudyClutterItem> ScanStudyClutter(
        IEnumerable<string>? customRoots = null,
        int oldDocDaysThreshold = 180,
        long minOldDocBytes = 256 * 1024,
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var roots = (customRoots != null && customRoots.Any())
            ? customRoots.Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase).ToList()
            : GetDefaultStudyRoots();

        var results = new List<StudyClutterItem>();
        var cutoffOldDocs = DateTime.UtcNow.AddDays(-Math.Max(1, oldDocDaysThreshold));
        var cutoffArchives = DateTime.UtcNow.AddDays(-30);
        long scanned = 0;

        var enumOpts = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            RecurseSubdirectories = false,
            ReturnSpecialDirectories = false,
            AttributesToSkip = FileAttributes.ReparsePoint
        };

        foreach (string root in roots)
        {
            if (ct.IsCancellationRequested) break;
            var stack = new Stack<string>();
            stack.Push(root);

            while (stack.Count > 0 && !ct.IsCancellationRequested)
            {
                string dir = stack.Pop();
                if (!SafetyGuard.CanTraverseDirectory(dir)) continue;

                string dirName = Path.GetFileName(dir);
                bool isScanRoot = string.Equals(dir, root, StringComparison.OrdinalIgnoreCase);

                string normLower = SafetyGuard.NormalizeForComparison(dir).ToLowerInvariant();
                string driveRel = (normLower.Length >= 2 && normLower[1] == ':') ? normLower[2..] : normLower;
                if (driveRel.StartsWith(@"\windows", StringComparison.Ordinal)
                    || driveRel.StartsWith(@"\program files", StringComparison.Ordinal)
                    || driveRel.StartsWith(@"\programdata", StringComparison.Ordinal)
                    || (!isScanRoot && dirName.Equals("AppData", StringComparison.OrdinalIgnoreCase)))
                {
                    continue;
                }

                if (dirName.Equals(".git", StringComparison.OrdinalIgnoreCase))
                    continue; // Never traverse or touch Git history!

                // Check if this directory itself is a regenerable Dev/Study build folder
                if (IsRegenerableBuildFolder(dir, dirName) && SafetyGuard.EvaluatePathForDeletion(dir, isDirectory: true).IsAllowed)
                {
                    long sz = RecycleBinDeleter.EstimatePathSize(dir, isDirectory: true);
                    if (sz >= 512 * 1024) // >= 512 KB to avoid cluttering UI with empty tiny caches
                    {
                        var di = new DirectoryInfo(dir);
                        results.Add(new StudyClutterItem
                        {
                            Name = $"{Path.GetFileName(Path.GetDirectoryName(dir) ?? "")}\\{dirName}",
                            FullPath = dir,
                            Kind = StudyClutterKind.RegenerableDevBuild,
                            Reason = $"Dossier build/dépendances régénérable ({dirName})",
                            SizeBytes = sz,
                            LastAccessUtc = SafeDate(() => di.LastWriteTimeUtc),
                            IsDirectory = true,
                            IsSelected = false // SafeReview: user explicitly chooses which old projects to clean!
                        });
                    }
                    continue; // Do not descend inside node_modules / venv / target!
                }

                bool isDownloadsOrDesktop = normLower.Contains("downloads")
                    || normLower.Contains("téléchargements")
                    || normLower.Contains("desktop")
                    || normLower.Contains("bureau");

                try
                {
                    foreach (string file in Directory.EnumerateFiles(dir, "*", enumOpts))
                    {
                        if (ct.IsCancellationRequested) break;
                        scanned++;
                        if ((scanned & 1023) == 0)
                        {
                            progress?.Report(new ScanProgress
                            {
                                Stage = "Analyse Fin d'Études (builds, installeurs, anciens cours)...",
                                CurrentPath = dir,
                                Percent = 55,
                                FilesScanned = scanned,
                                BytesFound = results.Sum(r => r.SizeBytes)
                            });
                        }

                        if (!SafetyGuard.EvaluatePathForDeletion(file, isDirectory: false).IsAllowed)
                            continue;

                        try
                        {
                            var fi = new FileInfo(file);
                            if (SafetyGuard.IsReparsePointOrCloudStub(fi.Attributes)) continue;

                            string ext = fi.Extension.ToLowerInvariant();
                            DateTime lastTouch = fi.LastWriteTimeUtc > fi.LastAccessTimeUtc ? fi.LastWriteTimeUtc : fi.LastAccessTimeUtc;

                            if (InstallerExtensions.Contains(ext) && (isDownloadsOrDesktop || fi.Length >= 15L * 1024 * 1024))
                            {
                                results.Add(new StudyClutterItem
                                {
                                    Name = fi.Name,
                                    FullPath = file,
                                    Kind = StudyClutterKind.ForgottenInstallerOrArchive,
                                    Reason = isDownloadsOrDesktop
                                        ? "Installeur/Image ISO oublié dans Téléchargements ou Bureau"
                                        : "Gros fichier installeur/ISO oublié",
                                    SizeBytes = fi.Length,
                                    LastAccessUtc = lastTouch,
                                    IsDirectory = false,
                                    IsSelected = false
                                });
                            }
                            else if (ArchiveExtensions.Contains(ext) && isDownloadsOrDesktop && fi.Length >= 5L * 1024 * 1024 && lastTouch <= cutoffArchives)
                            {
                                results.Add(new StudyClutterItem
                                {
                                    Name = fi.Name,
                                    FullPath = file,
                                    Kind = StudyClutterKind.ForgottenInstallerOrArchive,
                                    Reason = "Archive compressée ancienne (> 30 jours) dans Téléchargements/Bureau",
                                    SizeBytes = fi.Length,
                                    LastAccessUtc = lastTouch,
                                    IsDirectory = false,
                                    IsSelected = false
                                });
                            }
                            else if (StudyDocExtensions.Contains(ext) && fi.Length >= minOldDocBytes && lastTouch <= cutoffOldDocs)
                            {
                                results.Add(new StudyClutterItem
                                {
                                    Name = fi.Name,
                                    FullPath = file,
                                    Kind = StudyClutterKind.OldStudyDocument,
                                    Reason = $"Document non modifié depuis > {oldDocDaysThreshold} jours (vérification manuelle)",
                                    SizeBytes = fi.Length,
                                    LastAccessUtc = lastTouch,
                                    IsDirectory = false,
                                    IsSelected = false // ALWAYS manual!
                                });
                            }
                        }
                        catch { }
                    }

                    foreach (string sub in Directory.EnumerateDirectories(dir, "*", enumOpts))
                    {
                        stack.Push(sub);
                    }
                }
                catch { }
            }
        }

        progress?.Report(new ScanProgress
        {
            Stage = $"Analyse Fin d'Études terminée ({results.Count} éléments trouvés)",
            Percent = 100,
            FilesScanned = scanned,
            BytesFound = results.Sum(r => r.SizeBytes)
        });

        return results.OrderByDescending(r => r.SizeBytes).ToList();
    }

    private static bool IsRegenerableBuildFolder(string fullPath, string dirName)
    {
        if (RegenerableBuildDirNames.Contains(dirName)) return true;

        string? parent = Path.GetDirectoryName(fullPath);
        if (string.IsNullOrEmpty(parent)) return false;

        // C# / .NET bin or obj ONLY when sibling .csproj/.vbproj/.fsproj exists!
        if (dirName.Equals("obj", StringComparison.OrdinalIgnoreCase) || dirName.Equals("bin", StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                return Directory.EnumerateFiles(parent, "*.csproj").Any()
                    || Directory.EnumerateFiles(parent, "*.vbproj").Any()
                    || Directory.EnumerateFiles(parent, "*.fsproj").Any();
            }
            catch { return false; }
        }

        // Rust / Maven `target` ONLY when Cargo.toml or pom.xml is sibling!
        if (dirName.Equals("target", StringComparison.OrdinalIgnoreCase))
        {
            return File.Exists(Path.Combine(parent, "Cargo.toml")) || File.Exists(Path.Combine(parent, "pom.xml"));
        }

        // Gradle/Android `build` ONLY when build.gradle / build.gradle.kts is sibling!
        if (dirName.Equals("build", StringComparison.OrdinalIgnoreCase))
        {
            return File.Exists(Path.Combine(parent, "build.gradle"))
                || File.Exists(Path.Combine(parent, "build.gradle.kts"))
                || File.Exists(Path.Combine(parent, "CMakeLists.txt"));
        }

        return false;
    }

    private static List<string> GetDefaultStudyRoots()
    {
        var roots = new List<string>();
        string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (!string.IsNullOrWhiteSpace(user) && Directory.Exists(user))
            roots.Add(user);

        // Also include secondary data drives (D:\, E:\, etc.) so projects stored outside C:\ are ALWAYS found!
        string sysDrive = Path.GetPathRoot(Environment.SystemDirectory) ?? @"C:\";
        foreach (var d in FullDiskExplorerScanner.GetLocalDrives())
        {
            if (!d.Name.Equals(sysDrive, StringComparison.OrdinalIgnoreCase) && Directory.Exists(d.Name))
                roots.Add(d.Name);
        }
        return roots;
    }

    private static DateTime SafeDate(Func<DateTime> fn)
    {
        try { return fn(); } catch { return DateTime.MinValue; }
    }
}

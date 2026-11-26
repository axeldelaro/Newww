using System.Collections.Concurrent;
using System.Diagnostics;
using CleanForge.Core.Models;
using CleanForge.Core.Safety;

namespace CleanForge.Core.Scanners;

public sealed class FullDiskExplorerScanner
{
    private const int MaxIndexedFilesCap = 250_000;
    private const int TopLargestFilesCount = 250;

    public static List<DriveSummary> GetLocalDrives()
    {
        var drives = new List<DriveSummary>();
        foreach (var d in DriveInfo.GetDrives())
        {
            try
            {
                if (!d.IsReady) continue;
                if (d.DriveType != DriveType.Fixed && d.DriveType != DriveType.Removable) continue;
                drives.Add(new DriveSummary
                {
                    Name = d.Name,
                    VolumeLabel = SafeGet(() => d.VolumeLabel, ""),
                    DriveFormat = SafeGet(() => d.DriveFormat, ""),
                    TotalBytes = d.TotalSize,
                    FreeBytes = d.AvailableFreeSpace
                });
            }
            catch
            {
                // Skip unavailable drives gracefully
            }
        }
        return drives;
    }

    public FullDiskScanResult ScanAllDrives(
        IEnumerable<string>? customRoots = null,
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var sw = Stopwatch.StartNew();
        var result = new FullDiskScanResult();
        result.Drives.AddRange(GetLocalDrives());

        var roots = (customRoots != null && customRoots.Any())
            ? customRoots.Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase).ToList()
            : result.Drives.Select(d => d.Name).Where(Directory.Exists).ToList();

        long scannedFiles = 0;
        long scannedFolders = 0;
        long scannedBytes = 0;
        int skipped = 0;

        var indexedBag = new ConcurrentBag<FileNode>();
        var extMap = new ConcurrentDictionary<string, (int Count, long Bytes)>(StringComparer.OrdinalIgnoreCase);

        for (int r = 0; r < roots.Count; r++)
        {
            if (ct.IsCancellationRequested) break;
            string rootPath = roots[r];

            progress?.Report(new ScanProgress
            {
                Stage = $"Analyse complète du disque {rootPath} ({r + 1}/{roots.Count})...",
                CurrentPath = rootPath,
                Percent = (double)r / Math.Max(1, roots.Count) * 90.0,
                FilesScanned = Interlocked.Read(ref scannedFiles),
                BytesFound = Interlocked.Read(ref scannedBytes)
            });

            var rootNode = ScanRootParallel(
                rootPath,
                indexedBag,
                extMap,
                ref scannedFiles,
                ref scannedFolders,
                ref scannedBytes,
                ref skipped,
                progress,
                r,
                roots.Count,
                ct);

            result.RootNodes.Add(rootNode);
        }

        var allFiles = indexedBag.OrderByDescending(f => f.SizeBytes).ToList();
        if (allFiles.Count > MaxIndexedFilesCap)
        {
            result.AllIndexedFiles.AddRange(allFiles.Take(MaxIndexedFilesCap));
        }
        else
        {
            result.AllIndexedFiles.AddRange(allFiles);
        }

        result.LargestFiles.AddRange(allFiles.Where(f => !f.IsDirectory).Take(TopLargestFilesCount));

        foreach (var kv in extMap.OrderByDescending(x => x.Value.Bytes).Take(30))
        {
            result.ExtensionStats.Add(new ExtensionStat
            {
                Extension = string.IsNullOrEmpty(kv.Key) ? "(sans ext.)" : kv.Key,
                CategoryLabel = ClassifyExtension(kv.Key),
                FileCount = kv.Value.Count,
                TotalBytes = kv.Value.Bytes
            });
        }

        result.TotalScannedFiles = scannedFiles;
        result.TotalScannedFolders = scannedFolders;
        result.TotalScannedBytes = scannedBytes;
        result.SkippedProtectedOrDenied = skipped;
        result.Elapsed = sw.Elapsed;

        progress?.Report(new ScanProgress
        {
            Stage = "Cartographie disque terminée",
            Percent = 100.0,
            FilesScanned = scannedFiles,
            BytesFound = scannedBytes
        });

        return result;
    }

    private static FileNode ScanRootParallel(
        string rootPath,
        ConcurrentBag<FileNode> indexedBag,
        ConcurrentDictionary<string, (int Count, long Bytes)> extMap,
        ref long scannedFiles,
        ref long scannedFolders,
        ref long scannedBytes,
        ref int skipped,
        IProgress<ScanProgress>? progress,
        int rootIndex,
        int rootCount,
        CancellationToken ct)
    {
        var rootNode = new FileNode
        {
            Name = rootPath,
            FullPath = rootPath,
            IsDirectory = true,
            IsProtected = !SafetyGuard.EvaluatePathForDeletion(rootPath, isDirectory: true).IsAllowed
        };

        var enumOpts = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            RecurseSubdirectories = false,
            ReturnSpecialDirectories = false,
            AttributesToSkip = FileAttributes.ReparsePoint
        };

        List<string> topDirs = [];
        try { topDirs = Directory.EnumerateDirectories(rootPath, "*", enumOpts).ToList(); }
        catch { Interlocked.Increment(ref skipped); }

        // Direct root files
        try
        {
            foreach (string file in Directory.EnumerateFiles(rootPath, "*", enumOpts))
            {
                if (ct.IsCancellationRequested) break;
                var fNode = TryInspectFile(file, extMap, ref scannedFiles, ref scannedBytes, ref skipped);
                if (fNode != null)
                {
                    rootNode.Children.Add(fNode);
                    rootNode.SizeBytes += fNode.SizeBytes;
                    rootNode.FileCount++;
                    if (fNode.SizeBytes >= 64 * 1024 || indexedBag.Count < 100_000)
                        indexedBag.Add(fNode);
                }
            }
        }
        catch { Interlocked.Increment(ref skipped); }

        // Scan top-level subdirectories in parallel across CPU cores!
        var childNodes = new ConcurrentBag<FileNode>();
        int dirsDone = 0;
        long localFiles = 0;
        long localFolders = 0;
        long localBytes = 0;
        int localSkipped = 0;

        var parallelOpts = new ParallelOptions
        {
            MaxDegreeOfParallelism = Math.Clamp(Environment.ProcessorCount, 2, 8),
            CancellationToken = ct
        };

        try
        {
            Parallel.ForEach(topDirs, parallelOpts, dir =>
            {
                if (!SafetyGuard.CanTraverseDirectory(dir))
                {
                    Interlocked.Increment(ref localSkipped);
                    return;
                }

                long fCount = 0, dCount = 0, bTotal = 0;
                int sk = 0;
                var subNode = ScanDirectoryIterative(dir, depth: 1, indexedBag, extMap, ref fCount, ref dCount, ref bTotal, ref sk, ct);
                childNodes.Add(subNode);

                Interlocked.Add(ref localFiles, fCount);
                Interlocked.Add(ref localFolders, dCount);
                Interlocked.Add(ref localBytes, bTotal);
                Interlocked.Add(ref localSkipped, sk);

                int done = Interlocked.Increment(ref dirsDone);
                if ((done & 3) == 0 || done == topDirs.Count)
                {
                    double basePct = (double)rootIndex / Math.Max(1, rootCount) * 90.0;
                    double spanPct = 90.0 / Math.Max(1, rootCount);
                    progress?.Report(new ScanProgress
                    {
                        Stage = $"Exploration de {rootPath} ({done}/{topDirs.Count} dossiers racine)...",
                        CurrentPath = dir,
                        Percent = basePct + spanPct * ((double)done / Math.Max(1, topDirs.Count)),
                        FilesScanned = Interlocked.Read(ref localFiles),
                        BytesFound = Interlocked.Read(ref localBytes)
                    });
                }
            });
        }
        catch (OperationCanceledException) { }

        Interlocked.Add(ref scannedFiles, localFiles);
        Interlocked.Add(ref scannedFolders, localFolders + 1);
        Interlocked.Add(ref scannedBytes, localBytes);
        Interlocked.Add(ref skipped, localSkipped);

        foreach (var child in childNodes.OrderByDescending(c => c.SizeBytes))
        {
            rootNode.Children.Add(child);
            rootNode.SizeBytes += child.SizeBytes;
            rootNode.FileCount += child.FileCount;
        }

        SortAndComputePercentages(rootNode);
        return rootNode;
    }

    private static FileNode ScanDirectoryIterative(
        string dirPath,
        int depth,
        ConcurrentBag<FileNode> indexedBag,
        ConcurrentDictionary<string, (int Count, long Bytes)> extMap,
        ref long filesScanned,
        ref long foldersScanned,
        ref long bytesScanned,
        ref int skipped,
        CancellationToken ct)
    {
        foldersScanned++;
        var dirInfo = new DirectoryInfo(dirPath);
        var node = new FileNode
        {
            Name = dirInfo.Name,
            FullPath = dirPath,
            IsDirectory = true,
            LastWriteUtc = SafeGet(() => dirInfo.LastWriteTimeUtc, DateTime.MinValue),
            LastAccessUtc = SafeGet(() => dirInfo.LastAccessTimeUtc, DateTime.MinValue),
            IsProtected = !SafetyGuard.EvaluatePathForDeletion(dirPath, isDirectory: true).IsAllowed
        };

        if (ct.IsCancellationRequested) return node;

        var enumOpts = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            RecurseSubdirectories = false,
            ReturnSpecialDirectories = false,
            AttributesToSkip = FileAttributes.ReparsePoint
        };

        // Keep full interactive tree up to depth 5 (ultra responsive in WPF TreeView + instant drilldown)
        const int MaxTreeDepth = 5;

        try
        {
            foreach (string file in Directory.EnumerateFiles(dirPath, "*", enumOpts))
            {
                if (ct.IsCancellationRequested) break;
                var fNode = TryInspectFile(file, extMap, ref filesScanned, ref bytesScanned, ref skipped);
                if (fNode != null)
                {
                    node.SizeBytes += fNode.SizeBytes;
                    node.FileCount++;
                    if (depth <= MaxTreeDepth)
                    {
                        node.Children.Add(fNode);
                    }
                    if (fNode.SizeBytes >= 64 * 1024 || indexedBag.Count < 100_000)
                    {
                        indexedBag.Add(fNode);
                    }
                }
            }
        }
        catch { skipped++; }

        try
        {
            foreach (string subDir in Directory.EnumerateDirectories(dirPath, "*", enumOpts))
            {
                if (ct.IsCancellationRequested) break;
                if (!SafetyGuard.CanTraverseDirectory(subDir))
                {
                    skipped++;
                    continue;
                }

                var child = ScanDirectoryIterative(subDir, depth + 1, indexedBag, extMap, ref filesScanned, ref foldersScanned, ref bytesScanned, ref skipped, ct);
                node.SizeBytes += child.SizeBytes;
                node.FileCount += child.FileCount;
                if (depth < MaxTreeDepth)
                {
                    node.Children.Add(child);
                }
            }
        }
        catch { skipped++; }

        if (depth <= MaxTreeDepth && node.SizeBytes >= 1024 * 1024)
        {
            indexedBag.Add(node);
        }

        SortAndComputePercentages(node);
        return node;
    }

    private static FileNode? TryInspectFile(
        string filePath,
        ConcurrentDictionary<string, (int Count, long Bytes)> extMap,
        ref long filesScanned,
        ref long bytesScanned,
        ref int skipped)
    {
        try
        {
            var fi = new FileInfo(filePath);
            if (SafetyGuard.IsReparsePointOrCloudStub(fi.Attributes))
            {
                skipped++;
                return null;
            }

            long len = fi.Length;
            string ext = fi.Extension.ToLowerInvariant();
            filesScanned++;
            bytesScanned += len;

            extMap.AddOrUpdate(ext, (1, len), (_, prev) => (prev.Count + 1, prev.Bytes + len));

            return new FileNode
            {
                Name = fi.Name,
                FullPath = filePath,
                IsDirectory = false,
                SizeBytes = len,
                FileCount = 1,
                Extension = ext,
                LastWriteUtc = fi.LastWriteTimeUtc,
                LastAccessUtc = fi.LastAccessTimeUtc,
                IsProtected = !SafetyGuard.EvaluatePathForDeletion(filePath, isDirectory: false).IsAllowed
            };
        }
        catch
        {
            skipped++;
            return null;
        }
    }

    private static void SortAndComputePercentages(FileNode parent)
    {
        if (parent.Children.Count == 0) return;
        parent.Children.Sort((a, b) => b.SizeBytes.CompareTo(a.SizeBytes));
        long denom = Math.Max(1, parent.SizeBytes);
        foreach (var child in parent.Children)
        {
            child.PercentOfParent = (double)child.SizeBytes / denom * 100.0;
        }
    }

    /// <summary>
    /// Instant in-memory search across the entire indexed PC (name, path, extension, minimum size).
    /// </summary>
    public static List<FileNode> SearchGlobal(
        FullDiskScanResult scan,
        string? query,
        string? extensionFilter = null,
        long minSizeBytes = 0,
        bool includeFolders = true,
        int maxResults = 300)
    {
        string q = (query ?? "").Trim();
        string ext = (extensionFilter ?? "").Trim().ToLowerInvariant();
        if (!string.IsNullOrEmpty(ext) && !ext.StartsWith('.')) ext = "." + ext;

        return scan.AllIndexedFiles
            .Where(n => (includeFolders || !n.IsDirectory))
            .Where(n => n.SizeBytes >= minSizeBytes)
            .Where(n => string.IsNullOrEmpty(ext) || n.Extension.Equals(ext, StringComparison.OrdinalIgnoreCase))
            .Where(n => string.IsNullOrEmpty(q)
                || n.Name.Contains(q, StringComparison.OrdinalIgnoreCase)
                || n.FullPath.Contains(q, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(n => n.SizeBytes)
            .Take(maxResults)
            .ToList();
    }

    public static string ClassifyExtension(string ext) => ext.ToLowerInvariant() switch
    {
        ".mp4" or ".mkv" or ".avi" or ".mov" or ".wmv" or ".webm" or ".flv" => "Vidéos",
        ".zip" or ".rar" or ".7z" or ".tar" or ".gz" or ".iso" or ".img" => "Archives & ISO",
        ".exe" or ".msi" or ".msix" or ".cab" or ".bat" => "Exécutables & Installeurs",
        ".pdf" or ".docx" or ".doc" or ".pptx" or ".ppt" or ".xlsx" or ".xls" or ".odt" => "Documents / Études",
        ".jpg" or ".jpeg" or ".png" or ".webp" or ".bmp" or ".raw" or ".dng" or ".psd" or ".heic" => "Images & Photo",
        ".mp3" or ".wav" or ".flac" or ".m4a" or ".ogg" or ".aac" => "Audio & Musique",
        ".vmdk" or ".vdi" or ".vhdx" or ".ova" or ".qcow2" => "Machines Virtuelles",
        ".dll" or ".pdb" or ".obj" or ".lib" or ".jar" or ".class" or ".pyc" => "Build / Dev",
        _ => "Autres"
    };

    private static T SafeGet<T>(Func<T> getter, T fallback)
    {
        try { return getter(); } catch { return fallback; }
    }
}

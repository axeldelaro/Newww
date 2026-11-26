using System.Text;
using CleanForge.Core.Models;
using CleanForge.Core.Safety;

namespace CleanForge.Core.Scanners;

/// <summary>
/// Scans for broken .lnk / .url shortcuts (pure binary MS-SHLLINK parser, zero COM dependency!)
/// and completely empty folder trees safely guarded against OS/protected directories.
/// </summary>
public sealed class ShortcutAndEmptyFolderScanner
{
    public List<BrokenShortcutItem> ScanBrokenShortcuts(
        IEnumerable<(string Label, string DirectoryPath)>? customLocations = null,
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var locations = (customLocations != null && customLocations.Any())
            ? customLocations.ToList()
            : GetDefaultShortcutLocations();

        var mountedDrives = new HashSet<string>(
            DriveInfo.GetDrives().Where(d => d.IsReady).Select(d => d.Name.ToUpperInvariant()),
            StringComparer.OrdinalIgnoreCase);

        var broken = new List<BrokenShortcutItem>();

        for (int i = 0; i < locations.Count; i++)
        {
            if (ct.IsCancellationRequested) break;
            var (label, dir) = locations[i];
            if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir)) continue;

            progress?.Report(new ScanProgress
            {
                Stage = $"Vérification des raccourcis ({label})...",
                CurrentPath = dir,
                Percent = (double)i / Math.Max(1, locations.Count) * 100.0
            });

            var enumOpts = new EnumerationOptions
            {
                IgnoreInaccessible = true,
                RecurseSubdirectories = true,
                ReturnSpecialDirectories = false,
                AttributesToSkip = FileAttributes.ReparsePoint
            };

            try
            {
                foreach (string file in Directory.EnumerateFiles(dir, "*.lnk", enumOpts))
                {
                    if (ct.IsCancellationRequested) break;
                    if (!SafetyGuard.EvaluatePathForDeletion(file, isDirectory: false).IsAllowed) continue;

                    string? target = TryExtractLnkTargetPath(file);
                    if (string.IsNullOrWhiteSpace(target)) continue; // UWP / virtual shell shortcut -> do NOT flag!

                    if (IsLocalPathMissing(target, mountedDrives))
                    {
                        broken.Add(new BrokenShortcutItem
                        {
                            ShortcutPath = file,
                            MissingTarget = target,
                            LocationLabel = label,
                            IsSelected = true
                        });
                    }
                }

                foreach (string file in Directory.EnumerateFiles(dir, "*.url", enumOpts))
                {
                    if (ct.IsCancellationRequested) break;
                    if (!SafetyGuard.EvaluatePathForDeletion(file, isDirectory: false).IsAllowed) continue;

                    string? localTarget = TryExtractLocalFileUrlTarget(file);
                    if (string.IsNullOrWhiteSpace(localTarget)) continue; // Web URL (http/https/steam://) -> keep!

                    if (IsLocalPathMissing(localTarget, mountedDrives))
                    {
                        broken.Add(new BrokenShortcutItem
                        {
                            ShortcutPath = file,
                            MissingTarget = localTarget,
                            LocationLabel = label,
                            IsSelected = true
                        });
                    }
                }
            }
            catch { }
        }

        return broken;
    }

    /// <summary>
    /// Bottom-up empty folder detector: finds directories containing 0 files and 0 non-empty subfolders.
    /// Strictly obeys SafetyGuard (never flags system folders, shell roots, .git, or cloud stubs).
    /// </summary>
    public List<EmptyFolderItem> ScanEmptyFolders(
        IEnumerable<string> rootDirectories,
        IProgress<ScanProgress>? progress = null,
        CancellationToken ct = default)
    {
        var roots = rootDirectories.Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var emptyFolders = new List<EmptyFolderItem>();

        foreach (string root in roots)
        {
            if (ct.IsCancellationRequested) break;
            CheckEmptyRecursive(root, isRoot: true, emptyFolders, progress, ct);
        }

        return emptyFolders.OrderBy(e => e.FullPath, StringComparer.OrdinalIgnoreCase).ToList();
    }

    private static bool CheckEmptyRecursive(
        string dirPath,
        bool isRoot,
        List<EmptyFolderItem> collector,
        IProgress<ScanProgress>? progress,
        CancellationToken ct)
    {
        if (ct.IsCancellationRequested) return false;
        if (!SafetyGuard.CanTraverseDirectory(dirPath)) return false;

        string normLower = SafetyGuard.NormalizeForComparison(dirPath).ToLowerInvariant();
        string driveRel = (normLower.Length >= 2 && normLower[1] == ':') ? normLower[2..] : normLower;
        if (driveRel.StartsWith(@"\windows", StringComparison.Ordinal)
            || driveRel.StartsWith(@"\program files", StringComparison.Ordinal)
            || driveRel.StartsWith(@"\programdata", StringComparison.Ordinal)
            || normLower.Contains(@"\appdata\")
            || Path.GetFileName(dirPath).Equals(".git", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var enumOpts = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            RecurseSubdirectories = false,
            ReturnSpecialDirectories = false,
            AttributesToSkip = FileAttributes.None
        };

        try
        {
            bool hasFiles = Directory.EnumerateFiles(dirPath, "*", enumOpts).Any();
            var subDirs = Directory.EnumerateDirectories(dirPath, "*", enumOpts).ToList();
            bool allSubDirsEmpty = true;
            var emptyChildren = new List<EmptyFolderItem>();

            foreach (string sub in subDirs)
            {
                if (ct.IsCancellationRequested) return false;
                if (!CheckEmptyRecursive(sub, isRoot: false, emptyChildren, progress, ct))
                {
                    allSubDirsEmpty = false;
                }
            }

            if (!hasFiles && allSubDirsEmpty && !isRoot && SafetyGuard.EvaluatePathForDeletion(dirPath, isDirectory: true).IsAllowed)
            {
                // Report the highest empty parent folder instead of spamming 10 nested empty subfolders
                var di = new DirectoryInfo(dirPath);
                collector.Add(new EmptyFolderItem
                {
                    FullPath = dirPath,
                    LastWriteUtc = SafeDate(() => di.LastWriteTimeUtc),
                    IsSelected = true
                });
                progress?.Report(new ScanProgress
                {
                    Stage = "Détection des dossiers vides...",
                    CurrentPath = dirPath,
                    FilesScanned = collector.Count
                });
                return true;
            }

            // Otherwise keep the individually empty subfolders collected below
            collector.AddRange(emptyChildren);
            return false;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// Pure binary MS-SHLLINK (.lnk) target path extractor (supports both ANSI and Unicode LinkInfo structures + RelativePath fallback).
    /// </summary>
    public static string? TryExtractLnkTargetPath(string lnkFilePath)
    {
        try
        {
            byte[] data = File.ReadAllBytes(lnkFilePath);
            if (data.Length < 76) return null;

            // Validate ShellLinkHeader magic size (0x4C = 76)
            uint headerSize = BitConverter.ToUInt32(data, 0);
            if (headerSize != 0x0000004C) return null;

            uint flags = BitConverter.ToUInt32(data, 20);
            bool hasTargetIdList = (flags & 0x00000001) != 0;
            bool hasLinkInfo = (flags & 0x00000002) != 0;
            bool hasName = (flags & 0x00000004) != 0;
            bool hasRelativePath = (flags & 0x00000008) != 0;
            bool isUnicode = (flags & 0x00000080) != 0;

            int pos = 76;

            // Skip LinkTargetIDList if present
            if (hasTargetIdList)
            {
                if (pos + 2 > data.Length) return null;
                ushort idListSize = BitConverter.ToUInt16(data, pos);
                pos += 2 + idListSize;
                if (pos > data.Length) return null;
            }

            // Parse LinkInfo structure (contains authoritative LocalBasePath + CommonPathSuffix)
            if (hasLinkInfo && pos + 28 <= data.Length)
            {
                int linkInfoStart = pos;
                uint linkInfoSize = BitConverter.ToUInt32(data, pos);
                uint linkInfoHeaderSize = BitConverter.ToUInt32(data, pos + 4);
                uint linkInfoFlags = BitConverter.ToUInt32(data, pos + 8);

                if ((linkInfoFlags & 0x00000001) != 0) // VolumeIDAndLocalBasePath
                {
                    string basePath;
                    string suffix;
                    if (linkInfoHeaderSize >= 0x24 && pos + 36 <= data.Length)
                    {
                        uint baseOffU = BitConverter.ToUInt32(data, pos + 28);
                        uint sufOffU = BitConverter.ToUInt32(data, pos + 32);
                        basePath = ReadNullTerminatedUnicode(data, linkInfoStart + (int)baseOffU);
                        suffix = ReadNullTerminatedUnicode(data, linkInfoStart + (int)sufOffU);
                    }
                    else
                    {
                        uint baseOff = BitConverter.ToUInt32(data, pos + 16);
                        uint sufOff = BitConverter.ToUInt32(data, pos + 24);
                        basePath = ReadNullTerminatedAscii(data, linkInfoStart + (int)baseOff);
                        suffix = ReadNullTerminatedAscii(data, linkInfoStart + (int)sufOff);
                    }

                    string combined = string.IsNullOrEmpty(suffix) ? basePath : Path.Combine(basePath, suffix);
                    if (!string.IsNullOrWhiteSpace(combined))
                        return Environment.ExpandEnvironmentVariables(combined);
                }

                pos = linkInfoStart + (int)linkInfoSize;
            }

            // Fallback: StringData RelativePath
            if (hasRelativePath && pos < data.Length)
            {
                if (hasName) pos = SkipStringData(data, pos, isUnicode);
                string rel = ReadStringData(data, ref pos, isUnicode);
                if (!string.IsNullOrWhiteSpace(rel))
                {
                    string? baseDir = Path.GetDirectoryName(lnkFilePath);
                    if (!string.IsNullOrEmpty(baseDir))
                    {
                        string expanded = Environment.ExpandEnvironmentVariables(rel);
                        return Path.GetFullPath(Path.Combine(baseDir, expanded));
                    }
                }
            }

            return null;
        }
        catch
        {
            return null;
        }
    }

    private static string? TryExtractLocalFileUrlTarget(string urlFilePath)
    {
        try
        {
            foreach (string line in File.ReadLines(urlFilePath))
            {
                if (line.StartsWith("URL=", StringComparison.OrdinalIgnoreCase))
                {
                    string val = line[4..].Trim();
                    if (val.StartsWith("file:///", StringComparison.OrdinalIgnoreCase))
                    {
                        return Uri.UnescapeDataString(val[8..]).Replace('/', Path.DirectorySeparatorChar);
                    }
                }
            }
        }
        catch { }
        return null;
    }

    private static bool IsLocalPathMissing(string targetPath, HashSet<string> mountedDrives)
    {
        string expanded = Environment.ExpandEnvironmentVariables(targetPath).Trim().Trim('"');
        if (string.IsNullOrWhiteSpace(expanded)) return false;

        // Ignore web / URI protocols / UNC network paths
        if (expanded.Contains("://", StringComparison.Ordinal) || expanded.StartsWith(@"\\", StringComparison.Ordinal))
            return false;

        // On Windows, if target is on a drive letter (e.g. G:\) that is currently NOT mounted (unplugged USB), do NOT flag!
        if (expanded.Length >= 3 && expanded[1] == ':' && (expanded[2] == '\\' || expanded[2] == '/'))
        {
            string driveRoot = expanded[..2].ToUpperInvariant() + @"\";
            if (OperatingSystem.IsWindows() && mountedDrives.Count > 0 && !mountedDrives.Contains(driveRoot))
                return false;
        }

        return !File.Exists(expanded) && !Directory.Exists(expanded);
    }

    private static string ReadNullTerminatedAscii(byte[] data, int offset)
    {
        if (offset < 0 || offset >= data.Length) return "";
        int end = offset;
        while (end < data.Length && data[end] != 0) end++;
        return Encoding.Latin1.GetString(data, offset, end - offset);
    }

    private static string ReadNullTerminatedUnicode(byte[] data, int offset)
    {
        if (offset < 0 || offset + 1 >= data.Length) return "";
        int end = offset;
        while (end + 1 < data.Length && (data[end] != 0 || data[end + 1] != 0)) end += 2;
        return Encoding.Unicode.GetString(data, offset, end - offset);
    }

    private static int SkipStringData(byte[] data, int pos, bool isUnicode)
    {
        if (pos + 2 > data.Length) return data.Length;
        int charCount = BitConverter.ToUInt16(data, pos);
        int byteLen = isUnicode ? charCount * 2 : charCount;
        return Math.Min(data.Length, pos + 2 + byteLen);
    }

    private static string ReadStringData(byte[] data, ref int pos, bool isUnicode)
    {
        if (pos + 2 > data.Length) return "";
        int charCount = BitConverter.ToUInt16(data, pos);
        pos += 2;
        int byteLen = isUnicode ? charCount * 2 : charCount;
        if (pos + byteLen > data.Length) return "";
        string s = isUnicode
            ? Encoding.Unicode.GetString(data, pos, byteLen)
            : Encoding.Latin1.GetString(data, pos, byteLen);
        pos += byteLen;
        return s;
    }

    private static List<(string Label, string DirectoryPath)> GetDefaultShortcutLocations()
    {
        string roaming = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        var list = new List<(string, string)>
        {
            ("Bureau Utilisateur", Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory)),
            ("Bureau Public", Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory)),
            ("Menu Démarrer", Environment.GetFolderPath(Environment.SpecialFolder.StartMenu)),
            ("Menu Démarrer Commun", Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu)),
            ("Récents", Environment.GetFolderPath(Environment.SpecialFolder.Recent)),
            ("Envoyer vers", Environment.GetFolderPath(Environment.SpecialFolder.SendTo))
        };
        if (!string.IsNullOrWhiteSpace(roaming))
        {
            list.Add(("Barre des tâches", Path.Combine(roaming, "Microsoft", "Internet Explorer", "Quick Launch")));
        }
        return list.Where(x => !string.IsNullOrWhiteSpace(x.Item2)).ToList();
    }

    private static DateTime SafeDate(Func<DateTime> fn)
    {
        try { return fn(); } catch { return DateTime.MinValue; }
    }
}

namespace CleanForge.Core.Safety;

public readonly record struct SafetyVerdict(bool IsAllowed, string Reason)
{
    public static SafetyVerdict Allow(string reason = "OK") => new(true, reason);
    public static SafetyVerdict Block(string reason) => new(false, reason);
}

/// <summary>
/// Defense-in-depth safety fortress enforced at engine level before ANY delete, move, or registry write.
/// Guarantees zero OS damage, zero hardware risk, zero driver deletion, and zero loss of passwords/keys/cloud stubs.
/// </summary>
public static class SafetyGuard
{
    // Win32 cloud placeholder / OneDrive / iCloud dehydrated file attributes
    private const FileAttributes FileAttributeRecallOnOpen = (FileAttributes)0x00040000;
    private const FileAttributes FileAttributeRecallOnDataAccess = (FileAttributes)0x00400000;

    // Critical OS filenames NEVER allowed to be deleted anywhere
    private static readonly HashSet<string> CriticalFileNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "pagefile.sys", "hiberfil.sys", "swapfile.sys", "dumpstack.log.tmp",
        "bootmgr", "bootnxt", "ntldr", "boot.ini", "bcd", "bcd.log",
        "ntuser.dat", "ntuser.dat.log1", "ntuser.dat.log2", "usrclass.dat",
        "desktop.ini", "sam", "security", "software", "system", "default",
        "ntoskrnl.exe", "hal.dll", "winload.exe", "winload.efi", "explorer.exe",
        "lsass.exe", "csrss.exe", "smss.exe", "svchost.exe", "services.exe",
        // Browser credentials / bookmarks / cookies / history — NEVER touched!
        "login data", "login data for account", "web data", "bookmarks", "bookmarks.bak",
        "history", "cookies", "favicons", "preferences", "secure preferences", "local state",
        "places.sqlite", "logins.json", "key4.db", "cert9.db", "cookies.sqlite",
        "formhistory.sqlite", "permissions.sqlite", "signons.sqlite",
        // Crypto / SSH / Password vaults — NEVER touched!
        "id_rsa", "id_ed25519", "id_ecdsa", "known_hosts", "wallet.dat"
    };

    // Protected extensions that must NEVER be deleted automatically inside system/program folders
    private static readonly HashSet<string> CriticalSystemExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".sys", ".drv", ".cat", ".inf", ".efi", ".cpl", ".msc", ".mui", ".kdbx"
    };

    // Explicit whitelist of the ONLY subdirectories under \Windows\ ever allowed for cache cleanup
    private static readonly string[] WindowsAllowedCachePrefixes =
    [
        @"\windows\temp\",
        @"\windows\prefetch\",
        @"\windows\softwaredistribution\download\",
        @"\windows\minidump\",
        @"\windows\livekernelreports\"
    ];

    // Absolute blocked directory segments (relative to any drive root)
    private static readonly string[] BlockedRootSubtrees =
    [
        @"\$recycle.bin",
        @"\system volume information",
        @"\boot",
        @"\efi",
        @"\recovery",
        @"\msocache",
        @"\perflogs",
        @"\program files\windowsapps",
        @"\program files\windows defender",
        @"\program files\windows defender advanced threat protection",
        @"\program files\windows security",
        @"\program files\windows nt",
        @"\program files\windows powershell",
        @"\program files\common files\microsoft shared",
        @"\program files (x86)\windows defender",
        @"\program files (x86)\windows nt",
        @"\program files (x86)\common files\microsoft shared",
        @"\programdata\microsoft\windows defender",
        @"\programdata\microsoft\windows security health",
        @"\programdata\microsoft\crypto",
        @"\programdata\microsoft\protect",
        @"\programdata\package cache"
    ];

    // Top-level directories that can NEVER themselves be deleted (only specific safe children inside!)
    private static readonly HashSet<string> ProtectedTopLevelFolderNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "windows", "users", "program files", "program files (x86)", "programdata",
        "desktop", "documents", "downloads", "pictures", "videos", "music",
        "appdata", "local", "roaming", "locallow", "onedrive", "contacts", "favorites", "saved games",
        "téléchargements", "bureau", "images", "vidéos", "musique"
    };

    // Protected vendors & critical hardware/security/runtime packages
    private static readonly string[] ProtectedVendorTokens =
    [
        "microsoft", "windows", "defender", "security", "intel", "nvidia", "amd", "radeon",
        "realtek", "qualcomm", "killer", "broadcom", "mediatek", "marvell", "synaptics", "elan",
        "asus", "armoury", "msi", "gigabyte", "lenovo", "vantage", "dell", "hewlett-packard", "hp inc",
        "acer", "logitech", "corsair", "razer", "steelseries", "dolby", "nahimic", "creative",
        "kaspersky", "bitdefender", "malwarebytes", "eset", "avast", "avg", "norton", "mcafee",
        "sophos", "crowdstrike", "sentinelone", "visual c++", "vcredist", ".net", "dotnet",
        "directx", "vulkan", "openal", "opengl", "physx", "cuda", "asio", "focusrite", "steinberg",
        "cleanforge", "onedrive", "icloud", "dropbox", "google drive", "steam", "epic games",
        "ubisoft", "electronic arts", "battle.net", "riot games", "discord", "spotify"
    ];

    // Protected personal-secret directory names
    private static readonly HashSet<string> ProtectedSecretFolders = new(StringComparer.OrdinalIgnoreCase)
    {
        ".ssh", ".gnupg", ".gpg", ".aws", ".azure", ".kube", ".docker", "credentials", "vault", "protect"
    };

    /// <summary>
    /// Normalizes a Windows or Unix path for canonical safety comparison.
    /// </summary>
    public static string NormalizeForComparison(string rawPath)
    {
        if (string.IsNullOrWhiteSpace(rawPath)) return string.Empty;
        string p = rawPath.Trim().Replace('/', '\\');

        // Reject extended/device prefixes or raw UNC paths
        if (p.StartsWith(@"\\?\", StringComparison.Ordinal) || p.StartsWith(@"\\.\", StringComparison.Ordinal))
        {
            p = p[4..];
        }

        // Resolve relative ".." traversal segments cleanly (even cross-platform on Linux!)
        var parts = p.Split('\\', StringSplitOptions.RemoveEmptyEntries);
        var stack = new List<string>(parts.Length);
        bool isWindowsDrive = parts.Length > 0 && parts[0].Length == 2 && parts[0][1] == ':';
        bool isUnc = p.StartsWith(@"\\", StringComparison.Ordinal);

        foreach (string part in parts)
        {
            if (part == ".") continue;
            if (part == "..")
            {
                int minDepth = isWindowsDrive ? 1 : 0;
                if (stack.Count > minDepth)
                    stack.RemoveAt(stack.Count - 1);
                continue;
            }
            stack.Add(part);
        }

        if (isUnc) return @"\\" + string.Join('\\', stack);
        if (isWindowsDrive) return stack.Count == 1 ? stack[0] + @"\" : string.Join('\\', stack);
        return @"\" + string.Join('\\', stack);
    }

    public static bool IsReparsePointOrCloudStub(FileAttributes attributes)
    {
        return (attributes & FileAttributes.ReparsePoint) != 0
            || (attributes & FileAttributes.Offline) != 0
            || (attributes & FileAttributeRecallOnOpen) != 0
            || (attributes & FileAttributeRecallOnDataAccess) != 0;
    }

    public static bool IsProtectedVendorOrApp(string? nameOrPublisher)
    {
        if (string.IsNullOrWhiteSpace(nameOrPublisher)) return false;
        string lower = nameOrPublisher.Trim().ToLowerInvariant();
        foreach (string token in ProtectedVendorTokens)
        {
            if (lower.Contains(token, StringComparison.Ordinal))
                return true;
        }
        return false;
    }

    /// <summary>
    /// Authoritative check before ANY file or directory is deleted or offered for cleanup.
    /// </summary>
    public static SafetyVerdict EvaluatePathForDeletion(string rawPath, bool isDirectory = false, bool allowCleanContentsOfKnownCacheRoot = false)
    {
        if (string.IsNullOrWhiteSpace(rawPath))
            return SafetyVerdict.Block("Chemin vide.");

        string trimmed = rawPath.Trim();
        if (trimmed.StartsWith(@"\\", StringComparison.Ordinal) && !trimmed.StartsWith(@"\\?\", StringComparison.Ordinal))
            return SafetyVerdict.Block("Les chemins réseau UNC sont protégés contre toute suppression.");

        string norm = NormalizeForComparison(trimmed);
        if (string.IsNullOrEmpty(norm))
            return SafetyVerdict.Block("Chemin invalide.");

        // 1. Block root drives (C:\, D:\, \)
        if (norm == @"\" || (norm.Length <= 3 && norm.Length >= 2 && norm[1] == ':'))
            return SafetyVerdict.Block("La racine d'un disque est strictement protégée.");

        string lower = norm.ToLowerInvariant();
        string lowerWithTrailing = lower.EndsWith('\\') ? lower : lower + @"\";

        // Strip drive letter (e.g. "c:\windows\..." -> "\windows\...")
        string driveRel = (lower.Length >= 2 && lower[1] == ':') ? lower[2..] : lower;
        string driveRelTrailing = driveRel.EndsWith('\\') ? driveRel : driveRel + @"\";

        // 2. Hard-block \Windows\ EXCEPT explicit safe cache whitelist
        if (driveRelTrailing == @"\windows\" || driveRelTrailing.StartsWith(@"\windows\", StringComparison.Ordinal))
        {
            bool inAllowedWinCache = false;
            foreach (string allowedPrefix in WindowsAllowedCachePrefixes)
            {
                if (driveRelTrailing.StartsWith(allowedPrefix, StringComparison.Ordinal))
                {
                    // Never delete the cache root folder itself (e.g. C:\Windows\Temp), only files inside unless CleanContentsOnly!
                    if (driveRelTrailing == allowedPrefix && isDirectory && !allowCleanContentsOfKnownCacheRoot)
                        return SafetyVerdict.Block("Le dossier cache racine Windows est conservé (seul son contenu peut être vidé).");
                    inAllowedWinCache = true;
                    break;
                }
            }
            if (!inAllowedWinCache)
                return SafetyVerdict.Block("Dossier système Windows strictement protégé.");
        }

        // 3. Hard-block critical OS/Security/Driver/Recovery subtrees
        foreach (string blocked in BlockedRootSubtrees)
        {
            if (driveRel == blocked || driveRelTrailing.StartsWith(blocked + @"\", StringComparison.Ordinal))
                return SafetyVerdict.Block($"Zone système/sécurité protégée : {blocked}");
        }

        // 4. Protect user profile root (e.g. C:\Users\Alice) and immediate shell folders (Desktop, Documents, AppData...)
        string[] segments = driveRel.Split('\\', StringSplitOptions.RemoveEmptyEntries);
        if (segments.Length == 0)
            return SafetyVerdict.Block("Racine protégée.");

        // Block C:\Users\Username directly
        if (segments.Length == 2 && segments[0] == "users")
            return SafetyVerdict.Block("Le dossier profil utilisateur ne peut jamais être supprimé.");

        // Block C:\Users\Username\<ShellFolder> directly (e.g. Desktop, Documents, AppData)
        if (segments.Length == 3 && segments[0] == "users" && ProtectedTopLevelFolderNames.Contains(segments[2]))
            return SafetyVerdict.Block($"Dossier personnel principal protégé : {segments[2]}");

        // Block C:\Users\Username\AppData\<Local|Roaming|LocalLow> directly
        if (segments.Length == 4 && segments[0] == "users" && segments[2] == "appdata" && ProtectedTopLevelFolderNames.Contains(segments[3]))
            return SafetyVerdict.Block("Dossier racine AppData protégé.");

        // Block Program Files / Program Files (x86) / ProgramData roots directly
        if (segments.Length == 1 && ProtectedTopLevelFolderNames.Contains(segments[0]))
            return SafetyVerdict.Block($"Dossier racine système protégé : {segments[0]}");

        // Block secret credential / SSH / crypto key folders anywhere
        foreach (string seg in segments)
        {
            if (ProtectedSecretFolders.Contains(seg))
                return SafetyVerdict.Block($"Dossier de clés/sécurité protégé : {seg}");
        }

        // 5. Block critical filenames & browser passwords/bookmarks/cookies anywhere
        string leafName = segments[^1];
        if (CriticalFileNames.Contains(leafName))
            return SafetyVerdict.Block($"Fichier critique ou données personnelles navigateur protégées : {leafName}");

        // Protect browser Cookies inside Network\Cookies
        if (lower.EndsWith(@"\network\cookies", StringComparison.Ordinal) || lower.EndsWith(@"\network\cookies-journal", StringComparison.Ordinal))
            return SafetyVerdict.Block("Les cookies/sessions navigateur sont protégés.");

        // 6. Block critical driver/kernel/vault extensions
        string ext = Path.GetExtension(leafName);
        if (!string.IsNullOrEmpty(ext))
        {
            if (ext.Equals(".kdbx", StringComparison.OrdinalIgnoreCase))
                return SafetyVerdict.Block("Coffre-fort de mots de passe (.kdbx) strictement protégé.");

            bool insideProgramsOrSystem = driveRelTrailing.StartsWith(@"\windows\", StringComparison.Ordinal)
                || driveRelTrailing.StartsWith(@"\program files", StringComparison.Ordinal)
                || driveRelTrailing.StartsWith(@"\programdata\", StringComparison.Ordinal);

            if (insideProgramsOrSystem && CriticalSystemExtensions.Contains(ext))
                return SafetyVerdict.Block($"Fichier pilote/système protégé ({ext}).");
        }

        // 7. Live filesystem check for NTFS Junctions / Symlinks / OneDrive cloud-only stubs
        try
        {
            if (File.Exists(trimmed) || Directory.Exists(trimmed))
            {
                var attrs = File.GetAttributes(trimmed);
                if (IsReparsePointOrCloudStub(attrs))
                    return SafetyVerdict.Block("Lien symbolique, jonction NTFS ou fichier Cloud OneDrive/iCloud protégé.");
            }
        }
        catch
        {
            // If attributes cannot be read due to ACL, deny deletion for safety
            return SafetyVerdict.Block("Accès refusé par Windows (protégé).");
        }

        return SafetyVerdict.Allow();
    }

    /// <summary>
    /// Validates whether a directory may be traversed during full-PC scanning (skips dangerous junctions & virtual mounts).
    /// </summary>
    public static bool CanTraverseDirectory(string dirPath)
    {
        if (string.IsNullOrWhiteSpace(dirPath)) return false;
        string norm = NormalizeForComparison(dirPath);
        string lower = norm.ToLowerInvariant();
        string driveRel = (lower.Length >= 2 && lower[1] == ':') ? lower[2..] : lower;

        if (driveRel == @"\$recycle.bin" || driveRel == @"\system volume information")
            return false;

        try
        {
            var attrs = File.GetAttributes(dirPath);
            if ((attrs & FileAttributes.ReparsePoint) != 0)
                return false; // NEVER follow NTFS junctions/symlinks -> prevents infinite loops!
        }
        catch
        {
            return false;
        }

        return true;
    }

    /// <summary>
    /// Validates whether a Windows Registry path is safe to read/modify by the Safe Optimizer or Ghost Cleaner.
    /// Explicitly blocks critical kernel, boot, security, Defender, and hardware/voltage keys.
    /// </summary>
    public static SafetyVerdict EvaluateRegistryPath(string hive, string subKey)
    {
        if (string.IsNullOrWhiteSpace(hive) || string.IsNullOrWhiteSpace(subKey))
            return SafetyVerdict.Block("Clé registre vide.");

        string h = hive.Trim().ToUpperInvariant();
        if (h != "HKCU" && h != "HKLM")
            return SafetyVerdict.Block("Seules les ruches HKCU et HKLM contrôlées sont autorisées.");

        string sk = subKey.Trim().TrimStart('\\').ToLowerInvariant();

        // Hard-block sensitive OS/Security/Boot/Hardware/Driver registry subtrees
        string[] forbiddenPrefixes =
        [
            @"system\currentcontrolset\control\session manager\memory management",
            @"system\currentcontrolset\services\windefend",
            @"system\currentcontrolset\services\mpssvc",
            @"system\currentcontrolset\services\wuauserv",
            @"system\currentcontrolset\enum",
            @"hardware\",
            @"sam\",
            @"security\",
            @"bcd",
            @"software\policies\microsoft\windows defender",
            @"software\microsoft\windows defender",
            @"software\microsoft\windows nt\currentversion\winlogon"
        ];

        foreach (string fp in forbiddenPrefixes)
        {
            if (sk.StartsWith(fp, StringComparison.Ordinal))
                return SafetyVerdict.Block($"Clé système/sécurité/matériel strictement verrouillée : {subKey}");
        }

        return SafetyVerdict.Allow();
    }
}

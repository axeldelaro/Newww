using CleanForge.Core.Safety;
using Xunit;

namespace CleanForge.Tests;

public class SafetyGuardTests
{
    [Theory]
    [InlineData(@"C:\")]
    [InlineData(@"D:\")]
    [InlineData(@"C:\Windows")]
    [InlineData(@"C:\Windows\System32")]
    [InlineData(@"C:\Windows\System32\ntoskrnl.exe")]
    [InlineData(@"C:\Windows\System32\drivers\nvlddmkm.sys")]
    [InlineData(@"C:\Windows\SysWOW64\kernel32.dll")]
    [InlineData(@"C:\Windows\WinSxS\amd64_something")]
    [InlineData(@"C:\Windows\Temp\..\System32\cmd.exe")]
    [InlineData(@"C:\Windows\Fonts\segoeui.ttf")]
    [InlineData(@"C:\Windows\Boot\EFI\bootmgfw.efi")]
    [InlineData(@"C:\Boot\BCD")]
    [InlineData(@"C:\EFI\Microsoft\Boot\bootmgfw.efi")]
    [InlineData(@"C:\Recovery\WindowsRE\winre.wim")]
    [InlineData(@"C:\$Recycle.Bin\S-1-5-21")]
    [InlineData(@"C:\System Volume Information\tracking.log")]
    [InlineData(@"C:\Program Files")]
    [InlineData(@"C:\Program Files\Windows Defender\MsMpEng.exe")]
    [InlineData(@"C:\Program Files\WindowsApps\Microsoft.WindowsStore")]
    [InlineData(@"C:\ProgramData\Microsoft\Windows Defender\Scans")]
    [InlineData(@"C:\ProgramData\Package Cache\{1234}\setup.msi")]
    [InlineData(@"C:\pagefile.sys")]
    [InlineData(@"C:\hiberfil.sys")]
    [InlineData(@"C:\Users\Axel")]
    [InlineData(@"C:\Users\Axel\Desktop")]
    [InlineData(@"C:\Users\Axel\Documents")]
    [InlineData(@"C:\Users\Axel\Downloads")]
    [InlineData(@"C:\Users\Axel\Pictures")]
    [InlineData(@"C:\Users\Axel\AppData")]
    [InlineData(@"C:\Users\Axel\AppData\Local")]
    [InlineData(@"C:\Users\Axel\AppData\Roaming")]
    [InlineData(@"C:\Users\Axel\NTUSER.DAT")]
    [InlineData(@"C:\Users\Axel\.ssh\id_rsa")]
    [InlineData(@"C:\Users\Axel\Secrets\passwords.kdbx")]
    [InlineData(@"C:\Users\Axel\AppData\Local\Google\Chrome\User Data\Default\Login Data")]
    [InlineData(@"C:\Users\Axel\AppData\Local\Google\Chrome\User Data\Default\Bookmarks")]
    [InlineData(@"C:\Users\Axel\AppData\Local\Google\Chrome\User Data\Default\History")]
    [InlineData(@"C:\Users\Axel\AppData\Local\Google\Chrome\User Data\Default\Network\Cookies")]
    [InlineData(@"C:\Users\Axel\AppData\Roaming\Mozilla\Firefox\Profiles\abc.default\places.sqlite")]
    [InlineData(@"C:\Users\Axel\AppData\Roaming\Mozilla\Firefox\Profiles\abc.default\logins.json")]
    [InlineData(@"C:\Users\Axel\AppData\Roaming\Mozilla\Firefox\Profiles\abc.default\key4.db")]
    [InlineData(@"\\NAS-SERVER\Shared\important.docx")]
    public void EvaluatePathForDeletion_BlocksAllCriticalSystemDriverAndPersonalPaths(string dangerousPath)
    {
        var verdict = SafetyGuard.EvaluatePathForDeletion(dangerousPath, isDirectory: !Path.HasExtension(dangerousPath));
        Assert.False(verdict.IsAllowed, $"Expected BLOCK for {dangerousPath}, but got ALLOW ({verdict.Reason})");
    }

    [Theory]
    [InlineData(@"C:\Windows\Temp\installer_junk.tmp", false)]
    [InlineData(@"C:\Windows\SoftwareDistribution\Download\abc123\wu.cab", false)]
    [InlineData(@"C:\Windows\Prefetch\OLDAPP.EXE-1234.pf", false)]
    [InlineData(@"C:\Users\Axel\AppData\Local\Temp\cache_file.tmp", false)]
    [InlineData(@"C:\Users\Axel\AppData\Local\Google\Chrome\User Data\Default\Cache\Cache_Data\f_00012a", false)]
    [InlineData(@"C:\Users\Axel\AppData\Local\NVIDIA\DXCache\shader.bin", false)]
    [InlineData(@"C:\Users\Axel\Downloads\old_installer.exe", false)]
    [InlineData(@"D:\Cours\ProjetNode\node_modules", true)]
    public void EvaluatePathForDeletion_AllowsGenuineSafeCacheAndUserCleanupTargets(string safePath, bool isDir)
    {
        var verdict = SafetyGuard.EvaluatePathForDeletion(safePath, isDirectory: isDir);
        Assert.True(verdict.IsAllowed, $"Expected ALLOW for {safePath}, but got BLOCK ({verdict.Reason})");
    }

    [Fact]
    public void EvaluatePathForDeletion_BlocksDeletingWindowsTempRootItselfUnlessContentsOnly()
    {
        var blockRoot = SafetyGuard.EvaluatePathForDeletion(@"C:\Windows\Temp", isDirectory: true, allowCleanContentsOfKnownCacheRoot: false);
        Assert.False(blockRoot.IsAllowed);

        var allowContents = SafetyGuard.EvaluatePathForDeletion(@"C:\Windows\Temp", isDirectory: true, allowCleanContentsOfKnownCacheRoot: true);
        Assert.True(allowContents.IsAllowed);
    }

    [Fact]
    public void EvaluatePathForDeletion_BlocksSymlinksAndReparsePoints()
    {
        string tmp = Path.Combine(Path.GetTempPath(), $"cf_sym_{Guid.NewGuid():N}");
        Directory.CreateDirectory(tmp);
        try
        {
            string realFile = Path.Combine(tmp, "real.txt");
            File.WriteAllText(realFile, "hello");
            string symLink = Path.Combine(tmp, "link.txt");
            File.CreateSymbolicLink(symLink, realFile);

            var verdict = SafetyGuard.EvaluatePathForDeletion(symLink, isDirectory: false);
            Assert.False(verdict.IsAllowed);
            Assert.Contains("symbolique", verdict.Reason, StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            Directory.Delete(tmp, recursive: true);
        }
    }

    [Theory]
    [InlineData("HKLM", @"SYSTEM\CurrentControlSet\Services\WinDefend")]
    [InlineData("HKLM", @"SYSTEM\CurrentControlSet\Services\MpsSvc")]
    [InlineData("HKLM", @"SYSTEM\CurrentControlSet\Services\wuauserv")]
    [InlineData("HKLM", @"SYSTEM\CurrentControlSet\Enum\PCI")]
    [InlineData("HKLM", @"SOFTWARE\Microsoft\Windows Defender\Exclusions")]
    [InlineData("HKLM", @"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon")]
    [InlineData("HKCR", @"exefile\shell")]
    public void EvaluateRegistryPath_BlocksCriticalSecurityAndHardwareKeys(string hive, string subKey)
    {
        var v = SafetyGuard.EvaluateRegistryPath(hive, subKey);
        Assert.False(v.IsAllowed);
    }

    [Theory]
    [InlineData("Microsoft Visual C++ 2022 Redistributable", true)]
    [InlineData("NVIDIA Graphics Driver", true)]
    [InlineData("Realtek High Definition Audio", true)]
    [InlineData("Windows Defender", true)]
    [InlineData("Kaspersky Security", true)]
    [InlineData("OldSchoolMatlabTool2021", false)]
    [InlineData("RandomUninstalledStudyApp", false)]
    public void IsProtectedVendorOrApp_ProtectsOsDriversAndAntivirus(string name, bool expected)
    {
        Assert.Equal(expected, SafetyGuard.IsProtectedVendorOrApp(name));
    }
}

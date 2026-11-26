using System.Runtime.InteropServices;
using System.Security.Principal;
using CleanForge.Core.Models;
using CleanForge.Core.Scanners;

namespace CleanForge.Core.Optimizer;

public sealed class SystemHealthMonitor
{
    public static bool IsRunningAsAdministrator()
    {
        if (!OperatingSystem.IsWindows()) return false;
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            var principal = new WindowsPrincipal(identity);
            return principal.IsInRole(WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    public static SystemHealthSummary CaptureHealth()
    {
        var (totalRam, availRam) = ReadSystemMemory();
        return new SystemHealthSummary
        {
            OsDescription = RuntimeInformation.OSDescription,
            MachineName = Environment.MachineName,
            CpuLogicalCores = Environment.ProcessorCount,
            IsAdministrator = IsRunningAsAdministrator(),
            TotalRamBytes = totalRam,
            AvailableRamBytes = availRam,
            Drives = FullDiskExplorerScanner.GetLocalDrives()
        };
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);

    private static (long TotalBytes, long AvailBytes) ReadSystemMemory()
    {
        if (OperatingSystem.IsWindows())
        {
            try
            {
                var mem = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
                if (GlobalMemoryStatusEx(ref mem))
                {
                    return ((long)mem.ullTotalPhys, (long)mem.ullAvailPhys);
                }
            }
            catch { }
        }

        // Cross-platform fallback via GC memory info
        var gcInfo = GC.GetGCMemoryInfo();
        long tot = gcInfo.TotalAvailableMemoryBytes;
        return (tot, Math.Max(0, tot / 2));
    }
}

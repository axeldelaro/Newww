using System.Text;
using System.Text.Json;
using CleanForge.Core.Models;

namespace CleanForge.Core.Safety;

public sealed class AuditLogger
{
    public string LogsDirectory { get; }

    public AuditLogger(string? customDirectory = null)
    {
        LogsDirectory = customDirectory
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CleanForge", "logs");
        Directory.CreateDirectory(LogsDirectory);
    }

    public string WriteCleanupReport(CleanupExecutionReport report)
    {
        string stamp = DateTime.Now.ToString("yyyyMMdd_HHmmss");
        string filePath = Path.Combine(LogsDirectory, $"cleanforge_audit_{stamp}.txt");

        var sb = new StringBuilder();
        sb.AppendLine("============================================================");
        sb.AppendLine($"CLEANFORGE PRO — JOURNAL D'AUDIT ({DateTime.Now:dd/MM/yyyy HH:mm:ss})");
        sb.AppendLine($"Mode : {(report.WasDryRun ? "SIMULATION (AUCUNE SUPPRESSION)" : "EXÉCUTION RÉELLE (CORBEILLE WINDOWS)")}");
        sb.AppendLine($"Traités : {report.SucceededCount} | Bloqués sécurité : {report.BlockedBySafetyCount} | Ignorés/Verrouillés : {report.FailedOrLockedCount}");
        sb.AppendLine($"Espace libéré : {report.TotalFreedFormatted}");
        sb.AppendLine("============================================================");

        foreach (var d in report.Details)
        {
            string status = d.Success ? (d.WasDryRun ? "[SIMU]" : (d.SentToRecycleBin ? "[CORBEILLE]" : "[SUPPR]")) : "[BLOQUÉ/SKIP]";
            sb.AppendLine($"{status} {ByteFormatter.Format(d.BytesFreed),-10} | {d.Path} | {d.Message}");
        }

        File.WriteAllText(filePath, sb.ToString(), Encoding.UTF8);
        report.AuditLogPath = filePath;
        return filePath;
    }

    public string WriteOptimizerSnapshot(OptimizerSnapshot snapshot, string? customSnapshotsDir = null)
    {
        string dir = customSnapshotsDir
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CleanForge", "snapshots");
        Directory.CreateDirectory(dir);
        string path = Path.Combine(dir, $"snapshot_{snapshot.SnapshotId}.json");
        snapshot.FilePath = path;
        string json = JsonSerializer.Serialize(snapshot, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(path, json, Encoding.UTF8);
        return path;
    }

    public List<OptimizerSnapshot> LoadOptimizerSnapshots(string? customSnapshotsDir = null)
    {
        string dir = customSnapshotsDir
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CleanForge", "snapshots");
        if (!Directory.Exists(dir)) return [];

        var list = new List<OptimizerSnapshot>();
        foreach (string file in Directory.GetFiles(dir, "snapshot_*.json").OrderByDescending(f => f))
        {
            try
            {
                var snap = JsonSerializer.Deserialize<OptimizerSnapshot>(File.ReadAllText(file, Encoding.UTF8));
                if (snap != null)
                {
                    snap.FilePath = file;
                    list.Add(snap);
                }
            }
            catch
            {
                // Ignore corrupted snapshot file cleanly
            }
        }
        return list;
    }
}

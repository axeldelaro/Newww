using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using CleanForge.Core.Models;
using CleanForge.Core.Optimizer;
using CleanForge.Core.Safety;
using CleanForge.Core.Scanners;

namespace CleanForge.App.ViewModels;

public sealed class RelayCommand : ICommand
{
    private readonly Action<object?> _execute;
    private readonly Func<object?, bool>? _canExecute;

    public RelayCommand(Action<object?> execute, Func<object?, bool>? canExecute = null)
    {
        _execute = execute;
        _canExecute = canExecute;
    }

    public event EventHandler? CanExecuteChanged
    {
        add => CommandManager.RequerySuggested += value;
        remove => CommandManager.RequerySuggested -= value;
    }

    public bool CanExecute(object? parameter) => _canExecute?.Invoke(parameter) ?? true;
    public void Execute(object? parameter) => _execute(parameter);
}

public sealed class DuplicateFlatRow : INotifyPropertyChanged
{
    public DuplicateGroup Group { get; init; } = null!;
    public DuplicateFileEntry Entry { get; init; } = null!;
    public string GroupHeader => Group.SummaryTitle;
    public string ShortHash => Group.ShortHash;
    public string FileName => Entry.FileName;
    public string DirectoryName => Entry.DirectoryName;
    public string FullPath => Entry.FullPath;
    public string SizeFormatted => Entry.SizeFormatted;
    public string RoleLabel => Entry.IsOriginalKept ? "ORIGINAL CONSERVÉ" : "DOUBLON";
    public bool CanToggleDelete => !Entry.IsOriginalKept;

    public bool IsSelectedForDelete
    {
        get => Entry.IsSelectedForDelete;
        set
        {
            if (Entry.IsOriginalKept) return; // Hard-locked!
            if (Entry.IsSelectedForDelete != value)
            {
                Entry.IsSelectedForDelete = value;
                DuplicateFileScanner.EnforceAtLeastOneKept(Group);
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsSelectedForDelete)));
            }
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
}

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly AuditLogger _auditLogger = new();
    private readonly RecycleBinDeleter _deleter;
    private readonly SafeWindowsOptimizer _optimizer;
    private readonly StartupManager _startupManager = new();

    private CancellationTokenSource? _activeCts;
    private FullDiskScanResult? _lastDiskScan;
    private List<StudyClutterItem> _allStudyItems = [];
    private List<GhostSoftwareItem> _allInstalledApps = [];

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    // ==================== NAVIGATION ====================
    private int _selectedTabIndex;
    public int SelectedTabIndex
    {
        get => _selectedTabIndex;
        set { if (_selectedTabIndex != value) { _selectedTabIndex = value; OnPropertyChanged(); } }
    }

    // ==================== GLOBAL SAFETY & PROGRESS ====================
    private bool _isBusy;
    public bool IsBusy
    {
        get => _isBusy;
        set { _isBusy = value; OnPropertyChanged(); OnPropertyChanged(nameof(IsNotBusy)); }
    }
    public bool IsNotBusy => !IsBusy;

    private double _progressPercent;
    public double ProgressPercent { get => _progressPercent; set { _progressPercent = value; OnPropertyChanged(); } }

    private string _progressStage = "Prêt — Zéro modification effectuée tant que vous ne validez pas.";
    public string ProgressStage { get => _progressStage; set { _progressStage = value; OnPropertyChanged(); } }

    private string _progressPath = "";
    public string ProgressPath { get => _progressPath; set { _progressPath = value; OnPropertyChanged(); } }

    private string _statusMessage = "Bouclier SafetyGuard actif · Suppression sécurisée vers la Corbeille Windows par défaut.";
    public string StatusMessage { get => _statusMessage; set { _statusMessage = value; OnPropertyChanged(); } }

    private bool _isDryRun;
    public bool IsDryRun
    {
        get => _isDryRun;
        set { _isDryRun = value; OnPropertyChanged(); OnPropertyChanged(nameof(ActionModeBanner)); }
    }

    private bool _useRecycleBin = true;
    public bool UseRecycleBin
    {
        get => _useRecycleBin;
        set { _useRecycleBin = value; OnPropertyChanged(); OnPropertyChanged(nameof(ActionModeBanner)); }
    }

    public string ActionModeBanner => IsDryRun
        ? "MODE SIMULATION (À BLANC) : aucune suppression réelle ne sera effectuée"
        : (UseRecycleBin
            ? "CORBEILLE WINDOWS ACTIVE : tout fichier nettoyé reste 100 % récupérable"
            : "SUPPRESSION DIRECTE ACTIVE : uniquement sur éléments cochés et validés par SafetyGuard");

    // ==================== SYSTEM HEALTH ====================
    private SystemHealthSummary _health = SystemHealthMonitor.CaptureHealth();
    public SystemHealthSummary Health
    {
        get => _health;
        set
        {
            _health = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(AdminBadgeText));
            OnPropertyChanged(nameof(ShowElevateButton));
        }
    }

    public string AdminBadgeText => Health.IsAdministrator
        ? "MODE ADMINISTRATEUR ACTIF"
        : "MODE STANDARD (SÛR)";

    public bool ShowElevateButton => !Health.IsAdministrator;

    // ==================== DASHBOARD METRICS ====================
    public string TotalReclaimableFormatted => ByteFormatter.Format(
        CacheCandidates.Sum(c => c.SizeBytes)
        + _allStudyItems.Sum(s => s.SizeBytes)
        + DuplicateGroups.Sum(g => g.ReclaimableBytes)
        + GhostAndOrphans.Sum(g => g.EstimatedBytes));

    public string CacheTotalFormatted => ByteFormatter.Format(CacheCandidates.Sum(c => c.SizeBytes));
    public string StudyTotalFormatted => ByteFormatter.Format(_allStudyItems.Sum(s => s.SizeBytes));
    public string DuplicateTotalFormatted => ByteFormatter.Format(DuplicateGroups.Sum(g => g.ReclaimableBytes));
    public string GhostCountText => $"{GhostAndOrphans.Count} fantômes/orphelins";
    public string ShortcutEmptyCountText => $"{BrokenShortcuts.Count} raccourcis · {EmptyFolders.Count} vides";
    public string DiskScanSummaryText => _lastDiskScan == null
        ? "Non analysé"
        : $"{_lastDiskScan.TotalScannedFiles:N0} fichiers · {ByteFormatter.Format(_lastDiskScan.TotalScannedBytes)} ({_lastDiskScan.Elapsed.TotalSeconds:0.1}s)";

    // ==================== COLLECTIONS ====================
    public ObservableCollection<DriveSummary> Drives { get; } = [];
    public ObservableCollection<FileNode> ExplorerRoots { get; } = [];
    public ObservableCollection<FileNode> ExplorerSearchResults { get; } = [];
    public ObservableCollection<FileNode> LargestFiles { get; } = [];
    public ObservableCollection<ExtensionStat> ExtensionStats { get; } = [];

    public ObservableCollection<CleanCandidate> CacheCandidates { get; } = [];
    public ObservableCollection<StudyClutterItem> StudyItems { get; } = [];
    public ObservableCollection<DuplicateGroup> DuplicateGroups { get; } = [];
    public ObservableCollection<DuplicateFlatRow> DuplicateRows { get; } = [];
    public ObservableCollection<GhostSoftwareItem> GhostAndOrphans { get; } = [];
    public ObservableCollection<GhostSoftwareItem> InstalledApps { get; } = [];
    public ObservableCollection<BrokenShortcutItem> BrokenShortcuts { get; } = [];
    public ObservableCollection<EmptyFolderItem> EmptyFolders { get; } = [];

    public ObservableCollection<OptimizerProfile> OptimizerProfiles { get; } = [];
    public ObservableCollection<OptimizationTweak> OptimizerTweaks { get; } = [];
    public ObservableCollection<StartupAppItem> StartupApps { get; } = [];
    public ObservableCollection<OptimizerSnapshot> SavedSnapshots { get; } = [];

    // ==================== EXPLORER SEARCH FILTERS ====================
    private string _globalSearchQuery = "";
    public string GlobalSearchQuery
    {
        get => _globalSearchQuery;
        set { _globalSearchQuery = value; OnPropertyChanged(); RefreshGlobalSearch(); }
    }

    private string _globalSearchExt = "";
    public string GlobalSearchExt
    {
        get => _globalSearchExt;
        set { _globalSearchExt = value; OnPropertyChanged(); RefreshGlobalSearch(); }
    }

    private int _globalSearchMinMb;
    public int GlobalSearchMinMb
    {
        get => _globalSearchMinMb;
        set { _globalSearchMinMb = value; OnPropertyChanged(); RefreshGlobalSearch(); }
    }

    private FileNode? _selectedExplorerNode;
    public FileNode? SelectedExplorerNode
    {
        get => _selectedExplorerNode;
        set { _selectedExplorerNode = value; OnPropertyChanged(); }
    }

    // ==================== STUDY FILTER ====================
    private int _studyFilterIndex; // 0=All, 1=Dev/Build, 2=Installers, 3=OldDocs
    public int StudyFilterIndex
    {
        get => _studyFilterIndex;
        set { _studyFilterIndex = value; OnPropertyChanged(); ApplyStudyFilter(); }
    }

    private int _studyOldDays = 180;
    public int StudyOldDays
    {
        get => _studyOldDays;
        set { _studyOldDays = value; OnPropertyChanged(); }
    }

    // ==================== INSTALLED APPS SEARCH ====================
    private string _installedAppQuery = "";
    public string InstalledAppQuery
    {
        get => _installedAppQuery;
        set { _installedAppQuery = value; OnPropertyChanged(); ApplyInstalledAppFilter(); }
    }

    private OptimizerSnapshot? _selectedSnapshot;
    public OptimizerSnapshot? SelectedSnapshot
    {
        get => _selectedSnapshot;
        set { _selectedSnapshot = value; OnPropertyChanged(); }
    }

    private string _activeProfileLabel = "Personnalisé";
    public string ActiveProfileLabel { get => _activeProfileLabel; set { _activeProfileLabel = value; OnPropertyChanged(); } }

    // ==================== COMMANDS ====================
    public ICommand FullPcScanCommand { get; }
    public ICommand ScanExplorerCommand { get; }
    public ICommand ScanCachesCommand { get; }
    public ICommand ScanStudyCommand { get; }
    public ICommand ScanDuplicatesCommand { get; }
    public ICommand ScanGhostsCommand { get; }
    public ICommand ScanShortcutsEmptyCommand { get; }
    public ICommand CancelActiveScanCommand { get; }

    public ICommand CleanSelectedCachesCommand { get; }
    public ICommand SelectSafeCachesCommand { get; }
    public ICommand UnselectAllCachesCommand { get; }
    public ICommand CleanSelectedStudyCommand { get; }
    public ICommand CleanSelectedDuplicatesCommand { get; }
    public ICommand CleanSelectedGhostsCommand { get; }
    public ICommand LaunchUninstallerCommand { get; }
    public ICommand CleanSelectedShortcutsCommand { get; }
    public ICommand CleanSelectedEmptyFoldersCommand { get; }
    public ICommand DeleteExplorerItemCommand { get; }
    public ICommand OpenInExplorerCommand { get; }

    public ICommand SelectOptimizerProfileCommand { get; }
    public ICommand ApplyOptimizerCommand { get; }
    public ICommand RestoreSelectedSnapshotCommand { get; }
    public ICommand CreateWindowsRestorePointCommand { get; }
    public ICommand ToggleStartupAppCommand { get; }
    public ICommand RefreshStartupCommand { get; }
    public ICommand OpenLogsFolderCommand { get; }
    public ICommand RelaunchAsAdminCommand { get; }

    public MainViewModel()
    {
        _deleter = new RecycleBinDeleter(_auditLogger);
        _optimizer = new SafeWindowsOptimizer(_auditLogger);

        FullPcScanCommand = new RelayCommand(_ => _ = RunFullPcDiagnosticAsync(), _ => !IsBusy);
        ScanExplorerCommand = new RelayCommand(_ => _ = RunExplorerScanAsync(), _ => !IsBusy);
        ScanCachesCommand = new RelayCommand(_ => _ = RunCacheScanAsync(), _ => !IsBusy);
        ScanStudyCommand = new RelayCommand(_ => _ = RunStudyScanAsync(), _ => !IsBusy);
        ScanDuplicatesCommand = new RelayCommand(_ => _ = RunDuplicatesScanAsync(), _ => !IsBusy);
        ScanGhostsCommand = new RelayCommand(_ => _ = RunGhostScanAsync(), _ => !IsBusy);
        ScanShortcutsEmptyCommand = new RelayCommand(_ => _ = RunShortcutsAndEmptyScanAsync(), _ => !IsBusy);
        CancelActiveScanCommand = new RelayCommand(_ => CancelActiveWork(), _ => IsBusy);

        CleanSelectedCachesCommand = new RelayCommand(_ => _ = CleanSelectedCachesAsync(), _ => !IsBusy);
        SelectSafeCachesCommand = new RelayCommand(_ => SetCacheSelection(onlySafeAuto: true));
        UnselectAllCachesCommand = new RelayCommand(_ => SetCacheSelection(onlySafeAuto: false, clearAll: true));
        CleanSelectedStudyCommand = new RelayCommand(_ => _ = CleanSelectedStudyAsync(), _ => !IsBusy);
        CleanSelectedDuplicatesCommand = new RelayCommand(_ => _ = CleanSelectedDuplicatesAsync(), _ => !IsBusy);
        CleanSelectedGhostsCommand = new RelayCommand(_ => _ = CleanSelectedGhostsAsync(), _ => !IsBusy);
        LaunchUninstallerCommand = new RelayCommand(p => { if (p is GhostSoftwareItem app) LaunchAppUninstaller(app); });
        CleanSelectedShortcutsCommand = new RelayCommand(_ => _ = CleanSelectedShortcutsAsync(), _ => !IsBusy);
        CleanSelectedEmptyFoldersCommand = new RelayCommand(_ => _ = CleanSelectedEmptyFoldersAsync(), _ => !IsBusy);
        DeleteExplorerItemCommand = new RelayCommand(p => _ = DeleteExplorerNodeAsync(p as FileNode ?? SelectedExplorerNode), _ => !IsBusy);
        OpenInExplorerCommand = new RelayCommand(p => OpenPathInWindowsExplorer(p?.ToString()));

        SelectOptimizerProfileCommand = new RelayCommand(p => { if (p is OptimizerProfile prof) SelectProfile(prof); });
        ApplyOptimizerCommand = new RelayCommand(_ => ApplySelectedOptimizations(), _ => !IsBusy);
        RestoreSelectedSnapshotCommand = new RelayCommand(_ => RestoreSelectedSnapshot(), _ => !IsBusy && SelectedSnapshot != null);
        CreateWindowsRestorePointCommand = new RelayCommand(_ => _ = CreateRestorePointAsync(), _ => !IsBusy);
        ToggleStartupAppCommand = new RelayCommand(p => { if (p is StartupAppItem s) ToggleStartup(s); });
        RefreshStartupCommand = new RelayCommand(_ => LoadOptimizerAndStartup());
        OpenLogsFolderCommand = new RelayCommand(_ => OpenPathInWindowsExplorer(_auditLogger.LogsDirectory));
        RelaunchAsAdminCommand = new RelayCommand(_ => RelaunchElevated());

        InitializeStaticData();
    }

    private void InitializeStaticData()
    {
        RefreshHealth();
        foreach (var p in SafeWindowsOptimizer.GetProfiles()) OptimizerProfiles.Add(p);
        foreach (var t in SafeWindowsOptimizer.CreateAllTweaks()) OptimizerTweaks.Add(t);
        LoadOptimizerAndStartup();
    }

    private void RefreshHealth()
    {
        Health = SystemHealthMonitor.CaptureHealth();
        Drives.Clear();
        foreach (var d in Health.Drives) Drives.Add(d);
    }

    private void LoadOptimizerAndStartup()
    {
        var tweaksList = OptimizerTweaks.ToList();
        _optimizer.RefreshCurrentWindowsStates(tweaksList);
        OptimizerTweaks.Clear();
        foreach (var t in tweaksList) OptimizerTweaks.Add(t);

        StartupApps.Clear();
        foreach (var s in _startupManager.ListStartupApps()) StartupApps.Add(s);

        SavedSnapshots.Clear();
        foreach (var snap in _optimizer.GetSavedSnapshots()) SavedSnapshots.Add(snap);
        SelectedSnapshot = SavedSnapshots.FirstOrDefault();
    }

    private IProgress<ScanProgress> CreateProgressReporter() => new Progress<ScanProgress>(p =>
    {
        ProgressPercent = Math.Clamp(p.Percent, 0, 100);
        if (!string.IsNullOrWhiteSpace(p.Stage)) ProgressStage = p.Stage;
        if (!string.IsNullOrWhiteSpace(p.CurrentPath)) ProgressPath = p.CurrentPath;
    });

    private CancellationToken BeginWork(string initialStage)
    {
        _activeCts?.Cancel();
        _activeCts?.Dispose();
        _activeCts = new CancellationTokenSource();
        IsBusy = true;
        ProgressPercent = 2;
        ProgressStage = initialStage;
        ProgressPath = "";
        return _activeCts.Token;
    }

    private void EndWork(string finalMessage)
    {
        IsBusy = false;
        ProgressPercent = 100;
        ProgressStage = finalMessage;
        ProgressPath = "";
        StatusMessage = finalMessage;
        NotifyDashboardMetrics();
        CommandManager.InvalidateRequerySuggested();
    }

    public void CancelActiveWork()
    {
        _activeCts?.Cancel();
        StatusMessage = "Opération annulée proprement.";
    }

    private void NotifyDashboardMetrics()
    {
        OnPropertyChanged(nameof(TotalReclaimableFormatted));
        OnPropertyChanged(nameof(CacheTotalFormatted));
        OnPropertyChanged(nameof(StudyTotalFormatted));
        OnPropertyChanged(nameof(DuplicateTotalFormatted));
        OnPropertyChanged(nameof(GhostCountText));
        OnPropertyChanged(nameof(ShortcutEmptyCountText));
        OnPropertyChanged(nameof(DiskScanSummaryText));
    }

    // ==================== SCANNERS ====================
    public async Task RunFullPcDiagnosticAsync()
    {
        var ct = BeginWork("Analyse complète du PC en cours (tous les disques)...");
        var prog = CreateProgressReporter();

        try
        {
            var caches = await Task.Run(() => new DeepCacheCleanerScanner().ScanDefaultWindowsCaches(prog, ct), ct);
            UpdateCollection(CacheCandidates, caches);
            NotifyDashboardMetrics();

            var (ghosts, installed) = await Task.Run(() => new GhostSoftwareScanner().ScanWindowsSoftware(prog, ct), ct);
            UpdateCollection(GhostAndOrphans, ghosts);
            _allInstalledApps = installed;
            ApplyInstalledAppFilter();

            var scScanner = new ShortcutAndEmptyFolderScanner();
            var broken = await Task.Run(() => scScanner.ScanBrokenShortcuts(progress: prog, ct: ct), ct);
            UpdateCollection(BrokenShortcuts, broken);

            var studyRoots = GetAllUserAndDataRoots();
            var empties = await Task.Run(() => scScanner.ScanEmptyFolders(studyRoots, prog, ct), ct);
            UpdateCollection(EmptyFolders, empties);

            var study = await Task.Run(() => new StudyGraduationScanner().ScanStudyClutter(studyRoots, StudyOldDays, progress: prog, ct: ct), ct);
            _allStudyItems = study;
            ApplyStudyFilter();
            NotifyDashboardMetrics();

            var dups = await Task.Run(() => new DuplicateFileScanner().ScanForDuplicates(studyRoots, minSizeBytes: 512 * 1024, progress: prog, ct: ct), ct);
            PopulateDuplicates(dups);
            NotifyDashboardMetrics();

            var diskRes = await Task.Run(() => new FullDiskExplorerScanner().ScanAllDrives(progress: prog, ct: ct), ct);
            PopulateDiskScan(diskRes);

            RefreshHealth();
            EndWork($"Analyse complète terminée ! {TotalReclaimableFormatted} potentiellement récupérables (vérifiez chaque onglet avant nettoyage).");
        }
        catch (OperationCanceledException)
        {
            EndWork("Analyse complète interrompue par l'utilisateur.");
        }
    }

    public async Task RunExplorerScanAsync()
    {
        var ct = BeginWork("Cartographie complète de tous les disques...");
        var prog = CreateProgressReporter();
        try
        {
            var res = await Task.Run(() => new FullDiskExplorerScanner().ScanAllDrives(progress: prog, ct: ct), ct);
            PopulateDiskScan(res);
            RefreshHealth();
            EndWork($"Explorateur mis à jour : {res.TotalScannedFiles:N0} fichiers indexés en {res.Elapsed.TotalSeconds:0.1} s.");
        }
        catch (OperationCanceledException) { EndWork("Cartographie annulée."); }
    }

    public async Task RunCacheScanAsync()
    {
        var ct = BeginWork("Analyse des 60+ zones de caches profonds...");
        var prog = CreateProgressReporter();
        try
        {
            var list = await Task.Run(() => new DeepCacheCleanerScanner().ScanDefaultWindowsCaches(prog, ct), ct);
            UpdateCollection(CacheCandidates, list);
            EndWork($"{list.Count} caches trouvés ({CacheTotalFormatted}).");
        }
        catch (OperationCanceledException) { EndWork("Analyse caches annulée."); }
    }

    public async Task RunStudyScanAsync()
    {
        var ct = BeginWork("Analyse Fin d'Études (builds Dev, installeurs, anciens cours)...");
        var prog = CreateProgressReporter();
        try
        {
            var list = await Task.Run(() => new StudyGraduationScanner().ScanStudyClutter(GetAllUserAndDataRoots(), StudyOldDays, progress: prog, ct: ct), ct);
            _allStudyItems = list;
            ApplyStudyFilter();
            EndWork($"{list.Count} éléments Fin d'Études trouvés ({StudyTotalFormatted}).");
        }
        catch (OperationCanceledException) { EndWork("Analyse Fin d'Études annulée."); }
    }

    public async Task RunDuplicatesScanAsync()
    {
        var ct = BeginWork("Recherche de doublons exacts SHA-256 sur tout le PC...");
        var prog = CreateProgressReporter();
        try
        {
            var groups = await Task.Run(() => new DuplicateFileScanner().ScanForDuplicates(GetAllUserAndDataRoots(), minSizeBytes: 256 * 1024, progress: prog, ct: ct), ct);
            PopulateDuplicates(groups);
            EndWork($"{groups.Count} groupes de doublons exacts détectés ({DuplicateTotalFormatted} récupérables).");
        }
        catch (OperationCanceledException) { EndWork("Recherche de doublons annulée."); }
    }

    public async Task RunGhostScanAsync()
    {
        var ct = BeginWork("Audit Registre & dossiers orphelins...");
        var prog = CreateProgressReporter();
        try
        {
            var (ghosts, installed) = await Task.Run(() => new GhostSoftwareScanner().ScanWindowsSoftware(prog, ct), ct);
            UpdateCollection(GhostAndOrphans, ghosts);
            _allInstalledApps = installed;
            ApplyInstalledAppFilter();
            EndWork($"{ghosts.Count} fantômes/orphelins détectés · {installed.Count} logiciels actifs.");
        }
        catch (OperationCanceledException) { EndWork("Audit logiciels annulé."); }
    }

    public async Task RunShortcutsAndEmptyScanAsync()
    {
        var ct = BeginWork("Analyse des raccourcis morts & dossiers vides...");
        var prog = CreateProgressReporter();
        try
        {
            var scanner = new ShortcutAndEmptyFolderScanner();
            var broken = await Task.Run(() => scanner.ScanBrokenShortcuts(progress: prog, ct: ct), ct);
            var empties = await Task.Run(() => scanner.ScanEmptyFolders(GetAllUserAndDataRoots(), prog, ct), ct);
            UpdateCollection(BrokenShortcuts, broken);
            UpdateCollection(EmptyFolders, empties);
            EndWork($"{broken.Count} raccourcis morts et {empties.Count} dossiers vides détectés.");
        }
        catch (OperationCanceledException) { EndWork("Analyse annulée."); }
    }

    // ==================== CLEANUP ACTIONS ====================
    private bool ConfirmSafeDeletion(int itemCount, long totalBytes, string title)
    {
        if (itemCount == 0)
        {
            MessageBox.Show("Aucun élément n'est coché.", "CleanForge Pro", MessageBoxButton.OK, MessageBoxImage.Information);
            return false;
        }
        if (IsDryRun) return true; // Simulation never deletes anything!

        string dest = UseRecycleBin
            ? "Destination : CORBEILLE WINDOWS (100 % récupérable en 1 clic si besoin)."
            : "ATTENTION : Suppression directe (hors Corbeille). SafetyGuard reste actif.";

        var ans = MessageBox.Show(
            $"{title}\n\n• Éléments sélectionnés : {itemCount}\n• Espace estimé : {ByteFormatter.Format(totalBytes)}\n• {dest}\n\nConfirmer le nettoyage ?",
            "Confirmation CleanForge Pro",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        return ans == MessageBoxResult.Yes;
    }

    private async Task CleanSelectedCachesAsync()
    {
        var selected = CacheCandidates.Where(c => c.IsSelected).ToList();
        if (!ConfirmSafeDeletion(selected.Count, selected.Sum(c => c.SizeBytes), "Nettoyage des caches sélectionnés")) return;

        var ct = BeginWork(IsDryRun ? "Simulation nettoyage caches..." : "Nettoyage des caches sélectionnés...");
        var prog = CreateProgressReporter();
        var report = await Task.Run(() => _deleter.ExecuteCleanup(selected, IsDryRun, UseRecycleBin, prog, ct), ct);

        if (!IsDryRun)
        {
            var refreshed = await Task.Run(() => new DeepCacheCleanerScanner().ScanDefaultWindowsCaches(ct: ct), ct);
            UpdateCollection(CacheCandidates, refreshed);
            RefreshHealth();
        }
        EndWork(FormatReportSummary(report));
    }

    private async Task CleanSelectedStudyAsync()
    {
        var selected = _allStudyItems.Where(s => s.IsSelected).ToList();
        if (!ConfirmSafeDeletion(selected.Count, selected.Sum(s => s.SizeBytes), "Nettoyage des éléments Fin d'Études sélectionnés")) return;

        var ct = BeginWork(IsDryRun ? "Simulation Fin d'Études..." : "Nettoyage sécurisé Fin d'Études...");
        var prog = CreateProgressReporter();
        var targets = selected.Select(s => (s.FullPath, s.IsDirectory)).ToList();
        var report = await Task.Run(() => _deleter.DeletePaths(targets, IsDryRun, UseRecycleBin, prog, ct), ct);

        if (!IsDryRun)
        {
            _allStudyItems.RemoveAll(s => s.IsSelected && !File.Exists(s.FullPath) && !Directory.Exists(s.FullPath));
            ApplyStudyFilter();
            RefreshHealth();
        }
        EndWork(FormatReportSummary(report));
    }

    private async Task CleanSelectedDuplicatesAsync()
    {
        foreach (var grp in DuplicateGroups) DuplicateFileScanner.EnforceAtLeastOneKept(grp);

        var selectedEntries = DuplicateGroups
            .SelectMany(g => g.Entries)
            .Where(e => e.IsSelectedForDelete && !e.IsOriginalKept)
            .ToList();

        if (!ConfirmSafeDeletion(selectedEntries.Count, selectedEntries.Sum(e => e.SizeBytes), "Nettoyage des doublons exacts (les originaux sont conservés)"))
            return;

        var ct = BeginWork(IsDryRun ? "Simulation doublons..." : "Envoi des doublons vers la Corbeille...");
        var prog = CreateProgressReporter();
        var targets = selectedEntries.Select(e => (e.FullPath, false)).ToList();
        var report = await Task.Run(() => _deleter.DeletePaths(targets, IsDryRun, UseRecycleBin, prog, ct), ct);

        if (!IsDryRun)
        {
            foreach (var grp in DuplicateGroups.ToList())
            {
                grp.Entries.RemoveAll(e => !File.Exists(e.FullPath));
                if (grp.Entries.Count < 2) DuplicateGroups.Remove(grp);
            }
            RebuildDuplicateFlatRows();
            RefreshHealth();
        }
        EndWork(FormatReportSummary(report));
    }

    private async Task CleanSelectedGhostsAsync()
    {
        var selected = GhostAndOrphans.Where(g => g.IsSelected && !g.IsProtectedVendor).ToList();
        if (!ConfirmSafeDeletion(selected.Count, selected.Sum(g => g.EstimatedBytes), "Nettoyage des logiciels fantômes et dossiers orphelins"))
            return;

        var ct = BeginWork(IsDryRun ? "Simulation nettoyage fantômes..." : "Nettoyage fantômes & orphelins...");
        var prog = CreateProgressReporter();

        var folderTargets = selected.Where(g => g.Kind == GhostItemKind.OrphanResidualFolder).Select(g => (g.PathOrKey, true)).ToList();
        var report = await Task.Run(() => _deleter.DeletePaths(folderTargets, IsDryRun, UseRecycleBin, prog, ct), ct);

        int regCleaned = 0;
        foreach (var regGhost in selected.Where(g => g.Kind == GhostItemKind.DeadRegistryUninstaller))
        {
            if (GhostSoftwareScanner.RemoveGhostRegistryEntry(regGhost, IsDryRun, out _))
                regCleaned++;
        }

        if (!IsDryRun)
        {
            var (ghosts, installed) = await Task.Run(() => new GhostSoftwareScanner().ScanWindowsSoftware(ct: ct), ct);
            UpdateCollection(GhostAndOrphans, ghosts);
            _allInstalledApps = installed;
            ApplyInstalledAppFilter();
            RefreshHealth();
        }
        EndWork($"{FormatReportSummary(report)} · {regCleaned} entrées fantômes Registre nettoyées.");
    }

    private async Task CleanSelectedShortcutsAsync()
    {
        var selected = BrokenShortcuts.Where(b => b.IsSelected).ToList();
        if (!ConfirmSafeDeletion(selected.Count, selected.Count * 2048L, "Nettoyage des raccourcis morts")) return;

        var ct = BeginWork("Nettoyage des raccourcis cassés...");
        var report = await Task.Run(() => _deleter.DeletePaths(selected.Select(s => (s.ShortcutPath, false)), IsDryRun, UseRecycleBin, ct: ct), ct);
        if (!IsDryRun)
        {
            foreach (var s in selected.Where(x => !File.Exists(x.ShortcutPath))) BrokenShortcuts.Remove(s);
        }
        EndWork(FormatReportSummary(report));
    }

    private async Task CleanSelectedEmptyFoldersAsync()
    {
        var selected = EmptyFolders.Where(e => e.IsSelected).ToList();
        if (!ConfirmSafeDeletion(selected.Count, 0, "Suppression des dossiers vides")) return;

        var ct = BeginWork("Nettoyage des dossiers vides...");
        var report = await Task.Run(() => _deleter.DeletePaths(selected.Select(e => (e.FullPath, true)), IsDryRun, UseRecycleBin, ct: ct), ct);
        if (!IsDryRun)
        {
            foreach (var e in selected.Where(x => !Directory.Exists(x.FullPath))) EmptyFolders.Remove(e);
        }
        EndWork(FormatReportSummary(report));
    }

    private async Task DeleteExplorerNodeAsync(FileNode? node)
    {
        if (node == null) return;
        var verdict = SafetyGuard.EvaluatePathForDeletion(node.FullPath, node.IsDirectory);
        if (!verdict.IsAllowed)
        {
            MessageBox.Show($"Action bloquée par SafetyGuard :\n{verdict.Reason}", "Protection Système", MessageBoxButton.OK, MessageBoxImage.Stop);
            return;
        }
        if (!ConfirmSafeDeletion(1, node.SizeBytes, $"Nettoyer : {node.FullPath}")) return;

        var ct = BeginWork($"Nettoyage de {node.Name}...");
        var report = await Task.Run(() => _deleter.DeletePaths([(node.FullPath, node.IsDirectory)], IsDryRun, UseRecycleBin, ct: ct), ct);
        if (!IsDryRun && report.SucceededCount > 0)
        {
            ExplorerSearchResults.Remove(node);
            LargestFiles.Remove(node);
            RefreshHealth();
        }
        EndWork(FormatReportSummary(report));
    }

    private void SetCacheSelection(bool onlySafeAuto, bool clearAll = false)
    {
        var list = CacheCandidates.ToList();
        foreach (var c in list)
            c.IsSelected = !clearAll && (!onlySafeAuto || c.SafetyTier == SafetyTier.SafeAuto);
        UpdateCollection(CacheCandidates, list);
    }

    // ==================== OPTIMIZER & STARTUP ====================
    private void SelectProfile(OptimizerProfile profile)
    {
        ActiveProfileLabel = profile.Title;
        var list = OptimizerTweaks.ToList();
        SafeWindowsOptimizer.ApplyProfileSelection(list, profile.Id);
        UpdateCollection(OptimizerTweaks, list);
        StatusMessage = $"Profil « {profile.Title} » pré-sélectionné (vérifiez les cases puis cliquez sur Appliquer).";
    }

    private void ApplySelectedOptimizations()
    {
        var selected = OptimizerTweaks.Where(t => t.IsEnabledByUser).ToList();
        if (selected.Count == 0)
        {
            MessageBox.Show("Sélectionnez d'abord un profil ou cochez au moins un réglage.", "Optimiseur Sûr", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        if (!IsDryRun)
        {
            var ans = MessageBox.Show(
                $"Appliquer {selected.Count} réglages Windows sûrs (Profil : {ActiveProfileLabel}) ?\n\n• 0 % risque matériel (aucun overclocking, aucune tension modifiée)\n• Sauvegarde JSON automatique créée avant modification (Restauration 1-clic disponible).",
                "Appliquer l'Optimisation Sûre",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);
            if (ans != MessageBoxResult.Yes) return;
        }

        var report = _optimizer.ApplySelectedTweaks(OptimizerTweaks, ActiveProfileLabel, IsDryRun);
        LoadOptimizerAndStartup();
        StatusMessage = IsDryRun
            ? $"Simulation : {report.AppliedCount} réglages prêts à appliquer."
            : $"{report.AppliedCount} optimisations appliquées ! Sauvegarde créée (restaurable en 1 clic).";
        MessageBox.Show(string.Join("\n", report.Messages), "Résultat Optimiseur Sûr", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void RestoreSelectedSnapshot()
    {
        if (SelectedSnapshot == null) return;
        var ans = MessageBox.Show(
            $"Restaurer Windows exactement à l'état de la sauvegarde :\n{SelectedSnapshot.DisplayLabel} ?",
            "Restauration 1-Clic",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (ans != MessageBoxResult.Yes) return;

        var report = _optimizer.RestoreSnapshot(SelectedSnapshot);
        LoadOptimizerAndStartup();
        StatusMessage = report.Messages.LastOrDefault() ?? "Restauration terminée.";
        MessageBox.Show(string.Join("\n", report.Messages), "Restauration Réussie", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private async Task CreateRestorePointAsync()
    {
        BeginWork("Création d'un Point de Restauration Windows...");
        var (ok, msg) = await Task.Run(() =>
        {
            bool r = SafeWindowsOptimizer.TryCreateWindowsRestorePoint($"CleanForge Pro {DateTime.Now:dd-MM-yyyy HH-mm}", out string m);
            return (r, m);
        });
        EndWork(msg);
        MessageBox.Show(msg, ok ? "Point de Restauration Créé" : "Information Windows", MessageBoxButton.OK, ok ? MessageBoxImage.Information : MessageBoxImage.Warning);
    }

    private void ToggleStartup(StartupAppItem item)
    {
        bool targetState = !item.IsEnabled;
        bool ok = _startupManager.SetStartupEnabled(item, targetState, out string msg);
        StatusMessage = msg;
        if (!ok)
        {
            MessageBox.Show(msg, "Protection Démarrage", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        var refreshed = _startupManager.ListStartupApps();
        UpdateCollection(StartupApps, refreshed);
    }

    private void LaunchAppUninstaller(GhostSoftwareItem app)
    {
        if (!GhostSoftwareScanner.LaunchOfficialUninstaller(app, out string msg))
            MessageBox.Show(msg, "Désinstalleur", MessageBoxButton.OK, MessageBoxImage.Warning);
        else
            StatusMessage = msg;
    }

    // ==================== HELPERS ====================
    private void PopulateDiskScan(FullDiskScanResult res)
    {
        _lastDiskScan = res;
        UpdateCollection(ExplorerRoots, res.RootNodes);
        UpdateCollection(LargestFiles, res.LargestFiles);
        UpdateCollection(ExtensionStats, res.ExtensionStats);
        RefreshGlobalSearch();
    }

    private void RefreshGlobalSearch()
    {
        if (_lastDiskScan == null) return;
        long minBytes = Math.Max(0, GlobalSearchMinMb) * 1024L * 1024L;
        var hits = FullDiskExplorerScanner.SearchGlobal(_lastDiskScan, GlobalSearchQuery, GlobalSearchExt, minBytes, includeFolders: true, maxResults: 300);
        UpdateCollection(ExplorerSearchResults, hits);
    }

    private void ApplyStudyFilter()
    {
        IEnumerable<StudyClutterItem> q = _allStudyItems;
        q = StudyFilterIndex switch
        {
            1 => q.Where(x => x.Kind == StudyClutterKind.RegenerableDevBuild),
            2 => q.Where(x => x.Kind == StudyClutterKind.ForgottenInstallerOrArchive),
            3 => q.Where(x => x.Kind == StudyClutterKind.OldStudyDocument),
            _ => q
        };
        UpdateCollection(StudyItems, q.ToList());
    }

    private void ApplyInstalledAppFilter()
    {
        string q = (InstalledAppQuery ?? "").Trim();
        var filtered = string.IsNullOrEmpty(q)
            ? _allInstalledApps
            : _allInstalledApps.Where(a =>
                a.DisplayName.Contains(q, StringComparison.OrdinalIgnoreCase)
                || a.Publisher.Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();
        UpdateCollection(InstalledApps, filtered);
    }

    private void PopulateDuplicates(List<DuplicateGroup> groups)
    {
        UpdateCollection(DuplicateGroups, groups);
        RebuildDuplicateFlatRows();
    }

    private void RebuildDuplicateFlatRows()
    {
        DuplicateRows.Clear();
        foreach (var grp in DuplicateGroups)
        {
            foreach (var entry in grp.Entries)
            {
                DuplicateRows.Add(new DuplicateFlatRow { Group = grp, Entry = entry });
            }
        }
    }

    private static List<string> GetAllUserAndDataRoots()
    {
        var roots = new List<string>();
        string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (!string.IsNullOrWhiteSpace(user) && Directory.Exists(user)) roots.Add(user);

        string sysDrive = Path.GetPathRoot(Environment.SystemDirectory) ?? @"C:\";
        foreach (var d in FullDiskExplorerScanner.GetLocalDrives())
        {
            if (!d.Name.Equals(sysDrive, StringComparison.OrdinalIgnoreCase) && Directory.Exists(d.Name))
                roots.Add(d.Name);
        }
        return roots;
    }

    private static void UpdateCollection<T>(ObservableCollection<T> target, IEnumerable<T> items)
    {
        target.Clear();
        foreach (var item in items) target.Add(item);
    }

    private static string FormatReportSummary(CleanupExecutionReport r)
    {
        string mode = r.WasDryRun ? "[SIMULATION]" : "[NETTOYAGE RÉUSSI]";
        return $"{mode} {r.SucceededCount} traités · {r.TotalFreedFormatted} libérés · {r.BlockedBySafetyCount} protégés intacts · {r.FailedOrLockedCount} verrouillés ignorés.";
    }

    private static void OpenPathInWindowsExplorer(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return;
        try
        {
            if (File.Exists(path))
                Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true });
            else if (Directory.Exists(path))
                Process.Start(new ProcessStartInfo("explorer.exe", $"\"{path}\"") { UseShellExecute = true });
        }
        catch { }
    }

    private static void RelaunchElevated()
    {
        try
        {
            string? exe = Environment.ProcessPath;
            if (string.IsNullOrWhiteSpace(exe)) return;
            Process.Start(new ProcessStartInfo
            {
                FileName = exe,
                UseShellExecute = true,
                Verb = "runas"
            });
            Application.Current.Shutdown();
        }
        catch { }
    }
}

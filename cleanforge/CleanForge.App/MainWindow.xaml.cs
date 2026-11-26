using System.Globalization;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Data;
using System.Windows.Interop;
using CleanForge.App.ViewModels;
using CleanForge.Core.Models;

namespace CleanForge.App;

public sealed class TabVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is int selected && parameter != null && int.TryParse(parameter.ToString(), out int target))
            return selected == target ? Visibility.Visible : Visibility.Collapsed;
        return Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is bool b && b ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _vm;
        SourceInitialized += (_, _) => EnableDarkTitleBar();
    }

    private void OnNavChecked(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag != null && int.TryParse(fe.Tag.ToString(), out int idx))
        {
            _vm.SelectedTabIndex = idx;
        }
    }

    private void OnExplorerTreeSelectedChanged(object sender, RoutedPropertyChangedEventArgs<object> e)
    {
        _vm.SelectedExplorerNode = e.NewValue as FileNode;
    }

    private void OnStudyFilterClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag != null && int.TryParse(fe.Tag.ToString(), out int idx))
        {
            _vm.StudyFilterIndex = idx;
        }
    }

    private void OnQuickExtFilterClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe)
        {
            _vm.GlobalSearchExt = fe.Tag?.ToString() ?? "";
        }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    private void EnableDarkTitleBar()
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == IntPtr.Zero) return;
            int dark = 1;
            // DWMWA_USE_IMMERSIVE_DARK_MODE (Windows 10 20H1+ & Windows 11)
            DwmSetWindowAttribute(hwnd, 20, ref dark, sizeof(int));
        }
        catch { }
    }
}

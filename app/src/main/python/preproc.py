from scipy.signal import find_peaks
from scipy.interpolate import splrep, splev
import numpy as np

def detrend(ppg_signal):
  # Create a time axis for the signal
    time_axis = np.arange(len(ppg_signal))

    # Fit a linear regression model to the signal
    coefficients = np.polyfit(time_axis, ppg_signal, 1)
    trend_line = np.polyval(coefficients, time_axis)

    # Detrend the signal by subtracting the trend line
    detrended_signal = ppg_signal - trend_line

    return detrended_signal, time_axis

def get_peaks(detrended_signal):
    min_sig = min(detrended_signal)
    max_sig = max(detrended_signal)
    thresh = abs(min_sig-max_sig)/200

    # Find peaks in the detrended signal
    peaks, _ = find_peaks([-sig for sig in detrended_signal], width=1, distance=8)

    return peaks

def averge_interp_cycle(detrended_signal, peaks):

    cycles = [detrended_signal[peaks[i]:peaks[i + 1]] for i in range(len(peaks) - 1)]
    # Number of points for the interpolated cycles
    num_interpolated_points = 50

    # Create a time array for the interpolated cycles
    interpolated_timestamps = np.linspace(0, 1, num_interpolated_points)

    # Interpolate each cycle using quadratic spline interpolation
    interpolated_cycles = []
    for cycle in cycles:
        tck = splrep(np.linspace(0, 1, len(cycle)), cycle, k=2)  # Quadratic spline interpolation (k=2)
        interpolated_cycle = splev(interpolated_timestamps, tck)
        interpolated_cycles.append(interpolated_cycle)

    return np.mean(interpolated_cycles, axis=0)

def sig_preproc(ppg_signal):
    detrended_signal, _ = detrend(ppg_signal)
    detrended_signal = -np.flip(detrended_signal)
    peaks = get_peaks(detrended_signal)
    return averge_interp_cycle(detrended_signal, peaks)
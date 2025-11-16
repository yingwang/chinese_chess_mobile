#!/usr/bin/env python3
"""
Generate simple audio files for the Chinese Chess game
"""
import wave
import struct
import math

def generate_beep(filename, frequency, duration, sample_rate=44100):
    """Generate a simple beep sound"""
    num_samples = int(sample_rate * duration)

    with wave.open(filename, 'w') as wav_file:
        # Set parameters: nchannels, sampwidth, framerate, nframes, comptype, compname
        wav_file.setparams((1, 2, sample_rate, num_samples, 'NONE', 'not compressed'))

        for i in range(num_samples):
            # Generate sine wave
            value = int(32767.0 * 0.5 * math.sin(2.0 * math.pi * frequency * i / sample_rate))
            # Apply envelope to avoid clicks (fade in/out)
            envelope = 1.0
            if i < sample_rate * 0.01:  # Fade in (10ms)
                envelope = i / (sample_rate * 0.01)
            elif i > num_samples - sample_rate * 0.05:  # Fade out (50ms)
                envelope = (num_samples - i) / (sample_rate * 0.05)

            value = int(value * envelope)
            data = struct.pack('<h', value)
            wav_file.writeframes(data)

def generate_move_sound(filename):
    """Generate a pleasant click sound for piece movement"""
    sample_rate = 44100
    duration = 0.15  # 150ms

    with wave.open(filename, 'w') as wav_file:
        wav_file.setparams((1, 2, sample_rate, int(sample_rate * duration), 'NONE', 'not compressed'))

        # Generate a click-like sound with multiple harmonics
        for i in range(int(sample_rate * duration)):
            t = i / sample_rate
            # Mix multiple frequencies for a richer sound
            value = 0
            value += 0.4 * math.sin(2 * math.pi * 800 * t)  # Main frequency
            value += 0.2 * math.sin(2 * math.pi * 1600 * t) # Harmonic
            value += 0.1 * math.sin(2 * math.pi * 2400 * t) # Higher harmonic

            # Fast decay envelope
            envelope = math.exp(-15 * t)
            value = int(32767.0 * value * envelope * 0.5)

            data = struct.pack('<h', value)
            wav_file.writeframes(data)

def generate_capture_sound(filename):
    """Generate a more pronounced sound for capturing pieces"""
    sample_rate = 44100
    duration = 0.2  # 200ms

    with wave.open(filename, 'w') as wav_file:
        wav_file.setparams((1, 2, sample_rate, int(sample_rate * duration), 'NONE', 'not compressed'))

        # Generate a more emphatic sound
        for i in range(int(sample_rate * duration)):
            t = i / sample_rate
            # Lower frequencies for a heavier sound
            value = 0
            value += 0.5 * math.sin(2 * math.pi * 600 * t)
            value += 0.3 * math.sin(2 * math.pi * 1200 * t)
            value += 0.2 * math.sin(2 * math.pi * 400 * t)

            # Medium decay envelope
            envelope = math.exp(-10 * t)
            value = int(32767.0 * value * envelope * 0.6)

            data = struct.pack('<h', value)
            wav_file.writeframes(data)

def generate_background_music(filename):
    """Generate a simple, pleasant background melody"""
    sample_rate = 44100
    duration = 30  # 30 seconds, will loop

    # Pentatonic scale frequencies (pleasant Chinese-style scale)
    # Using C pentatonic: C, D, E, G, A
    notes = [
        261.63,  # C4
        293.66,  # D4
        329.63,  # E4
        392.00,  # G4
        440.00,  # A4
        523.25,  # C5
    ]

    # Simple melody pattern (note indices and durations)
    melody = [
        (4, 0.5), (3, 0.5), (2, 0.5), (1, 0.5),  # A G E D
        (0, 1.0),                                 # C (hold)
        (2, 0.5), (3, 0.5), (4, 0.5), (5, 0.5),  # E G A C5
        (4, 1.0),                                 # A (hold)
        (3, 0.5), (2, 0.5), (1, 0.5), (0, 0.5),  # G E D C
        (1, 1.0),                                 # D (hold)
        (2, 0.5), (1, 0.5), (2, 0.5), (3, 0.5),  # E D E G
        (2, 2.0),                                 # E (long hold)
    ]

    with wave.open(filename, 'w') as wav_file:
        wav_file.setparams((1, 2, sample_rate, int(sample_rate * duration), 'NONE', 'not compressed'))

        current_time = 0
        melody_index = 0

        while current_time < duration:
            note_idx, note_duration = melody[melody_index % len(melody)]
            frequency = notes[note_idx]

            # Generate note
            for i in range(int(sample_rate * note_duration)):
                if current_time >= duration:
                    break

                t = i / sample_rate
                # Generate note with harmonics
                value = 0
                value += 0.5 * math.sin(2 * math.pi * frequency * t)
                value += 0.2 * math.sin(2 * math.pi * frequency * 2 * t)  # Octave
                value += 0.1 * math.sin(2 * math.pi * frequency * 3 * t)  # Fifth

                # Envelope for smooth note transitions
                envelope = 1.0
                attack_time = 0.05
                release_time = 0.1

                if t < attack_time:
                    envelope = t / attack_time
                elif t > note_duration - release_time:
                    envelope = (note_duration - t) / release_time

                value = int(32767.0 * value * envelope * 0.15)  # Lower volume for background

                data = struct.pack('<h', value)
                wav_file.writeframes(data)
                current_time += 1.0 / sample_rate

            melody_index += 1

def wav_to_mp3(wav_file, mp3_file):
    """Convert WAV to MP3 using lame if available"""
    import subprocess
    try:
        subprocess.run(['lame', '--quiet', wav_file, mp3_file], check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False

if __name__ == '__main__':
    import os

    output_dir = 'app/src/main/res/raw'
    os.makedirs(output_dir, exist_ok=True)

    print("Generating audio files...")

    # Generate WAV files first
    print("  - Generating move_piece.wav...")
    generate_move_sound(f'{output_dir}/move_piece.wav')

    print("  - Generating capture_piece.wav...")
    generate_capture_sound(f'{output_dir}/capture_piece.wav')

    print("  - Generating background_music.wav...")
    generate_background_music(f'{output_dir}/background_music.wav')

    # Try to convert to MP3
    print("\nAttempting to convert to MP3...")
    for name in ['move_piece', 'capture_piece', 'background_music']:
        wav_file = f'{output_dir}/{name}.wav'
        mp3_file = f'{output_dir}/{name}.mp3'

        if wav_to_mp3(wav_file, mp3_file):
            print(f"  ✓ Converted {name}.wav to MP3")
            os.remove(wav_file)
        else:
            print(f"  ! Could not convert {name}.wav to MP3 (lame not available)")
            print(f"    Keeping WAV file. Android supports WAV format.")

    print("\n✓ Audio files generated successfully!")
    print(f"  Files are in: {output_dir}/")

# Building and Running the Donut Algorithm Mod

## Prerequisites

- **Java 21** or later (OpenJDK or Temurin recommended)
- **Fabric Loader** for Minecraft 1.21.11
- **Fabric API** for Minecraft 1.21.11

## Building the JAR

### Option 1: Using the build script

```bash
./build.sh
```

### Option 2: Using Gradle directly

```bash
cd Donut-Algorithm
chmod +x gradlew
./gradlew build
```

### Option 3: Using Docker

```bash
docker build -t donut-mod-builder .
docker run --rm -v $(pwd)/output:/output donut-mod-builder
```

The JAR file will be in `Donut-Algorithm/build/libs/`.

## Installing the Mod

1. **Install Fabric Loader**
   - Download from https://fabricmc.net/use/
   - Run the installer for Minecraft 1.21.11

2. **Install Fabric API**
   - Download from https://modrinth.com/mod/fabric-api
   - Place in `.minecraft/mods/`

3. **Install Donut Algorithm**
   - Copy `donut-algorithm-0.1.0.jar` to `.minecraft/mods/`

4. **Launch Minecraft**
   - Select the Fabric profile
   - Start the game

## Usage

### Local Mode (No API Key)

By default, the mod runs in **local mode** without requiring an API key. It generates realistic sample market data for testing and development.

To use local mode:
- Simply launch the mod without creating an `api-key.txt` file
- The mod will automatically detect the missing key and use local data

### API Mode (With API Key)

To use real DonutSMP market data:

1. Get an API key from https://api.donutsmp.net/
2. Create a file at `config/donut-flip-scanner/api-key.txt`
3. Paste your API key into the file
4. Launch the mod

The mod will automatically detect the API key and fetch real market data.

## Features

- **Market Scanner**: Scans auction listings for profitable flips
- **Opportunity Detection**: Identifies items with high profit potential
- **Balance Display**: Shows your current balance (API mode) or simulated balance (local mode)
- **Trade Automation**: Optional auto-purchasing (disabled on DonutSMP by default)
- **GUI Interface**: Press the configured keybind to open the mod menu

## Configuration

The mod configuration is stored in `config/donut-flip-scanner/config.json`.

## Troubleshooting

- **Mod doesn't load**: Ensure you have Fabric Loader and Fabric API installed
- **No market data**: Check that you're in the correct mode (local or API)
- **GUI doesn't open**: Check your keybind settings in Minecraft controls
